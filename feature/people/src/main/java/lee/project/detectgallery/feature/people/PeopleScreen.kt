package lee.project.detectgallery.feature.people

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
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
import lee.project.detectgallery.core.domain.logic.FaceAnalysisState

/** 인물 목록. 누르면 상세(얼굴 나누기·빼기·이름), 길게 누르면 합치기. 상단 스위치로 인물 기능 전체를 끈다. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PeopleScreen(onOpenPerson: (Long) -> Unit, vm: PeopleViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        // 상단: 회색 카드 안에 제목·설명·스위치
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("인물 기능", style = MaterialTheme.typography.titleSmall)
                Text(
                    "얼굴을 분석해 같은 사람끼리 묶어요. 기기 밖으로 나가지 않아요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.facesEnabled,
                onCheckedChange = { vm.onIntent(PeopleIntent.ToggleFaces(it)) })
        }

        if (!state.facesEnabled) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp), contentAlignment = Alignment.Center
            ) {
                Text("인물 기능이 꺼져 있어요. 얼굴을 분석하지 않고 얼굴 데이터도 보관하지 않아요.")
            }
            return@Column
        }

        state.mergeSource?.let { source ->
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${source.name ?: "이 인물"}과(와) 합칠 사람을 고르세요",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = { vm.onIntent(PeopleIntent.CancelMerge) }) { Text("취소") }
                }
            }
        }

        if (state.people.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp), contentAlignment = Alignment.Center
            ) {
                Text(faceStatusText(state.faceState))      // 왜 비어 있는지 상태별로 알린다
            }
            return@Column
        }
        // 인물이 있어도 얼굴만 남은 경우(모델을 나중에 넣었거나 기능을 다시 켬): 위 현황 줄 숫자는 장면 기준이라 여기서 알린다
        (state.faceState as? FaceAnalysisState.FacesCatchingUp)?.let {
            FaceProgressRow(
                it.done,
                it.total
            )
        }

        LazyVerticalGrid(GridCells.Adaptive(96.dp), contentPadding = PaddingValues(12.dp)) {
            items(state.people, key = { it.id }) { p ->
                Column(
                    Modifier
                        .combinedClickable(
                            onClick = {
                                if (state.mergeSource != null) vm.onIntent(
                                    PeopleIntent.MergeInto(
                                        p
                                    )
                                ) else onOpenPerson(p.id)
                            },
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
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape),
                        )
                    } else {
                        // 사진이 없는 이름 인물: 이름 첫 글자. 길게 눌러 다른 인물과 합치면 이름이 넘어간다
                        Box(
                            Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                p.name?.take(1).orEmpty(),
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }
                    Text(
                        p.name ?: "이름 붙이기",
                        color = if (p.name == null) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "${p.photoCount}장",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (state.confirmDisable) {
        AlertDialog(
            onDismissRequest = { vm.onIntent(PeopleIntent.CancelDisable) },
            title = { Text("인물 기능을 끌까요?") },
            text = { Text("저장된 얼굴 데이터와 붙인 이름이 모두 지워져요. 다시 켜면 사진을 처음부터 다시 분석해요.") },
            confirmButton = {
                // 되돌릴 수 없는 삭제라 빨간 글자로 구분한다. 다른 대화상자의 확인 버튼은 그대로 파랑
                TextButton(
                    onClick = { vm.onIntent(PeopleIntent.ConfirmDisable) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("끄고 지우기") }
            },
            dismissButton = {
                TextButton(onClick = { vm.onIntent(PeopleIntent.CancelDisable) }) {
                    Text(
                        "취소"
                    )
                }
            },
        )
    }
}

/**
 * 이름 붙이기·바꾸기. 열면 입력칸에 바로 커서가 가고 기존 이름은 전체 선택돼 바로 덮어쓸 수 있다.
 * 키보드의 완료 키로도 저장한다. 빈 이름은 막고, 띄어쓰기는 저장할 때 NamePersonUseCase 가 뺀다.
 */
@Composable
internal fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    var error by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val submit: () -> Unit = {
        if (field.text.isBlank()) error = true else onConfirm(field.text.trim())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.isEmpty()) "이름 붙이기" else "이름 바꾸기") },
        text = {
            // 대화상자 창 안에서 입력칸이 붙은 뒤 포커스를 준다 (대화상자 밖에서 부르면 아직 붙지 않았을 수 있다)
            LaunchedEffect(Unit) { focus.requestFocus() }
            OutlinedTextField(
                value = field,
                // TextFieldValue 는 커서·선택만 바뀌어도 불린다 → 글자가 바뀔 때만 오류를 지운다
                onValueChange = { if (it.text != field.text) error = false; field = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                singleLine = true,
                isError = error,
                placeholder = { Text("엄마") },
                supportingText = { Text(if (error) "이름을 입력하세요" else "띄어쓰기 없이 저장돼요. 검색할 때 이 이름으로 찾아요.") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
        },
        confirmButton = { TextButton(onClick = submit) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
// 목록의 대표 사진은 사진 전체를 원형으로 자른다. 얼굴만 보이게 하려면 대표 얼굴의 box 로 FaceThumb 를 쓴다.

/** 인물 목록이 비었을 때의 안내. 꺼짐은 위에서 따로 처리하지만 when 을 빠짐없이 쓰려고 둔다. */
private fun faceStatusText(s: FaceAnalysisState?): String = when (s) {
    FaceAnalysisState.ModelMissing -> "인물 모델이 설치되지 않아 얼굴을 분석하지 않아요. 사진·키워드 검색은 그대로 쓸 수 있어요."
    FaceAnalysisState.Disabled -> "인물 기능이 꺼져 있어요. 얼굴을 분석하지 않고 얼굴 데이터도 보관하지 않아요."
    is FaceAnalysisState.FacesCatchingUp -> "얼굴을 분석하고 있어요 (${s.done} / ${s.total}장). 얼굴이 나온 사진이 분석되면 여기에 묶여요."
    is FaceAnalysisState.Analyzing ->
        if (s.total == null) "사진을 확인하고 있어요."
        else "사진을 분석하고 있어요 (${s.done} / ${s.total}장). 얼굴이 나온 사진이 분석되면 여기에 묶여요."

    FaceAnalysisState.NoFacesFound -> "사진에서 얼굴을 찾지 못했어요."
    FaceAnalysisState.Ready, null -> "사진 분석이 끝나면 얼굴별로 묶어서 보여드려요."
}

/** 인물 목록 위 진행 줄. 얼굴만 따로 분석 중일 때만 보인다. */
@Composable
private fun FaceProgressRow(done: Int, total: Int) {
    Column(Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            "얼굴 분석 $done / ${total}장",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else done / total.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }
}