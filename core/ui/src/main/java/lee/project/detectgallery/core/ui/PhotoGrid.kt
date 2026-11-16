package lee.project.detectgallery.core.ui

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

fun mediaUri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

/**
 * 결과 사진을 기본 갤러리 앱으로 연다. 확대·공유·편집·삭제를 갤러리 앱이 맡으므로 앱 안 뷰어를 따로 만들지 않는다.
 * 사진을 열 수 있는 앱이 없으면 안내만 한다.
 */
fun openInGallery(context: Context, mediaId: Long) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(mediaUri(mediaId), "image/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "사진을 열 수 있는 앱이 없어요.", Toast.LENGTH_SHORT).show()
    }
}
// ponytail: 스와이프로 결과를 넘겨 보는 앱 안 뷰어는 필요해지면 추가 (HorizontalPager + 같은 ids).

/** onClick 을 주지 않으면 누른 사진을 갤러리 앱으로 연다. 둥근 모서리 + 4dp 간격, 읽는 동안은 회색 칸. */
@Composable
fun PhotoGrid(ids: List<Long>, modifier: Modifier = Modifier, onClick: ((Long) -> Unit)? = null) {
    val context = LocalContext.current
    LazyVerticalGrid(
        GridCells.Adaptive(104.dp),
        modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(ids, key = { it }) { id ->
            AsyncImage(
                model = mediaUri(id),
                contentDescription = "사진",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onClick?.invoke(id) ?: openInGallery(context, id) },
            )
        }
    }
}