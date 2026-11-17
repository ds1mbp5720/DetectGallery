package lee.project.detectgallery.core.data.repo

import android.content.Context
import androidx.room.withTransaction
import lee.project.detectgallery.core.data.db.AppDatabase
import lee.project.detectgallery.core.data.db.PersonDao
import lee.project.detectgallery.core.data.db.PersonEntity
import lee.project.detectgallery.core.domain.logic.meanNormalized
import lee.project.detectgallery.core.domain.model.Box
import lee.project.detectgallery.core.domain.model.FaceRow
import lee.project.detectgallery.core.domain.model.Person
import lee.project.detectgallery.core.domain.model.PersonCenter
import lee.project.detectgallery.core.domain.model.PersonFace
import lee.project.detectgallery.core.domain.port.PersonRepository
import lee.project.detectgallery.core.domain.usecase.IndexNewPhotosUseCase.Companion.INDEX_VERSION
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log
import kotlinx.coroutines.CancellationException

@Singleton
class RoomPersonRepository @Inject constructor(
    private val db: AppDatabase,
    private val dao: PersonDao,
    @ApplicationContext context: Context,
) : PersonRepository {
    // "중심이 낡음" 표시. 색인이 마지막 배치 직후 10분 제한으로 멈추면 다음 실행은 처리할 사진이 0 이라
    // 재계산할 계기가 없다 → 프로세스가 바뀌어도 남도록 저장해 둔다.
    private val prefs = context.getSharedPreferences("persons", Context.MODE_PRIVATE)

    override suspend fun unassignedFaces() =
        dao.unassignedFaces(INDEX_VERSION).map { FaceRow(it.id, it.vector) }

    override suspend fun centers() =
        dao.centers(INDEX_VERSION).map { PersonCenter(it.personId, it.vector) }

    override suspend fun create(center: FloatArray) =
        dao.insert(PersonEntity(center = center, centerVersion = INDEX_VERSION))

    override suspend fun rename(personId: Long, name: String) = dao.rename(personId, name)
    override suspend fun deleteOrphans() = dao.deleteOrphans()

    override suspend fun assign(assignments: List<Pair<Long, Long>>) {
        dao.assignAll(assignments)
        if (assignments.isNotEmpty()) markCentersDirty()   // 새 인물의 중심은 아직 첫 얼굴뿐
    }

    override suspend fun merge(from: Long, into: Long) {
        if (dao.merge(
                from,
                into
            )
        ) markCentersDirty()   // false = 그사이 한쪽이 사라짐. 목록이 새로 고쳐지므로 다시 고르면 된다
    }

    /** 낡음 표시는 횟수로 센다. 0 = 깨끗함. 재계산은 시작할 때 본 값이 끝날 때도 같을 때만 0 으로 지운다. */
    override suspend fun markCentersDirty() = synchronized(prefs) {
        prefs.edit().putLong(KEY_DIRTY, prefs.getLong(KEY_DIRTY, 0) + 1).apply()
    }

    override suspend fun recomputeCenters(force: Boolean) {
        val seen = prefs.getLong(KEY_DIRTY, 0)
        if (!force && seen == 0L) return
        // 읽기·계산·쓰기를 한 트랜잭션으로. 그 사이에 화면에서 합치기가 끼면 합치기 전 상태로 계산한 중심이
        // 합친 인물을 덮어쓴다.
        try {
            db.withTransaction {
                // 현재 버전 얼굴만 쓴다. 모델 교체 중 새 얼굴이 아직 없는 인물은 옛 중심·옛 버전 그대로 남아 배정 후보에서 빠진다.
                val centers = dao.facesWithPerson(INDEX_VERSION)
                    .groupBy({ it.personId }, { it.vector })
                    .mapNotNull { (id, vs) -> meanNormalized(vs)?.let { id to it } }
                    .toMap()
                dao.updateCenters(centers, INDEX_VERSION)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 편집 화면은 이 예외를 삼키므로(recomputeCentersAfterEdit) 원인은 여기서 남긴다. 색인 Worker 는 그대로 재시도
            Log.e("Persons", "인물 중심 재계산 실패 (낡음 표시가 남아 다음 실행이 다시 계산)", e)
            throw e
        }
        // 커밋 뒤, 그사이 다른 쪽(배정·나누기)이 표시를 올리지 않았을 때만 지운다.
        // 예외·취소·프로세스 종료면 여기까지 오지 않아 표시가 남고 다음 실행이 다시 계산한다.
        synchronized(prefs) {
            if (prefs.getLong(KEY_DIRTY, 0) == seen) prefs.edit().putLong(KEY_DIRTY, 0).apply()
        }
    }

    private companion object {
        const val KEY_DIRTY = "centersDirtySeq"
    }

    override fun persons(): Flow<List<Person>> =
        dao.persons()
            .map { rows -> rows.map { Person(it.id, it.name, it.coverMediaId, it.photoCount) } }

    override fun faces(personId: Long): Flow<List<PersonFace>> =
        dao.facesOf(personId).map { rows ->
            rows.map {
                PersonFace(
                    it.id,
                    it.mediaId,
                    Box(it.left, it.top, it.right, it.bottom)
                )
            }
        }

    override suspend fun split(faceIds: List<Long>): Long? = db.withTransaction {
        // 읽기·옮기기를 한 트랜잭션으로: 그사이 재색인으로 얼굴 id 가 바뀌면 아무것도 안 옮기고 성공으로 보고하게 된다.
        val rows = faceIds.chunked(500).flatMap { dao.vectorsOf(it) }
        if (rows.isEmpty()) return@withTransaction null   // 얼굴이 이미 없음 → 화면이 "다시 골라 주세요"
        // 현재 버전 벡터가 있으면 그것으로. 없으면(모델 교체 중, 아직 재분석 전) 가장 많은 옛 버전으로 만들고
        // centerVersion = 0 으로 둔다 → 배정 후보에서 빠졌다가 재분석 뒤 recomputeCenters 가 새 중심을 준다.
        val current = rows.filter { it.version == INDEX_VERSION }
        val (vs, version) = if (current.isNotEmpty()) current to INDEX_VERSION
        else rows.groupBy { it.version }.maxBy { it.value.size }.value to 0
        val center = meanNormalized(vs.map { it.vector }) ?: return@withTransaction null
        val owners = faceIds.chunked(500).flatMap { dao.personIdsOf(it) }.distinct()
        val newId = dao.split(faceIds, center, version)
        // 얼굴을 전부 나눈 원래 인물: 이름이 있으면 사진 없는 이름 카드로 남기고(사용자가 "이 사람이 아니다" 라고 한 것이므로
        // 이름을 새 인물로 넘기지 않는다) 배정 후보에서 뺀다. 이름이 없으면 지운다.
        owners.chunked(500).forEach { dao.deleteUnnamedIfEmpty(it); dao.retireIfEmpty(it) }
        newId
    }.also { if (it != null) markCentersDirty() }

    override suspend fun excludeFaces(faceIds: List<Long>): Int = db.withTransaction {
        val owners = faceIds.chunked(500).flatMap { dao.personIdsOf(it) }.distinct()
        val n = faceIds.chunked(500).sumOf { dao.exclude(it) }
        // 얼굴을 전부 뺀 인물은 이름이 있어도 지운다. 사용자가 직접 "이 사람 얼굴 아님" 으로 전부 뺀 것이라 남길 이유가 없다.
        owners.chunked(500).forEach { dao.deleteIfEmpty(it) }
        n
    }.also { if (it > 0) markCentersDirty() }
}
// ponytail: recomputeCenters 는 모든 얼굴 벡터를 읽는다 (1만 얼굴 × 512차원 ≈ 20MB, 1회).
// 색인 실행이 끝날 때(낡았을 때만)와 합치기 때만 계산하므로 충분하다.