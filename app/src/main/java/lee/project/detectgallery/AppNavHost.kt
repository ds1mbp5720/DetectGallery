package lee.project.detectgallery

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import lee.project.detectgallery.core.ui.findActivity
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import lee.project.detectgallery.feature.live.LiveScreen
import lee.project.detectgallery.feature.people.PeopleScreen
import lee.project.detectgallery.feature.people.detail.PersonDetailScreen
import lee.project.detectgallery.feature.search.SearchScreen
import lee.project.detectgallery.core.ui.components.BottomItem
import lee.project.detectgallery.core.ui.components.CompactBottomBar
import lee.project.detectgallery.core.ui.components.LensStatusBar
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.NavBackStackEntry
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import lee.project.detectgallery.album.AlbumPickerIntent
import lee.project.detectgallery.album.AlbumPickerViewModel
import lee.project.detectgallery.album.AlbumSheet
import lee.project.detectgallery.feature.search.clean.CleanupScreen

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    LIVE("live", "카메라", Icons.Outlined.PhotoCamera),
    SEARCH("search", "검색", Icons.Outlined.Search),
    PEOPLE("people", "인물", Icons.Outlined.People),
}

/** "people/{personId}" 같은 하위 화면도 그 탭으로 본다. */
private fun tabOf(route: String?): Tab? =
    Tab.entries.firstOrNull { route == it.route || route?.startsWith(it.route + "/") == true }

/** 이 항목이 지금 맨 위 화면인가. 이동·뒤로를 맨 위 화면에서 온 것만 받는다(연타는 첫 번째 뒤로 맨 위가 바뀌어 걸러진다). */
private fun NavController.isTop(entry: NavBackStackEntry) = currentBackStackEntry?.id == entry.id

/**
 * @param sharedUri 다른 앱에서 공유받은 사진. 검색 탭으로 옮겨 사진으로 찾고, 받으면 onSharedConsumed(그 사진) 로 알린다.
 */
@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun AppNavHost(
    access: GalleryAccess,
    sharedUri: String? = null,
    onSharedConsumed: (String) -> Unit = {},
    statusVm: AppStatusViewModel = hiltViewModel(),
) {
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    // 공유받은 사진은 검색 탭에서 찾는다. 검색이 시작 화면이라 늘 스택 맨 아래에 있다 → 그 위(다른 탭·정리 화면)를 걷어 낸다.
    // 그래프가 붙기 전(첫 프레임)의 pop 은 아무 일도 하지 않으므로 화면이 생긴 뒤(current != null) 한다
    LaunchedEffect(sharedUri, current != null) {
        if (sharedUri != null && current != null) nav.popBackStack(
            Tab.SEARCH.route,
            inclusive = false
        )
    }
    val library by statusVm.library.collectAsStateWithLifecycle()
    val requestMore = rememberGalleryRequest()
    val context = LocalContext.current
    // 검색 첫 화면에서 뒤로 = 앱 닫기 확인 (C11). 뒤로 처리는 나중에 등록된 것이 먼저라 이 처리는 맨 마지막 차례다:
    // 사진 카드가 있으면 SearchScreen 의 뒤로(끝내기·공유한 앱으로)가, 다른 탭·하위 화면이면 NavHost 의 뒤로(검색으로)가 먼저 받는다
    var confirmExit by rememberSaveable { mutableStateOf(false) }   // 회전해도 열린 채
    BackHandler(enabled = current?.destination?.route == Tab.SEARCH.route) { confirmExit = true }
    if (confirmExit) {
        ExitDialog(
            indexing = library?.indexing == true,
            onDismiss = { confirmExit = false },
            onExit = {
                confirmExit = false
                context.findActivity()?.finishAndRemoveTask()
            },
        )
    }
    var accessMenu by remember { mutableStateOf(false) }   // 사진 범위 메뉴 열림
    var albumSheet by rememberSaveable { mutableStateOf(false) }   // 분석할 앨범 고르기 시트 (회전해도 열린 채)
    val albumVm: AlbumPickerViewModel =
        hiltViewModel()   // Activity 범위: 시트를 닫아도 저장이 끝까지, 회전해도 고르던 내용 유지
    val albumFiltered by statusVm.albumFiltered.collectAsStateWithLifecycle()
    val albumState by albumVm.state.collectAsStateWithLifecycle()
    LaunchedEffect(albumState.saveFailed) {   // 시트가 닫힌 뒤의 저장 실패라 화면 어디서든 보이는 토스트로
        if (albumState.saveFailed) {
            Toast.makeText(context, "앨범 선택을 저장하지 못했어요. 다시 골라 주세요.", Toast.LENGTH_SHORT).show()
            albumVm.onIntent(AlbumPickerIntent.SaveFailedShown)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            CompactBottomBar(
                items = Tab.entries.map { BottomItem(it.route, it.label, it.icon) },
                selectedKey = tabOf(current?.destination?.route)?.route,
                onSelect = { route ->
                    if (tabOf(current?.destination?.route)?.route == route) {
                        // 지금 탭을 다시 누르면 그 탭 첫 화면으로 (인물 상세 → 인물 목록). navigate 는 저장된 상세를 그대로 복원한다
                        nav.popBackStack(route, inclusive = false)
                    } else {
                        nav.navigate(route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
            )
        },
    ) { padding ->
        // consumeWindowInsets: 바깥에서 쓴 시스템 바 여백을 "썼다" 고 알린다. 빼면 안쪽 화면(인물 상세의 Scaffold·
        // TopAppBar)이 상태바·내비게이션 바 여백을 한 번 더 넣는다. 화면마다 WindowInsets(0) 을 줄 필요가 없어진다.
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            // 모든 탭 위에 항상. 카드를 누르면("범위 ›") 사진 범위 메뉴: 분석할 앨범 고르기, 부분 접근이면 더 고르기·전체 허용
            Box {   // 메뉴를 현황 줄 바로 아래에 붙이려고 같은 Box 에 둔다
                LensStatusBar(
                    galleryCount = library?.galleryCount,
                    analyzedCount = library?.analyzedCount ?: 0,
                    indexing = library?.indexing == true,
                    partial = access == GalleryAccess.PARTIAL,
                    albumFiltered = albumFiltered,
                    onScopeClick = { accessMenu = true },
                )
                AccessMenu(
                    expanded = accessMenu,
                    partial = access == GalleryAccess.PARTIAL,   // 부분 접근 항목은 그때만. 연 채로 나가 전체 허용으로 바꿔 와도 그 두 항목은 사라진다
                    onDismiss = { accessMenu = false },
                    onPickAlbums = {
                        albumVm.onIntent(AlbumPickerIntent.Load()); albumSheet = true
                    },   // 열 때마다 새로 읽는다
                    onPickMore = requestMore,
                    onAllowAll = { openAppSettings(context) },
                )
            }
            if (albumSheet) AlbumSheet(albumVm, onDismiss = { albumSheet = false })
            NavHost(nav, startDestination = Tab.SEARCH.route) {
                composable(Tab.LIVE.route) { LiveScreen() }
                composable(Tab.SEARCH.route) { entry ->
                    SearchScreen(
                        sharedUri = sharedUri,
                        onSharedConsumed = onSharedConsumed,
                        onOpenCleanup = { if (nav.isTop(entry)) nav.navigate("search/cleanup") },
                    )
                }
                // 검색 탭의 하위 화면(tabOf 가 "search/" 로 시작하면 검색 탭으로 본다)
                composable("search/cleanup") { entry -> CleanupScreen(onBack = { if (nav.isTop(entry)) nav.popBackStack() }) }
                // 이동·뒤로는 그 화면이 맨 위일 때만: 두 번 누르면 상세가 두 번 쌓이고, 뒤로를 연타하면 한 화면 더 빠진다.
                // RESUMED 로 거르면 전환 애니메이션 중(STARTED)의 자동 닫기(인물이 사라짐 → onBack)까지 막혀 빈 상세에 남는다.
                composable(Tab.PEOPLE.route) { entry ->
                    PeopleScreen(onOpenPerson = { id -> if (nav.isTop(entry)) nav.navigate("people/$id") })
                }
                composable(
                    "people/{personId}",
                    arguments = listOf(navArgument("personId") { type = NavType.LongType }),
                ) { entry -> PersonDetailScreen(onBack = { if (nav.isTop(entry)) nav.popBackStack() }) }
            }
        }
    }
}

/**
 * 앱 닫기 확인 (C11). 닫으면 화면과 최근 앱 항목만 없앤다(finishAndRemoveTask) — 프로세스는 죽이지 않는다:
 * 사진 분석은 WorkManager 가 알림과 함께 이어서 하고 끝나면 완료 알림이 뜬다. 다음에 열면 검색 첫 화면부터.
 */
@Composable
private fun ExitDialog(indexing: Boolean, onDismiss: () -> Unit, onExit: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("앱을 닫을까요?") },
        text = if (indexing) {
            { Text("사진 분석은 앱을 닫아도 알림과 함께 계속돼요.") }
        } else null,
        confirmButton = { TextButton(onClick = onExit) { Text("닫기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/**
 * 사진 범위 메뉴. "분석할 앨범 고르기" 는 항상, 부분 접근("선택한 사진만 허용")이면 더 고르기·전체 허용을 더한다.
 * 앱은 권한을 직접 "모두 허용" 으로 바꿀 수 없어(사용자가 시스템 화면에서 골라야 한다) 전체 허용은 앱 설정 화면으로 보낸다.
 * 돌아오면 GalleryAccessGate 의 복귀 확인이 FULL 을 읽고 색인을 다시 예약한다.
 */
@Composable
private fun AccessMenu(
    expanded: Boolean,
    partial: Boolean,
    onDismiss: () -> Unit,
    onPickAlbums: () -> Unit,
    onPickMore: () -> Unit,
    onAllowAll: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(16.dp, 0.dp)
    ) {
        AccessMenuItem("분석할 앨범 고르기", "고른 앨범의 사진만 분석·검색해요") { onDismiss(); onPickAlbums() }
        if (partial) {
            AccessMenuItem("사진 더 고르기", "분석할 사진을 더 골라요") { onDismiss(); onPickMore() }
            AccessMenuItem("전체 사진 허용", "앱 정보의 권한에서 사진 및 동영상을 모두 허용으로") { onDismiss(); onAllowAll() }
        }
    }
}

@Composable
private fun AccessMenuItem(title: String, desc: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        onClick = onClick,
    )
}