package lee.project.detectgallery.core.domain.logic.logic

import lee.project.detectgallery.core.domain.logic.CleanupSets
import lee.project.detectgallery.core.domain.logic.DuplicateGroup
import lee.project.detectgallery.core.domain.logic.groupDuplicates
import org.junit.Assert.assertEquals
import org.junit.Test

class CleanupTest {
    /** 1 인 비트가 32개인 해시. 0 근처 해시는 묶기에서 빠지므로 무늬가 있는 사진을 흉내 낸다. */
    private val h = 0x0F0F0F0F0F0F0F0FL

    @Test
    fun `흔들리지 않은 사진을 남기기로 추천한다`() {
        val g = groupDuplicates(mapOf(10L to h, 9L to (h xor 1L)), blurry = setOf(10L), pixels = emptyMap())
        assertEquals(listOf(DuplicateGroup(listOf(9L, 10L))), g)
        assertEquals(9L, g.single().keep)
    }

    @Test
    fun `둘 다 선명하면 화소가 많은 사진, 화소도 같으면 최신 사진`() {
        val byPixels = groupDuplicates(mapOf(5L to h, 4L to h), emptySet(), mapOf(5L to 8_000_000L, 4L to 12_000_000L))
        assertEquals(4L, byPixels.single().keep)
        val tie = groupDuplicates(mapOf(5L to h, 4L to h), emptySet(), emptyMap())
        assertEquals(5L, tie.single().keep)
    }

    @Test
    fun `거리 5 는 묶고 6 은 묶지 않는다`() {
        assertEquals(1, groupDuplicates(mapOf(2L to h, 1L to (h xor 0b11111L)), emptySet(), emptyMap()).size)
        assertEquals(emptyList<DuplicateGroup>(), groupDuplicates(mapOf(2L to h, 1L to (h xor 0b111111L)), emptySet(), emptyMap()))
    }

    @Test
    fun `기준과 먼 사진은 이어 붙이지 않는다`() {
        // A~B 4비트, B~C 4비트, A~C 8비트: B 를 거쳐 C 까지 한 묶음이 되면 안 된다. C 는 혼자라 묶음이 없다
        val g = groupDuplicates(mapOf(3L to h, 2L to (h xor 0b1111L), 1L to (h xor 0xFFL)), emptySet(), emptyMap())
        assertEquals(listOf(DuplicateGroup(listOf(3L, 2L))), g)
    }

    @Test
    fun `1 인 비트가 6~58 개인 해시만 묶는다 (밝기 차이가 없는 사진 제외)`() {
        // 거리는 모두 2 라 비트 수만 결과를 가른다
        val six = 0b111111L to 0b1111110L          // 비트 6개씩 → 묶음
        val five = 0b11111L to 0b111110L           // 비트 5개씩 → 제외
        assertEquals(1, groupDuplicates(mapOf(2L to six.first, 1L to six.second), emptySet(), emptyMap()).size)
        assertEquals(emptyList<DuplicateGroup>(), groupDuplicates(mapOf(2L to five.first, 1L to five.second), emptySet(), emptyMap()))
        // 반대쪽 끝: 뒤집으면 58개(묶음)·59개(제외)
        assertEquals(1, groupDuplicates(mapOf(2L to six.first.inv(), 1L to six.second.inv()), emptySet(), emptyMap()).size)
        assertEquals(emptyList<DuplicateGroup>(), groupDuplicates(mapOf(2L to five.first.inv(), 1L to five.second.inv()), emptySet(), emptyMap()))
    }

    @Test
    fun `옮긴 사진을 빼면 한 장 남은 묶음은 없어지고 남은 첫 장이 추천이 된다`() {
        val sets = CleanupSets(
            duplicates = listOf(DuplicateGroup(listOf(2L, 3L, 1L)), DuplicateGroup(listOf(8L, 7L))),
            blurry = listOf(2L), dark = listOf(7L), screenshots = listOf(2L, 5L),
        )
        val left = sets.without(setOf(2L, 7L))
        assertEquals(listOf(DuplicateGroup(listOf(3L, 1L))), left.duplicates)
        assertEquals(emptyList<Long>(), left.blurry)
        assertEquals(emptyList<Long>(), left.dark)
        assertEquals(listOf(5L), left.screenshots)
    }
}