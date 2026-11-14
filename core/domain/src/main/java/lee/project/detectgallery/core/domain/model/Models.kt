package lee.project.detectgallery.core.domain.model

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

class IndexedVector(val mediaId: Long, val vector: FloatArray)
data class PhotoMatch(val mediaId: Long, val score: Float)

enum class ImageSearchMode { PERSON, SCENE, DUPLICATE }

sealed interface SimilarResult {
    data class Matches(val items: List<PhotoMatch>) : SimilarResult
    data object NoFaceInQuery : SimilarResult
    data object FaceModelUnavailable : SimilarResult // 얼굴 모델을 아직 안 넣음 (MVP). "얼굴 없음" 과 구분
    data object FacesDisabled : SimilarResult        // 사용자가 인물 기능을 껐음
    /** 쿼리 사진에 얼굴이 여럿. 누구를 찾을지 사용자가 고른다 (모두로 찾으면 그중 누구든 나온 사진이 섞인다). */
    data class MultipleFaces(val faces: List<DetectedFace>) : SimilarResult
    data object UnreadableQuery : SimilarResult      // 쿼리 사진을 열지 못함 ("결과 없음" 과 구분)
}

// ── 실시간 카메라 ──
enum class DetectionKind { FACE, PERSON, FOOD, OBJECT }
data class LiveDetection(val box: Box, val kind: DetectionKind, val label: String, val score: Float)
data class LiveFrameResult(val detections: List<LiveDetection>, val timestampMs: Long)

// ── 키워드 ──
data class ParsedQuery(
    val personNames: List<String> = emptyList(),
    /**
     * 검색어 단어마다 별칭으로 바꾼 라벨 묶음 (ML Kit 영문 + state:*).
     * "웃는 강아지" → [[state:smiling], [Dog]]. 묶음마다 따로 순위를 매겨 RRF 로 합치므로
     * 두 조건을 다 만족하는 사진이 위로 온다 (한 목록으로 합치면 합집합이 되어 순위가 섞인다).
     */
    val labelGroups: List<List<String>> = emptyList(),
    /**
     * 인물 이름이면서 라벨 별칭인 단어 ("하늘"이라는 이름 + 하늘 라벨).
     * 어느 뜻인지 알 수 없으므로 필터로 쓰지 않고, 그 인물 사진과 라벨 사진을 순위 병합한다.
     */
    val ambiguousNames: List<String> = emptyList(),
    val semantic: String? = null,                // 사전에 없는 남은 말. 문장 임베딩(P3)용
    val dateRange: LongRange? = null,            // epoch millis, [from, to)
)

// ── 인물 ──
data class FaceRow(val id: Long, val vector: FloatArray)
data class PersonCenter(val personId: Long, val vector: FloatArray)
/** coverMediaId 가 null 이면 얼굴이 하나도 없는 이름 인물 (접근 범위 축소·재색인·버전 교체·얼굴을 전부 나눈 뒤). 목록에서 합치거나 이름을 바꿀 수 있다. */
data class Person(val id: Long, val name: String?, val coverMediaId: Long?, val photoCount: Int)
/** 인물 상세에서 보여 주는 얼굴 한 개. 나누기·빼기의 단위. */
data class PersonFace(val faceId: Long, val mediaId: Long, val box: Box)

data class IndexProgress(val done: Int, val total: Int)

/**
 * 사용자 민감도. -1 = 엄격하게, 0 = 개발자가 보정한 기준 그대로, +1 = 느슨하게.
 * person 은 "같은 사람 사진 찾기"(사진 한 장·카메라 얼굴 탭)에만 쓴다. 인물 묶기(인물 화면)는 보정값 그대로라
 * 슬라이더를 움직여도 인물 목록이 바뀌지 않는다. 묶음이 틀렸으면 합치기로 고친다.
 */
data class Sensitivity(val person: Float = 0f, val scene: Float = 0f) {
    fun clamped() = Sensitivity(person.coerceIn(-1f, 1f), scene.coerceIn(-1f, 1f))
}