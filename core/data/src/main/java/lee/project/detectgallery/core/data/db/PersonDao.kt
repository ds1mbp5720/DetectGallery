package lee.project.detectgallery.core.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class FaceVectorIdRow(val id: Long, val vector: FloatArray)
data class VersionedVectorRow(val vector: FloatArray, val version: Int)
data class PersonCenterRow(val personId: Long, val vector: FloatArray)
data class PersonFaceRow(val personId: Long, val vector: FloatArray)
data class PersonRow(val id: Long, val name: String?, val coverMediaId: Long?, val photoCount: Int)
data class FaceBoxRow(
    val id: Long,
    val mediaId: Long,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

@Dao
interface PersonDao {
    /** "얼굴 아님" 으로 뺀 얼굴은 다시 배정하지 않는다 */
    /** 옛 모델 벡터(재색인 대기)는 배정하지 않는다 → 재색인되면 이어받거나 그때 배정된다 */
    @Query(
        """
        SELECT f.id, f.vector FROM face f JOIN photo p ON p.mediaId = f.mediaId
        WHERE f.personId IS NULL AND f.excluded = 0 AND p.vectorVersion = :version ORDER BY f.id
    """
    )
    suspend fun unassignedFaces(version: Int): List<FaceVectorIdRow>

    /** 인물 상세 화면용. 최신 사진부터 */
    @Query(
        """
        SELECT id, mediaId, `left`, top, `right`, bottom FROM face
        WHERE personId = :personId AND excluded = 0 ORDER BY mediaId DESC
    """
    )
    fun facesOf(personId: Long): Flow<List<FaceBoxRow>>

    /** 나누기용. 버전과 함께 읽어 현재 버전이 없으면(모델 교체 중) 옛 벡터로라도 나눌 수 있게 한다 */
    @Query(
        """
        SELECT f.vector AS vector, p.vectorVersion AS version FROM face f JOIN photo p ON p.mediaId = f.mediaId
        WHERE f.id IN (:faceIds)
    """
    )
    suspend fun vectorsOf(faceIds: List<Long>): List<VersionedVectorRow>

    @Query("SELECT DISTINCT personId FROM face WHERE id IN (:faceIds) AND personId IS NOT NULL")
    suspend fun personIdsOf(faceIds: List<Long>): List<Long>

    /** 얼굴이 하나도 남지 않은 인물을 이름과 관계없이 지운다 ("얼굴 아님" 으로 전부 뺀 경우) */
    @Query("DELETE FROM person WHERE id IN (:ids) AND id NOT IN (SELECT personId FROM face WHERE personId IS NOT NULL)")
    suspend fun deleteIfEmpty(ids: List<Long>): Int

    @Query("UPDATE face SET personId = :personId WHERE id IN (:faceIds)")
    suspend fun moveFacesTo(faceIds: List<Long>, personId: Long): Int

    /** 나누기로 비게 된 이름 없는 인물만 지운다. 이름 있는 인물은 사진 없는 이름 카드로 남는다 */
    @Query(
        """
        DELETE FROM person WHERE id IN (:ids) AND name IS NULL
          AND id NOT IN (SELECT personId FROM face WHERE personId IS NOT NULL)
    """
    )
    suspend fun deleteUnnamedIfEmpty(ids: List<Long>): Int

    /**
     * 나누기로 비게 된 이름 인물을 배정 후보에서 뺀다 (centerVersion = 0).
     * 중심이 방금 떼어 낸 얼굴들의 평균이라 그대로 두면 새 인물과 동률이 되어, 그 사람의 새 얼굴이 다시 이 이름에 붙는다.
     * 접근 범위 축소로 생긴 이름 카드는 여기를 거치지 않으므로 자동 재연결이 유지된다.
     */
    @Query(
        """
        UPDATE person SET centerVersion = 0
        WHERE id IN (:ids) AND name IS NOT NULL
          AND id NOT IN (SELECT personId FROM face WHERE personId IS NOT NULL)
    """
    )
    suspend fun retireIfEmpty(ids: List<Long>): Int

    @Query("UPDATE face SET excluded = 1, personId = NULL WHERE id IN (:faceIds) AND excluded = 0")
    suspend fun exclude(faceIds: List<Long>): Int

    /** 고른 얼굴로 새 인물을 만들고 옮긴다. 중심은 고른 얼굴들의 평균. */
    @Transaction
    suspend fun split(faceIds: List<Long>, center: FloatArray, version: Int): Long {
        val id = insert(PersonEntity(center = center, centerVersion = version))
        faceIds.chunked(500).forEach { moveFacesTo(it, id) }   // SQLite 변수 한도
        return id
    }

    /** 배정 후보. 옛 모델 공간의 중심은 차원이 같아도 비교할 수 없어 뺀다 (재계산되면 다시 후보가 된다) */
    @Query("SELECT id AS personId, center AS vector FROM person WHERE centerVersion = :version")
    suspend fun centers(version: Int): List<PersonCenterRow>

    @Query(
        """
        SELECT f.personId, f.vector FROM face f JOIN photo p ON p.mediaId = f.mediaId
        WHERE f.personId IS NOT NULL AND p.vectorVersion = :version
    """
    )
    suspend fun facesWithPerson(version: Int): List<PersonFaceRow>

    @Insert
    suspend fun insert(person: PersonEntity): Long

    // 미배정 얼굴을 읽은 뒤 커밋 전에 "얼굴 아님" 이 끼었으면 붙이지 않는다.
    // 뺀 얼굴에 인물이 붙으면 상세에는 안 보이고 사진 수·이름 검색에는 잡힌다.
    @Query("UPDATE face SET personId = :personId WHERE id = :faceId AND personId IS NULL AND excluded = 0")
    suspend fun assign(faceId: Long, personId: Long)

    /**
     * 없어진 인물을 가리키는 얼굴을 미배정으로 되돌린다.
     * 색인 도중 사용자가 인물을 합치면(= 한쪽 삭제) 그 인물 id 로 배정되거나 이어받은 얼굴이 생길 수 있다.
     * 외래키(SET NULL)로 막으면 그 순간의 UPDATE·INSERT 가 제약 위반으로 실패해 색인이 멈추므로, 사후에 고친다.
     */
    @Query("UPDATE face SET personId = NULL WHERE personId IS NOT NULL AND personId NOT IN (SELECT id FROM person)")
    suspend fun detachDangling(): Int

    /** 배정 전후로 끊긴 연결을 고친다. 빈 목록이어도 불러 정리만 하게 한다. */
    @Transaction
    suspend fun assignAll(assignments: List<Pair<Long, Long>>) {
        detachDangling()
        for ((faceId, personId) in assignments) assign(faceId, personId)
        detachDangling()
    }

    // center 는 ByteArray(BLOB) 로 받는다. FloatArray 로 받으면 Room 이 배열을 IN 목록처럼 "?,?,…" 로 펼쳐
    // "near ?: syntax error" 가 난다(TypeConverter 는 쿼리 인자의 배열에는 쓰이지 않는다).
    @Query("UPDATE person SET center = :center, centerVersion = :version WHERE id = :id")
    suspend fun updateCenter(id: Long, center: ByteArray, version: Int)

    @Transaction
    suspend fun updateCenters(centers: Map<Long, FloatArray>, version: Int) {
        val conv = Converters()   // 저장 형식(리틀 엔디언 float)은 엔티티 열과 같은 변환기로 맞춘다
        for ((id, c) in centers) updateCenter(id, conv.fromVector(c), version)
    }

    @Query("UPDATE person SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE face SET personId = :into WHERE personId = :from")
    suspend fun moveFaces(from: Long, into: Long)

    @Query("DELETE FROM person WHERE id = :id")
    suspend fun delete(id: Long)

    /** 합칠 대상에 이름이 없으면 없어질 쪽의 이름을 넘겨받는다. 둘 다 있으면 대상의 이름을 유지한다. */
    @Query("UPDATE person SET name = COALESCE(name, (SELECT name FROM person WHERE id = :from)) WHERE id = :into")
    suspend fun inheritName(from: Long, into: Long)

    @Query("SELECT COUNT(*) FROM person WHERE id IN (:ids)")
    suspend fun countExisting(ids: List<Long>): Int

    /**
     * 둘 중 하나가 그사이 지워졌으면(색인 끝 정리 등) 아무것도 하지 않고 false.
     * 확인 없이 진행하면 없는 into 로 얼굴을 옮기고 from 을 지워 이름이 사라진다.
     */
    @Transaction
    suspend fun merge(from: Long, into: Long): Boolean {
        if (countExisting(listOf(from, into)) != 2) return false
        inheritName(from, into)     // "엄마"(이름 있음)를 이름 없는 쪽으로 합쳐도 이름이 남는다
        moveFaces(from, into)
        delete(from)
        return true
    }

    @Query(
        """
        DELETE FROM person
        WHERE name IS NULL
          AND id NOT IN (SELECT DISTINCT personId FROM face WHERE personId IS NOT NULL)
    """
    )
    suspend fun deleteOrphans(): Int

    /**
     * 사진 많은 순. 대표 사진은 가장 최근(큰 id) 사진.
     * 얼굴 없는 이름 인물도 보인다 (cover = null): 접근 범위 축소·재색인·INDEX_VERSION 올림·전부 나누기 뒤 남은 이름이다.
     * 숨기면 이름 검색에는 남는데 고칠 방법이 없다. 이름 없는 빈 인물은 deleteOrphans 가 지우므로 여기 안 나온다.
     */
    @Query(
        """
        SELECT p.id AS id, p.name AS name,
               MAX(f.mediaId) AS coverMediaId,
               COUNT(DISTINCT f.mediaId) AS photoCount
        FROM person p LEFT JOIN face f ON f.personId = p.id
        GROUP BY p.id
        HAVING photoCount > 0 OR p.name IS NOT NULL
        ORDER BY photoCount DESC
    """
    )
    fun persons(): Flow<List<PersonRow>>
}