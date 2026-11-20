package lee.project.detectgallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lee.project.detectgallery.core.ui.mediaUri
import coil.compose.AsyncImage
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * 분석할 앨범 고르기. 고른 앨범의 사진만 분석해 검색·카메라·인물에 쓴다.
 * 빠진 앨범의 분석 결과는 다음 색인이 지우고(이름 붙은 인물은 사진 없는 이름 카드로 남음), 다시 넣으면 다시 분석한다.
 * vm 은 AppNavHost 가 넘긴다(시트를 열 때 Load 를 먼저 보낸다).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumSheet(vm: AlbumPickerViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.onIntent(AlbumPickerIntent.Load(fresh = false)) }   // 프로세스 복원 뒤 다시 열린 경우만 읽는다
    val colors = MaterialTheme.colorScheme
    // 앨범이 많아도 처음부터 다 펼친다: 반만 열리면 아래 "저장" 이 화면 밖에 있다
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // 버튼으로 닫을 때도 내려가는 애니메이션 뒤에 빼낸다 (onDismiss 를 바로 부르면 시트가 툭 사라진다)
    val close: () -> Unit =
        { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Text("분석할 앨범", style = MaterialTheme.typography.titleMedium)
            Text(
                "고른 앨범의 사진만 분석하고 검색해요. 앨범을 빼면 그 사진의 분석 결과를 지우고, 다시 넣으면 다시 분석해요. " +
                        "폴더 이름을 바꾼 앨범은 다른 앨범으로 보여 다시 골라야 해요.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            if (state.lost) {
                Text(
                    "고른 앨범이 없어졌어요. 분석할 앨범을 다시 골라 주세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = state.selected == null, role = Role.Switch) {
                        vm.onIntent(
                            AlbumPickerIntent.SetAll(it)
                        )
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("모든 앨범", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = state.selected == null,
                    onCheckedChange = null
                )   // 줄 전체가 토글 (TalkBack 은 줄 하나로 읽음)
            }
        }
        val albums = state.albums
        when {
            albums == null -> Box(
                Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            state.failed -> Text(
                "앨범을 불러오지 못했어요. 잠시 뒤 다시 열어 주세요.",
                Modifier.padding(24.dp),
                color = colors.error
            )

            albums.isEmpty() -> Text(
                "볼 수 있는 사진이 없어요.",
                Modifier.padding(24.dp),
                color = colors.onSurfaceVariant
            )

            else -> LazyColumn(Modifier.weight(1f, fill = false)) {   // 앨범이 많아도 아래 버튼이 화면 안에 남게
                items(albums, key = { it.id }) { a ->
                    val checked = state.selected?.contains(a.id) ?: true
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = checked, role = Role.Checkbox) {
                                vm.onIntent(
                                    AlbumPickerIntent.Toggle(a.id)
                                )
                            }
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AsyncImage(
                            model = mediaUri(a.coverMediaId),
                            contentDescription = null,   // 장식. 앨범 이름이 읽힌다
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(MaterialTheme.shapes.small),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                a.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${a.count}장",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        Checkbox(checked = checked, onCheckedChange = null)
                    }
                }
            }
        }
        val nothing = state.selected?.isEmpty() == true
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (nothing && !state.lost) {
                Text(
                    "앨범을 하나 이상 고르세요",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
            }
            TextButton(onClick = close) { Text("취소") }
            Button(
                onClick = { vm.onIntent(AlbumPickerIntent.Save); close() },   // 저장은 Activity 범위 VM 에서 끝까지 돈다
                enabled = albums != null && !state.failed && !nothing,
            ) { Text("저장") }
        }
    }
}
// 실제 앱에서는 화면 문구를 strings.xml 로 옮긴다.