package lee.project.detectgallery.core.domain.logic

import lee.project.detectgallery.core.domain.model.IndexedVector
import lee.project.detectgallery.core.domain.model.PersonCenter
import lee.project.detectgallery.core.domain.model.PhotoMatch
import kotlin.math.sqrt

/** 둘 다 L2 정규화돼 있으면 내적 = 코사인 유사도. */
fun dot(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "차원 불일치 ${a.size} != ${b.size}: 모델이 바뀌었으면 재색인 필요" }
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}

/**
 * 캐시 벡터(int8)와의 내적. 쿼리는 float 그대로 둔다(한쪽만 양자화): 오차가 캐시 쪽 반올림에서만 생긴다.
 * L2 정규화 1024차원에서 float 내적과의 차이는 보통 0.001 안팎 (VectorMathTest).
 */
fun dot(a: FloatArray, b: IndexedVector): Float {
    require(a.size == b.size) { "차원 불일치 ${a.size} != ${b.size}: 모델이 바뀌었으면 재색인 필요" }
    val q = b.q
    var s = 0f
    for (i in a.indices) s += a[i] * q[i]
    return s * b.scale
}

/** 캐시 벡터끼리의 내적 (둘 다 int8). 위치 메우기(C12)처럼 정밀도보다 속도가 중요한 비교용. */
fun dot(a: IndexedVector, b: IndexedVector): Float {
    require(a.size == b.size) { "차원 불일치 ${a.size} != ${b.size}: 모델이 바뀌었으면 재색인 필요" }
    var s = 0
    for (i in 0 until a.size) s += a.q[i] * b.q[i]
    return s * a.scale * b.scale
}

fun l2Normalize(v: FloatArray): FloatArray {
    var sum = 0f
    for (x in v) sum += x * x
    val n = sqrt(sum)
    return if (n == 0f) v else FloatArray(v.size) { v[it] / n }
}

/**
 * 사진 1장에 벡터가 여러 개(얼굴)일 수 있어 사진별 최고 점수로 묶는다.
 * threshold 는 포함(>=)한다.
 */
fun rankByBestMatch(
    queries: List<FloatArray>,
    candidates: List<IndexedVector>,
    threshold: Float,
    topK: Int,
): List<PhotoMatch> {
    if (queries.isEmpty()) return emptyList()
    val dim = queries.first().size
    require(queries.all { it.size == dim }) { "쿼리 벡터 차원이 섞여 있다" }   // 쿼리는 한 모델에서 나온다 → 섞이면 호출 쪽 버그
    val best = HashMap<Long, Float>()
    for (c in candidates) {
        // 모델 교체로 재색인 중이면 옛 차원 벡터가 섞여 있다 → 예외 대신 건너뛴다
        if (c.size != dim) continue
        val score = queries.maxOf { dot(it, c) }
        if (score >= threshold && score > (best[c.mediaId]
                ?: Float.NEGATIVE_INFINITY)
        ) best[c.mediaId] = score
    }
    return best.entries.sortedByDescending { it.value }.take(topK)
        .map { PhotoMatch(it.key, it.value) }
}

/** 가장 가까운 인물 중심. threshold 미만이면 null. 차원이 다른 중심(모델 교체 전)은 건너뛴다. */
fun nearest(v: FloatArray, centers: List<PersonCenter>, threshold: Float): Long? {
    val best =
        centers.filter { it.vector.size == v.size }.maxByOrNull { dot(v, it.vector) } ?: return null
    return best.personId.takeIf { dot(v, best.vector) >= threshold }
}

/**
 * 인물 중심: 좋은 얼굴(저품질 아님)의 평균. 좋은 얼굴이 하나도 없는 인물(합치기·나누기로 생김)만 전부의 평균 (C3).
 * faces = (벡터, 저품질) 쌍. 비어 있으면 null.
 */
fun personCenter(faces: List<Pair<FloatArray, Boolean>>): FloatArray? =
    meanNormalized(faces.filterNot { it.second }.ifEmpty { faces }.map { it.first })

/** 같은 차원 벡터들의 평균을 정규화. 인물 중심 재계산용. 비어 있으면 null. */
fun meanNormalized(vectors: List<FloatArray>): FloatArray? {
    if (vectors.isEmpty()) return null
    val dim = vectors.first().size
    val sum = FloatArray(dim)
    for (v in vectors) {
        require(v.size == dim) { "차원이 섞여 있다" }
        for (i in 0 until dim) sum[i] += v[i]
    }
    return l2Normalize(sum)
}

// ponytail: 메모리 전수 내적 O(N·D). 캐시는 int8(IndexedVector)이라 장면 장당 1KB, 얼굴 하나 0.5KB
// → 1만 장 ≈ 10MB, 5만 장 ≈ 50MB(+ 얼굴). 검색 한 번은 5만 장에 수십 ms.
// 10만 장을 넘거나 검색이 수백 ms 가 되면 sqlite-vec(ANN)로 교체.