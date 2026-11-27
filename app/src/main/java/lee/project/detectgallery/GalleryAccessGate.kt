package lee.project.detectgallery

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import lee.project.detectgallery.core.data.work.IndexScheduler

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

private const val PREFS_APP = "app"
private const val KEY_NOTIFICATIONS_ASKED = "notifications.asked"

/** Android 13+ 이고, 아직 허용 안 했고, 우리가 한 번도 묻지 않았을 때만. 시스템도 두 번 거부하면 더 띄우지 않는다. */
private fun shouldAskNotifications(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED &&
            !context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
                .getBoolean(KEY_NOTIFICATIONS_ASKED, false)

private fun markNotificationsAsked(context: Context) {
    context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE).edit()
        .putBoolean(KEY_NOTIFICATIONS_ASKED, true).apply()
}


private const val KEY_MEDIA_LOCATION_ASKED_FOR = "mediaLocation.askedFor"

/**
 * 사진 위치 권한이 없고 지금 사진 범위(전체·일부)에서 아직 묻지 않았을 때만 (C12). 새 설치는 사진 권한과 함께 받으므로 보통 업데이트 사용자다.
 * 범위마다 한 번: 묻는 창이 없는 권한이라 부분 접근에서 조용히 거부돼도, 범위를 바꾸면 다시 받을 수 있게.
 */
private fun shouldAskMediaLocation(context: Context, access: GalleryAccess): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_MEDIA_LOCATION
    ) != PackageManager.PERMISSION_GRANTED &&
            context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
                .getString(KEY_MEDIA_LOCATION_ASKED_FOR, null) != access.name

private fun markMediaLocationAsked(context: Context, access: GalleryAccess) {
    context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE).edit()
        .putString(KEY_MEDIA_LOCATION_ASKED_FOR, access.name).apply()
}

// ACCESS_MEDIA_LOCATION: 사진 EXIF 의 위치(추억 묶기 C12). 사진 권한과 같이 요청하며 보통 따로 묻는 창이 없다(기기에서 확인)
private fun galleryPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.ACCESS_MEDIA_LOCATION
    )

    Build.VERSION.SDK_INT >= 33 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.ACCESS_MEDIA_LOCATION
    )

    else -> arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.ACCESS_MEDIA_LOCATION
    )
}

/** afterCurrent: 볼 수 있는 사진이 바뀌었을 수 있으면 true → 도는 중인 색인 뒤에 한 번 더 붙인다. */
private fun scheduleIfAllowed(context: Context, afterCurrent: Boolean) {
    if (galleryAccess(context) != GalleryAccess.NONE) IndexScheduler.schedule(context, afterCurrent)
}

/** 부분 접근 상태에서 다시 부르면 Android 14 가 사진을 더 고르게 한다(선택 화면 또는 "더 선택·모두 허용" 대화상자, 버전마다 다름). 끝나면 색인을 다시 예약한다. */
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
    // 사진 접근을 받은 뒤 한 번만 묻는 것: 분석 진행 알림(Android 13+), 사진 위치(C12 — 업데이트 전에 사진만 허용한 사용자).
    // 한 번에 같이 묻는다(따로 띄우면 두 번째 요청이 무시될 수 있다). 거부해도 분석은 그대로 된다.
    // 위치는 보통 창 없이 허용되고, 허용되면 도는 색인 뒤에 한 번 더 붙여 이미 분석한 사진의 위치만 다시 읽는다
    val followUpLauncher =
        rememberLauncherForActivityResult(RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_MEDIA_LOCATION] == true) scheduleIfAllowed(
                context,
                afterCurrent = true
            )
        }
    LaunchedEffect(access) {
        if (access == GalleryAccess.NONE) return@LaunchedEffect
        val ask = buildList {
            if (shouldAskMediaLocation(context, access)) {
                markMediaLocationAsked(
                    context,
                    access
                ); add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            }
            if (shouldAskNotifications(context)) {
                markNotificationsAsked(context); add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ask.isNotEmpty()) followUpLauncher.launch(ask.toTypedArray())
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
            Text(
                "Detect Gallery",
                style = MaterialTheme.typography.titleSmall,
                color = colors.primary
            )
            Text(
                "갤러리 속 사진을\n바로 찾아 드려요",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onBackground,
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 24.dp)
                    .semantics { heading() },
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
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Button(onClick = onAllow, modifier = Modifier.fillMaxWidth()) { Text("사진 접근 허용") }
            if (denied) {
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("설정에서 허용") }
            }
        }
    }
}

/** 기능 한 줄: 파란 원 아이콘 + 제목 + 설명. TalkBack 은 한 줄을 한 번에 읽는다. */
@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.primaryContainer),
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

internal fun openAppSettings(context: Context) {   // AppNavHost 의 부분 접근 메뉴도 쓴다
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
// 실제 앱에서는 화면 문구를 strings.xml 로 옮긴다.