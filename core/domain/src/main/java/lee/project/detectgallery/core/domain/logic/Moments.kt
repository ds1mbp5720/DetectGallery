package lee.project.detectgallery.core.domain.logic

import lee.project.detectgallery.core.domain.model.GeoPoint
import lee.project.detectgallery.core.domain.model.MomentPhoto
import lee.project.detectgallery.core.domain.model.Place
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 추억 하나 (C12). ids 는 촬영 순, highlights 는 대표 사진(맨 앞이 표지). key = 첫 사진 id — 상세 화면 경로에 쓴다.
 * estimated = 앞뒤 사진에서 위치를 메운 장 수.
 */
data class Moment(
    val key: Long,
    val ids: List<Long>,
    val highlights: List<Long>,
    val title: String,
    val subtitle: String,
    val start: Long,
    val end: Long,
    val estimated: Int,
)

/**
 * located = 위치가 있는 사진 수, locationAllowed = 사진 위치 접근 권한이 있는가.
 * 화면이 "권한이 없어서" 와 "카메라 위치 태그가 꺼져서" 를 갈라 안내한다.
 */
data class MomentsResult(
    val moments: List<Moment>,
    val located: Int,
    val total: Int,
    val locationAllowed: Boolean = true
)

/** 제목에 쓸 주제 라벨(ML Kit) → 한국어. 갤러리 묶음 이름으로 어울리는 것만 고른다. */
val MOMENT_LABELS: Map<String, String> = mapOf(
    "Beach" to "바다",
    "Sky" to "하늘",
    "Sunset" to "노을",
    "Mountain" to "산",
    "Forest" to "숲",
    "Lake" to "호수",
    "River" to "강",
    "Waterfall" to "폭포",
    "Flower" to "꽃",
    "Garden" to "정원",
    "Park" to "공원",
    "Food" to "음식",
    "Cuisine" to "음식",
    "Cake" to "케이크",
    "Coffee" to "커피",
    "Dog" to "강아지",
    "Cat" to "고양이",
    "Baby" to "아기",
    "Party" to "파티",
    "Concert" to "공연",
    "Marriage" to "결혼식",
    "Christmas" to "크리스마스",
    "Fireworks" to "불꽃놀이",
    "Camping" to "캠핑",
    "Skiing" to "스키",
    "Swimming" to "수영",
    "Pool" to "수영장",
    "Bicycle" to "자전거",
    "Temple" to "사찰",
    "Castle" to "성",
    "Museum" to "박물관",
    "Aquarium" to "수족관",
    "Ferris wheel" to "놀이공원",
)

/** 두 좌표 사이 거리(km, 하버사인). */
fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h =
        sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(
            2
        )
    return 2 * 6371.0 * asin(sqrt(h))
}

/**
 * 위치 없는 사진의 위치 메우기 (C12-3). 앞뒤로 가장 가까운 "위치 있는" 사진 둘을 시각이 가까운 순서로 보고,
 * MOMENT_FILL_NEAR_MS(10분) 안이면 그대로, MOMENT_FILL_MAX_MS(30분) 안이면 장면이 비슷할 때만 그 위치를 쓴다(estimated = true).
 * 메운 위치는 다시 기준이 되지 않고(원본 위치만 기준), 묶기·제목에만 쓰고 저장하지 않는다. sceneSim 은 장면 벡터가 없으면 null.
 */
fun fillLocations(photos: List<MomentPhoto>, sceneSim: (Long, Long) -> Float?): List<MomentPhoto> {
    val located = photos.filter { it.location != null && !it.estimated }.sortedBy { it.takenAt }
    if (located.isEmpty()) return photos
    val times = LongArray(located.size) { located[it].takenAt }
    return photos.map { p ->
        if (p.location != null) return@map p
        val i = times.binarySearch(p.takenAt).let { if (it >= 0) it else -it - 1 }
        val src = listOfNotNull(located.getOrNull(i - 1), located.getOrNull(i))
            .sortedBy { abs(it.takenAt - p.takenAt) }
            .firstOrNull { n ->
                val gap = abs(n.takenAt - p.takenAt)
                gap <= Thresholds.MOMENT_FILL_NEAR_MS ||
                        (gap <= Thresholds.MOMENT_FILL_MAX_MS && (sceneSim(p.id, n.id)
                            ?: 0f) >= Thresholds.MOMENT_FILL_SCENE)
            }
        if (src != null) p.copy(location = src.location, estimated = true) else p
    }
}

/**
 * "집 근처" = 최근 1년(MOMENT_HOME_WINDOW_MS) 원본 위치가 가장 많이 몰린 곳. 칸(약 5km) 경계에 걸린 집이 둘로 나뉘지 않게
 * 이웃 3×3 칸을 합쳐 세고, 그 범위가 MOMENT_HOME_MIN_SHARE(20%) 이상일 때만 정한다(아니면 null → 여행 합치기 안 함).
 * 최근 1년만 보는 것은 이사한 사용자의 옛 집으로 정하지 않기 위해서다. 묶기 계산에만 쓰고 저장·표시하지 않는다(민감 정보).
 */
fun homeOf(photos: List<MomentPhoto>): GeoPoint? {
    val real = photos.filter { !it.estimated && it.location != null }
    if (real.isEmpty()) return null
    val newest = real.maxOf { it.takenAt }
    val pts = real.filter { it.takenAt >= newest - Thresholds.MOMENT_HOME_WINDOW_MS }
        .mapNotNull { it.location }
    val cells = pts.groupBy(::cellOf)
    val best = cells.keys.maxBy { c -> neighbors(c).sumOf { cells[it]?.size ?: 0 } }
    val near = neighbors(best).flatMap { cells[it].orEmpty() }
    if (near.size < pts.size * Thresholds.MOMENT_HOME_MIN_SHARE) return null
    return GeoPoint(near.map { it.lat }.average(), near.map { it.lon }.average())
}

private const val CELL_DEG = 0.05   // 위도 0.05° ≈ 5.6km, 경도는 위도 37° 에서 ≈ 4.4km
private fun cellOf(p: GeoPoint) =
    floor(p.lat / CELL_DEG).toLong() to floor(p.lon / CELL_DEG).toLong()

private fun neighbors(c: Pair<Long, Long>) =
    (-1L..1L).flatMap { dy -> (-1L..1L).map { dx -> (c.first + dy) to (c.second + dx) } }

/**
 * 추억 묶기 (C12-1). 촬영 순으로 훑어
 * ① 앞 사진과 MOMENT_GAP_MS(4시간) 넘게 떨어지거나, 이 묶음의 마지막 "위치 있는" 사진과 MOMENT_JUMP_KM(50km) 넘게 떨어지면 새 묶음
 *    (위치 없는 사진 — 탑승권 스크린샷 등 — 이 끼어도 이동을 놓치지 않게),
 * ② 위치도 없고 8장 미만인 묶음은 건너뛰어(여행을 끊지 않게 — 어차피 버려진다),
 *    앞 묶음이 8장 미만이라 버려질 것이고 이을 수도 없으면 앞 묶음에서 뺀 뒤(여행 중 잠깐 들른 곳이 여행을 끊지 않게),
 *    집에서 먼 묶음끼리 canJoinTrip 이면 하나의 여행으로 합치고,
 * ③ MOMENT_MIN_PHOTOS(8장)보다 적은 묶음은 버린다. 최신 묶음부터 돌려준다.
 * 위치가 하나도 없으면 ①의 시간 기준만으로 "같은 날 몰아 찍은 일" 이 묶인다(위치 태그를 끈 사용자도 쓸 수 있다).
 */
fun groupMoments(photos: List<MomentPhoto>, home: GeoPoint?): List<List<MomentPhoto>> {
    val segments = mutableListOf<MutableList<MomentPhoto>>()
    var lastLoc: GeoPoint? = null   // 지금 묶음의 마지막 "위치 있는" 사진
    for (p in photos.sortedBy { it.takenAt }) {
        val last = segments.lastOrNull()?.last()
        val loc = p.location
        val split = last == null || p.takenAt - last.takenAt > Thresholds.MOMENT_GAP_MS ||
                (lastLoc != null && loc != null && distanceKm(
                    lastLoc,
                    loc
                ) > Thresholds.MOMENT_JUMP_KM)
        if (split) {
            segments += mutableListOf(p)
            lastLoc = null
        } else {
            segments.last() += p
        }
        if (loc != null) lastLoc = loc
    }
    val merged = mutableListOf<MutableList<MomentPhoto>>()
    for (s in segments) {
        if (s.size < Thresholds.MOMENT_MIN_PHOTOS && s.none { it.location != null }) continue
        while (merged.isNotEmpty() && merged.last().size < Thresholds.MOMENT_MIN_PHOTOS &&
            (home == null || !canJoinTrip(merged.last(), s, home))
        ) merged.removeAt(merged.lastIndex)
        val prev = merged.lastOrNull()
        if (prev != null && home != null && canJoinTrip(prev, s, home)) prev += s else merged += s
    }
    return merged.filter { it.size >= Thresholds.MOMENT_MIN_PHOTOS }.reversed()
}

/**
 * 앞 묶음(지금까지의 여행)에 이어 붙일 수 있는가: 둘 다 집에서 멀고(MOMENT_AWAY_KM), 사이가 MOMENT_TRIP_GAP_MS(36시간) 안이고,
 * 합친 여행이 MOMENT_TRIP_MAX_MS(14일)를 넘지 않고, 앞의 마지막 위치와 뒤의 첫 위치가 MOMENT_TRIP_HOP_KM(100km) 안.
 * 길이 상한은 집을 잘못 정했을 때(이사 직후 등) 일상이 몇 달짜리 "여행" 으로 이어지지 않게, 거리 조건은 제주 여행 뒤 이어진 부산 출장을 가르게 둔다.
 */
private fun canJoinTrip(prev: List<MomentPhoto>, s: List<MomentPhoto>, home: GeoPoint): Boolean {
    if (!isAway(prev, home) || !isAway(s, home)) return false
    if (s.first().takenAt - prev.last().takenAt > Thresholds.MOMENT_TRIP_GAP_MS) return false
    if (s.last().takenAt - prev.first().takenAt > Thresholds.MOMENT_TRIP_MAX_MS) return false
    val a = prev.lastOrNull { it.location != null }?.location
    val b = s.firstOrNull { it.location != null }?.location
    return a == null || b == null || distanceKm(a, b) <= Thresholds.MOMENT_TRIP_HOP_KM
}

/** 위치 있는 사진의 절반 넘게 집에서 멀면 "멀리 나간" 묶음. 위치가 하나도 없으면 아니다. */
private fun isAway(s: List<MomentPhoto>, home: GeoPoint): Boolean {
    val pts = s.mapNotNull { it.location }
    return pts.isNotEmpty() && pts.count {
        distanceKm(
            it,
            home
        ) > Thresholds.MOMENT_AWAY_KM
    } * 2 > pts.size
}

/**
 * 대표 사진 (C12-2). 흔들리지 않은 사진 > 어둡지 않은 사진, 웃는 얼굴·단체 사진에 가산점. 같은 점수는 촬영 순.
 * 이미 고른 사진과 겹치는 사진(dHash, 무늬 있는 해시만)은 건너뛴다. 맨 앞(점수 최고)이 표지, 나머지는 촬영 순.
 */
fun pickHighlights(
    ids: List<Long>,
    tags: Map<Long, Set<String>>,
    hashes: Map<Long, Long>,
    n: Int = 6
): List<Long> {
    fun score(id: Long): Int {
        val t = tags[id].orEmpty()
        var s = 0
        if (StateTag.BLURRY !in t) s += 4
        if (StateTag.DARK !in t) s += 2
        if (StateTag.SMILING in t) s += 2
        if (StateTag.GROUP in t) s += 1
        return s
    }

    val chosen = mutableListOf<Long>()
    for (id in ids.sortedByDescending(::score)) {          // 안정 정렬: 같은 점수는 촬영 순 그대로
        if (chosen.size == n) break
        val h = hashes[id]?.takeIf(::hasDetail)
        val dup = h != null && chosen.any { c ->
            hashes[c]?.takeIf(::hasDetail)
                ?.let { hamming(h, it) <= Thresholds.DUPLICATE_MAX_HAMMING } == true
        }
        if (!dup) chosen += id
    }
    if (chosen.size <= 1) return chosen
    val order = ids.withIndex().associate { it.value to it.index }
    return listOf(chosen.first()) + chosen.drop(1).sortedBy { order.getValue(it) }
}
// ponytail: 같은 점수면 앞쪽(이른) 사진이 뽑힌다. 긴 여행에서 앞날만 나오면 날짜별로 나눠 고르기로.

/**
 * 제목·부제 (C12). 제목 = 장소, 장소로 부를 수 없으면 날짜. located = 이 묶음에서 위치가 있는 사진 수(지명 비율의 분모).
 * 부제 = (장소가 제목이면) 날짜 · 많이 나온 주제 둘 · n장.
 */
fun momentTitle(
    places: List<Place>,
    located: Int,
    labels: List<Set<String>>,
    start: Long,
    end: Long,
    count: Int,
    zone: ZoneId,
): Pair<String, String> {
    val date = dateRangeText(start, end, zone)
    val place = placeText(places, located)
    val topics = labels.flatMap { ls -> ls.mapNotNull { MOMENT_LABELS[it] }.distinct() }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(2)
        .map { it.key }
    val sub =
        (listOfNotNull(date.takeIf { place != null }) + topics + "${count}장").joinToString(" · ")
    return (place ?: date) to sub
}

/**
 * 지명이 붙은 사진이 위치 있는 사진의 절반이 안 되면(해외·바다 위가 대부분) 지명으로 부르지 않는다 — 공항 몇 장이 제목이 되지 않게.
 * 위치 있는 사진의 10% 미만으로 스친 시·도는 빼고, 남은 시·도가 여럿이면 "가장 많은 곳 외 n곳",
 * 하나면 그 안 한 시·군·구가 80% 이상일 때 "시·도 시·군·구", 아니면 "시·도". 모두 10% 미만으로 고르게 흩어졌으면 "가장 많은 곳 외 n곳".
 */
private fun placeText(places: List<Place>, located: Int): String? {
    if (places.isEmpty() || places.size * 2 < located) return null
    val all = places.groupBy { it.sido }.entries.sortedByDescending { it.value.size }
    val main = all.filter { it.value.size * 10 >= located }
    if (main.isEmpty()) return "${all.first().key} 외 ${all.size - 1}곳"
    val (sido, inSido) = main.first()
    if (main.size > 1) return "$sido 외 ${main.size - 1}곳"
    val (sgg, n) = inSido.groupingBy { it.sigungu }.eachCount().entries.maxBy { it.value }
    return if (n * 5 >= inSido.size * 4) "$sido $sgg" else sido
}

/** "2025.7.12", 같은 달이면 "2025.7.12–14", 달이 바뀌면 "2025.7.30–8.2", 해가 바뀌면 "2025.12.30–2026.1.2". */
fun dateRangeText(start: Long, end: Long, zone: ZoneId): String {
    val a = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
    val b = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
    val head = "${a.year}.${a.monthValue}.${a.dayOfMonth}"
    return when {
        a == b -> head
        a.year != b.year -> "$head–${b.year}.${b.monthValue}.${b.dayOfMonth}"
        a.monthValue != b.monthValue -> "$head–${b.monthValue}.${b.dayOfMonth}"
        else -> "$head–${b.dayOfMonth}"
    }
}