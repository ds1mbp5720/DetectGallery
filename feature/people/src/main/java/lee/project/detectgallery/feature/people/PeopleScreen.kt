package lee.project.detectgallery.feature.people

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lee.project.detectgallery.core.ui.mediaUri
import coil.compose.AsyncImage

/** 인물 목록. 누르면 상세(얼굴 나누기·빼기·이름), 길게 누르면 합치기. 상단 스위치로 인물 기능 전체를 끈다. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PeopleScreen(onOpenPerson: (Long) -> Unit, vm: PeopleViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("인물 기능", style = MaterialTheme.typography.titleSmall)
                Text("얼굴을 분석해 같은 사람끼리 묶어요. 기기 밖으로 나가지 않아요.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = state.facesEnabled, onCheckedChange = { vm.onIntent(PeopleIntent.ToggleFaces(it)) })
        }

        if (!state.facesEnabled) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("인물 기능이 꺼져 있어요. 얼굴을 분석하지 않고 얼굴 데이터도 보관하지 않아요.")
            }
            return@Column
        }

        state.mergeSource?.let { source ->
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${source.name ?: "이 인물"}과(와) 합칠 사람을 고르세요", Modifier.weight(1f))
                    TextButton(onClick = { vm.onIntent(PeopleIntent.CancelMerge) }) { Text("취소") }
                }
            }
        }

        if (state.people.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("사진 분석이 끝나면 얼굴별로 묶어서 보여드려요.")
            }
            return@Column
        }

        LazyVerticalGrid(GridCells.Adaptive(96.dp), contentPadding = PaddingValues(12.dp)) {
            items(state.people, key = { it.id }) { p ->
                Column(
                    Modifier
                        .combinedClickable(
                            onClick = { if (state.mergeSource != null) vm.onIntent(PeopleIntent.MergeInto(p)) else onOpenPerson(p.id) },
                            onLongClick = { vm.onIntent(PeopleIntent.StartMerge(p)) },
                        )
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val cover = p.coverMediaId
                    if (cover != null) {
                        AsyncImage(
                            model = mediaUri(cover),
                            contentDescription = p.name ?: "이름 없는 인물",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(72.dp).clip(CircleShape),
                        )
                    } else {
                        // 사진이 없는 이름 인물: 이름 첫 글자. 길게 눌러 다른 인물과 합치면 이름이 넘어간다
                        Box(
                            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) { Text(p.name?.take(1).orEmpty(), style = MaterialTheme.typography.titleLarge) }
                    }
                    Text(
                        p.name ?: "이름 붙이기",
                        color = if (p.name == null) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("${p.photoCount}장", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    if (state.confirmDisable) {
        AlertDialog(
            onDismissRequest = { vm.onIntent(PeopleIntent.CancelDisable) },
            title = { Text("인물 기능을 끌까요?") },
            text = { Text("저장된 얼굴 데이터와 붙인 이름이 모두 지워져요. 다시 켜면 사진을 처음부터 다시 분석해요.") },
            confirmButton = { TextButton(onClick = { vm.onIntent(PeopleIntent.ConfirmDisable) }) { Text("끄고 지우기") } },
            dismissButton = { TextButton(onClick = { vm.onIntent(PeopleIntent.CancelDisable) }) { Text("취소") } },
        )
    }
}

@Composable
internal fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이름 붙이기") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = false },
                singleLine = true,
                isError = error,
                supportingText = { Text(if (error) "이름을 입력하세요" else "띄어쓰기 없이 저장돼요. 검색할 때 이 이름으로 찾아요.") },
                placeholder = { Text("엄마") },
            )
        },
        confirmButton = { TextButton(onClick = { if (text.isBlank()) error = true else onConfirm(text.trim()) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
// 목록의 대표 사진은 사진 전체를 원형으로 자른다. 얼굴만 보이게 하려면 대표 얼굴의 box 로 FaceThumb 를 쓴다.