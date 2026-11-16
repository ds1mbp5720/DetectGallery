package lee.project.detectgallery.feature.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import lee.project.detectgallery.core.domain.model.Person
import lee.project.detectgallery.core.domain.port.PersonRepository
import lee.project.detectgallery.core.domain.port.SettingsRepository
import lee.project.detectgallery.core.domain.usecase.MergePersonsUseCase
import lee.project.detectgallery.core.domain.usecase.SetFaceFeatureUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PeopleUiState(
    val people: List<Person> = emptyList(),
    val mergeSource: Person? = null,     // 길게 누른 인물. 다음에 누른 인물로 합친다
    val facesEnabled: Boolean = true,
    val confirmDisable: Boolean = false, // 끄기 확인 대화상자 (데이터를 지우므로 확인을 받는다)
)

sealed interface PeopleIntent {
    data class StartMerge(val person: Person) : PeopleIntent
    data class MergeInto(val target: Person) : PeopleIntent
    data object CancelMerge : PeopleIntent
    data class ToggleFaces(val enabled: Boolean) : PeopleIntent
    data object ConfirmDisable : PeopleIntent
    data object CancelDisable : PeopleIntent
}

@HiltViewModel
class PeopleViewModel @Inject constructor(
    persons: PersonRepository,
    settings: SettingsRepository,
    private val mergePersons: MergePersonsUseCase,
    private val setFaceFeature: SetFaceFeatureUseCase,
) : ViewModel() {

    private val mergeSource = MutableStateFlow<Person?>(null)
    private val confirmDisable = MutableStateFlow(false)

    /** 사진 수 많은 순. 이름을 붙이면 키워드 검색에서 그 이름으로 찾을 수 있다. */
    val state: StateFlow<PeopleUiState> =
        combine(persons.persons(), mergeSource, settings.facesEnabled, confirmDisable) { people, source, enabled, confirm ->
            PeopleUiState(people, source, enabled, confirm)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeopleUiState())

    fun onIntent(intent: PeopleIntent) {
        when (intent) {
            is PeopleIntent.StartMerge -> mergeSource.value = intent.person
            PeopleIntent.CancelMerge -> mergeSource.value = null
            is PeopleIntent.MergeInto -> {
                val source = mergeSource.value ?: return
                mergeSource.value = null
                if (source.id != intent.target.id) viewModelScope.launch { mergePersons(source.id, intent.target.id) }
            }
            is PeopleIntent.ToggleFaces ->
                if (intent.enabled) viewModelScope.launch { setFaceFeature(true) } else confirmDisable.value = true
            PeopleIntent.ConfirmDisable -> {
                confirmDisable.value = false
                viewModelScope.launch { setFaceFeature(false) }
            }
            PeopleIntent.CancelDisable -> confirmDisable.value = false
        }
    }
}