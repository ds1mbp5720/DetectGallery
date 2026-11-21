package lee.project.detectgallery.core.domain.model

import kotlin.math.abs
import kotlin.math.roundToInt

/** 분석 입력. Bitmap 대신 ARGB 배열을 써서 domain 을 순수 JVM 으로 유지한다. */
class ImageFrame(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0) { "크기는 양수여야 한다 (${width}x$height)" }   // 0×0 이면 crop·meanLuma 가 깨진다
        require(argb.size == width * height) { "argb 크기 불일치" }
    }
}

/** 0..1 정규화 사각형. 이미지 크기와 무관하게 저장·전달한다. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val area: Float get() = (right - left) * (bottom - top)
    operator fun contains(p: Pair<Float, Float>) = p.first in left..right && p.second in top..bottom

    /** 겹친 넓이 / 합친 넓이. 재색인 때 "같은 자리의 같은 얼굴"인지 판단한다. */
    fun iou(o: Box): Float {
        val w = (minOf(right, o.right) - maxOf(left, o.left)).coerceAtLeast(0f)
        val h = (minOf(bottom, o.bottom) - maxOf(top, o.top)).coerceAtLeast(0f)
        val inter = w * h
        val union = area + o.area - inter
        return if (union <= 0f) 0f else inter / union
    }
}

/** generation = MediaStore GENERATION_MODIFIED. 사진이 바뀌면 커진다. */
data class PhotoRef(val mediaId: Long, val dateTaken: Long, val generation: Long)

/** 기기 앨범(MediaStore BUCKET). "분석할 앨범 고르기" 목록 한 줄. coverMediaId = 그 앨범의 가장 최근 사진. */
data class Album(val id: Long, val name: String, val count: Int, val coverMediaId: Long)

/** 색인에 저장된 사진별 상태. version 0 = 분석 실패(재시도 대상). */
/**
 * facesDone = 이 사진의 얼굴 분석을 마쳤는가. 인물 기능이 꺼져 있었거나 얼굴 모델이 없을 때 분석한 사진은 false.
 * 기능을 켜거나 모델을 넣으면 false 인 사진만 다시 분석된다 (전체 재색인·버전 올리기가 필요 없다).
 */
data class IndexedState(val generation: Long, val version: Int, val facesDone: Boolean = true)

data class DetectedFace(
    val box: Box,
    val vector: FloatArray,      // L2 정규화된 얼굴 임베딩
    val smiling: Float?,         // 0..1, 검출기가 못 주면 null
)

data class Label(val name: String, val confidence: Float)

/** 사진 1장의 색인 결과. failed 면 version 0 으로 저장되어 다음 실행에서 재시도된다. */
data class PhotoAnalysis(
    val ref: PhotoRef,
    val dHash: Long = 0,
    val scene: FloatArray? = null,
    val faces: List<DetectedFace> = emptyList(),
    val labels: List<Label> = emptyList(),   // ML Kit 라벨 + state:* 상태 태그
    val facesDone: Boolean = false,          // 얼굴 분석을 했는가 (faces 가 비어도 "얼굴 없음" 으로 확인했으면 true)
    val failed: Boolean = false,
)

/**
 * 검색 캐시용 벡터. int8 로 줄여 들고 있는다(장면 1024차원 장당 4KB → 1KB): 값 ≈ q[i] × scale.
 * DB 에는 float 그대로 저장한다 → 바꿔도 재색인이 필요 없다. 내적은 dot(FloatArray, IndexedVector).
 */
class IndexedVector(val mediaId: Long, vector: FloatArray) {
    val size: Int = vector.size

    // 벡터마다 따로: 가장 큰 성분이 ±127. NaN·무한대가 섞이면(max 가 그 값이 된다) 0 벡터로 둔다:
    // 그대로 나누면 roundToInt 가 예외를 던져 캐시를 만들지 못하고 모든 검색이 실패한다.
    val scale: Float =
        (vector.maxOfOrNull { abs(it) } ?: 0f).let { if (it.isFinite()) it / 127f else 0f }
    val q: ByteArray = ByteArray(size) {
        if (scale == 0f) 0.toByte() else (vector[it] / scale).roundToInt().toByte()
    }
}

enum class ImageSearchMode { PERSON, SCENE, DUPLICATE }