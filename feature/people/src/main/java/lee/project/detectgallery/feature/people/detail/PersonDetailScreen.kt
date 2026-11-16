package lee.project.detectgallery.feature.people.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lee.project.detectgallery.core.ui.FaceThumb
import lee.project.detectgallery.core.ui.mediaUri
import lee.project.detectgallery.core.ui.openInGallery
import lee.project.detectgallery.feature.people.RenameDialog

/**
 * 인물 상세. 이 인물로 묶인 얼굴을 모두 보여 준다.
 * 누르면 그 사진을 열고, 길게 눌러 고르면 "다른 사람으로 나누기" 또는 "사람 얼굴 아님" 을 할 수 있다.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PersonDetailScreen(onBack: () -> Unit, vm: PersonDetailViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.gone) { if (state.gone) onBack() }
    val selecting = state.selected.isNotEmpty()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        state.notice?.let { snackbar.showSnackbar(it); vm.onIntent(PersonDetailIntent.NoticeShown) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(state.name ?: if (state.loaded) "이름 없는 인물" else "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") } },
                actions = { TextButton(onClick = { vm.onIntent(PersonDetailIntent.ShowRename(true)) }) { Text("이름") } },
            )
        },
        bottomBar = {
            if (selecting) {
                BottomAppBar {
                    Text("${state.selected.size}개", Modifier.padding(horizontal = 12.dp))
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.onIntent(PersonDetailIntent.SplitSelected) }) { Text("다른 사람으로 나누기") }
                    TextButton(onClick = { vm.onIntent(PersonDetailIntent.ExcludeSelected) }) { Text("얼굴 아님") }
                    TextButton(onClick = { vm.onIntent(PersonDetailIntent.ClearSelection) }) { Text("취소") }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Text(
                when {
                    selecting -> "다른 사람이면 \"나누기\", 사람 얼굴이 아니면 \"얼굴 아님\" 을 누르세요."
                    !state.loaded -> ""
                    state.faces.isEmpty() -> "이 인물의 사진이 없어요. 인물 목록에서 길게 눌러 다른 인물과 합치면 이름이 옮겨 가요."
                    else -> "다른 사람이 섞였으면 그 얼굴을 길게 눌러 고르세요."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyVerticalGrid(GridCells.Adaptive(88.dp), contentPadding = PaddingValues(12.dp)) {
                items(state.faces, key = { it.faceId }) { f ->
                    val isSelected = f.faceId in state.selected
                    Box(Modifier.padding(6.dp), contentAlignment = Alignment.Center) {
                        FaceThumb(
                            mediaUri(f.mediaId), f.box.left, f.box.top, f.box.right, f.box.bottom,
                            Modifier
                                .size(76.dp)
                                .border(if (isSelected) 3.dp else 0.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                                .combinedClickable(
                                    onClick = {
                                        if (selecting) vm.onIntent(PersonDetailIntent.Toggle(f.faceId)) else openInGallery(context, f.mediaId)
                                    },
                                    onLongClick = { vm.onIntent(PersonDetailIntent.Toggle(f.faceId)) },
                                ),
                        )
                    }
                }
            }
        }
    }

    if (state.renaming) {
        RenameDialog(
            initial = state.name.orEmpty(),
            onDismiss = { vm.onIntent(PersonDetailIntent.ShowRename(false)) },
            onConfirm = { vm.onIntent(PersonDetailIntent.Rename(it)) },
        )
    }
}