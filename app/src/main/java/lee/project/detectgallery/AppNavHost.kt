package lee.project.detectgallery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
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

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    LIVE("live", "카메라", Icons.Outlined.PhotoCamera),
    SEARCH("search", "검색", Icons.Outlined.Search),
    PEOPLE("people", "인물", Icons.Outlined.People),
}

@Composable
fun AppNavHost(access: GalleryAccess) {
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    val requestMore = rememberGalleryRequest()

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current?.destination?.route == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (access == GalleryAccess.PARTIAL) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("선택한 사진에서만 찾아요", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = requestMore) { Text("사진 더 고르기") }
                    }
                }
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