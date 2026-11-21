package lee.project.detectgallery.feature.search

import lee.project.detectgallery.core.domain.model.DetectedFace
import lee.project.detectgallery.core.domain.model.ImageSearchMode
import lee.project.detectgallery.core.domain.model.IndexProgress
import lee.project.detectgallery.core.domain.model.Sensitivity

data class SearchUiState(
    val query: String = "",
    val imageMode: ImageSearchMode = ImageSearchMode.SCENE,
    val results: List<Long> = emptyList(),
    val isSearching: Boolean = false,
    val notice: SearchNotice? = null,
    val indexProgress: IndexProgress? = null,   // 색인 중이면 검색 안내(SearchGuide)에 진행 숫자
    val sensitivity: Sensitivity = Sensitivity(),
    val showSensitivity: Boolean = false,       // 민감도 시트
    val queryUri: String? = null,               // 사진으로 찾는 중인 사진. null = 키워드 검색 (있으면 검색창 아래 사진 카드)
    val faceChoices: List<DetectedFace>? = null, // 쿼리 사진에 얼굴이 여럿이면 고를 목록
)

enum class SearchNotice { NO_RESULTS, NO_FACE_IN_QUERY, FACE_UNAVAILABLE, FACES_DISABLED, UNREADABLE, FAILED }

sealed interface SearchIntent {
    data class QueryChanged(val text: String) : SearchIntent
    data object SubmitKeyword : SearchIntent
    data class QuickKeyword(val text: String) : SearchIntent          // 칩: SearchScreen 의 QUICK 목록
    data class ImageModeChanged(val mode: ImageSearchMode) :
        SearchIntent  // 사진 카드의 기준. 사진이 있으면 바로 다시 찾는다

    data class QueryImage(val uri: String) : SearchIntent             // 사진 선택기
    data object ClearImage :
        SearchIntent                              // 사진 카드의 ✕: 사진으로 찾기를 끝내고 키워드 화면으로

    data class ShowSensitivity(val show: Boolean) : SearchIntent
    data class SensitivityChanged(val value: Sensitivity) : SearchIntent
    data class FaceChosen(val face: DetectedFace) : SearchIntent
    data object CancelFaceChoice : SearchIntent
}