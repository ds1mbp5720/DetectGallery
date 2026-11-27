package lee.project.detectgallery.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class StateRow(
    val mediaId: Long,
    val generation: Long,
    val indexVersion: Int,
    val facesDone: Boolean
)

data class VectorRow(val mediaId: Long, val vector: FloatArray)
data class HashRow(val mediaId: Long, val dHash: Long)
data class LabelCount(val label: String, val count: Int)
data class MomentRow(val mediaId: Long, val dateTaken: Long, val lat: Double?, val lon: Double?)
data class LabelRow(val mediaId: Long, val label: String)
data class LocationUpdate(val mediaId: Long, val lat: Double?, val lon: Double?)
data class LocationRow(
    val mediaId: Long,
    val generation: Long,
    val lat: Double?,
    val lon: Double?,
    val locRead: Boolean
)

@Dao
interface PhotoIndexDao {
    @Upsert
    suspend fun upsertPhotos(rows: List<PhotoEntity>)

    @Upsert
    suspend fun upsertScenes(rows: List<SceneEmbeddingEntity>)

    @Insert
    suspend fun insertFaces(rows: List<FaceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLabels(rows: List<PhotoLabelEntity>)

    @Query("DELETE FROM scene_embedding WHERE mediaId IN (:ids)")
    suspend fun deleteScenes(ids: List<Long>)

    @Query("DELETE FROM face WHERE mediaId IN (:ids)")
    suspend fun deleteFaces(ids: List<Long>)

    @Query("DELETE FROM photo_label WHERE mediaId IN (:ids)")
    suspend fun deleteLabels(ids: List<Long>)

    @Query("DELETE FROM photo WHERE mediaId IN (:ids)")
    suspend fun deletePhotos(ids: List<Long>)

    /** 재색인 전 이전 얼굴. 같은 자리의 새 얼굴에 인물을 이어 주는 데 쓴다. */
    @Query("SELECT * FROM face WHERE mediaId IN (:ids)")
    suspend fun facesOf(ids: List<Long>): List<FaceEntity>

    /**
     * 재색인 시 이전 장면·얼굴·라벨을 지우고 새로 넣는다. 배치 단위 원자성.
     * 장면도 지우는 이유: 수정된 사진이 이번에 분석 실패하면 옛 벡터가 남아 엉뚱한 결과에 걸린다.
     */
    @Transaction
    suspend fun replace(
        photos: List<PhotoEntity>,
        scenes: List<SceneEmbeddingEntity>,
        faces: List<FaceEntity>,
        labels: List<PhotoLabelEntity>,
    ) {
        val ids = photos.map { it.mediaId }
        deleteScenes(ids); deleteFaces(ids); deleteLabels(ids)
        upsertPhotos(photos); upsertScenes(scenes); insertFaces(faces); insertLabels(labels)
    }

    @Transaction
    suspend fun deleteAll(ids: List<Long>) {
        // 외래키 CASCADE 가 켜져 있는지에 기대지 않고 명시적으로 지운다. 500개씩: SQLite 변수 한도 999.
        ids.chunked(500)
            .forEach { deleteScenes(it); deleteFaces(it); deleteLabels(it); deletePhotos(it) }
    }

    @Query("SELECT mediaId, generation, indexVersion, facesDone FROM photo")
    suspend fun states(): List<StateRow>

    @Query("SELECT mediaId, generation, indexVersion, facesDone FROM photo WHERE mediaId IN (:ids)")
    suspend fun statesOf(ids: List<Long>): List<StateRow>

    /** 내용이 그대로인 사진의 일시 실패: 기존 장면·얼굴·라벨은 두고 버전만 0 으로 → 다음 실행에서 재시도. */
    @Query("UPDATE photo SET indexVersion = 0 WHERE mediaId IN (:ids)")
    suspend fun markFailed(ids: List<Long>)

    /** 현재 버전 모델이 만든 벡터만. 모델 교체 중 남은 옛 벡터는 차원이 같아도 다른 공간이라 섞으면 안 된다. */
    // @Transaction: 결과가 커서 커서 창(약 2MB)을 넘으면 Room 이 여러 번 나눠 읽는다. 그 사이 색인 저장이 끼면
    // 저장 전·후가 섞인 목록이 캐시에 남는다 (장면 1만 장 ≈ 40MB) → 한 트랜잭션으로 읽는다. faces 도 같다.
    @Transaction
    @Query(
        """
        SELECT s.mediaId, s.vector FROM scene_embedding s JOIN photo p ON p.mediaId = s.mediaId
        WHERE p.vectorVersion = :version
    """
    )
    suspend fun scenes(version: Int): List<VectorRow>

    @Transaction
    @Query(
        """
        SELECT f.mediaId, f.vector FROM face f JOIN photo p ON p.mediaId = f.mediaId
        WHERE f.excluded = 0 AND p.vectorVersion = :version
    """
    )
    suspend fun faces(version: Int): List<VectorRow>

    // 인물 기능 끄기: 얼굴과 인물(이름 포함)을 모두 지운다
    @Query("DELETE FROM face")
    suspend fun deleteAllFaces()

    @Query("DELETE FROM person")
    suspend fun deleteAllPersons()

    /** 얼굴로 만든 상태 태그(웃는·단체·셀카)도 얼굴 분석 결과다 */
    @Query("DELETE FROM photo_label WHERE label IN (:tags)")
    suspend fun deleteLabelsNamed(tags: List<String>)

    /**
     * 얼굴 분석 기록도 지운다 → 다시 켜면 그 사진들이 다시 분석된다.
     * 분석은 사진 단위라 장면·라벨·해시도 다시 계산된다 (전체 재색인과 같은 시간). 그동안 기존 장면·라벨 검색은 계속 된다.
     */
    @Query("UPDATE photo SET facesDone = 0 WHERE facesDone = 1")
    suspend fun clearFacesDone()   // 꺼진 채 반복 실행돼도 쓰기 없음

    @Transaction
    suspend fun deleteAllFaceData(faceTags: List<String>) {
        deleteAllFaces(); deleteAllPersons(); deleteLabelsNamed(faceTags); clearFacesDone()
    }

    /** 장면·얼굴 벡터와 같은 기준: 일시 실패(indexVersion = 0, 내용 그대로)인 사진도 겹치는 사진 검색에 남는다 */
    @Query("SELECT mediaId, dHash FROM photo WHERE vectorVersion > 0")
    suspend fun hashes(): List<HashRow>

    /**
     * 지금 검색되는 사진 수 = 현재 버전 벡터가 있는 사진. 검색 캐시(scenes/faces)와 같은 기준이다.
     * indexVersion 으로 세면 일시 실패(markFailed, 벡터는 남아 검색됨)가 "대기" 로 잡힌다.
     * photo 테이블이 바뀔 때마다 Room 이 다시 센다 (1만 행 COUNT 는 1ms 안팎).
     */
    @Query("SELECT COUNT(*) FROM photo WHERE vectorVersion = :version")
    fun analyzedCount(version: Int): Flow<Int>

    /** 얼굴 분석까지 마친 사진 수. facesDone 은 현재 버전으로 성공 저장될 때만 1 이라 analyzedCount 이하다. */
    @Query("SELECT COUNT(*) FROM photo WHERE vectorVersion = :version AND facesDone = 1")
    fun facesAnalyzedCount(version: Int): Flow<Int>

    @Query(
        """
        SELECT f.mediaId FROM face f JOIN person p ON p.id = f.personId
        WHERE p.name IN (:names)
        GROUP BY f.mediaId HAVING COUNT(DISTINCT p.name) = :count
    """
    )
    suspend fun byPersons(names: List<String>, count: Int): List<Long>

    @Query("SELECT mediaId FROM photo_label WHERE label IN (:labels) GROUP BY mediaId ORDER BY MAX(confidence) DESC")
    suspend fun byLabels(labels: List<String>): List<Long>

    @Query("SELECT mediaId FROM photo WHERE dateTaken >= :from AND dateTaken < :to")
    suspend fun inRange(from: Long, to: Long): List<Long>

    /** 추억 묶기(C12): 검색되는 사진(현재 버전 벡터)의 촬영 시각·위치 */
    @Query("SELECT mediaId, dateTaken, lat, lon FROM photo WHERE vectorVersion = :version")
    suspend fun momentRows(version: Int): List<MomentRow>

    @Query("SELECT mediaId, label FROM photo_label WHERE label IN (:labels)")
    suspend fun labelRows(labels: List<String>): List<LabelRow>

    /** 위치를 아직 못 읽은 사진. 추억에 나오는 사진(벡터가 있는 것)과 같은 조건 — 실패한 사진은 다시 분석할 때 위치도 읽는다 */
    @Query("SELECT mediaId FROM photo WHERE locRead = 0 AND vectorVersion > 0")
    suspend fun locationPending(): List<Long>

    @Query("SELECT mediaId, generation, lat, lon, locRead FROM photo WHERE mediaId IN (:ids)")
    suspend fun locationsOf(ids: List<Long>): List<LocationRow>

    @Query("UPDATE photo SET lat = :lat, lon = :lon, locRead = 1 WHERE mediaId = :id")
    suspend fun setLocation(id: Long, lat: Double?, lon: Double?)

    @Transaction
    suspend fun setLocations(rows: List<LocationUpdate>) =
        rows.forEach { setLocation(it.mediaId, it.lat, it.lon) }

    @Query("SELECT label FROM label_alias WHERE alias = :alias")
    suspend fun labelsFor(alias: String): List<String>

    @Query("SELECT name FROM person WHERE name IS NOT NULL")
    suspend fun personNames(): List<String>

    /** 별칭 사전 채우기용: 내 사진에 실제로 붙은 라벨 빈도 (13절). */
    @Query(
        """
        SELECT label, COUNT(*) AS count FROM photo_label
        WHERE label NOT LIKE 'state:%'
        GROUP BY label ORDER BY count DESC LIMIT 100
    """
    )
    suspend fun labelCounts(): List<LabelCount>
}