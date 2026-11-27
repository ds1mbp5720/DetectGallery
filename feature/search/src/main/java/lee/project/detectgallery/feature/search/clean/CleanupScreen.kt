package lee.project.detectgallery.feature.search.clean

import android.app.Activity
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lee.project.detectgallery.core.ui.components.ChipRow
import lee.project.detectgallery.core.ui.components.NoticeCard
import lee.project.detectgallery.core.ui.mediaUri
import lee.project.detectgallery.core.ui.openInGallery
import coil.compose.AsyncImage
import kotlin.text.get

/**
 * 사진 정리 (C10). 겹치는·흔들린·어두운 사진과 스크린샷을 모아 고른 뒤 휴지통으로 옮긴다.
 * 누르면 고르고, 길게 누르면 갤러리 앱에서 크게 본다(흔들림은 작은 칸으로 판단하기 어렵다).
 * 지우기는 시스템 확인 창을 거친 휴지통 이동뿐이다(30일 뒤 자동 삭제, 그 전에는 되살릴 수 있다). 영구 삭제는 하지 않는다.
 */
@RequiresApi(Build.VERSION_CODES.R)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupScreen(onBack: () -> Unit, vm: CleanupViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pending by rememberSaveable { mutableStateOf(LongArray(0)) }   // 확인 창에 보낸 사진. 창이 떠 있는 동안 회전해도 남게
    val trash = rememberLauncherForActivityResult(StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && pending.isNotEmpty()) {
            vm.onIntent(CleanupIntent.Trashed(pending.toSet()))
            Toast.makeText(context, "${pending.size}장을 휴지통으로 옮겼어요.", Toast.LENGTH_SHORT).show()
        }
        pending = LongArray(0)
    }
    BackHandler(enabled = state.selected.isNotEmpty()) { vm.onIntent(CleanupIntent.ClearSelection) }   // 뒤로 = 고르기 취소 먼저

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("사진 정리") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
        bottomBar = {
            if (state.sets != null) {
                CleanupActionBar(
                    state,
                    onSelectAll = { vm.onIntent(CleanupIntent.SelectAll) },
                    onClear = { vm.onIntent(CleanupIntent.ClearSelection) },
                    onTrash = {
                        val ids = state.selected
                        runCatching { MediaStore.createTrashRequest(context.contentResolver, ids.map(::mediaUri), true).intentSender }
                            .onSuccess {
                                pending = ids.toLongArray()
                                trash.launch(IntentSenderRequest.Builder(it).build())
                            }
                            .onFailure { Toast.makeText(context, "휴지통으로 옮기지 못했어요. 고른 사진을 줄여 다시 해 보세요.", Toast.LENGTH_SHORT).show() }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // 칩 글자에 장 수를 붙인다. 글자가 곧 키라 장 수가 바뀌어도 탭마다 다르다
            val labels = CleanupTab.entries.associateWith { t -> if (state.sets == null) t.label else "${t.label} ${state.count(t)}" }
            ChipRow(
                items = labels.values.toList(),
                selected = labels[state.tab],
                onClick = { label -> labels.entries.firstOrNull { it.value == label }?.let { vm.onIntent(CleanupIntent.TabChanged(it.key)) } },
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "누르면 고르고, 길게 누르면 크게 봐요. 고른 사진은 휴지통으로 옮겨요(30일 뒤 자동 삭제).",
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.indexProgress?.let { p ->
                Text(
                    "아직 분석 중이라 분석된 사진만 보여요 (${p.done} / ${p.total}). 스크린샷은 모두 보여요.",
                    Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when {
                state.failed -> NoticeCard("정리할 사진을 불러오지 못했어요", "잠시 뒤 다시 시도해 주세요.") {
                    TextButton(onClick = { vm.onIntent(CleanupIntent.Retry) }) { Text("다시 시도") }
                }
                state.sets == null -> Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("사진을 살펴보는 중이에요", style = MaterialTheme.typography.bodyMedium)
                }
                state.visible.isEmpty() -> NoticeCard("정리할 사진이 없어요", emptyText(state.tab))
                else -> CleanupGrid(state, onToggle = { vm.onIntent(CleanupIntent.Toggle(it)) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun emptyText(tab: CleanupTab) = when (tab) {
    CleanupTab.DUPLICATE -> "연사나 같은 사진처럼 겹치는 사진이 없어요."
    CleanupTab.BLURRY -> "흔들린 사진이 없어요."
    CleanupTab.DARK -> "어두운 사진이 없어요."
    CleanupTab.SCREENSHOT -> "스크린샷 폴더에 사진이 없어요."
}

@Composable
private fun CleanupGrid(state: CleanupUiState, onToggle: (Long) -> Unit, modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        GridCells.Adaptive(104.dp),
        modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state.tab == CleanupTab.DUPLICATE) {
            // 묶음마다 머리글 한 줄 + 사진. 묶음끼리는 사진이 겹치지 않아 사진 id 를 그대로 키로 쓴다
            state.sets?.duplicates.orEmpty().forEach { g ->
                item(key = "group-${g.keep}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "비슷한 사진 ${g.ids.size}장",
                        Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() },
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                items(g.ids, key = { it }) { id -> CleanupCell(id, id in state.selected, keep = id == g.keep, onToggle) }
            }
        } else {
            items(state.visible, key = { it }) { id -> CleanupCell(id, id in state.selected, keep = false, onToggle) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CleanupCell(id: Long, selected: Boolean, keep: Boolean, onToggle: (Long) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(colors.surfaceVariant)
            .combinedClickable(
                onClickLabel = if (selected) "고르기 취소" else "고르기",
                onLongClickLabel = "크게 보기",
                onLongClick = { openInGallery(context, id) },
                role = Role.Checkbox,   // 고르지 않은 칸도 "고를 수 있는 항목" 으로 읽힌다
                onClick = { onToggle(id) },
            )
            .semantics {
                toggleableState = ToggleableState(selected)   // 체크 표시는 색·모양뿐이라 상태를 따로 알린다
                contentDescription = if (keep) "사진, 남기기 추천" else "사진"
            },
    ) {
        AsyncImage(model = mediaUri(id), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (selected) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            Icon(Icons.Filled.CheckCircle, contentDescription = null, Modifier.align(Alignment.TopEnd).padding(6.dp), tint = Color.White)
        }
        if (keep) {
            Text(
                "남기기 추천",
                Modifier
                    .align(Alignment.BottomStart)
                    .clearAndSetSemantics {}   // 칸의 설명("사진, 남기기 추천")에 이미 있다. 두 번 읽지 않게
                    .padding(4.dp)
                    .background(colors.primary, MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = colors.onPrimary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** 아래 막대: 왼쪽 고르기(모두/추천대로 ↔ 취소), 오른쪽 휴지통으로. */
@Composable
private fun CleanupActionBar(state: CleanupUiState, onSelectAll: () -> Unit, onClear: () -> Unit, onTrash: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.selected.isEmpty()) {
                TextButton(onClick = onSelectAll, enabled = state.selectable.isNotEmpty()) {
                    Text(if (state.tab == CleanupTab.DUPLICATE) "추천대로 고르기" else "모두 고르기")
                }
            } else {
                TextButton(onClick = onClear) { Text("고르기 취소") }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onTrash, enabled = state.selected.isNotEmpty()) {
                Text(if (state.selected.isEmpty()) "휴지통으로" else "${state.selected.size}장 휴지통으로")
            }
        }
    }
}
// ponytail: 고른 사진을 요청 한 번에 담는다. 수천 장에서 요청이 실패하면(위 토스트) 나눠 보내기를 추가.