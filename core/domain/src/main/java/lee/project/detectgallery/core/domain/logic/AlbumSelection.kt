package lee.project.detectgallery.core.domain.logic

/**
 * 앨범 고르기의 선택 편집. null = 모든 앨범(저장은 빈 집합), 빈 집합 = 아무것도 안 고름(저장 불가).
 * 모든 앨범에서 하나를 빼면 나머지 전부가 선택되고, 다시 전부 고르면 null 로 접는다.
 * 접어 두면 "모든 앨범" 이라 나중에 생긴 앨범도 자동으로 들어간다.
 */
fun toggleAlbum(selected: Set<Long>?, id: Long, all: Set<Long>): Set<Long>? {
    val base = selected ?: all
    val next = if (id in base) base - id else base + id
    return if (next == all) null else next
}

/**
 * 저장된 선택을 지금 있는 앨범으로 좁힌다. 아무것도 안 골랐거나(모든 앨범) 남은 게 전부면 null.
 * 고른 앨범이 모두 사라졌으면 빈 집합: 실제 설정은 그 앨범 그대로라 아무것도 분석하지 않는 상태이므로,
 * 시트가 "모든 앨범" 으로 꾸미지 않고 다시 고르라고 안내한다.
 */
fun visibleSelection(saved: Set<Long>, all: Set<Long>): Set<Long>? {
    if (saved.isEmpty()) return null
    val kept = saved.intersect(all)
    return if (kept == all) null else kept
}