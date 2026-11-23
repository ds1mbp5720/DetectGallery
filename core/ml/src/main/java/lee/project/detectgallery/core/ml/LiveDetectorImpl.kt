package lee.project.detectgallery.core.ml

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import lee.project.detectgallery.core.domain.logic.kindOf
import lee.project.detectgallery.core.domain.model.*
import lee.project.detectgallery.core.domain.port.LiveDetector
import lee.project.detectgallery.core.imaging.toBitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import lee.project.detectgallery.core.domain.port.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** 화면마다 새로 만들고 ViewModel.onCleared 에서 close 한다 (ViewModelComponent, 스코프 없음). */
class LiveDetectorImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: SettingsRepository,   // 인물 기능을 끄면 실시간 얼굴 검출도 하지 않는다
) : LiveDetector {

    private val _results = MutableSharedFlow<LiveFrameResult>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val results = _results.asSharedFlow()

    private val lock = Any()
    private var lastObjects = emptyList<LiveDetection>()
    private var lastFaces = emptyList<LiveDetection>()
    private var floorTs = 0L          // reset 시각. 그보다 오래된 프레임의 늦은 결과는 기억에 넣지 않는다 (lock 안에서)
    private var lastSubmitTs = 0L     // MediaPipe LIVE_STREAM 은 타임스탬프가 늘기만 해야 한다 (this 잠금 안에서)

    private val objects: ObjectDetector = ObjectDetector.createFromOptions(
        context,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build()
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setMaxResults(6)
            .setScoreThreshold(0.4f)
            .setResultListener { result, image ->
                val list = result.detections().mapNotNull { d ->
                    val c = d.categories().firstOrNull() ?: return@mapNotNull null   // 빈 카테고리는 건너뛴다
                    LiveDetection(
                        d.boundingBox().norm(image.width, image.height),
                        kindOf(c.categoryName()),
                        c.categoryName(),
                        c.score()
                    )
                }
                publish(objects = list, ts = result.timestampMs())
            }
            .build(),
    )

    private val faces = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.15f)               // 실시간에선 화면 폭 15% 이상 얼굴만 (탭할 수 있는 크기)
            .build(),
    )
    private val faceBusy = AtomicBoolean(false)

    @Volatile
    private var closed = false

    /**
     * 분석 스레드에서 불린다. close 와 겹치면 닫힌 검출기에 넣다 예외가 나므로 함께 잠근다.
     * 블록 본문이어야 한다: 식 본문(`= synchronized { ... }`) 안의 return 은 Kotlin 이 거부한다.
     */
    override fun submit(frame: ImageFrame, timestampMs: Long) {
        synchronized(this) {
            if (closed) return
            if (timestampMs <= lastSubmitTs) return   // 거꾸로 온 프레임(회전으로 executor 가 바뀐 순간 등)은 건너뛴다 — 넣으면 예외
            lastSubmitTs = timestampMs
            val bitmap = frame.toBitmap()
            objects.detectAsync(
                BitmapImageBuilder(bitmap).build(),
                timestampMs
            )   // timestamp 는 단조 증가해야 한다

            if (!settings.facesEnabledNow()) {
                publish(faces = emptyList(), ts = timestampMs)   // 끈 직후 남은 얼굴 박스를 지운다
                return
            }
            // ML Kit 는 스스로 프레임을 버리지 않는다 → 이전 프레임 처리 중이면 건너뛴다
            if (faceBusy.compareAndSet(false, true)) {
                faces.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { found ->
                        publish(
                            faces = found.map { f ->
                                LiveDetection(
                                    f.boundingBox.norm(
                                        bitmap.width,
                                        bitmap.height
                                    ), DetectionKind.FACE, "face", 1f
                                )
                            },
                            ts = timestampMs,
                        )
                    }
                    .addOnCompleteListener { faceBusy.set(false) }
            }
        }
    }

    private fun publish(
        objects: List<LiveDetection>? = null,
        faces: List<LiveDetection>? = null,
        ts: Long
    ) {
        if (closed) return                       // 닫힌 뒤 늦게 도착한 콜백
        val merged = synchronized(lock) {
            if (ts < floorTs) return              // 카메라 전환 전 프레임의 늦은 결과: 새 결과에 섞이지 않게 버린다
            objects?.let { lastObjects = it }
            faces?.let { lastFaces = it }
            lastFaces + lastObjects
        }
        _results.tryEmit(LiveFrameResult(merged, ts))
    }

    override fun reset() = synchronized(lock) {
        floorTs = SystemClock.uptimeMillis()   // 프레임 타임스탬프와 같은 시계 (CameraBinding)
        lastObjects = emptyList()
        lastFaces = emptyList()
    }

    override fun close() {
        synchronized(this) {
            closed = true
            objects.close()
            faces.close()
        }
    }
}

private fun RectF.norm(w: Int, h: Int) =
    Box(
        (left / w).coerceIn(0f, 1f),
        (top / h).coerceIn(0f, 1f),
        (right / w).coerceIn(0f, 1f),
        (bottom / h).coerceIn(0f, 1f)
    )

private fun Rect.norm(w: Int, h: Int) =
    Box(
        (left.toFloat() / w).coerceIn(0f, 1f),
        (top.toFloat() / h).coerceIn(0f, 1f),
        (right.toFloat() / w).coerceIn(0f, 1f),
        (bottom.toFloat() / h).coerceIn(0f, 1f)
    )

// ponytail: 프레임마다 ImageFrame(IntArray) 복사가 한 번 더 든다 (640×480 ≈ 1.2MB).
// fps 가 부족하면 :feature:live 에 Bitmap 을 직접 받는 Android 전용 포트를 두고 복사를 없앤다.