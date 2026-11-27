package lee.project.detectgallery.core.data.db

import androidx.room.*

@Entity(tableName = "photo")
data class PhotoEntity(
    @PrimaryKey val mediaId: Long,
    val dateTaken: Long,
    val generation: Long,       // 분석 당시 GENERATION_MODIFIED. 다르면 사진이 바뀐 것
    val dHash: Long,
    val indexVersion: Int,
    val facesDone: Boolean = false,   // 얼굴 분석을 했는가. 기능을 켜거나 얼굴 모델을 넣으면 false 인 사진을 다시 분석
    /**
     * 저장된 장면·얼굴 벡터를 만든 INDEX_VERSION. 0 = 벡터 없음.
     * indexVersion 과 따로 둔다: 일시 실패(markFailed)는 indexVersion 만 0 으로 내리고 기존 벡터는 그대로 검색에 쓰여야 한다.
     */
    val vectorVersion: Int = 0,
    /** EXIF 위도·경도 (C12). 없으면 null. 추억 묶기만 쓴다 */
    val lat: Double? = null,
    val lon: Double? = null,
    /** 위치를 읽었는가(찾음·없음). false 면 다음 색인이 위치만 다시 읽는다 (권한을 나중에 허용·일시 오류). 기본값은 첫 배포 뒤 열 추가용 */
    @ColumnInfo(defaultValue = "0") val locRead: Boolean = false,
)

@Entity(
    tableName = "scene_embedding",
    foreignKeys = [ForeignKey(
        PhotoEntity::class,
        ["mediaId"],
        ["mediaId"],
        onDelete = ForeignKey.CASCADE
    )],
)
data class SceneEmbeddingEntity(
    @PrimaryKey val mediaId: Long,
    val vector: FloatArray,       // 어느 모델 것인지는 photo.vectorVersion 으로 판단한다 (모델 id 열은 두지 않는다)
)

@Entity(
    tableName = "face",
    indices = [Index("mediaId"), Index("personId")],
    foreignKeys = [ForeignKey(
        PhotoEntity::class,
        ["mediaId"],
        ["mediaId"],
        onDelete = ForeignKey.CASCADE
    )],
)
data class FaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val vector: FloatArray,
    val personId: Long? = null,
    /** 사용자가 "이 사람 얼굴 아님" 으로 뺀 얼굴. 인물 배정·인물 검색에서 제외된다. */
    val excluded: Boolean = false,
    /**
     * 옆얼굴·작은 얼굴 (C3). 검색·배정에는 쓰고 새 인물·중심 계산에는 쓰지 않는다.
     * defaultValue 는 남겨 둔다: 첫 마이그레이션 예시(MigrationTest)의 INSERT 가 이 열을 빼도 되게.
     */
    @ColumnInfo(defaultValue = "0") val lowQuality: Boolean = false,
)

@Entity(tableName = "person")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String? = null,
    val center: FloatArray,
    val centerVersion: Int,       // center 를 만든 INDEX_VERSION. 다르면 배정에 쓰지 않는다 (옛 모델 공간)
)

@Entity(
    tableName = "photo_label",
    primaryKeys = ["mediaId", "label"],
    indices = [Index("label")],
    foreignKeys = [ForeignKey(
        PhotoEntity::class,
        ["mediaId"],
        ["mediaId"],
        onDelete = ForeignKey.CASCADE
    )],
)
data class PhotoLabelEntity(val mediaId: Long, val label: String, val confidence: Float)

@Entity(tableName = "label_alias", primaryKeys = ["alias", "label"])
data class LabelAliasEntity(val alias: String, val label: String)