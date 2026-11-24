package lee.project.detectgallery.core.domain.usecase

import lee.project.detectgallery.core.domain.logic.CleanupSets
import lee.project.detectgallery.core.domain.logic.StateTag
import lee.project.detectgallery.core.domain.logic.groupDuplicates
import lee.project.detectgallery.core.domain.port.PhotoIndexRepository
import lee.project.detectgallery.core.domain.port.PhotoSource
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 정리 모드(C10)의 네 목록. 흔들린·어두운은 색인의 상태 태그, 겹치는 사진은 색인의 dHash, 스크린샷은 MediaStore 폴더로 고른다.
 *
 * 색인은 마지막 분석 시점의 것이다. 그 뒤 지워졌거나(다른 앱·방금 휴지통, 색인 정리는 약 10초 뒤) 앨범 선택이 바뀌어 범위에서
 * 빠진 사진이 남아 있을 수 있다 → 앞의 세 목록을 지금 MediaStore 에 보이는 사진(pixelCounts 의 키)으로 거른다.
 * 거르지 않으면 지워진 사진이 "남기기 추천" 이 되어, 남은 유일한 사본이 지울 후보가 된다.
 * 그래서 pixelCounts 조회가 실패하면 화면 전체를 실패로 둔다(무엇이 남아 있는지 모르는 채로 지울 후보를 보여 주지 않는다).
 */
class LoadCleanupUseCase @Inject constructor(
    private val index: PhotoIndexRepository,
    private val source: PhotoSource,
) {
    suspend operator fun invoke(): CleanupSets = coroutineScope {
        val blurry = async { index.photosByLabels(listOf(StateTag.BLURRY)) }
        val dark = async { index.photosByLabels(listOf(StateTag.DARK)) }
        val hashes = async { index.dHashes() }
        val pixels = async { source.pixelCounts() }
        val shots = async { source.screenshots() }
        val px = pixels.await()
        val visible = px.keys
        val b = blurry.await().filter { it in visible }
        // 1만 장이면 수 초까지 걸릴 수 있어 메인 스레드 밖에서, 화면을 나가면 멈추게
        val groups = withContext(Dispatchers.Default) {
            groupDuplicates(hashes.await().filterKeys { it in visible }, b.toHashSet(), px, checkCancel = { ensureActive() })
        }
        CleanupSets(groups, b, dark.await().filter { it in visible }, shots.await())
    }
}