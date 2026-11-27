package lee.project.detectgallery.feature.search.clean

import lee.project.detectgallery.core.domain.logic.CleanupSets
import lee.project.detectgallery.core.domain.model.IndexProgress

enum class CleanupTab(val label: String) {
    DUPLICATE("겹치는 사진"), BLURRY("흔들린"), DARK("어두운"), SCREENSHOT("스크린샷"),
}

data class CleanupUiState(
    val sets: CleanupSets? = null,              // null = 불러오는 중
    val failed: Boolean = false,
    val tab: CleanupTab = CleanupTab.DUPLICATE,
    val selected: Set<Long> = emptySet(),       // 지금 탭에서 고른 사진. 탭을 바꾸면 비운다(안 보이는 사진이 함께 옮겨지지 않게)
    val indexProgress: IndexProgress? = null,   // 분석 중이면 "분석된 사진만 보여요" 안내
) {
    /** 지금 탭의 사진. 겹치는 사진은 묶음을 차례로 펼친 순서. */
    val visible: List<Long>
        get() = ids(tab)

    /** "모두 고르기" 가 고르는 사진. 겹치는 사진은 남기기 추천을 뺀 나머지("추천대로 고르기"). */
    val selectable: List<Long>
        get() = if (tab == CleanupTab.DUPLICATE) sets?.duplicates.orEmpty().flatMap { g -> g.ids - g.keep } else visible

    fun count(t: CleanupTab): Int = ids(t).size

    private fun ids(t: CleanupTab): List<Long> {
        val s = sets ?: return emptyList()
        return when (t) {
            CleanupTab.DUPLICATE -> s.duplicates.flatMap { it.ids }
            CleanupTab.BLURRY -> s.blurry
            CleanupTab.DARK -> s.dark
            CleanupTab.SCREENSHOT -> s.screenshots
        }
    }
}

sealed interface CleanupIntent {
    data object Retry : CleanupIntent
    data class TabChanged(val tab: CleanupTab) : CleanupIntent
    data class Toggle(val id: Long) : CleanupIntent
    data object SelectAll : CleanupIntent
    data object ClearSelection : CleanupIntent
    data class Trashed(val ids: Set<Long>) : CleanupIntent   // 시스템 확인 창에서 허용됨
}