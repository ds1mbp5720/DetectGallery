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
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

enum class GalleryAccess { FULL, PARTIAL, NONE }

fun galleryAccess(context: Context): GalleryAccess {
    fun granted(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> GalleryAccess.FULL
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> GalleryAccess.PARTIAL
        Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> GalleryAccess.FULL
        else -> GalleryAccess.NONE
    }
}

private fun galleryPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    )

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
    val launcher = rememberLauncherForActivityResult(RequestMultiplePermissions()) {
        scheduleIfAllowed(
            context,
            afterCurrent = true
        )
    }
    return remember(launcher) { { launcher.launch(galleryPermissions()) } }
}

@Composable
fun GalleryAccessGate(content: @Composable (GalleryAccess) -> Unit) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(galleryAccess(context)) }
    // 거부했는지만 기억한다. 시스템이 더 묻지 않는 상태는 가리지 않는다(AccessIntro 주석)
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(RequestMultiplePermissions()) {
        access = galleryAccess(context)
        denied = access == GalleryAccess.NONE
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
        AccessIntro(
            denied = denied,
            onAllow = { launcher.launch(galleryPermissions()) },
            onOpenSettings = { openAppSettings(context) },
        )
    } else {
        content(access)
    }
}
/**
 * 사진 권한이 없을 때(첫 실행 포함) 보이는 소개·권한 화면.
 * 탭 화면의 Scaffold 밖이라 바탕색·시스템 막대 여백을 여기서 직접 챙긴다. 큰 글자에서도 버튼까지 닿게 스크롤된다.
 * 거부한 뒤에는 "사진 접근 허용" 과 "설정에서 허용" 을 함께 둔다. 시스템이 더 묻지 않는 상태(두 번 거부)를
 * 앱에서 정확히 가릴 수 없어(첫 대화상자를 뒤로 가기로 닫아도 같은 값이 나온다) 두 길을 늘 열어 둔다.
 */
@Composable
private fun AccessIntro(denied: Boolean, onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            Text("Detect Gallery", style = MaterialTheme.typography.titleSmall, color = colors.primary)
            Text(
                "갤러리 속 사진을\n바로 찾아 드려요",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onBackground,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp).semantics { heading() },
            )
            Feature(Icons.Outlined.PhotoCamera, "카메라로 찾기", "비추고 박스를 누르면 같은 사람·비슷한 사진을 찾아요.")
            Feature(Icons.Outlined.Search, "낱말로 찾기", "\"바다\", \"엄마\", \"작년 3월\" 처럼 입력해 찾아요.")
            Feature(Icons.Outlined.People, "인물별로 모아 보기", "같은 사람의 얼굴을 묶고, 이름을 붙이면 이름으로 찾아요.")
            Row(
                Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(colors.surfaceVariant)
                    .padding(14.dp)
                    .semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = colors.onSurfaceVariant)
                Text(
                    "사진은 이 기기 안에서만 분석해요. 어디로도 보내지 않아요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            Text(
                "처음 한 번은 분석에 시간이 걸려요. 그동안에도 분석된 사진부터 쓸 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 32.dp),
            )
            if (denied) {
                // 거부 직후 나타나므로 TalkBack 이 바로 읽게 한다
                Text(
                    "사진 접근을 허용해야 시작할 수 있어요. 눌러도 다시 묻지 않으면 설정 → 권한에서 사진 접근을 허용해 주세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.error,
                    modifier = Modifier.padding(bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Button(onClick = onAllow, modifier = Modifier.fillMaxWidth()) { Text("사진 접근 허용") }
            if (denied) {
                TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("설정에서 허용") }
            }
        }
    }
}

/** 기능 한 줄: 파란 원 아이콘 + 제목 + 설명. TalkBack 은 한 줄을 한 번에 읽는다. */
@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(colors.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.onPrimaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onBackground)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
// 실제 앱에서는 화면 문구를 strings.xml 로 옮긴다.