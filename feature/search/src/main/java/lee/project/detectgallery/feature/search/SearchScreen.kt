package lee.project.detectgallery.feature.search

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lee.project.detectgallery.core.domain.model.DetectedFace
import lee.project.detectgallery.core.domain.model.ImageSearchMode
import lee.project.detectgallery.core.domain.model.IndexProgress
import lee.project.detectgallery.core.domain.model.Sensitivity
import lee.project.detectgallery.core.ui.FaceThumb
import lee.project.detectgallery.core.ui.PhotoGrid
import lee.project.detectgallery.core.ui.components.ChipRow
import lee.project.detectgallery.core.ui.components.SegmentedTabs
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface

private val QUICK = listOf("음식", "바다", "하늘", "웃는", "단체", "셀카", "흔들린")
private val IMAGE_MODES = listOf(
    ImageSearchMode.PERSON to "인물",
    ImageSearchMode.SCENE to "배경·장면",
    ImageSearchMode.DUPLICATE to "겹치는 사진",
)

@OptIn(ExperimentalMaterial3Api::class)   // ModalBottomSheet
@Composable
fun SearchScreen(vm: SearchViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        uri?.let { vm.onIntent(SearchIntent.QueryImage(it.toString())) }
    }

    val focus = LocalFocusManager.current   // 칩으로 검색해도 키보드를 내린다 (검색 키는 SearchField 가 내린다)
    BackHandler(enabled = state.queryUri != null) {
        vm.onIntent(SearchIntent.ClearImage)
    } // 뒤로 = 사진으로 찾기 끝내기
    Column(Modifier.fillMaxSize()) {
        SearchField(
            value = state.query,
            onValueChange = { vm.onIntent(SearchIntent.QueryChanged(it)) },
            onSearch = { vm.onIntent(SearchIntent.SubmitKeyword) },
            onPickPhoto = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
            onTune = { vm.onIntent(SearchIntent.ShowSensitivity(true)) },
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp),
        )
        // 한 줄 고정. 넘치는 칩은 가로로 밀어서 본다. 바깥 Column 에 가로 여백을 주지 않아야 화면 끝까지 밀린다.
        ChipRow(
            items = QUICK,
            onClick = { focus.clearFocus(); vm.onIntent(SearchIntent.QuickKeyword(it)) },
            // 검색창 글자와 같은 칩을 강조 (직접 쳐도, 칩을 눌러도 같다). 사진으로 찾는 중에는 강조하지 않는다: 결과가 키워드 결과처럼 보이지 않게
            selected = state.query.trim().takeIf { state.queryUri == null && it in QUICK },
            modifier = Modifier.padding(top = 8.dp),
        )
        // 사진으로 찾는 중이면 고른 사진 카드 안에 비교 기준(인물·배경·겹치는 사진)을 둔다 (C8).
        // 기준은 사진 검색에만 쓰이므로 키워드 검색 때는 보이지 않는다 — 늘 보이면 키워드에도 적용되는 것처럼 읽힌다
        state.queryUri?.let { uri ->
            QueryImageCard(
                uri = uri,
                mode = state.imageMode,
                onMode = { vm.onIntent(SearchIntent.ImageModeChanged(it)) },
                onClose = { vm.onIntent(SearchIntent.ClearImage) },
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp),
            )
        }

        // 색인 진행률은 모든 탭 위의 현황 줄(LensStatusBar)이 보여 준다.
        if (state.isSearching) LinearProgressIndicator(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        )
        when (state.notice) {
            SearchNotice.NO_RESULTS -> Notice("맞는 사진이 없어요.")
            SearchNotice.NO_FACE_IN_QUERY -> Notice("고른 사진에서 얼굴을 찾지 못했어요. 배경·장면으로 바꿔 보세요.")
            SearchNotice.FACE_UNAVAILABLE -> Notice("인물 찾기는 아직 준비 중이에요. 배경·장면이나 겹치는 사진으로 찾아 보세요.")
            SearchNotice.FACES_DISABLED -> Notice("인물 기능이 꺼져 있어요. 아래 인물 탭에서 켜거나 배경·장면으로 찾아 보세요.")
            SearchNotice.UNREADABLE -> Notice("사진을 열지 못했어요. 다른 사진을 골라 주세요.")
            SearchNotice.FAILED -> Notice("검색하지 못했어요. 다시 시도해 주세요.")
            null -> when {
                state.results.isNotEmpty() || state.isSearching -> PhotoGrid(
                    state.results,
                    Modifier.weight(1f)
                )
                // 사진으로 찾는 중인데 결과도 안내도 없다 = 얼굴 고르기를 취소했다 (다른 경우는 안내가 있다)
                state.queryUri != null -> Notice("찾을 사람을 고르지 않았어요. 인물을 다시 누르면 고를 수 있어요.")
                else -> SearchGuide(state.indexProgress, Modifier.weight(1f))
            }
        }
    }

    if (state.showSensitivity) {
        ModalBottomSheet(onDismissRequest = { vm.onIntent(SearchIntent.ShowSensitivity(false)) }) {
            SensitivitySheet(state.sensitivity) { vm.onIntent(SearchIntent.SensitivityChanged(it)) }
        }
    }

    val choices = state.faceChoices
    val queryUri = state.queryUri
    if (choices != null && queryUri != null) {
        FaceChoiceDialog(
            Uri.parse(queryUri),
            choices,
            onPick = { vm.onIntent(SearchIntent.FaceChosen(it)) }) {
            vm.onIntent(SearchIntent.CancelFaceChoice)
        }
    }
}

/** 테두리 없는 회색 채움 검색창. 오른쪽 아이콘 두 개: 사진으로 찾기·검색 민감도. */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPickPhoto: () -> Unit,
    onTune: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        // singleLine 은 입력 글자에만 적용된다. 안내 문구도 한 줄로 막아야 큰 글꼴에서 검색창이 커지지 않는다.
        placeholder = { Text("음식, 바다, 엄마, 작년 3월", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            Row {
                IconButton(onClick = onPickPhoto) {
                    Icon(
                        Icons.Outlined.AddPhotoAlternate,
                        contentDescription = "사진으로 찾기"
                    )
                }
                IconButton(onClick = onTune) {
                    Icon(
                        Icons.Outlined.Tune,
                        contentDescription = "사진·카메라 검색 민감도"
                    )
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.surfaceVariant,
            unfocusedContainerColor = colors.surfaceVariant,
            focusedIndicatorColor = Color.Transparent,      // 밑줄 없음
            unfocusedIndicatorColor = Color.Transparent,
            unfocusedLeadingIconColor = colors.onSurfaceVariant,
            unfocusedTrailingIconColor = colors.onSurfaceVariant,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // 검색하면 키보드를 내린다: 앱이 화면 끝까지 그려(edge-to-edge) 키보드가 결과 그리드 아래쪽을 가린다
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onSearch() }),
    )
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 아직 검색하지 않았을 때의 안내. 첫 실행에는 색인이 수십 분 걸려 결과가 적으므로 그 이유를 먼저 알린다.
 * (상단 진행률 바만으로는 "왜 사진이 안 나오는지" 를 알기 어렵다)
 */
@Composable
private fun SearchGuide(progress: IndexProgress?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (progress != null) {
            Text("갤러리 사진을 분석하고 있어요", style = MaterialTheme.typography.titleMedium)
            Text(
                "처음 한 번은 사진이 많을수록 오래 걸려요 (1만 장이면 수십 분). 앱을 닫아도 이어서 하고, " +
                        "분석된 사진부터 바로 검색할 수 있어요. 지금 ${progress.done} / ${progress.total}",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text("이렇게 찾아 보세요", style = MaterialTheme.typography.titleMedium)
            Text("• 위의 칩을 누르거나 \"엄마 작년 바다\" 처럼 검색해요", style = MaterialTheme.typography.bodyMedium)
        }
        // 사진으로 찾기는 분석 중에도 알린다: 기준(인물·배경·겹치는 사진)은 사진을 고른 뒤에야 보인다 (C8)
        Text(
            "• 검색창 오른쪽 사진 버튼으로 사진을 고르면 비슷한 사진을 찾아요. 고른 사진 아래에서 인물·배경·겹치는 사진 중 기준을 바꿀 수 있어요",
            style = MaterialTheme.typography.bodyMedium
        )
        if (progress == null) Text(
            "• 카메라 탭에서 음식·사람을 비추고 박스를 눌러요",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * 사진으로 찾는 중일 때의 카드 (C8): 고른 사진 + 무엇을 찾는지 + 비교 기준 + 끝내기(✕).
 * 기준을 바꾸면 같은 사진으로 바로 다시 찾는다(사진을 다시 고르지 않아도 된다).
 */
@Composable
private fun QueryImageCard(
    uri: String,
    mode: ImageSearchMode,
    onMode: (ImageSearchMode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 카드는 흰 바탕 + 테두리: 회색 바탕이면 같은 회색인 기준 탭의 트랙 경계가 사라진다
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = null,   // 옆 설명 글자가 읽힌다 (같은 말을 두 번 읽지 않게)
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(MaterialTheme.shapes.small),
                )
                Text(
                    when (mode) {
                        ImageSearchMode.PERSON -> "이 사진 속 사람이 나온 사진"
                        ImageSearchMode.SCENE -> "이 사진과 배경·장면이 비슷한 사진"
                        ImageSearchMode.DUPLICATE -> "이 사진과 겹치는 사진"
                    },
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "사진으로 찾기 끝내기"
                    )
                }
            }
            SegmentedTabs(options = IMAGE_MODES, selected = mode, onSelect = onMode)
        }
    }
}


/** 쿼리 사진에 얼굴이 여럿일 때 찾을 사람을 고른다. 왼쪽부터 (사진 속 위치 순). */
@Composable
private fun FaceChoiceDialog(
    uri: Uri,
    faces: List<DetectedFace>,
    onPick: (DetectedFace) -> Unit,
    onDismiss: () -> Unit
) {
    val sorted = remember(faces) { faces.sortedBy { it.box.left } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("누구를 찾을까요?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "사진에 얼굴이 ${sorted.size}개 있어요. 찾을 사람을 누르세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(sorted) { i, f ->
                        FaceThumb(
                            uri, f.box.left, f.box.top, f.box.right, f.box.bottom,
                            // 둥글게 자른 뒤 누르게 해 누름 표시(물결)도 원 안에만 보인다
                            Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .clickable(onClickLabel = "이 사람으로 찾기", role = Role.Button) {
                                    onPick(
                                        f
                                    )
                                },
                            contentDescription = "얼굴 ${i + 1}/${sorted.size}, 왼쪽부터",
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/**
 * 민감도 슬라이더 2개. 가운데(0)가 개발자가 보정한 기준이고, 양 끝이 그 기준 ±0.1 이다.
 * 5칸(steps = 3)으로 끊어 "조금/많이" 정도만 고르게 한다. 숫자 임계값은 보여주지 않는다.
 */
@Composable
private fun SensitivitySheet(value: Sensitivity, onChange: (Sensitivity) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
    ) {
        Text("사진·카메라 검색 민감도", style = MaterialTheme.typography.titleMedium)
        Text(
            "사진으로 찾기와 카메라 탭에 쓰여요(키워드 검색에는 쓰이지 않아요). 결과에 다른 사진이 섞이면 엄격하게, 찾는 사진이 빠지면 느슨하게 옮겨 보세요.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        SensitivityRow("같은 사람 찾기", value.person) { onChange(value.copy(person = it)) }
        SensitivityRow("비슷한 배경·장면 찾기", value.scene) { onChange(value.copy(scene = it)) }
        TextButton(
            onClick = { onChange(Sensitivity()) },
            modifier = Modifier.align(Alignment.End)
        ) { Text("기본값으로") }
        Text(
            "인물 목록의 묶음은 바뀌지 않아요. 같은 사람이 둘로 나뉘었으면 인물 화면에서 합쳐 주세요.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SensitivityRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = -1f..1f,
        steps = 3,
        colors = SliderDefaults.colors(
            inactiveTrackColor = MaterialTheme.colorScheme.outline,          // 시트 바탕 위에서도 보이게
            inactiveTickColor = MaterialTheme.colorScheme.surface,
        ),
    )
    Row(Modifier.fillMaxWidth()) {
        Text("엄격하게", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.weight(1f))
        Text("느슨하게", style = MaterialTheme.typography.labelSmall)
    }
    Spacer(Modifier.height(12.dp))
}