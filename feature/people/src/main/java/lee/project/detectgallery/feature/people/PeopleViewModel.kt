package lee.project.detectgallery.feature.people

import android.util.Log
import kotlinx.coroutines.CancellationException
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
import lee.project.detectgallery.core.domain.logic.FaceAnalysisState
import lee.project.detectgallery.core.domain.logic.faceAnalysisState
import lee.project.detectgallery.core.domain.model.LibraryStatus
import lee.project.detectgallery.core.domain.port.IndexStatus
import lee.project.detectgallery.core.domain.port.VisionEncoder

data class PeopleUiState(
    val people: List<Person> = emptyList(),
    val mergeSource: Person? = null,     // 길게 누른 인물. 다음에 누른 인물로 합친다
    val facesEnabled: Boolean = false,
    // 켜기(true)·끄기(false) 확인 대화상자. 켜기는 얼굴 분석 동의, 끄기는 데이터 삭제 확인
    val confirmFaces: Boolean? = null,
    val faceState: FaceAnalysisState? = null,   // null = 현황 첫 값 전
    val notice: String? = null,                  // 합치기·켜기/끄기 실패 안내 (스낵바)
    val faceModel: Boolean = true,               // 얼굴 모델이 있는가. 없으면 켜기 스위치를 막는다
)

sealed interface PeopleIntent {
    data class StartMerge(val person: Person) : PeopleIntent
    data class MergeInto(val target: Person) : PeopleIntent
    data object CancelMerge : PeopleIntent
    data class ToggleFaces(val enabled: Boolean) : PeopleIntent
    data object ConfirmFaces : PeopleIntent
    data object CancelFaces : PeopleIntent
    data object NoticeShown : PeopleIntent
}

@HiltViewModel
class PeopleViewModel @Inject constructor(
    persons: PersonRepository,
    settings: SettingsRepository,
    private val mergePersons: MergePersonsUseCase,
    private val setFaceFeature: SetFaceFeatureUseCase,
    indexStatus: IndexStatus,
    vision: VisionEncoder,     // 얼굴 모델 유무(facesAvailable)만 본다. 모델들은 lazy 라 여기서 읽히지 않는다
) : ViewModel() {

    private val mergeSource = MutableStateFlow<Person?>(null)
    private val confirmFaces = MutableStateFlow<Boolean?>(null)
    private val notice = MutableStateFlow<String?>(null)

    /** 사진 수 많은 순. 이름을 붙이면 키워드 검색에서 그 이름으로 찾을 수 있다. */
    val state: StateFlow<PeopleUiState> =
        combine(
            // combine 은 5개까지라 확인 대화상자·안내를 한 쌍으로 묶는다
            persons.persons(),
            mergeSource,
            settings.facesEnabled,
            confirmFaces.combine(notice, ::Pair),
            // 현황 첫 값(갤러리 수, 약 0.5초)을 기다리느라 인물 목록이 늦게 뜨지 않게 null 로 먼저 시작한다
            indexStatus.library.map<LibraryStatus, LibraryStatus?> { it }.onStart { emit(null) },
        ) { people, source, enabled, (confirm, msg), lib ->
            PeopleUiState(
                people, source, enabled, confirm,
                faceState = faceAnalysisState(
                    modelAvailable = vision.facesAvailable,
                    enabled = enabled,
                    galleryCount = lib?.galleryCount,
                    sceneAnalyzed = lib?.analyzedCount ?: 0,
                    facesAnalyzed = lib?.facesAnalyzed ?: 0,
                    indexing = lib?.indexing == true,
                    personCount = people.size,
                ),
                notice = msg,
                faceModel = vision.facesAvailable,
            )
            // 첫 값은 지금 설정으로: 기본값(꺼짐)으로 시작하면 켜 둔 사용자에게도 잠깐 "꺼짐" 이 보이고, 그때 누르면 켜기 동의가 뜬다
        }.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            PeopleUiState(
                facesEnabled = settings.facesEnabledNow(),
                faceModel = vision.facesAvailable
            ),
        )

    fun onIntent(intent: PeopleIntent) {
        when (intent) {
            is PeopleIntent.StartMerge -> mergeSource.value = intent.person
            PeopleIntent.CancelMerge -> mergeSource.value = null
            is PeopleIntent.MergeInto -> {
                val source = mergeSource.value ?: return
                mergeSource.value = null
                if (source.id != intent.target.id) {
                    launchSafely("합치지 못했어요. 다시 해 주세요.") {
                        mergePersons(
                            source.id,
                            intent.target.id
                        )
                    }
                }
            }

            // 켜기·끄기 모두 확인을 받는다 (켜기 = 얼굴 분석 동의, 끄기 = 얼굴 데이터·이름 삭제)
            is PeopleIntent.ToggleFaces -> confirmFaces.value = intent.enabled
            PeopleIntent.ConfirmFaces -> {
                val on = confirmFaces.value ?: return
                confirmFaces.value = null
                launchSafely(TOGGLE_FAILED) { setFaceFeature(on) }
            }

            PeopleIntent.CancelFaces -> confirmFaces.value = false
            PeopleIntent.NoticeShown -> notice.value = null
        }
    }

    /** DB 오류 등으로 실패해도 앱이 죽지 않게: 취소는 그대로 넘기고, 그 밖의 예외는 로그 + 안내로 바꾼다. */
    private fun launchSafely(failMessage: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, failMessage, e)
                notice.value = failMessage
            }
        }
    }

    private companion object {
        const val TAG = "People"
        const val TOGGLE_FAILED = "인물 기능을 바꾸지 못했어요. 다시 해 주세요."
    }
}