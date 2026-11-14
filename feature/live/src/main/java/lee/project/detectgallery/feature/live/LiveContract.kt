package lee.project.detectgallery.feature.live

import app.gallerylens.domain.model.ImageFrame
import app.gallerylens.domain.model.LiveDetection

data class LiveUiState(
    val frameW: Int = 0,
    val frameH: Int = 0,
    val detections: List<LiveDetection> = emptyList(),
    val preview: List<Long> = emptyList(),          // 지금 화면과 비슷한 갤러리 사진 (하단 띠)
    val foodScene: Boolean = false,                 // 화면 전체가 음식으로 보임 (박스 없는 음식용 칩)
    val selected: LiveDetection? = null,            // 탭한 대상. null 이 아니면 결과 시트가 떠 있고 분석은 멈춘다
    val queryCrop: ImageFrame? = null,              // 탭할 때 잘라 둔 검색 대상 (시트 위 썸네일)
    val results: List<Long> = emptyList(),          // 결과 시트
    val isSearching: Boolean = false,
    val notice: LiveNotice? = null,
)

enum class LiveNotice { NO_FACE, FACE_UNAVAILABLE, FACES_DISABLED, NO_RESULTS, FAILED }

sealed interface LiveIntent {
    data class Frame(val frame: ImageFrame, val timestampMs: Long) : LiveIntent
    data class Tap(val x: Float, val y: Float) : LiveIntent      // 분석 이미지 기준 0..1
    data object DismissResults : LiveIntent
    data object SearchFoodScene : LiveIntent                     // "음식 장면" 칩
}