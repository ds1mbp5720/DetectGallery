package lee.project.detectgallery.core.domain.logic

import kotlinx.coroutines.test.runTest
import lee.project.detectgallery.core.domain.port.PhotoIndexRepository
import lee.project.detectgallery.core.domain.port.PhotoSource
import lee.project.detectgallery.core.domain.usecase.LoadCleanupUseCase
import org.junit.Assert.assertEquals
import org.junit.Test

class LoadCleanupUseCaseTest {
    private val h = 0x0F0F0F0F0F0F0F0FL

    /**
     * 색인에는 남았지만 갤러리에서는 지워진 사진(3)이 정리 목록에 나오지 않는가.
     * 3 은 선명하고 1·2 는 흔들렸다: 거르지 않으면 지워진 3 이 남기기 추천이 되어 남은 사본 1·2 가 모두 지울 후보가 된다.
     */
    @Test
    fun `지금 갤러리에 없는 색인 사진은 빼고 묶는다`() = runTest {
        val index = object : PhotoIndexRepository by unused() {
            override suspend fun photosByLabels(labels: List<String>) = when (labels.single()) {
                StateTag.BLURRY -> listOf(2L, 1L)
                StateTag.DARK -> listOf(3L)
                else -> emptyList()
            }
            override suspend fun dHashes() = mapOf(3L to h, 2L to h, 1L to (h xor 1L))
        }
        val source = object : PhotoSource by unused() {
            override suspend fun pixelCounts() = mapOf(2L to 100L, 1L to 100L)   // 3 은 지워졌다
            override suspend fun screenshots() = emptyList<Long>()
        }
        val sets = LoadCleanupUseCase(index, source)()
        assertEquals(listOf(DuplicateGroup(listOf(2L, 1L))), sets.duplicates)   // 둘 다 흔들림·화소 같음 → 최신 2 가 추천
        assertEquals(2L, sets.duplicates.single().keep)
        assertEquals(listOf(2L, 1L), sets.blurry)
        assertEquals(emptyList<Long>(), sets.dark)
    }

    /** 쓰지 않는 함수는 부르면 실패하는 위임 대상 (인터페이스가 커서 위임으로 필요한 것만 덮어쓴다). */
    private inline fun <reified T : Any> unused(): T = java.lang.reflect.Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, m, _ -> error("쓰지 않는 함수: ${m.name}") } as T
}