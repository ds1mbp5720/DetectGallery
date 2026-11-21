package lee.project.detectgallery.core.imaging

import android.graphics.Bitmap
import android.graphics.Matrix
import lee.project.detectgallery.core.domain.model.ImageFrame

/** 긴 변이 maxSide 를 넘으면 줄인 뒤 ARGB 배열로. HARDWARE 비트맵은 복사해서 읽는다. */
fun Bitmap.toImageFrame(maxSide: Int = 512): ImageFrame {
    val scale = maxSide.toFloat() / maxOf(width, height)
    // 아주 긴 파노라마는 짧은 변이 0 이 되어 createScaledBitmap 이 예외 → 최소 1픽셀
    val sized = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true
        )
    } else this
    val soft = if (sized.config == Bitmap.Config.ARGB_8888) sized else sized.copy(
        Bitmap.Config.ARGB_8888,
        false
    )
    val px = IntArray(soft.width * soft.height)
    soft.getPixels(px, 0, soft.width, 0, 0, soft.width, soft.height)
    return ImageFrame(soft.width, soft.height, px)
}

fun ImageFrame.toBitmap(): Bitmap =
    Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)

fun Bitmap.rotate(degrees: Int): Bitmap =
    if (degrees == 0) this
    else Bitmap.createBitmap(
        this,
        0,
        0,
        width,
        height,
        Matrix().apply { postRotate(degrees.toFloat()) },
        true
    )