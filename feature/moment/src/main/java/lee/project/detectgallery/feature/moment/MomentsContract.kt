package lee.project.detectgallery.feature.moment

import lee.project.detectgallery.core.domain.logic.Moment
import lee.project.detectgallery.core.domain.model.IndexProgress

data class MomentsUiState(
    val moments: List<Moment>? = null,        // null = 묶는 중
    val failed: Boolean = false,              // 마지막 묶기 실패. 옛 목록이 있으면 그 위에 한 줄로 알린다
    val located: Int = 0,                     // 위치가 있는 사진 수
    val total: Int = 0,
    val locationAllowed: Boolean = true,      // 사진 위치 접근 권한. 없으면 "권한" 안내, 있는데 located 0 이면 "위치 태그" 안내
    val indexProgress: IndexProgress? = null, // 분석 중이면 "분석된 사진만 묶었어요"
)

sealed interface MomentsIntent {
    data object Retry : MomentsIntent
    data object Resumed : MomentsIntent   // 화면이 보일 때마다(처음 포함)
}

data class MomentDetailUiState(
    val moment: Moment? = null,
    val loaded: Boolean = false,
    val failed: Boolean = false,
)