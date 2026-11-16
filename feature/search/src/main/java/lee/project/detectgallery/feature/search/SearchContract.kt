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
    val indexProgress: IndexProgress? = null,   // 색인 중이면 "일부 사진만 검색됨" 배너
    val sensitivity: Sensitivity = Sensitivity(),
    val showSensitivity: Boolean = false,       // 민감도 시트
    val queryUri: String? = null,               // 마지막 쿼리 사진 (얼굴 고르기 화면에 쓴다)
    val faceChoices: List<DetectedFace>? = null, // 쿼리 사진에 얼굴이 여럿이면 고를 목록
)

enum class SearchNotice { NO_RESULTS, NO_FACE_IN_QUERY, FACE_UNAVAILABLE, FACES_DISABLED, UNREADABLE, FAILED }

sealed interface SearchIntent {
    data class QueryChanged(val text: String) : SearchIntent
    data object SubmitKeyword : SearchIntent
    data class QuickKeyword(val text: String) : SearchIntent          // 칩: 음식, 바다, 웃는, 단체, 셀카
    data class ImageModeChanged(val mode: ImageSearchMode) : SearchIntent
    data class QueryImage(val uri: String) : SearchIntent             // 사진 선택기·촬영
    data class ShowSensitivity(val show: Boolean) : SearchIntent
    data class SensitivityChanged(val value: Sensitivity) : SearchIntent
    data class FaceChosen(val face: DetectedFace) : SearchIntent
    data object CancelFaceChoice : SearchIntent
}