package lee.project.detectgallery.core.data.repo

import androidx.room.withTransaction
import lee.project.detectgallery.core.data.db.*
import lee.project.detectgallery.core.domain.logic.StateTag
import lee.project.detectgallery.core.domain.model.*
import lee.project.detectgallery.core.domain.port.PhotoIndexRepository
import lee.project.detectgallery.core.domain.port.QueryVocabulary
import lee.project.detectgallery.core.domain.usecase.IndexNewPhotosUseCase.Companion.INDEX_VERSION
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomPhotoIndexRepository @Inject constructor(
    private val db: AppDatabase,
    private val dao: PhotoIndexDao,
) : PhotoIndexRepository, QueryVocabulary {

    // 검색마다 DB 에서 수만 개 BLOB 을 읽지 않도록 메모리에 둔다.
    // 저장·삭제 시 바뀐 사진의 행만 고친다. 통째로 비우면 첫 색인 동안 50장마다 전체를 다시 읽는다.
    //
    // DB 쓰기와 캐시 갱신은 NonCancellable 로 묶는다. Room suspend 호출은 커밋 뒤에도 취소돼 있으면 예외를 던지므로,
    // 그대로 두면 (WorkManager 10분 제한·화면 이탈로) 커밋만 되고 캐시는 옛 상태로 앱 종료 때까지 남는다.
    private val lock = Mutex()
    private var sceneCache: List<IndexedVector>? = null
    private var faceCache: List<IndexedVector>? = null

    override suspend fun save(all: List<PhotoAnalysis>, version: Int) {
        // 실패한 사진 중 내용이 그대로인 것(변경 번호 같음)은 기존 결과를 지우지 않는다.
        // 지우면 다음에 성공해도 이어받을 얼굴이 없어 합쳐 둔 인물이 그 사진에서 다시 갈라진다.
        // 내용이 바뀐 사진의 실패는 옛 결과가 틀린 정보라 아래 replace 로 지운다.
        val failedIds = all.filter { it.failed }.map { it.ref.mediaId }
        val stored = failedIds.chunked(500).flatMap { dao.statesOf(it) }.associateBy { it.mediaId }
        val keepIds = all.filter { a -> a.failed && stored[a.ref.mediaId]?.generation == a.ref.generation }
            .mapTo(HashSet()) { it.ref.mediaId }
        keepIds.chunked(500).forEach { dao.markFailed(it) }
        val batch = all.filterNot { it.ref.mediaId in keepIds }
        if (batch.isEmpty()) return

        val ids = batch.map { it.ref.mediaId }
        val scenes = batch.mapNotNull { a -> a.scene?.let { IndexedVector(a.ref.mediaId, it) } }
        val faces = batch.flatMap { a -> a.faces.map { f -> a.ref.mediaId to f } }
        withContext(NonCancellable) {
            // 이전 얼굴 읽기와 교체를 한 트랜잭션으로: 그 사이 사용자가 "얼굴 아님"·나누기를 하면 옛 상태로 덮어쓴다
            val excludedNow = db.withTransaction {
                // 재색인되는 사진의 이전 얼굴. 같은 자리 얼굴에 인물(이름·합치기 결과)과 "얼굴 아님" 을 이어 준다.
                // 미배정 이전 얼굴도 짝짓기 후보에 넣는다: 빼면 더 잘 겹치는 미배정 얼굴을 두고 옆 사람의 인물을 받는다.
                val previous = ids.chunked(500).flatMap { dao.facesOf(it) }.groupBy { it.mediaId }
                val matched = batch.flatMap { a -> matchPrevious(a.faces.map { it.box }, previous[a.ref.mediaId].orEmpty()) }
                val excluded = matched.map { it?.excluded ?: false }
                dao.replace(
                    photos = batch.map {
                        PhotoEntity(
                            it.ref.mediaId, it.ref.dateTaken, it.ref.generation, it.dHash,
                            indexVersion = if (it.failed) 0 else version,
                            facesDone = !it.failed && it.facesDone,
                            vectorVersion = if (it.failed) 0 else version,
                        )
                    },
                    scenes = scenes.map { SceneEmbeddingEntity(it.mediaId, it.vector) },
                    faces = faces.mapIndexed { i, (id, f) ->
                        FaceEntity(
                            mediaId = id, left = f.box.left, top = f.box.top, right = f.box.right, bottom = f.box.bottom,
                            vector = f.vector,
                            personId = matched[i]?.personId,
                            excluded = excluded[i],
                        )
                    },
                    labels = batch.flatMap { a -> a.labels.map { PhotoLabelEntity(a.ref.mediaId, it.name, it.confidence) } },
                )
                excluded
            }
            // "얼굴 아님" 을 이어받은 얼굴은 검색 캐시에 넣지 않는다 (DB 쿼리와 같은 조건)
            val searchable = faces.filterIndexed { i, _ -> !excludedNow[i] }.map { (id, f) -> IndexedVector(id, f.vector) }
            patchCache(ids.toSet(), scenes, searchable)
        }
    }

    override suspend fun delete(ids: Set<Long>) {
        if (ids.isEmpty()) return
        withContext(NonCancellable) {
            dao.deleteAll(ids.toList())
            patchCache(ids, emptyList(), emptyList())
        }
    }

    /** 캐시가 아직 안 만들어졌으면(null) 손대지 않는다. 다음 조회 때 DB 에서 통째로 읽는다. */
    private suspend fun patchCache(ids: Set<Long>, scenes: List<IndexedVector>, faces: List<IndexedVector>) = lock.withLock {
        sceneCache = sceneCache?.let { c -> c.filterNot { it.mediaId in ids } + scenes }
        faceCache = faceCache?.let { c -> c.filterNot { it.mediaId in ids } + faces }
    }

    override suspend fun sceneVectors() = lock.withLock {
        sceneCache ?: dao.scenes(INDEX_VERSION).map { IndexedVector(it.mediaId, it.vector) }.also { sceneCache = it }
    }

    override suspend fun faceVectors() = lock.withLock {
        faceCache ?: dao.faces(INDEX_VERSION).map { IndexedVector(it.mediaId, it.vector) }.also { faceCache = it }
    }

    override suspend fun dHashes() = dao.hashes().associate { it.mediaId to it.dHash }
    override suspend fun photosByPerson(names: List<String>) = dao.byPersons(names.distinct(), names.distinct().size).toSet()
    override suspend fun photosByLabels(labels: List<String>) = dao.byLabels(labels)
    override suspend fun photosInRange(range: LongRange) = dao.inRange(range.first, range.last + 1).toSet()

    override suspend fun indexStates() =
        dao.states().associate { it.mediaId to IndexedState(it.generation, it.indexVersion, it.facesDone) }

    override suspend fun deleteAllFaceData() = withContext(NonCancellable) {
        dao.deleteAllFaceData(StateTag.FACE_TAGS.toList())
        lock.withLock { faceCache = emptyList() }   // 지운 얼굴로 검색되지 않게
    }

    /** 얼굴 "아님" 표시가 바뀌었을 때. 다음 검색에서 DB 기준으로 다시 읽는다. */
    override suspend fun invalidateFaceCache() = lock.withLock { faceCache = null }

    override suspend fun personNames() = dao.personNames().toSet()
    override suspend fun labelsForAlias(alias: String) = dao.labelsFor(alias)
}

/**
 * 새 얼굴마다 같은 자리의 이전 얼굴 (IoU ≥ 0.5, 없으면 null). 인물과 "얼굴 아님" 표시를 이어받는 데 쓴다.
 * 겹침이 큰 쌍부터 1:1 로 짝짓는다: 얼굴 상자가 겹친 단체 사진에서 두 새 얼굴이 한 이전 얼굴을 함께 이어받지 않게.
 */
internal fun matchPrevious(boxes: List<Box>, previous: List<FaceEntity>): List<FaceEntity?> {
    val pairs = boxes.indices.flatMap { i ->
        previous.map { p -> Triple(i, p, boxes[i].iou(Box(p.left, p.top, p.right, p.bottom))) }
    }.filter { it.third >= 0.5f }.sortedByDescending { it.third }
    val out = arrayOfNulls<FaceEntity>(boxes.size)
    val used = HashSet<Long>()
    for ((i, p, _) in pairs) if (out[i] == null && used.add(p.id)) out[i] = p
    return out.toList()
}
// ponytail: 탐욕 매칭 O(n·m log). 사진당 얼굴 수십 개라 충분하다. 헝가리안은 필요 없다.