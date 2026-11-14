package lee.project.detectgallery.core.domain.usecase

import lee.project.detectgallery.core.domain.logic.QueryParser
import lee.project.detectgallery.core.domain.logic.Thresholds
import lee.project.detectgallery.core.domain.logic.rankByBestMatch
import lee.project.detectgallery.core.domain.logic.rrf
import lee.project.detectgallery.core.domain.port.PhotoIndexRepository
import lee.project.detectgallery.core.domain.port.TextEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SearchPhotosByKeywordUseCase @Inject constructor(
    private val parser: QueryParser,
    private val text: TextEncoder,
    private val index: PhotoIndexRepository,
) {
    suspend operator fun invoke(raw: String, topK: Int = 300): List<Long> {
        val q = parser.parse(raw)

        // 반드시 만족: 인물·날짜
        val filters = buildList {
            if (q.personNames.isNotEmpty()) add(index.photosByPerson(q.personNames))
            q.dateRange?.let { add(index.photosInRange(it)) }
        }

        // 순위: 라벨 묶음마다 하나씩(단어별), 문장 의미(P3 CLIP. 그 전엔 TextEncoder 가 null)
        val ranked = buildList {
            q.labelGroups.forEach { group -> add(index.photosByLabels(group)) }
            // 이름이자 라벨인 단어: 그 인물 사진도 후보로 (필터가 아니라 순위 병합). id 내림차순 ≈ 최신순
            q.ambiguousNames.forEach { name -> add(index.photosByPerson(listOf(name)).sortedDescending()) }
            q.semantic?.let { s ->
                text.encode(s)?.let { v ->
                    val scenes = index.sceneVectors()
                    add(withContext(Dispatchers.Default) {
                        rankByBestMatch(listOf(v), scenes, Thresholds.SEMANTIC, topK).map { it.mediaId }
                    })
                }
            }
        }

        val hits = ranked.filter { it.isNotEmpty() }
        val candidates = when {
            hits.isNotEmpty() -> rrf(hits)
            // 알아들은 라벨이 있는데 0건이면 0건이다. "작년 바다"에서 바다 사진이 없으면
            // 작년 사진 전체를 보여주지 않는다 (바다 사진은 있는데 작년 것이 없을 때와 결과가 같아야 한다).
            q.labelGroups.isNotEmpty() -> emptyList()
            // 라벨 없이 인물·날짜만 있거나, 남은 말이 사전에 없는 경우("엄마 생일")
            // → 인물·날짜 조건만으로 보여준다. MediaStore id 내림차순 ≈ 최신순.
            filters.isNotEmpty() -> filters.reduce { a, b -> a intersect b }.sortedDescending()
            else -> emptyList()
        }
        return candidates.filter { id -> filters.all { id in it } }.take(topK)
    }
}