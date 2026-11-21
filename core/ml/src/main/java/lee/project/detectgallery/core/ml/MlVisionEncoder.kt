package lee.project.detectgallery.core.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import lee.project.detectgallery.core.domain.logic.Thresholds
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import lee.project.detectgallery.core.domain.model.Box
import lee.project.detectgallery.core.domain.model.DetectedFace
import lee.project.detectgallery.core.domain.model.ImageFrame
import lee.project.detectgallery.core.domain.model.Label
import lee.project.detectgallery.core.domain.port.VisionEncoder
import lee.project.detectgallery.core.imaging.toBitmap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark

@Singleton
class MlVisionEncoder @Inject constructor(
    @ApplicationContext private val context: Context,
) : VisionEncoder {

    /** facenet.tflite 가 assets 에 있는지. 열어 보기만 하고 닫는다. */
    override val facesAvailable: Boolean by lazy {
        runCatching { context.assets.openFd("facenet.tflite").close() }.isSuccess
    }

    private val embedder by lazy {
        ImageEmbedder.createFromOptions(
            context,
            ImageEmbedder.ImageEmbedderOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder().setModelAssetPath("mobilenet_v3_small.tflite").build()
                )
                .setL2Normalize(true)                  // 내적 = 코사인
                .build(),
        )
    }
    private val embedLock = Mutex()                    // IMAGE 모드 태스크는 동시 호출하지 않는다

    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build())
    }
    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)  // 미소·눈 뜸 확률
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)              // 눈 위치 → 얼굴 수평 정렬
                // 이미지 "폭 대비" 얼굴 폭 비율이다. 0.1 이면 해상도와 무관하게 폭 10% 미만 얼굴(단체 사진)이 빠진다.
                // 0.04 + 1024px 썸네일 → 약 40px 이상 얼굴까지. 그보다 작은 건 아래 MIN_FACE_PX 가 거른다.
                .setMinFaceSize(0.04f)
                .build(),
        )
    }
    private val faceNet by lazy { FaceNet(context, "facenet.tflite") }

    override suspend fun scene(frame: ImageFrame): FloatArray = embedLock.withLock {
        withContext(Dispatchers.Default) {
            embedder.embed(BitmapImageBuilder(frame.toBitmap()).build())
                .embeddingResult().embeddings()[0].floatEmbedding()
        }
    }

    override suspend fun labels(frame: ImageFrame): List<Label> =
        labeler.process(frame.toInputImage()).await().map { Label(it.text, it.confidence) }

    override suspend fun faces(frame: ImageFrame): List<DetectedFace> {
        val bmp = frame.toBitmap()
        val found = faceDetector.process(InputImage.fromBitmap(bmp, 0)).await()
        return found.mapNotNull { face ->
            val r = face.boundingBox
            val l = r.left.coerceAtLeast(0);
            val t = r.top.coerceAtLeast(0)
            val w = (r.right.coerceAtMost(bmp.width) - l);
            val h = (r.bottom.coerceAtMost(bmp.height) - t)
            if (w < MIN_FACE_PX || h < MIN_FACE_PX) return@mapNotNull null
            DetectedFace(
                box = Box(
                    l / bmp.width.toFloat(),
                    t / bmp.height.toFloat(),
                    (l + w) / bmp.width.toFloat(),
                    (t + h) / bmp.height.toFloat()
                ),
                vector = faceNet.embed(alignedCrop(bmp, face, l, t, w, h)),
                smiling = face.smilingProbability,
                // 옆얼굴·작은 얼굴은 특징값이 흔들려 엉뚱한 새 인물을 만들거나 중심을 끌어당긴다 → 표시만 하고 배정에서 다르게 쓴다
                lowQuality = abs(face.headEulerAngleY) > Thresholds.FACE_MAX_YAW || min(
                    w,
                    h
                ) < Thresholds.FACE_GOOD_MIN_PX,
            )
        }
    }

    private fun ImageFrame.toInputImage() = InputImage.fromBitmap(toBitmap(), 0)

    private companion object {
        const val MIN_FACE_PX = 40
    }
}

/**
 * 두 눈이 수평이 되게, 얼굴 상자 가운데를 축으로 사진을 돌린 뒤 상자만큼 자른다 (C3).
 * FaceNet 은 눈을 맞춘(정렬된) 얼굴로 학습돼서, 기운 얼굴을 그대로 넣으면 같은 사람 점수가 떨어진다.
 * 눈을 못 찾았거나 기울기가 3° 미만이면 상자 그대로 자른다. 돌려서 비는 모서리는 검게 남는다.
 * 색인과 사진 검색(쿼리 얼굴)이 이 함수를 함께 거치므로 두 쪽 벡터가 같은 기준이다.
 */
internal fun alignedCrop(bmp: Bitmap, face: Face, l: Int, t: Int, w: Int, h: Int): Bitmap {
    val a = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
    val b = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
    if (a == null || b == null) return Bitmap.createBitmap(bmp, l, t, w, h)
    val (p, q) = if (a.x <= b.x) a to b else b to a   // 화면 왼쪽 눈 → 오른쪽 눈 (ML Kit 의 LEFT 는 사람 기준이라 화면에선 오른쪽)
    val deg = Math.toDegrees(atan2((q.y - p.y).toDouble(), (q.x - p.x).toDouble())).toFloat()
    if (abs(deg) < 3f) return Bitmap.createBitmap(bmp, l, t, w, h)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(out).apply {
        translate(-l.toFloat(), -t.toFloat())
        rotate(-deg, l + w / 2f, t + h / 2f)   // y 가 아래로 커지는 좌표: deg > 0 = 오른쪽 눈이 낮음 → 반시계로 돌린다
        drawBitmap(bmp, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
    }
    return out
}