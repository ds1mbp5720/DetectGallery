package lee.project.detectgallery

import androidx.compose.foundation.layout.Column
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

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    LIVE("live", "카메라", Icons.Outlined.PhotoCamera),
    SEARCH("search", "검색", Icons.Outlined.Search),
    PEOPLE("people", "인물", Icons.Outlined.People),
}

/** "people/{personId}" 같은 하위 화면도 그 탭으로 본다. */
private fun tabOf(route: String?): Tab? =
    Tab.entries.firstOrNull { route == it.route || route?.startsWith(it.route + "/") == true }

@Composable
fun AppNavHost(access: GalleryAccess, statusVm: AppStatusViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    val library by statusVm.library.collectAsStateWithLifecycle()
    val requestMore = rememberGalleryRequest()
    val context = LocalContext.current
    var accessMenu by remember { mutableStateOf(false) }   // 부분 접근 메뉴 열림

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            CompactBottomBar(
                items = Tab.entries.map { BottomItem(it.route, it.label, it.icon) },
                selectedKey = tabOf(current?.destination?.route)?.route,
                onSelect = { route ->
                    nav.navigate(route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
    ) { padding ->
        // consumeWindowInsets: 바깥에서 쓴 시스템 바 여백을 "썼다" 고 알린다. 빼면 안쪽 화면(인물 상세의 Scaffold·
        // TopAppBar)이 상태바·내비게이션 바 여백을 한 번 더 넣는다. 화면마다 WindowInsets(0) 을 줄 필요가 없어진다.
        Column(Modifier
            .padding(padding)
            .consumeWindowInsets(padding)) {
            // 모든 탭 위에 항상. 부분 접근이면 카드를 눌러("사진 범위 ›") 더 고르기·전체 허용 메뉴를 연다.
            Box {   // 메뉴를 현황 줄 바로 아래에 붙이려고 같은 Box 에 둔다
                LensStatusBar(
                    galleryCount = library?.galleryCount,
                    analyzedCount = library?.analyzedCount ?: 0,
                    indexing = library?.indexing == true,
                    partial = access == GalleryAccess.PARTIAL,
                    onPartialClick = { accessMenu = true },
                )
                AccessMenu(
                    expanded = accessMenu && access == GalleryAccess.PARTIAL,   // 연 채로 나가 설정에서 바꾸고 와도 남지 않게
                    onDismiss = { accessMenu = false },
                    onPickMore = requestMore,
                    onAllowAll = { openAppSettings(context) },
                )
            }
            NavHost(nav, startDestination = Tab.SEARCH.route) {
                composable(Tab.LIVE.route) { LiveScreen() }
                composable(Tab.SEARCH.route) { SearchScreen() }
                composable(Tab.PEOPLE.route) { PeopleScreen(onOpenPerson = { id -> nav.navigate("people/$id") }) }
                composable(
                    "people/{personId}",
                    arguments = listOf(navArgument("personId") { type = NavType.LongType }),
                ) { PersonDetailScreen(onBack = { nav.popBackStack() }) }
            }
        }
    }
}

/**
 * 부분 접근("선택한 사진만 허용") 메뉴. 앱은 권한을 직접 "모두 허용" 으로 바꿀 수 없어(사용자가 시스템 화면에서 골라야 한다)
 * 전체 허용은 앱 설정 화면으로 보낸다. 돌아오면 GalleryAccessGate 의 복귀 확인이 FULL 을 읽고 색인을 다시 예약한다.
 */
@Composable
private fun AccessMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPickMore: () -> Unit,
    onAllowAll: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(16.dp, 0.dp)
    ) {
        AccessMenuItem("사진 더 고르기", "분석할 사진을 더 골라요") { onDismiss(); onPickMore() }
        AccessMenuItem("전체 사진 허용", "앱 정보의 권한에서 사진 및 동영상을 모두 허용으로") { onDismiss(); onAllowAll() }
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