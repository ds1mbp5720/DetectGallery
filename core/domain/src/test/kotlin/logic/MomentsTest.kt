package lee.project.detectgallery.core.domain.logic.logic

import lee.project.detectgallery.core.domain.logic.StateTag
import lee.project.detectgallery.core.domain.logic.distanceKm
import lee.project.detectgallery.core.domain.logic.fillLocations
import lee.project.detectgallery.core.domain.logic.groupMoments
import lee.project.detectgallery.core.domain.logic.homeOf
import lee.project.detectgallery.core.domain.logic.momentTitle
import lee.project.detectgallery.core.domain.logic.pickHighlights
import lee.project.detectgallery.core.domain.model.GeoPoint
import lee.project.detectgallery.core.domain.model.MomentPhoto
import lee.project.detectgallery.core.domain.model.Place
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MomentsTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val seoul = GeoPoint(37.56, 126.97)
    private val jeju = GeoPoint(33.50, 126.53)    // 서울에서 약 450km
    private val busan = GeoPoint(35.18, 129.08)   // 제주에서 약 290km

    /** start 부터 10분 간격으로 n장 */
    private fun run(firstId: Long, start: Long, n: Int, loc: GeoPoint? = null) =
        (0 until n).map { MomentPhoto(firstId + it, start + it * 10 * min, loc) }

    @Test
    fun `4시간 넘게 떨어지면 다른 추억, 8장보다 적으면 버린다`() {
        val photos = run(1, 0, 8) + run(11, 10 * hour, 5)
        assertEquals(listOf((1L..8L).toList()), groupMoments(photos, home = null).map { g -> g.map { it.id } })
    }

    @Test
    fun `집에서 멀리 나간 이틀은 한 여행으로, 최신 묶음부터`() {
        val photos = run(21, 0, 8, seoul) + run(1, 24 * hour, 8, jeju) + run(11, 48 * hour, 8, jeju)
        assertEquals(listOf(16, 8), groupMoments(photos, home = seoul).map { it.size })
    }

    @Test
    fun `연달아 찍어도 50km 넘게 떨어지면 다른 추억`() {
        val photos = run(1, 0, 8, seoul) + run(11, 80 * min, 8, jeju)
        assertEquals(2, groupMoments(photos, home = null).size)
    }

    @Test
    fun `이동 판정은 묶음의 마지막 위치 있는 사진과 비교한다`() {
        // 김포 → 위치 없는 탑승권 스크린샷 → 제주: 4시간 안이어도 둘로 나뉜다
        val photos = run(1, 0, 8, seoul) + MomentPhoto(100, 75 * min, null) + run(11, 90 * min, 8, jeju)
        assertEquals(listOf(8, 9), groupMoments(photos, home = null).map { it.size })
    }

    @Test
    fun `버려질 작은 묶음은 여행을 끊지 않는다`() {
        val photos = run(21, 0, 8, seoul) + run(1, 24 * hour, 8, jeju) + MomentPhoto(100, 34 * hour, null) + run(11, 48 * hour, 8, jeju)
        assertEquals(listOf(16, 8), groupMoments(photos, home = seoul).map { it.size })
        // 위치가 있어도 8장 미만이면: 여행 사이 집에 들러 찍은 2장
        val stop = run(21, 0, 8, seoul) + run(1, 24 * hour, 8, jeju) + run(100, 34 * hour, 2, seoul) + run(11, 48 * hour, 8, jeju)
        assertEquals(listOf(16, 8), groupMoments(stop, home = seoul).map { it.size })
    }

    @Test
    fun `여행은 멀리 이어지면 나누고 14일을 넘지 않는다`() {
        val hop = run(1, 24 * hour, 8, jeju) + run(11, 48 * hour, 8, busan)
        assertEquals(listOf(8, 8), groupMoments(hop, home = seoul).map { it.size })
        // 제주에서 20일: 0~13일째(마지막 사진 13일 70분 뒤 ≤ 14일)가 한 여행, 14일째부터 새 여행
        val long = (0 until 20).flatMap { d -> run(d * 100L, d * 24 * hour, 8, jeju) }
        assertEquals(listOf(48, 112), groupMoments(long, home = seoul).map { it.size })
    }

    @Test
    fun `위치 메우기는 10분 안이면 그대로, 30분 안이면 장면이 비슷할 때만`() {
        val photos = listOf(
            MomentPhoto(1, 0, seoul),
            MomentPhoto(2, 5 * min, null),
            MomentPhoto(3, 20 * min, null),   // 장면 0.7 → 메움
            MomentPhoto(4, 25 * min, null),   // 장면 0.3 → 안 메움 (메운 3 이 기준이 되면 메워진다)
            MomentPhoto(5, 2 * hour, null),   // 너무 멂
        )
        val filled = fillLocations(photos) { a, _ -> when (a) { 3L -> 0.7f; 4L -> 0.3f; else -> null } }
        assertEquals(listOf(1L, 2L, 3L), filled.filter { it.location != null }.map { it.id })
        assertEquals(listOf(2L, 3L), filled.filter { it.estimated }.map { it.id })
    }

    @Test
    fun `위치 메우기는 앞뒤 이웃을 다 본다`() {
        // 왼쪽(20분)은 장면이 달라 실패, 오른쪽(25분)은 장면이 비슷해 메운다
        val photos = listOf(MomentPhoto(1, 0, seoul), MomentPhoto(2, 20 * min, null), MomentPhoto(3, 45 * min, jeju))
        val filled = fillLocations(photos) { _, n -> if (n == 3L) 0.8f else 0.1f }
        assertEquals(jeju, filled.single { it.id == 2L }.location)
    }

    @Test
    fun `가장 많이 찍힌 곳이 20% 이상일 때만 집으로 본다`() {
        val home = homeOf(run(1, 0, 5, seoul) + run(11, hour, 3, jeju))!!
        assertTrue(distanceKm(home, seoul) < 1.0)
        val scattered = (0 until 10).map { MomentPhoto(it.toLong(), it * hour, GeoPoint(30.0 + it, 127.0)) }
        assertNull(homeOf(scattered))
        // 20% 경계: 2/10 은 집, 2/11 은 아님
        fun spread(n: Int) = (0 until n).map { MomentPhoto(100L + it, it * hour, GeoPoint(30.0 + it, 127.0)) }
        assertTrue(homeOf(run(1, 0, 2, seoul) + spread(8)) != null)
        assertNull(homeOf(run(1, 0, 2, seoul) + spread(9)))
    }

    @Test
    fun `칸 경계에 걸린 집도 이웃 칸을 합쳐 찾는다`() {
        // 위도 37.55 가 칸 경계: 2장씩 갈려 한 칸만 세면 2/16 = 12.5% 로 집이 없다. 3×3 으로 세면 4/16 = 25%
        val home = run(1, 0, 2, GeoPoint(37.549, 126.97)) + run(11, hour, 2, GeoPoint(37.551, 126.97))
        val scattered = (0 until 12).map { MomentPhoto(100L + it, it * hour, GeoPoint(30.0 + it, 127.0)) }
        assertTrue(distanceKm(homeOf(home + scattered)!!, GeoPoint(37.55, 126.97)) < 1.0)
    }

    @Test
    fun `집은 최근 1년 원본 위치로 정한다 - 옛 집과 메운 위치는 세지 않는다`() {
        val year = 365 * 24 * hour
        val oldHome = run(1, 0, 10, jeju)                                          // 2년 전
        val recent = run(101, 2 * year, 5, seoul)
        val estimated = (0 until 20).map { MomentPhoto(200L + it, 2 * year + it * min, jeju, estimated = true) }
        val home = homeOf(oldHome + recent + estimated)!!
        assertTrue(distanceKm(home, seoul) < 1.0)
    }

    @Test
    fun `대표 사진은 흔들림 없는 사진이 먼저, 겹치는 사진은 건너뛰고, 표지 뒤는 촬영 순`() {
        val h = 0x0F0F0F0F0F0F0F0FL
        val tags = mapOf(1L to setOf(StateTag.BLURRY), 2L to setOf(StateTag.SMILING))
        val hashes = mapOf(2L to 0x3333333333333333L, 3L to h, 4L to (h xor 1L))   // 4 는 3 과 겹침
        assertEquals(listOf(2L, 1L, 3L), pickHighlights(listOf(1L, 2L, 3L, 4L), tags, hashes, n = 3))
    }

    @Test
    fun `제목은 장소, 부제는 날짜·주제 둘·장 수`() {
        val zone = ZoneId.of("Asia/Seoul")
        val start = LocalDateTime.of(2025, 7, 12, 10, 0).atZone(zone).toInstant().toEpochMilli()
        val end = LocalDateTime.of(2025, 7, 14, 18, 0).atZone(zone).toInstant().toEpochMilli()
        val places = List(19) { Place("제주", "제주시") } + Place("부산", "해운대구")   // 부산 5% → 뺀다
        val labels = List(10) { setOf("Beach", "Sky") } + List(5) { setOf("Food") } + List(5) { emptySet<String>() }
        assertEquals("제주 제주시" to "2025.7.12–14 · 바다 · 하늘 · 20장", momentTitle(places, 20, labels, start, end, 20, zone))
        // 장소를 모르면 날짜가 제목
        assertEquals("2025.7.12" to "음식 · 3장", momentTitle(emptyList(), 0, listOf(setOf("Food"), emptySet(), emptySet()), start, start, 3, zone))
    }

    @Test
    fun `지명이 적거나 고르게 흩어지면 그에 맞게 부른다`() {
        val zone = ZoneId.of("Asia/Seoul")
        // 해외 여행 200장 중 공항 10장만 지명 → 날짜 제목
        assertEquals("1970.1.1", momentTitle(List(10) { Place("인천", "중구") }, 200, emptyList(), 0, 0, 200, zone).first)
        // 10% 경계: 2/20 은 포함 → 외 1곳
        val edge = List(18) { Place("제주", "제주시") } + List(2) { Place("부산", "중구") }
        assertEquals("제주 외 1곳", momentTitle(edge, 20, emptyList(), 0, 0, 20, zone).first)
        // 80% 경계: 시·군·구 4/5 는 "시·도 시·군·구", 3/5 는 "시·도"
        val jejuSi = Place("제주", "제주시")
        val seogwipo = Place("제주", "서귀포시")
        assertEquals("제주 제주시", momentTitle(List(4) { jejuSi } + seogwipo, 5, emptyList(), 0, 0, 5, zone).first)
        assertEquals("제주", momentTitle(List(3) { jejuSi } + List(2) { seogwipo }, 5, emptyList(), 0, 0, 5, zone).first)
        // 12곳에 고르게(모두 10% 미만) → 가장 앞 외 11곳
        assertEquals("도0 외 11곳", momentTitle((0 until 12).map { Place("도$it", "시") }, 12, emptyList(), 0, 0, 12, zone).first)
    }
}