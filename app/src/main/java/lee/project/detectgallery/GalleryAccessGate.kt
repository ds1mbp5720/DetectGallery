package lee.project.detectgallery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import lee.project.detectgallery.core.data.work.IndexScheduler

enum class GalleryAccess { FULL, PARTIAL, NONE }

fun galleryAccess(context: Context): GalleryAccess {
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> GalleryAccess.FULL
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> GalleryAccess.PARTIAL
        Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> GalleryAccess.FULL
        else -> GalleryAccess.NONE
    }
}

private fun galleryPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** afterCurrent: 볼 수 있는 사진이 바뀌었을 수 있으면 true → 도는 중인 색인 뒤에 한 번 더 붙인다. */
private fun scheduleIfAllowed(context: Context, afterCurrent: Boolean) {
    if (galleryAccess(context) != GalleryAccess.NONE) IndexScheduler.schedule(context, afterCurrent)
}

/** 부분 접근 상태에서 다시 부르면 Android 14 가 사진 선택 화면을 띄운다. 고른 뒤 색인을 다시 예약한다. */
@Composable
fun rememberGalleryRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(RequestMultiplePermissions()) { scheduleIfAllowed(context, afterCurrent = true) }
    return remember(launcher) { { launcher.launch(galleryPermissions()) } }
}

@Composable
fun GalleryAccessGate(content: @Composable (GalleryAccess) -> Unit) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(galleryAccess(context)) }
    val launcher = rememberLauncherForActivityResult(RequestMultiplePermissions()) {
        access = galleryAccess(context)
        scheduleIfAllowed(context, afterCurrent = true)
    }

    // 앱으로 돌아올 때마다: 설정에서 권한을 바꿨을 수 있다.
    // 접근 범위가 바뀐 경우만 도는 색인 뒤에 한 번 더 붙인다. 부분 접근이라는 이유만으로 붙이면 회전·앱 전환·
    // 사진 선택기 복귀마다 실행이 하나씩 쌓인다. 선택만 바뀐 경우는 도는 색인이 끝에 목록을 다시 읽어 잡는다.
    LifecycleResumeEffect(Unit) {
        val before = access
        access = galleryAccess(context)
        scheduleIfAllowed(context, afterCurrent = access != before)
        onPauseOrDispose { }
    }

    if (access == GalleryAccess.NONE) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("갤러리 사진에 접근하도록 허용해 주세요", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("사진은 이 기기 안에서만 분석해요. 어디로도 보내지 않아요.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "처음 한 번은 사진을 분석하는 데 시간이 걸려요. 그동안에도 분석된 사진부터 쓸 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { launcher.launch(galleryPermissions()) }) { Text("사진 접근 허용") }
        }
    } else {
        content(access)
    }
}
// "다시 묻지 않음"으로 거부하면 launch() 가 아무것도 띄우지 않는다.
// 그때를 위해 Settings.ACTION_APPLICATION_DETAILS_SETTINGS 로 가는 "설정 열기" 버튼을 함께 두는 것을 권장한다.