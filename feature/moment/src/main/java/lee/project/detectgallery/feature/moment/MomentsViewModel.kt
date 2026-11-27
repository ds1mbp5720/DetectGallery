package lee.project.detectgallery.feature.moment

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import lee.project.detectgallery.core.domain.port.IndexStatus
import lee.project.detectgallery.core.domain.usecase.LoadMomentsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 추억 목록 (C12). 화면이 보일 때마다(처음·탭 복귀·설정에서 돌아옴), 다시 시도할 때, 분석이 끝났을 때 다시 묶는다.
 * 복귀마다 묶는 것은 위치 권한·위치만 다시 읽은 결과를 놓치지 않기 위해서다(1초 안팎, 그동안 옛 목록이 보인다).
 */
@HiltViewModel
class MomentsViewModel @Inject constructor(
    private val load: LoadMomentsUseCase,
    indexStatus: IndexStatus,
) : ViewModel() {

    private val _state = MutableStateFlow(MomentsUiState())
    val state = _state.asStateFlow()
    private var job: Job? = null

    init {
        indexStatus.progress.onEach { p ->
            val wasIndexing = _state.value.indexProgress != null
            _state.update { it.copy(indexProgress = p) }
            if (wasIndexing && p == null) reload()   // 분석이 끝나면(위치 다시 읽기 포함) 새로 분석된 사진까지 다시 묶는다
        }.launchIn(viewModelScope)
    }

    fun onIntent(intent: MomentsIntent) {
        when (intent) {
            MomentsIntent.Retry, MomentsIntent.Resumed -> reload()
        }
    }

    private fun reload() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(failed = false) }   // 다시 묶는 동안 옛 목록은 그대로 보인다(처음이면 묶는 중)
            try {
                val r = load()
                _state.update { it.copy(moments = r.moments, located = r.located, total = r.total, locationAllowed = r.locationAllowed) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(failed = true) }
            }
        }
    }
}

/** 추억 상세. 목록과 같은 계산을 다시 해 key 로 찾는다(분석이 다시 돌아 묶음이 바뀌었으면 못 찾을 수 있다). */
@HiltViewModel
class MomentDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val load: LoadMomentsUseCase,
) : ViewModel() {
    private val key: Long = checkNotNull(savedState["key"])
    private val _state = MutableStateFlow(MomentDetailUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val m = load().moments.firstOrNull { it.key == key }
                _state.update { it.copy(moment = m, loaded = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loaded = true, failed = true) }
            }
        }
    }
}