package lee.project.detectgallery.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import lee.project.detectgallery.core.domain.model.DetectedFace
import lee.project.detectgallery.core.domain.model.SimilarResult
import lee.project.detectgallery.core.domain.port.IndexStatus
import lee.project.detectgallery.core.domain.port.SettingsRepository
import lee.project.detectgallery.core.domain.usecase.FindSimilarPhotosUseCase
import lee.project.detectgallery.core.domain.usecase.SearchPhotosByKeywordUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val byKeyword: SearchPhotosByKeywordUseCase,
    private val similar: FindSimilarPhotosUseCase,
    indexStatus: IndexStatus,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state = _state.asStateFlow()
    private var job: Job? = null

    init {
        indexStatus.progress.onEach { p -> _state.update { it.copy(indexProgress = p) } }
            .launchIn(viewModelScope)
        settings.sensitivity.onEach { s -> _state.update { it.copy(sensitivity = s) } }
            .launchIn(viewModelScope)
    }

    fun onIntent(intent: SearchIntent) {
        when (intent) {
            is SearchIntent.QueryChanged -> _state.update { it.copy(query = intent.text) }
            // 기준을 바꾸면 고른 사진으로 바로 다시 찾는다 (C8). 기준 탭은 사진 카드 안에만 있어 사진은 늘 있다
            is SearchIntent.ImageModeChanged -> {
                val s = _state.value
                // 같은 기준을 다시 누르면 그대로 둔다. 단 얼굴 고르기를 취소해 결과·안내가 없을 때는 다시 찾아 고르는 창을 띄운다
                if (intent.mode == s.imageMode && (s.results.isNotEmpty() || s.isSearching || s.notice != null)) return
                _state.update { it.copy(imageMode = intent.mode, faceChoices = null) }
                _state.value.queryUri?.let { searchImage(it) }
            }

            is SearchIntent.ShowSensitivity -> _state.update { it.copy(showSensitivity = intent.show) }
            // 다음 검색부터 적용된다. 실시간 카메라 미리보기는 다음 갱신(1초)부터 바로 반영된다.
            is SearchIntent.SensitivityChanged -> viewModelScope.launch {
                settings.setSensitivity(
                    intent.value
                )
            }

            SearchIntent.SubmitKeyword -> submitKeyword(_state.value.query)
            is SearchIntent.QuickKeyword -> {
                _state.update { it.copy(query = intent.text) }
                submitKeyword(intent.text)
            }

            is SearchIntent.QueryImage -> {
                // 검색창 글자는 비운다: 지금 결과가 사진으로 찾은 것이라는 게 섞여 보이지 않게
                _state.update {
                    it.copy(
                        queryUri = intent.uri,
                        queryFromShare = intent.fromShare,
                        query = "",
                        faceChoices = null
                    )
                }
                searchImage(intent.uri)
            }

            SearchIntent.ClearImage -> {
                job?.cancel()
                _state.update {
                    it.copy(
                        queryUri = null,
                        queryFromShare = false,
                        faceChoices = null,
                        results = emptyList(),
                        notice = null,
                        isSearching = false
                    )
                }
            }

            is SearchIntent.FaceChosen -> {
                _state.update { it.copy(faceChoices = null) }
                launchSearch { similar.byFace(intent.face).toOutcome() }
            }

            SearchIntent.CancelFaceChoice -> _state.update { it.copy(faceChoices = null) }
        }
    }

    private fun SimilarResult.toOutcome(): Outcome = when (this) {
        is SimilarResult.Matches -> Outcome(items.map { it.mediaId })
        is SimilarResult.MultipleFaces -> Outcome(emptyList(), faces = faces)   // 화면에서 한 명을 고른다
        SimilarResult.NoFaceInQuery -> Outcome(emptyList(), SearchNotice.NO_FACE_IN_QUERY)
        SimilarResult.FaceModelUnavailable -> Outcome(emptyList(), SearchNotice.FACE_UNAVAILABLE)
        SimilarResult.FacesDisabled -> Outcome(emptyList(), SearchNotice.FACES_DISABLED)
        SimilarResult.UnreadableQuery -> Outcome(emptyList(), SearchNotice.UNREADABLE)
    }

    private fun searchImage(uri: String) =
        launchSearch { similar(uri, _state.value.imageMode).toOutcome() }

    /** 키워드로 찾으면 사진으로 찾기는 끝난다(사진 카드가 사라진다). 두 검색이 한 화면에 섞이지 않게 (C8) */
    private fun submitKeyword(q: String) {
        if (q.isBlank()) return
        _state.update { it.copy(queryUri = null, queryFromShare = false, faceChoices = null) }
        launchSearch { Outcome(byKeyword(q)) }
    }

    private data class Outcome(
        val ids: List<Long>,
        val notice: SearchNotice? = null,
        val faces: List<DetectedFace>? = null,
    )

    /** 새 검색이 이전 검색을 대체한다. */
    private fun launchSearch(block: suspend () -> Outcome) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(isSearching = true, notice = null) }
            try {
                val o = block()
                _state.update {
                    it.copy(
                        isSearching = false, results = o.ids, faceChoices = o.faces,
                        // 얼굴을 고르는 중이면 "결과 없음" 이 아니다
                        notice = o.notice
                            ?: if (o.ids.isEmpty() && o.faces == null) SearchNotice.NO_RESULTS else null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isSearching = false, notice = SearchNotice.FAILED) }
            }
        }
    }
}