package lee.project.detectgallery.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import lee.project.detectgallery.core.domain.logic.toggleAlbum
import lee.project.detectgallery.core.domain.logic.visibleSelection
import lee.project.detectgallery.core.domain.model.Album
import lee.project.detectgallery.core.domain.port.PhotoSource
import lee.project.detectgallery.core.domain.port.SettingsRepository
import lee.project.detectgallery.core.domain.usecase.SetAlbumsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.util.Log

data class AlbumPickerUiState(
    val albums: List<Album>? = null,     // null = 불러오는 중
    val selected: Set<Long>? = null,     // null = 모든 앨범, 빈 집합 = 아무것도 안 고름(저장 불가)
    val lost: Boolean = false,           // 고른 앨범이 모두 사라짐 (지금은 아무것도 분석하지 않는 상태)
    val failed: Boolean = false,         // 앨범 목록을 읽지 못함
    val saveFailed: Boolean = false,     // 저장 실패. 시트는 이미 닫혀 있어 AppNavHost 가 알린다
)

sealed interface AlbumPickerIntent {
    /** fresh = 시트를 새로 열 때(새로 읽음). false = 회전·프로세스 복원 뒤 다시 그릴 때(읽었거나 읽는 중이면 그대로). */
    data class Load(val fresh: Boolean = true) : AlbumPickerIntent
    data class Toggle(val id: Long) : AlbumPickerIntent
    data class SetAll(val all: Boolean) : AlbumPickerIntent
    data object Save : AlbumPickerIntent
    data object SaveFailedShown : AlbumPickerIntent
}

/**
 * "분석할 앨범 고르기" 시트. Activity 범위(AppNavHost 에서 얻음)라 시트를 닫아도 저장이 끝까지 돌고,
 * 회전해도 고르던 내용이 남는다. 시트를 새로 열 때만 Load(fresh) 로 다시 읽는다.
 */
@HiltViewModel
class AlbumPickerViewModel @Inject constructor(
    private val source: PhotoSource,
    private val settings: SettingsRepository,
    private val setAlbums: SetAlbumsUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(AlbumPickerUiState())
    val state: StateFlow<AlbumPickerUiState> = _state.asStateFlow()
    private var loadJob: Job? = null

    fun onIntent(intent: AlbumPickerIntent) {
        when (intent) {
            is AlbumPickerIntent.Load -> {
                if (!intent.fresh && (_state.value.albums != null || loadJob?.isActive == true)) return
                loadJob?.cancel()
                _state.value = AlbumPickerUiState()
                loadJob = viewModelScope.launch {
                    val albums = try {
                        source.albums()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    val all = albums.orEmpty().mapTo(HashSet()) { it.id }
                    val saved = settings.albumsNow()
                    val selected = visibleSelection(saved, all)
                    _state.value = AlbumPickerUiState(
                        albums = albums.orEmpty(),
                        selected = selected,
                        lost = saved.isNotEmpty() && selected?.isEmpty() == true,
                        failed = albums == null,
                    )
                }
            }

            is AlbumPickerIntent.Toggle -> _state.update { s ->
                if (s.albums == null) s
                else s.copy(
                    selected = toggleAlbum(
                        s.selected,
                        intent.id,
                        s.albums.mapTo(HashSet()) { it.id }), lost = false
                )
            }

            is AlbumPickerIntent.SetAll -> _state.update {
                it.copy(
                    selected = if (intent.all) null else emptySet(),
                    lost = false
                )
            }

            AlbumPickerIntent.Save -> {
                val s = _state.value
                if (s.albums == null || s.failed || s.selected?.isEmpty() == true) return
                // 시트는 바로 닫는다. 실패해도 앱이 죽지 않고 알리게 한다 (B1 의 launchSafely 와 같은 원칙)
                viewModelScope.launch {
                    try {
                        setAlbums(s.selected ?: emptySet())   // 모든 앨범 = 빈 집합으로 저장
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("Albums", "앨범 선택 저장 실패", e)
                        _state.update { it.copy(saveFailed = true) }
                    }
                }
            }
            AlbumPickerIntent.SaveFailedShown -> _state.update { it.copy(saveFailed = false) }
        }
    }
}
// 실제 앱에서는 화면 문구를 strings.xml 로 옮긴다.