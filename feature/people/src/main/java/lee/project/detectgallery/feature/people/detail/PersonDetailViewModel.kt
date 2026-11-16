package lee.project.detectgallery.feature.people.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import lee.project.detectgallery.core.domain.model.PersonFace
import lee.project.detectgallery.core.domain.port.PersonRepository
import lee.project.detectgallery.core.domain.usecase.ExcludeFacesUseCase
import lee.project.detectgallery.core.domain.usecase.NamePersonUseCase
import lee.project.detectgallery.core.domain.usecase.SplitPersonUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PersonDetailUiState(
    val name: String? = null,
    val faces: List<PersonFace> = emptyList(),
    val selected: Set<Long> = emptySet(),   // 고른 얼굴 id. 비어 있으면 보기 모드
    val renaming: Boolean = false,
    val gone: Boolean = false,              // 얼굴을 다 빼서 인물이 사라짐 → 화면을 닫는다
    val notice: String? = null,             // 나누기·빼기 실패 안내 (스낵바)
    val loaded: Boolean = false,            // 첫 DB 결과 전에는 "사진 없음" 안내·"이름 없는 인물" 을 그리지 않는다
)

sealed interface PersonDetailIntent {
    data class Toggle(val faceId: Long) : PersonDetailIntent
    data object ClearSelection : PersonDetailIntent
    data object SplitSelected : PersonDetailIntent      // 고른 얼굴 = 다른 사람 → 새 인물로
    data object ExcludeSelected : PersonDetailIntent    // 고른 얼굴 = 사람 얼굴 아님 → 빼기
    data class ShowRename(val show: Boolean) : PersonDetailIntent
    data class Rename(val name: String) : PersonDetailIntent
    data object NoticeShown : PersonDetailIntent
}

@HiltViewModel
class PersonDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    persons: PersonRepository,
    private val namePerson: NamePersonUseCase,
    private val splitPerson: SplitPersonUseCase,
    private val excludeFaces: ExcludeFacesUseCase,
) : ViewModel() {

    private val personId: Long = checkNotNull(savedState["personId"])
    private val selected = MutableStateFlow(emptySet<Long>())
    private val renaming = MutableStateFlow(false)
    private val notice = MutableStateFlow<String?>(null)

    val state: StateFlow<PersonDetailUiState> = combine(
        persons.persons().map { list -> list.firstOrNull { it.id == personId } },
        persons.faces(personId),
        selected,
        renaming,
        notice,
    ) { person, faces, sel, ren, msg ->
        PersonDetailUiState(
            name = person?.name,
            faces = faces,
            selected = sel intersect faces.mapTo(HashSet()) { it.faceId },   // 사라진 얼굴은 선택에서 뺀다
            renaming = ren,
            gone = person == null,
            notice = msg,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonDetailUiState())

    fun onIntent(intent: PersonDetailIntent) {
        when (intent) {
            is PersonDetailIntent.Toggle -> selected.update { if (intent.faceId in it) it - intent.faceId else it + intent.faceId }
            PersonDetailIntent.ClearSelection -> selected.value = emptySet()
            PersonDetailIntent.SplitSelected -> act { splitPerson(it) != null }
            PersonDetailIntent.ExcludeSelected -> act { excludeFaces(it) > 0 }
            PersonDetailIntent.NoticeShown -> notice.value = null
            is PersonDetailIntent.ShowRename -> renaming.value = intent.show
            is PersonDetailIntent.Rename -> {
                renaming.value = false
                viewModelScope.launch { namePerson(personId, intent.name) }
            }
        }
    }

    /** block 이 false 면 고른 얼굴이 그사이 사라진 것(재색인·기능 끄기). 예외는 DB 오류. 둘 다 알려 준다. */
    private fun act(block: suspend (List<Long>) -> Boolean) {
        val ids = state.value.selected.toList()
        if (ids.isEmpty()) return
        selected.value = emptySet()
        viewModelScope.launch {
            val ok = try { block(ids) } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            if (!ok) notice.value = "바꾸지 못했어요. 목록이 새로 고쳐졌으니 다시 골라 주세요."
        }
    }
}