package lee.project.detectgallery.feature.search

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import  lee.project.detectgallery.core.ui.components.ChipRow
import  lee.project.detectgallery.core.ui.components.SegmentedTabs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lee.project.detectgallery.core.domain.model.DetectedFace
import lee.project.detectgallery.core.domain.model.ImageSearchMode
import lee.project.detectgallery.core.domain.model.IndexProgress
import lee.project.detectgallery.core.domain.model.Sensitivity
import lee.project.detectgallery.core.ui.FaceThumb
import lee.project.detectgallery.core.ui.PhotoGrid

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
            onClick = { vm.onIntent(SearchIntent.QuickKeyword(it)) },
            selected = state.query.trim()
                .takeIf { it in QUICK },   // 검색창 글자와 같은 칩을 강조 (직접 쳐도, 칩을 눌러도 같다)
            modifier = Modifier.padding(top = 8.dp),
        )
        // 사진 한 장으로 찾을 때의 기준 (검색창 오른쪽 사진 버튼). 설명 줄은 두지 않는다: 세로 공간을 아끼고,
        // 안내는 검색 전 SearchGuide 가 한다.
        SegmentedTabs(
            options = IMAGE_MODES,
            selected = state.imageMode,
            onSelect = { vm.onIntent(SearchIntent.ImageModeChanged(it)) },
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp),
        )

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
            SearchNotice.FACES_DISABLED -> Notice("인물 기능이 꺼져 있어요. 인물 탭에서 켜거나 배경·장면으로 찾아 보세요.")
            SearchNotice.UNREADABLE -> Notice("사진을 열지 못했어요. 다른 사진을 골라 주세요.")
            SearchNotice.FAILED -> Notice("검색하지 못했어요. 다시 시도해 주세요.")
            null ->
                if (state.results.isEmpty() && !state.isSearching) SearchGuide(
                    state.indexProgress,
                    Modifier.weight(1f)
                )
                else PhotoGrid(state.results, Modifier.weight(1f))
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
                        contentDescription = "검색 민감도"
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
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
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
            Text(
                "• 검색창 오른쪽 사진 버튼으로 사진을 고르면, 위 '인물 / 배경·장면 / 겹치는 사진' 중 고른 기준으로 찾아요",
                style = MaterialTheme.typography.bodyMedium
            )
            Text("• 카메라 탭에서 음식·사람을 비추고 박스를 눌러요", style = MaterialTheme.typography.bodyMedium)
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("누구를 찾을까요?") },
        text = {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(faces.sortedBy { it.box.left }) { f ->
                    FaceThumb(
                        uri, f.box.left, f.box.top, f.box.right, f.box.bottom,
                        Modifier
                            .size(72.dp)
                            .clickable { onPick(f) },
                    )
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
    Column(Modifier
        .fillMaxWidth()
        .padding(horizontal = 24.dp)
        .padding(bottom = 32.dp)) {
        Text("검색 민감도", style = MaterialTheme.typography.titleMedium)
        Text(
            "결과에 다른 사진이 섞이면 엄격하게, 찾는 사진이 빠지면 느슨하게 옮겨 보세요.",
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