package lee.project.detectgallery.core.ml.clip

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lee.project.detectgallery.core.domain.logic.l2Normalize
import lee.project.detectgallery.core.domain.port.TextEncoder
import java.io.File
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

/** 다운로드된 모델이 filesDir/clip/ 에 있을 때만 동작한다. */
@Singleton
class ClipTextEncoder @Inject constructor(
    @ApplicationContext private val context: Context,
) : TextEncoder {
    private val env = OrtEnvironment.getEnvironment()
    private val lock = Mutex()
    private var session: OrtSession? = null
    private var tokenizer: WordPieceTokenizer? = null

    override suspend fun encode(text: String): FloatArray? = lock.withLock {
        val dir = File(context.filesDir, "clip")
        val model = File(dir, "text_model.onnx")
        val vocab = File(dir, "vocab.txt")
        if (!model.exists() || !vocab.exists()) return null

        withContext(Dispatchers.Default) {
            val s = session ?: env.createSession(model.path).also { session = it }
            val tok = tokenizer ?: WordPieceTokenizer(vocab.readLines()).also { tokenizer = it }
            val ids = tok.encode(text, maxLen = 64)
            val shape = longArrayOf(1, ids.size.toLong())
            OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { inputIds ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1L }), shape).use { mask ->
                    s.run(mapOf("input_ids" to inputIds, "attention_mask" to mask)).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        val out = (r[0].value as Array<FloatArray>)[0]
                        check(out.size == CLIP_DIM) { "출력 ${out.size}차원: pooling + Dense 까지 포함한 export 인지 확인" }
                        l2Normalize(out)
                    }
                }
            }
        }
    }

    private companion object { const val CLIP_DIM = 512 }
}