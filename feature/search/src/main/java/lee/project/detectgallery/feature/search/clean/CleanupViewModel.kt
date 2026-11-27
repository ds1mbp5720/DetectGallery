package lee.project.detectgallery.feature.search.clean

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import lee.project.detectgallery.core.domain.port.IndexStatus
import lee.project.detectgallery.core.domain.usecase.LoadCleanupUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 사진 정리 (C10). 화면에 들어올 때마다 새로 만든다(nav 항목 범위) → 들어올 때마다 다시 읽는다. */
@HiltViewModel
class CleanupViewModel @Inject constructor(
    private val load: LoadCleanupUseCase,
    indexStatus: IndexStatus,
) : ViewModel() {

    private val _state = MutableStateFlow(CleanupUiState())
    val state = _state.asStateFlow()

    init {
        indexStatus.progress.onEach { p -> _state.update { it.copy(indexProgress = p) } }.launchIn(viewModelScope)
        reload()
    }

    fun onIntent(intent: CleanupIntent) {
        when (intent) {
            CleanupIntent.Retry -> reload()
            is CleanupIntent.TabChanged -> _state.update { it.copy(tab = intent.tab, selected = emptySet()) }
            is CleanupIntent.Toggle -> _state.update { s ->
                s.copy(selected = if (intent.id in s.selected) s.selected - intent.id else s.selected + intent.id)
            }
            CleanupIntent.SelectAll -> _state.update { it.copy(selected = it.selectable.toSet()) }
            CleanupIntent.ClearSelection -> _state.update { it.copy(selected = emptySet()) }
            // 색인에서는 새 사진 감지(10초 뒤 분석)가 "사라진 사진" 으로 지운다. 화면은 기다리지 않고 바로 뺀다
            is CleanupIntent.Trashed -> _state.update { s ->
                s.copy(sets = s.sets?.without(intent.ids), selected = s.selected - intent.ids)
            }
        }
    }

    private fun reload() {
        viewModelScope.launch {
            _state.update { it.copy(sets = null, failed = false, selected = emptySet()) }
            try {
                val sets = load()
                _state.update { it.copy(sets = sets) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(failed = true) }
            }
        }
    }
}