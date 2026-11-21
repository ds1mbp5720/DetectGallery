package lee.project.detectgallery.feature.people.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lee.project.detectgallery.core.domain.model.PersonFace
import lee.project.detectgallery.core.ui.FaceThumb
import lee.project.detectgallery.core.ui.components.NoticeCard
import lee.project.detectgallery.core.ui.mediaUri
import lee.project.detectgallery.core.ui.openInGallery
import lee.project.detectgallery.feature.people.RenameDialog

/**
 * 인물 상세. 이 인물로 묶인 얼굴을 모두 보여 준다.
 * 누르면 그 사진을 열고, 길게 눌러 고르면 "나누기"(다른 사람) 또는 "얼굴 아님" 을 할 수 있다.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    // 고르는 중 뒤로 가기 = 고르기 취소 (화면은 닫지 않는다)
    BackHandler(enabled = selecting) { vm.onIntent(PersonDetailIntent.ClearSelection) }

    val grid = rememberLazyGridState()
    val headerGone by remember { derivedStateOf { grid.firstVisibleItemIndex > 0 } }   // 머리가 스크롤로 사라지면 위 막대에 이름
    val title = state.name ?: if (state.loaded) "이름 없는 인물" else ""
    val photoCount = remember(state.faces) { state.faces.mapTo(HashSet()) { it.mediaId }.size }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${state.selected.size}개 고름") },
                    navigationIcon = {
                        IconButton(onClick = { vm.onIntent(PersonDetailIntent.ClearSelection) }) {
                            Icon(Icons.Filled.Close, contentDescription = "고르기 취소")
                        }
                    },
                )
            } else {
                TopAppBar(
                    // 빈 상태엔 머리가 없으므로 처음부터 이름을 띄운다
                    title = {
                        if (headerGone || state.faces.isEmpty()) Text(
                            title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "뒤로"
                            )
                        }
                    },
                    actions = {
                        if (state.loaded) {
                            TextButton(onClick = { vm.onIntent(PersonDetailIntent.ShowRename(true)) }) {
                                Text(if (state.name == null) "이름 붙이기" else "이름 바꾸기")
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (selecting) SelectionBar(
                onExclude = { vm.onIntent(PersonDetailIntent.ExcludeSelected) },
                onSplit = { vm.onIntent(PersonDetailIntent.SplitSelected) },
            )
        },
    ) { padding ->
        when {
            !state.loaded -> Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
            )

            state.faces.isEmpty() -> NoticeCard(
                title = "이 인물의 사진이 없어요",
                body = "인물 목록에서 이 인물을 길게 눌러 다른 인물과 합치면 이름이 옮겨 가요.",
                modifier = Modifier.padding(padding),
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                state = grid,
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 16.dp),
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    Header(title, state.faces.first(), photoCount, state.faces.size, selecting)
                }
                itemsIndexed(state.faces, key = { _, f -> f.faceId }) { i, f ->
                    FaceCell(
                        face = f,
                        label = "얼굴 ${i + 1}/${state.faces.size}",   // TalkBack 이 셀을 구분하게 (FaceChoiceDialog 와 같은 방식)
                        selected = f.faceId in state.selected,
                        selecting = selecting,
                        onClick = {
                            if (selecting) vm.onIntent(PersonDetailIntent.Toggle(f.faceId)) else openInGallery(
                                context,
                                f.mediaId
                            )
                        },
                        onLongClick = { vm.onIntent(PersonDetailIntent.Toggle(f.faceId)) },
                    )
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

/** 큰 대표 얼굴 + 이름 + 장 수 + 사용법 한 줄. 그리드 첫 줄을 가로로 다 차지하고 함께 스크롤된다. */
@Composable
private fun Header(
    title: String,
    cover: PersonFace,
    photoCount: Int,
    faceCount: Int,
    selecting: Boolean
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            FaceThumb(
                mediaUri(cover.mediaId),
                cover.box.left,
                cover.box.top,
                cover.box.right,
                cover.box.bottom,
                Modifier.size(72.dp),
                contentDescription = null,   // 장식. 이름은 옆 글자가 읽는다
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onSurface
                )
                Text(
                    if (faceCount == photoCount) "사진 ${photoCount}장" else "사진 ${photoCount}장 · 얼굴 ${faceCount}개",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Text(
            if (selecting) "누르면 고르거나 뺄 수 있어요. 다 고르면 아래 버튼을 누르세요."
            else "누르면 사진을 열어요. 다른 사람이 섞였으면 그 얼굴을 길게 눌러 고르세요.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FaceCell(
    face: PersonFace,
    label: String,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .padding(6.dp)
            .aspectRatio(1f)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .then(
                    if (selected) Modifier.border(
                        3.dp,
                        colors.primary,
                        CircleShape
                    ) else Modifier
                )
                .combinedClickable(
                    onClickLabel = when {
                        !selecting -> "갤러리에서 열기"; selected -> "고르기 해제"; else -> "고르기"
                    },
                    onLongClickLabel = "고르기",
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .semantics { this.selected = selected }
                .padding(if (selected) 7.dp else 0.dp),   // 고른 얼굴은 테두리 안쪽으로 작아진다
        ) {
            FaceThumb(
                mediaUri(face.mediaId),
                face.box.left,
                face.box.top,
                face.box.right,
                face.box.bottom,
                Modifier.fillMaxSize(),
                contentDescription = label,
            )
        }
        if (selecting) {
            // 고르는 중엔 모든 얼굴에 동그라미: 빈 원 = 안 고름, 파란 원 + 체크 = 고름
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.primary else colors.surface)
                    .border(2.dp, if (selected) colors.surface else colors.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = colors.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** 고르는 중 아래 막대: 설명 한 줄 + 두 버튼(주요 동작 "나누기" 를 오른쪽에). */
@Composable
private fun SelectionBar(onExclude: () -> Unit, onSplit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainer) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                "다른 사람이면 나누기(새 인물로), 사람 얼굴이 아니면 얼굴 아님.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onExclude,
                    modifier = Modifier.weight(1f)
                ) { Text("얼굴 아님") }
                Button(onClick = onSplit, modifier = Modifier.weight(1f)) { Text("나누기") }
            }
        }
    }
}
// 실제 앱에서는 화면 문구를 strings.xml 로 옮긴다.