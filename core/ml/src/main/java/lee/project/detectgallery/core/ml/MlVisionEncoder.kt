package lee.project.detectgallery.core.ml

import android.content.Context
import android.graphics.Bitmap
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
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("mobilenet_v3_small.tflite").build())
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
            val l = r.left.coerceAtLeast(0); val t = r.top.coerceAtLeast(0)
            val w = (r.right.coerceAtMost(bmp.width) - l); val h = (r.bottom.coerceAtMost(bmp.height) - t)
            if (w < MIN_FACE_PX || h < MIN_FACE_PX) return@mapNotNull null
            DetectedFace(
                box = Box(
                    l / bmp.width.toFloat(),
                    t / bmp.height.toFloat(),
                    (l + w) / bmp.width.toFloat(),
                    (t + h) / bmp.height.toFloat()
                ),
                vector = faceNet.embed(Bitmap.createBitmap(bmp, l, t, w, h)),
                smiling = face.smilingProbability,
            )
        }
    }

    private fun ImageFrame.toInputImage() = InputImage.fromBitmap(toBitmap(), 0)

    private companion object { const val MIN_FACE_PX = 40 }
}