package lee.project.detectgallery.core.domain.model

// ── 인물 ──
data class FaceRow(
    val id: Long,
    val vector: FloatArray
)

data class PersonCenter(
    val personId: Long,
    val vector: FloatArray
)

/** coverMediaId 가 null 이면 얼굴이 하나도 없는 이름 인물 (접근 범위 축소·재색인·버전 교체·얼굴을 전부 나눈 뒤). 목록에서 합치거나 이름을 바꿀 수 있다. */
data class Person(
    val id: Long,
    val name: String?,
    val coverMediaId: Long?,
    val photoCount: Int
)

/** 인물 상세에서 보여 주는 얼굴 한 개. 나누기·빼기의 단위. */
data class PersonFace(
    val faceId: Long,
    val mediaId: Long,
    val box: Box
)

data class IndexProgress(
    val done: Int,
    val total: Int
)
