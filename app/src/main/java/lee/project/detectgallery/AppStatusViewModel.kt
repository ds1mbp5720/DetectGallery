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

/** 탭 공통 상단 현황. 읽기 전용이라 Intent 가 없다. null = 첫 값 전. */
@HiltViewModel
class AppStatusViewModel @Inject constructor(indexStatus: IndexStatus) : ViewModel() {
    val library: StateFlow<LibraryStatus?> =
        indexStatus.library.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}