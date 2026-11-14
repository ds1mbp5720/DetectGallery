package lee.project.detectgallery.feature.live

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gallerylens.domain.logic.toImagePoint
import app.gallerylens.domain.logic.toViewRect
import app.gallerylens.imaging.toBitmap
import app.gallerylens.domain.model.DetectionKind
import app.gallerylens.ui.PhotoGrid
import app.gallerylens.ui.mediaUri
import coil.compose.AsyncImage
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(vm: LiveViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            // NavHost 안에서는 LocalLifecycleOwner 가 백스택 항목이라, 화면을 떠나면 카메라는 이미 멈춘다.
            // 그래도 분석기가 묶인 채 executor 만 닫히지 않도록 명시적으로 푼다.
            // get() 은 초기화가 안 끝났으면 메인 스레드를 막으므로, 끝났을 때만 (안 끝났으면 묶인 것도 없다).
            val future = ProcessCameraProvider.getInstance(context)
            // 초기화가 실패한 future 도 isDone 이다 → get() 예외를 삼킨다
            if (future.isDone) runCatching { future.get().unbindAll() }
            executor.shutdown()
        }
    }

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) askCamera.launch(Manifest.permission.CAMERA) }
    var noCamera by remember { mutableStateOf(false) }
    if (noCamera) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("이 기기에서는 카메라를 쓸 수 없어 실시간 찾기를 할 수 없어요. 사진·키워드 검색은 쓸 수 있어요.", Modifier.padding(24.dp))
        }
        return
    }
    if (!granted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("카메라 권한을 허용하면 실시간으로 찾을 수 있어요. 사진·키워드 검색은 권한 없이 쓸 수 있어요.", Modifier.padding(24.dp))
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                    .also {
                        bindCamera(
                            ctx, owner, it, executor,
                            onNoCamera = { noCamera = true },
                            shouldAnalyze = { vm.state.value.selected == null },   // 결과 시트가 닫혀 있을 때만 분석
                            onFrame = vm::onIntent,
                        )
                    }
            },
            modifier = Modifier.fillMaxSize(),
        )
        DetectionOverlay(state) { x, y -> vm.onIntent(LiveIntent.Tap(x, y)) }

        // 박스가 안 뜨는 음식(한식 등)이 화면에 있으면 칩으로 검색할 수 있게 한다
        if (state.foodScene && state.detections.none { it.kind == DetectionKind.FOOD }) {
            AssistChip(
                onClick = { vm.onIntent(LiveIntent.SearchFoodScene) },
                label = { Text("음식이 보여요 · 비슷한 음식 사진 찾기") },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            )
        }

        LazyRow(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(state.preview, key = { it }) { id ->
                AsyncImage(mediaUri(id), contentDescription = "비슷한 사진", modifier = Modifier.size(64.dp))
            }
        }
    }

    if (state.selected != null) {
        ModalBottomSheet(onDismissRequest = { vm.onIntent(LiveIntent.DismissResults) }) {
            val title = when (state.selected?.kind) {
                DetectionKind.FACE, DetectionKind.PERSON -> "이 사람이 나온 사진"
                DetectionKind.FOOD -> "비슷한 음식 사진"
                else -> "비슷한 사진"
            }
            // 무엇으로 검색했는지: 탭할 때 잘라 둔 대상 + 제목. 카메라가 움직여도 결과의 기준이 보인다.
            val thumb = remember(state.queryCrop) { state.queryCrop?.toBitmap()?.asImageBitmap() }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                thumb?.let {
                    Image(
                        it, contentDescription = "검색한 대상", contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text("누른 순간의 화면으로 찾았어요", style = MaterialTheme.typography.bodySmall)
                }
            }
            when {
                state.isSearching -> LinearProgressIndicator(Modifier.fillMaxWidth())
                state.notice == LiveNotice.NO_FACE -> Text("얼굴을 찾지 못했어요. 조금 더 가까이 비춰 주세요.", Modifier.padding(16.dp))
                state.notice == LiveNotice.FACE_UNAVAILABLE -> Text("인물 찾기는 아직 준비 중이에요. 음식·물체 박스를 눌러 보세요.", Modifier.padding(16.dp))
                state.notice == LiveNotice.FACES_DISABLED -> Text("인물 기능이 꺼져 있어요. 인물 탭에서 켤 수 있어요.", Modifier.padding(16.dp))
                state.notice == LiveNotice.NO_RESULTS -> Text("갤러리에서 찾지 못했어요.", Modifier.padding(16.dp))
                state.notice == LiveNotice.FAILED -> Text("검색하지 못했어요. 다시 탭해 주세요.", Modifier.padding(16.dp))
                else -> PhotoGrid(state.results, Modifier.heightIn(max = 480.dp))
            }
        }
    }
}

@Composable
private fun DetectionOverlay(state: LiveUiState, onTap: (Float, Float) -> Unit) {
    val measurer = rememberTextMeasurer()
    val currentState by rememberUpdatedState(state)
    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { p ->
                val s = currentState
                if (s.frameW == 0) return@detectTapGestures
                toImagePoint(p.x, p.y, s.frameW, s.frameH, size.width.toFloat(), size.height.toFloat())
                    ?.let { (x, y) -> onTap(x, y) }
            }
        },
    ) {
        if (state.frameW == 0) return@Canvas
        state.detections.forEach { d ->
            val r = toViewRect(d.box, state.frameW, state.frameH, size.width, size.height)
            // FILL_CENTER 는 4:3 영상을 긴 화면에 맞추며 양옆을 자른다 → 박스가 화면 밖일 수 있다. 안 보이면 건너뛴다.
            if (r[2] <= 0f || r[0] >= size.width || r[3] <= 0f || r[1] >= size.height) return@forEach
            val color = when (d.kind) {
                DetectionKind.FACE -> Color(0xFF4FC3F7)
                DetectionKind.PERSON -> Color(0xFF81C784)
                DetectionKind.FOOD -> Color(0xFFFFB74D)
                DetectionKind.OBJECT -> Color.White
            }
            drawRect(color, Offset(r[0], r[1]), Size(r[2] - r[0], r[3] - r[1]), style = Stroke(3.dp.toPx()))
            val name = when (d.kind) {
                DetectionKind.FACE -> "얼굴"
                DetectionKind.PERSON -> "사람"
                DetectionKind.FOOD -> "음식 · ${d.label}"
                DetectionKind.OBJECT -> d.label
            }
            // 글자 위치를 화면 안으로 당기고 크기를 명시한다. 크기를 주지 않으면 "화면 크기 − 시작점" 을 쓰는데,
            // 시작점이 화면 밖이면 음수가 되어 예외가 난다 (Compose 버전에 따라).
            val tx = (r[0] + 6f).coerceIn(0f, size.width - 1f)
            val ty = (r[1] + 4f).coerceIn(0f, size.height - 1f)
            drawText(
                measurer, name, Offset(tx, ty), TextStyle(color = color, fontSize = 13.sp),
                maxLines = 1, size = Size(size.width - tx, size.height - ty),
            )
        }
    }
}
// 실제 앱에서는 화면 문구를 strings.xml 로 옮긴다.