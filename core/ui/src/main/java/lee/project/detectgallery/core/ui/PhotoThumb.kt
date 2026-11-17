package lee.project.detectgallery.core.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import kotlin.math.max

/**
 * 사진에서 얼굴 부분만 잘라 보여 준다 (인물 상세, 쿼리 얼굴 고르기).
 * 좌표는 0..1 정규화 값. :core:ui 가 domain 에 의존하지 않도록 Box 대신 숫자 4개를 받는다.
 */
class FaceCropTransformation(
    private val left: Float,
    private val top: Float,
    private val right: Float,
    private val bottom: Float,
    private val padding: Float = 0.25f,
) : Transformation {
    override val cacheKey = "face-crop-$left-$top-$right-$bottom-$padding"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val px = (right - left) * padding
        val py = (bottom - top) * padding
        val l = ((left - px).coerceIn(0f, 1f) * input.width).toInt()
        val t = ((top - py).coerceIn(0f, 1f) * input.height).toInt()
        val r = ((right + px).coerceIn(0f, 1f) * input.width).toInt()
        val b = ((bottom + py).coerceIn(0f, 1f) * input.height).toInt()
        return Bitmap.createBitmap(input, l, t, max(1, r - l), max(1, b - t))
    }
}

@Composable
fun FaceThumb(
    uri: Uri, left: Float, top: Float, right: Float, bottom: Float,
    modifier: Modifier = Modifier,
    contentDescription: String? = "얼굴",
) {
    val context = LocalContext.current
    val request = remember(uri, left, top, right, bottom) {
        ImageRequest.Builder(context)
            .data(uri)
            .size(1024)   // 작게 줄인 뒤 자르면 얼굴이 뭉개진다 → 크게 받아 자른다
            .transformations(FaceCropTransformation(left, top, right, bottom))
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(CircleShape)
    )
}