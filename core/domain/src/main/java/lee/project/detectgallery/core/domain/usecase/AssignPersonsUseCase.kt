package lee.project.detectgallery.core.domain.usecase

import lee.project.detectgallery.core.domain.logic.Thresholds
import lee.project.detectgallery.core.domain.logic.nearest
import lee.project.detectgallery.core.domain.model.PersonCenter
import lee.project.detectgallery.core.domain.port.PersonRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 인물이 없는 얼굴을 가장 가까운 인물에 붙이고, 없으면 새 인물을 만든다. 결과는 한 번에 저장.
 * createNew = false 면 맞는 인물이 없는 얼굴은 미배정으로 둔다. 모델 교체 재색인 중에는 아직 옛 중심인 인물(이름 포함)이
 * 후보에서 빠져 있어, 새로 만들면 같은 사람이 이름 인물과 새 인물로 갈린다. 재색인이 끝나면 다시 배정된다.
 * 저품질 얼굴(옆얼굴·작은 얼굴, C3)은 맞는 인물에 붙기만 하고 새 인물을 만들지 않는다 → 1회성 잡인물이 줄어든다.
 */
class AssignPersonsUseCase @Inject constructor(private val persons: PersonRepository) {
    suspend operator fun invoke(createNew: Boolean = true) {
        val centers = persons.centers().toMutableList()
        val assignments = mutableListOf<Pair<Long, Long>>()
        for (face in persons.unassignedFaces()) {
            val personId = nearest(face.vector, centers, Thresholds.PERSON)
                ?: if (createNew && !face.lowQuality) persons.create(face.vector).also { centers += PersonCenter(it, face.vector) } else continue
            assignments += face.id to personId
        }
        // 한 트랜잭션으로 저장 (1만 개를 개별로 하면 수십 초). 비어 있어도 부른다:
        // 저장소가 이때 "없어진 인물을 가리키는 얼굴"을 미배정으로 되돌려 다음 실행에서 다시 배정되게 한다.
        persons.assign(assignments)
    }
}
// ponytail: 새 인물의 중심은 첫 얼굴. 색인이 끝날 때 recomputeCenters 가 소속 얼굴 평균으로 바꾼다.
// 붙을 인물이 없는 저품질 얼굴은 미배정으로 남아 실행마다 다시 비교된다(가볍다). 많아지면 시도 표시를 둔다.
// 오분류가 잦으면 DBSCAN 등으로. 사용자의 합치기 UI 가 알고리즘 개선보다 효과가 크다.

class NamePersonUseCase @Inject constructor(private val persons: PersonRepository) {
    suspend operator fun invoke(personId: Long, name: String) {
        // 띄어쓰기는 뺀다: 검색어는 띄어쓰기로 나눠 단어마다 이름과 비교하므로 "김 철수"는 영영 찾을 수 없다.
        val compact = name.filterNot { it.isWhitespace() }
        require(compact.isNotEmpty()) { "이름이 비어 있다" }
        persons.rename(personId, compact)
    }
}

/**
 * 같은 사람이 둘로 나뉜 경우 합친다. 중심을 두 무리의 평균으로 다시 계산해야
 * 나중에 재색인된 얼굴이 합친 인물에 다시 붙는다 (첫 얼굴 중심만 쓰면 합치기가 조용히 풀린다).
 */
class MergePersonsUseCase @Inject constructor(private val persons: PersonRepository) {
    suspend operator fun invoke(from: Long, into: Long) {
        require(from != into) { "같은 인물끼리는 합칠 수 없다" }
        // 나누기·빼기와 같이: 합친 직후 화면을 나가도 중심 재계산까지 끝내야 다음 색인에서 합치기가 풀리지 않는다
        withContext(NonCancellable) {
            persons.merge(from, into)
            persons.recomputeCentersAfterEdit()   // 실패해도 합치기는 이미 저장됐다 (PersonEditUseCases.kt)
        }
    }
}