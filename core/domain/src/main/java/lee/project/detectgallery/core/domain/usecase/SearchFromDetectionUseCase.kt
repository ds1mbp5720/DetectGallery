package lee.project.detectgallery.core.domain.usecase

import lee.project.detectgallery.core.domain.logic.FOOD_SCENE_LABELS
import lee.project.detectgallery.core.domain.logic.crop
import lee.project.detectgallery.core.domain.logic.rrf
import lee.project.detectgallery.core.domain.model.*
import lee.project.detectgallery.core.domain.port.PhotoIndexRepository
import javax.inject.Inject

/** 실시간 카메라에서 탭한 박스로 갤러리 검색. 대상 종류마다 판정 근거가 다르다. */
class SearchFromDetectionUseCase @Inject constructor(
    private val findSimilar: FindSimilarPhotosUseCase,
    private val index: PhotoIndexRepository,
) {
    suspend operator fun invoke(frame: ImageFrame, target: LiveDetection): SimilarResult {
        val crop = frame.crop(target.box, padding = if (target.kind == DetectionKind.FACE) 0.2f else 0.05f)
        return when (target.kind) {
            DetectionKind.FACE, DetectionKind.PERSON -> when (val r = findSimilar(crop, ImageSearchMode.PERSON)) {
                // 탭한 박스를 잘랐으므로 가장 큰 얼굴이 탭한 사람이다 (사람 박스 안에 남의 얼굴이 걸칠 수 있다)
                is SimilarResult.MultipleFaces -> findSimilar.byFace(r.faces.maxBy { it.box.area })
                else -> r
            }
            DetectionKind.OBJECT -> findSimilar(crop, ImageSearchMode.SCENE)
            DetectionKind.FOOD -> {
                // 잘라낸 음식과 비슷한 사진 + 음식 장면 라벨(FOOD_SCENE_LABELS) 사진을 순위 병합
                val similar = (findSimilar(crop, ImageSearchMode.SCENE) as SimilarResult.Matches).items.map { it.mediaId }
                val labeled = index.photosByLabels(FOOD_SCENE_LABELS.toList())
                SimilarResult.Matches(rrf(listOf(similar, labeled)).take(100).map { PhotoMatch(it, 0f) })
            }
        }
    }
}