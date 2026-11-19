package lee.project.detectgallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import lee.project.detectgallery.core.domain.model.LibraryStatus
import lee.project.detectgallery.core.domain.port.IndexStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlinx.coroutines.flow.map
import lee.project.detectgallery.core.domain.port.SettingsRepository

/** 탭 공통 상단 현황. 읽기 전용이라 Intent 가 없다. null = 첫 값 전. 사진 범위 바꾸기는 AlbumPickerViewModel 이 맡는다. */
@HiltViewModel
class AppStatusViewModel @Inject constructor(
    indexStatus: IndexStatus,
    settings: SettingsRepository
) : ViewModel() {
    val library: StateFlow<LibraryStatus?> =
        indexStatus.library.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 고른 앨범만 분석 중인지 (현황 줄 "고른 앨범" 표기) */
    val albumFiltered: StateFlow<Boolean> =
        settings.albums.map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

}