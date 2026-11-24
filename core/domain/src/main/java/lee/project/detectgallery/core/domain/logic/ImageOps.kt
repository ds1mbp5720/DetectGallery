package lee.project.detectgallery.core.domain.logic

import lee.project.detectgallery.core.domain.model.Box
import lee.project.detectgallery.core.domain.model.ImageFrame


internal fun luma(p: Int): Int =
    ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

/** 박스 영역을 잘라낸다. padding 은 박스 크기 대비 비율 (얼굴 주변 여백). */
fun ImageFrame.crop(box: Box, padding: Float = 0.1f): ImageFrame {
    val px = (box.right - box.left) * padding
    val py = (box.bottom - box.top) * padding
    val l = ((box.left - px).coerceIn(0f, 1f) * width).toInt().coerceIn(0, width - 1)
    val t = ((box.top - py).coerceIn(0f, 1f) * height).toInt().coerceIn(0, height - 1)
    val r = ((box.right + px).coerceIn(0f, 1f) * width).toInt().coerceIn(l + 1, width)
    val b = ((box.bottom + py).coerceIn(0f, 1f) * height).toInt().coerceIn(t + 1, height)
    val cw = r - l
    val ch = b - t
    val out = IntArray(cw * ch)
    for (y in 0 until ch) System.arraycopy(argb, (t + y) * width + l, out, y * cw, cw)
    return ImageFrame(cw, ch, out)
}

/**
 * dHash: 9×8 박스 평균 축소 → 그레이 → 가로 인접 대소 비교 → 64bit.
 * 리사이즈·재압축·약한 보정에 강해 "겹치는 사진" 판정에 쓴다.
 */
fun dHash(frame: ImageFrame): Long {
    val gray = IntArray(72)
    for (gy in 0 until 8) for (gx in 0 until 9) {
        val x0 = gx * frame.width / 9
        val x1 = maxOf(x0 + 1, (gx + 1) * frame.width / 9)
        val y0 = gy * frame.height / 8
        val y1 = maxOf(y0 + 1, (gy + 1) * frame.height / 8)
        var sum = 0L
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            sum += luma(frame.argb[y * frame.width + x]); n++
        }
        gray[gy * 9 + gx] = (sum / n).toInt()
    }
    var h = 0L
    for (y in 0 until 8) for (x in 0 until 8) {
        h = (h shl 1) or if (gray[y * 9 + x] > gray[y * 9 + x + 1]) 1L else 0L
    }
    return h
}

fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

/**
 * dHash 에 무늬가 있는가. 밝기 차이가 거의 없는 사진(검은 화면·단색·아주 어두운 야경)은 1 인 비트가 0 근처(또는 64 근처)로 몰려
 * 서로 다른 사진끼리 "겹치는 사진" 이 된다 → 겹치는 사진 판정(검색 FindSimilarPhotosUseCase·정리 groupDuplicates)에서 뺀다.
 */
fun hasDetail(hash: Long): Boolean =
    java.lang.Long.bitCount(hash) in Thresholds.DUPLICATE_MIN_DETAIL_BITS..(64 - Thresholds.DUPLICATE_MIN_DETAIL_BITS)

fun meanLuma(f: ImageFrame): Float {
    var s = 0L
    for (p in f.argb) s += luma(p)
    return s.toFloat() / f.argb.size
}

/** 라플라시안 분산. 작을수록 흐리다 (흔들림·초점 실패). */
fun laplacianVariance(f: ImageFrame): Float {
    val w = f.width
    val h = f.height
    if (w < 3 || h < 3) return Float.MAX_VALUE
    var sum = 0.0
    var sumSq = 0.0
    var n = 0
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        val lap = luma(f.argb[(y - 1) * w + x]) + luma(f.argb[(y + 1) * w + x]) +
                luma(f.argb[y * w + x - 1]) + luma(f.argb[y * w + x + 1]) - 4 * luma(f.argb[y * w + x])
        sum += lap
        sumSq += lap.toDouble() * lap
        n++
    }
    val mean = sum / n
    return (sumSq / n - mean * mean).toFloat()
}