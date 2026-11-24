package lee.project.detectgallery.core.domain.logic

/** 겹치는 사진 한 묶음 (정리 모드, C10). ids 는 남길 순서(흔들리지 않은 것 > 화소 > 최신), 맨 앞이 남기기 추천. */
data class DuplicateGroup(val ids: List<Long>) {
    val keep: Long get() = ids.first()
}

/** 정리 모드의 네 목록. 서로 겹칠 수 있다(흔들린 사진이 겹치는 사진 묶음에도 들어갈 수 있다). */
data class CleanupSets(
    val duplicates: List<DuplicateGroup>,
    val blurry: List<Long>,        // 많이 흔들린 순
    val dark: List<Long>,          // 많이 어두운 순
    val screenshots: List<Long>,   // 최신순
) {
    /** 휴지통으로 옮긴 사진을 뺀다. 두 장 미만이 된 묶음은 없앤다. 묶음은 남길 순서라 남은 첫 장이 그대로 다음 추천이다. */
    fun without(gone: Set<Long>) = CleanupSets(
        duplicates = duplicates.mapNotNull { g -> g.ids.filter { it !in gone }.takeIf { it.size >= 2 }?.let(::DuplicateGroup) },
        blurry = blurry - gone,
        dark = dark - gone,
        screenshots = screenshots - gone,
    )
}

/**
 * 겹치는 사진 묶기. 최신 사진부터 차례로 기준이 되어, 아직 묶이지 않은 사진 중 기준과 해밍 거리가
 * maxHamming 이하인 것을 모은다. 둘씩 이어 붙이면(A~B, B~C) 서로 다른 사진이 한 묶음으로 길게 이어지므로
 * 묶음 안 모든 사진이 기준과 가깝게 한다. 거리는 대칭이라 기준보다 앞(더 최신)의 남은 사진은 이미 기준과 멀다 → 뒤만 본다.
 * 검색의 "겹치는 사진" 기준(FindSimilarPhotosUseCase)과 같은 해시·같은 거리·같은 무늬 판정(hasDetail)이다.
 * checkCancel 은 기준마다 부른다(화면을 나가면 멈추게, 호출하는 쪽이 ensureActive 를 넘긴다).
 */
fun groupDuplicates(
    hashes: Map<Long, Long>,          // mediaId → dHash
    blurry: Set<Long>,
    pixels: Map<Long, Long>,          // mediaId → 가로×세로 (없으면 0)
    maxHamming: Int = Thresholds.DUPLICATE_MAX_HAMMING,
    checkCancel: () -> Unit = {},
): List<DuplicateGroup> {
    val entries = hashes.entries
        .filter { hasDetail(it.value) }                // 밝기 차이가 없는 사진은 묶지 않는다
        .sortedByDescending { it.key }                 // MediaStore id 내림차순 ≈ 최신순
    val ids = LongArray(entries.size) { entries[it].key }
    val hs = LongArray(entries.size) { entries[it].value }
    val used = BooleanArray(ids.size)
    val keepOrder = compareBy<Long>({ it !in blurry }, { pixels[it] ?: 0L }, { it }).reversed()
    val groups = mutableListOf<DuplicateGroup>()
    for (i in ids.indices) {
        if (used[i]) continue
        checkCancel()
        val members = ArrayList<Long>()
        for (j in i + 1 until ids.size) {
            if (!used[j] && hamming(hs[i], hs[j]) <= maxHamming) {
                used[j] = true
                members += ids[j]
            }
        }
        if (members.isNotEmpty()) {
            members += ids[i]
            groups += DuplicateGroup(members.sortedWith(keepOrder))
        }
    }
    return groups
}
// ponytail: 남은 사진끼리 전부 비교(1만 장 ≈ 5천만 번, 기기에서 수 초가 걸릴 수 있다 — 실기기에서 잰다). 5만 장을 넘거나 느리면 해시 조각별 버킷(multi-index)으로.