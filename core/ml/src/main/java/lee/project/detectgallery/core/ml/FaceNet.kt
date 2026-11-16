package lee.project.detectgallery.core.ml

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lee.project.detectgallery.core.domain.logic.l2Normalize
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

internal class FaceNet(context: Context, asset: String) {
    private val interpreter = Interpreter(context.loadMappedAsset(asset), Interpreter.Options().setNumThreads(4))
    private val inSize = interpreter.getInputTensor(0).shape()[1]     // FaceNet 160, MobileFaceNet 112
    private val outDim = interpreter.getOutputTensor(0).shape()[1]    // 128 / 192 / 512
    private val lock = Mutex()                                        // Interpreter 는 thread-safe 아님

    suspend fun embed(face: Bitmap): FloatArray = lock.withLock {
        withContext(Dispatchers.Default) {
            val scaled = Bitmap.createScaledBitmap(face, inSize, inSize, true)
            val px = IntArray(inSize * inSize).also { scaled.getPixels(it, 0, inSize, 0, 0, inSize, inSize) }
            val out = Array(1) { FloatArray(outDim) }
            interpreter.run(standardize(px), out)
            l2Normalize(out[0])
        }
    }
}

/**
 * FaceNet(davidsandberg) 전처리: 이미지별 표준화 (x − mean) / max(std, 1/√N).
 * 모델 카드가 (x − 127.5) / 128 이면 그쪽으로 바꾼다. 틀리면 에러 없이 정확도만 떨어진다.
 */
internal fun standardize(argb: IntArray): ByteBuffer {
    val n = argb.size * 3
    var sum = 0.0
    var sq = 0.0
    for (p in argb) {
        val r = p shr 16 and 0xFF
        val g = p shr 8 and 0xFF
        val b = p and 0xFF
        sum += r + g + b
        sq += r.toDouble() * r + g.toDouble() * g + b.toDouble() * b
    }
    val mean = sum / n
    val std = max(sqrt(sq / n - mean * mean), 1.0 / sqrt(n.toDouble()))
    return ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).apply {
        for (p in argb) {
            putFloat((((p shr 16 and 0xFF) - mean) / std).toFloat())
            putFloat((((p shr 8 and 0xFF) - mean) / std).toFloat())
            putFloat((((p and 0xFF) - mean) / std).toFloat())
        }
        rewind()
    }
}