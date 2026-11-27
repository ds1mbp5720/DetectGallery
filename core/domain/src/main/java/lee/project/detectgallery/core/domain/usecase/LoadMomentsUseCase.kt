package lee.project.detectgallery.core.domain.usecase

import lee.project.detectgallery.core.domain.logic.MOMENT_LABELS
import lee.project.detectgallery.core.domain.logic.Moment
import lee.project.detectgallery.core.domain.logic.MomentsResult
import lee.project.detectgallery.core.domain.logic.StateTag
import lee.project.detectgallery.core.domain.logic.dot
import lee.project.detectgallery.core.domain.logic.fillLocations
import lee.project.detectgallery.core.domain.logic.groupMoments
import lee.project.detectgallery.core.domain.logic.homeOf
import lee.project.detectgallery.core.domain.logic.momentTitle
import lee.project.detectgallery.core.domain.logic.pickHighlights
import lee.project.detectgallery.core.domain.model.Place
import lee.project.detectgallery.core.domain.port.PhotoIndexRepository
import lee.project.detectgallery.core.domain.port.PhotoSource
import lee.project.detectgallery.core.domain.port.PlaceDirectory
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 추억 목록 (C12). 색인의 촬영 시각·위치 → 위치 메우기 → 묶기 → 묶음마다 제목(지명·주제)과 대표 사진.
 * 저장하지 않고 열 때마다 계산한다(1만 장 1초 안팎). 메운 위치·"집" 은 이 계산 안에서만 쓴다.
 */
class LoadMomentsUseCase @Inject constructor(
    private val index: PhotoIndexRepository,
    private val source: PhotoSource,
    private val places: PlaceDirectory,
) {
    suspend operator fun invoke(zone: ZoneId = ZoneId.systemDefault()): MomentsResult = withContext(Dispatchers.Default) {
        val photos = index.momentPhotos()
        val scenes = index.sceneVectors().associateBy { it.mediaId }
        val filled = fillLocations(photos) { a, b -> scenes[a]?.let { x -> scenes[b]?.let { y -> dot(x, y) } } }
        val groups = groupMoments(filled, homeOf(filled))
        val tags = index.labelsOf(MOMENT_LABELS.keys + StateTag.BLURRY + StateTag.DARK + StateTag.SMILING + StateTag.GROUP)
        val hashes = index.dHashes()
        // 좌표를 약 1km 로 반올림한 칸마다 한 번만 찾는다: 원본 좌표는 장마다 달라 그대로 키로 쓰면 1만 장 × 250곳을 다 비교한다
        val placeOf = HashMap<Pair<Long, Long>, Place?>()
        val moments = groups.map { g ->
            ensureActive()
            val ids = g.map { it.id }
            val ps = g.mapNotNull { p ->
                p.location?.let { loc ->
                    val k = Math.round(loc.lat * 100) to Math.round(loc.lon * 100)
                    if (k in placeOf) placeOf[k] else places.nearest(loc).also { placeOf[k] = it }
                }
            }
            val located = g.count { it.location != null }
            val (title, sub) = momentTitle(ps, located, ids.map { tags[it].orEmpty() }, g.first().takenAt, g.last().takenAt, ids.size, zone)
            Moment(ids.first(), ids, pickHighlights(ids, tags, hashes), title, sub, g.first().takenAt, g.last().takenAt, g.count { it.estimated })
        }
        MomentsResult(moments, located = photos.count { it.location != null }, total = photos.size, locationAllowed = source.canReadLocation())
    }
}
// ponytail: 목록·상세가 각각 계산한다(1초 안팎 두 번). 느리면 색인 실행 끝에 결과를 캐시.