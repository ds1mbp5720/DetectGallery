package lee.project.detectgallery.core.domain.usecase

import lee.project.detectgallery.core.domain.port.IndexTrigger
import lee.project.detectgallery.core.domain.port.PersonRepository
import lee.project.detectgallery.core.domain.port.PhotoIndexRepository
import lee.project.detectgallery.core.domain.port.SettingsRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 한 인물에 다른 사람이 섞였을 때, 고른 얼굴들을 새 인물로 떼어 낸다.
 * 두 인물의 중심을 다시 계산해야 이후 새 얼굴이 맞는 쪽으로 붙는다.
 */
class SplitPersonUseCase @Inject constructor(private val persons: PersonRepository) {
    /**
     * 새 인물 id. 고른 얼굴이 이미 없으면 null (화면을 보는 사이 그 사진이 다시 분석돼 얼굴 id 가 바뀐 경우).
     * 예외로 던지지 않는다: 화면에서는 "다시 골라 주세요" 로 안내하면 되는 정상 흐름이다.
     */
    suspend operator fun invoke(faceIds: List<Long>): Long? {
        if (faceIds.isEmpty()) return null
        // 빼기와 같이: 나누기 직후 화면을 나가도 중심 재계산까지 끝내야 섞인 중심이 남지 않는다
        return withContext(NonCancellable) {
            persons.split(faceIds)?.also { persons.recomputeCenters(force = true) }
        }
    }
}

/** 사람 얼굴이 아니거나(오검출) 묶을 필요 없는 얼굴을 뺀다. 다시 배정·검색되지 않는다. 뺀 개수를 돌려준다. */
class ExcludeFacesUseCase @Inject constructor(
    private val persons: PersonRepository,
    private val index: PhotoIndexRepository,
) {
    suspend operator fun invoke(faceIds: List<Long>): Int {
        if (faceIds.isEmpty()) return 0
        // 빼기 직후 화면을 나가도(viewModelScope 취소) 캐시 비우기까지 끝내야 뺀 얼굴이 검색에 안 남는다
        return withContext(NonCancellable) {
            val n = persons.excludeFaces(faceIds)  // 얼굴을 전부 뺀 인물(이름 있어도)도 여기서 지워진다
            if (n > 0) {
                index.invalidateFaceCache()        // 검색이 메모리의 얼굴 목록을 쓰므로 비워야 뺀 얼굴이 안 걸린다
                persons.recomputeCenters(force = true)
            }
            n                                      // 0 = 고른 얼굴이 이미 없음 (다시 분석됨)
        }
    }
}

/**
 * 인물 기능 켜기·끄기.
 * 끄기: 얼굴·인물(이름 포함)을 지운다. 얼굴 벡터는 생체정보에 가까우므로 "꺼짐" 은 "보관하지 않음" 이어야 한다.
 * 켜기: 색인을 요청한다. 얼굴 분석을 안 한 사진(facesDone = false)을 다시 분석한다 (사진 단위라 장면·라벨도 다시 계산).
 *
 * 앱이 중간에 종료돼도 결과가 맞도록 설정 값만 단일 출처로 둔다:
 * - 끄기 도중 종료 → 다음 색인 실행 끝에서 "꺼져 있으면 지우기" 가 다시 지운다.
 * - 켜기 도중 종료 → 다음 색인 실행이 facesDone = false 인 사진을 알아서 다시 분석한다.
 * 그래서 값이 이미 같아도 정리·요청은 다시 해도 된다 (멱등).
 */
class SetFaceFeatureUseCase @Inject constructor(
    private val settings: SettingsRepository,
    private val index: PhotoIndexRepository,
    private val trigger: IndexTrigger,
) {
    suspend operator fun invoke(enabled: Boolean) {
        settings.setFacesEnabled(enabled)         // 먼저 바꿔야 도는 색인이 새 얼굴을 더 만들지 않는다
        if (!enabled) index.deleteAllFaceData()   // 즉시 지운다. 겹친 저장이 남기면 색인 끝의 정리가 지운다
        trigger.requestIndex()                    // 켜기: 얼굴 채우기 / 끄기: 도는 색인 뒤에 정리 실행
    }
}