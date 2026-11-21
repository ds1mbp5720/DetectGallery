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
 * 저장된 선택을 지금 있는 앨범으로 좁힌다. 저장값이 비어 있을 때(모든 앨범)만 null.
 * 고른 앨범이 지금 앨범 전부와 같아져도 접지 않는다: 실제 설정은 고른 앨범이라 새 앨범이 분석되지 않는데
 * "모든 앨범" 이 켜져 보이면 안 된다(남은 앨범이 체크된 채 보이고, 저장하면 사라진 앨범이 정리된다).
 * 고른 앨범이 모두 사라졌으면 빈 집합 → 시트가 다시 고르라고 안내한다.
 */
fun visibleSelection(saved: Set<Long>, all: Set<Long>): Set<Long>? =
    if (saved.isEmpty()) null else saved.intersect(all)