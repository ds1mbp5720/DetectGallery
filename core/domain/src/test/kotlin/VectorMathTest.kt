package lee.project.detectgallery.core.domain.logic

import lee.project.detectgallery.core.domain.model.IndexedVector
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class VectorMathTest {
    private fun unit(rnd: Random, dim: Int) =
        l2Normalize(FloatArray(dim) { rnd.nextFloat() * 2 - 1 })

    @Test
    fun `int8 캐시 내적은 float 내적과 0_01 안에서 같다`() {
        val rnd = Random(1)
        repeat(200) {
            val a = unit(rnd, 1024)
            val b = unit(rnd, 1024)
            assertEquals(dot(a, b), dot(a, IndexedVector(1, b)), 0.01f)
        }
        val face = unit(rnd, 512)
        assertEquals(1f, dot(face, IndexedVector(1, face)), 0.01f)   // 같은 얼굴 벡터 = 1
    }

    @Test
    fun `0 벡터는 내적 0`() {
        assertEquals(0f, dot(FloatArray(4) { 0.5f }, IndexedVector(1, FloatArray(4))), 0f)
    }

    @Test
    fun `NaN·무한대가 섞인 벡터는 예외 없이 0 벡터`() {
        val q = FloatArray(4) { 0.5f }
        assertEquals(0f, dot(q, IndexedVector(1, floatArrayOf(Float.NaN, 1f, 0f, 0f))), 0f)
        assertEquals(
            0f,
            dot(q, IndexedVector(2, floatArrayOf(Float.POSITIVE_INFINITY, 1f, 0f, 0f))),
            0f
        )
    }
}