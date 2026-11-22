package lee.project.detectgallery.feature.live

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import lee.project.detectgallery.core.domain.logic.toImagePoint
import lee.project.detectgallery.core.domain.logic.toViewRect
import lee.project.detectgallery.core.domain.model.DetectionKind
import lee.project.detectgallery.core.domain.model.LiveDetection
import lee.project.detectgallery.core.imaging.toBitmap
import lee.project.detectgallery.core.ui.PhotoGrid
import lee.project.detectgallery.core.ui.components.NoticeCard
import lee.project.detectgallery.core.ui.mediaUri
import lee.project.detectgallery.core.ui.openInGallery
import java.util.concurrent.Executors
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material3.Icon
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable

/** 카메라 위 박스·라벨 색. 라벨은 이 색 바탕에 LABEL_INK 글자라 화면 밝기와 무관하게 읽힌다. */
private val FACE_COLOR = Color(0xFF4C9AFF)
private val PERSON_COLOR = Color(0xFF3DD68C)
private val FOOD_COLOR = Color(0xFFFFB020)
private val OBJECT_COLOR = Color.White
private val LABEL_INK = Color(0xFF101013)
private val SCRIM = Color.Black.copy(alpha = 0.55f)

/** 카메라 위 흰 글자 그림자. 밝은 장면에서 그라데이션만으로 모자란 대비를 보탠다. */
private val TEXT_SHADOW = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), blurRadius = 4f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(vm: LiveViewModel = hiltViewModel()) {
    // ponytail: state 를 통째로 읽어 검출 결과마다(15~30fps) 화면 전체가 다시 그려진다.
    // 끊김이 보이면 detections 만 DetectionOverlay 안에서(그리기 단계) 읽게 나눈다.
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val bound = remember { mutableListOf<UseCase>() }   // 이 화면이 묶은 프리뷰·분석 (bindCamera 가 채운다)
    DisposableEffect(Unit) {
        onDispose {
            // NavHost 안에서는 LocalLifecycleOwner 가 백스택 항목이라, 화면을 떠나면 카메라는 이미 멈춘다.
            // 그래도 분석기가 묶인 채 executor 만 닫히지 않도록 명시적으로 푼다.
            // get() 은 초기화가 안 끝났으면 메인 스레드를 막으므로, 끝났을 때만 (안 끝났으면 묶인 것도 없다).
            val future = ProcessCameraProvider.getInstance(context)
            // 초기화가 실패한 future 도 isDone 이다 → get() 예외를 삼킨다
            if (future.isDone) runCatching { future.get().unbind(*bound.toTypedArray()) }
            bound.filterIsInstance<ImageAnalysis>()
                .forEach { it.clearAnalyzer() }   // 닫힌 executor 로 프레임이 가지 않게
            bound.clear()
            executor.shutdown()
        }
    }

    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    // 두 번 거부하면 시스템이 더 묻지 않는다(창 없이 바로 거부). 이 상태를 shouldShowRequestPermissionRationale 로 가르면
    // 첫 요청 창을 뒤로 가기로 닫은 경우도 같은 값이라 허용 버튼이 사라진다 → 두 버튼을 늘 두고 안내 문구로 알린다.
    val askCamera =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            granted = ok
        }
    LaunchedEffect(Unit) { if (!granted) askCamera.launch(Manifest.permission.CAMERA) }
    // 설정 앱에서 권한을 켜고 돌아오면 다시 확인한다 (예전에는 앱을 다시 켜야 했다)
    LifecycleResumeEffect(Unit) {
        granted = hasCameraPermission(context)
        onPauseOrDispose { }
    }
    var noCamera by remember { mutableStateOf(false) }
    if (noCamera) {
        NoticeCard(
            title = "카메라를 쓸 수 없어요",
            body = "이 기기에서는 실시간 찾기를 할 수 없어요. 사진·키워드 검색은 그대로 쓸 수 있어요.",
        )
        return
    }
    if (!granted) {
        NoticeCard(
            title = "카메라로 찾기",
            body = "카메라를 비추면 얼굴·음식·물체에 박스가 뜨고, 누르면 갤러리에서 같은 사람이나 비슷한 사진을 찾아요. " +
                    "사진·키워드 검색은 권한 없이도 쓸 수 있어요. 허용 창이 뜨지 않으면 설정 열기에서 카메라를 허용해 주세요.",
        ) {
            TextButton(onClick = { openAppSettings(context) }) { Text("설정 열기") }
            Button(onClick = { askCamera.launch(Manifest.permission.CAMERA) }) { Text("카메라 허용") }
        }
        return
    }
// 셀카 모드(전면 카메라, C4). 회전해도 유지. 바꾸면 key 로 프리뷰를 새로 만들어 다시 묶는다(bindCamera 가 옛 것을 푼다)
    var front by rememberSaveable { mutableStateOf(false) }
    var hasFront by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        key(front) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                        .also {
                            bindCamera(
                                ctx, owner, it, executor, bound,
                                front = front,
                                onHasFront = { hasFront = it },
                                onNoCamera = { noCamera = true },
                                shouldAnalyze = { vm.state.value.selected == null },   // 결과 시트가 닫혀 있을 때만 분석
                                onFrame = vm::onIntent,
                            )
                        }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        DetectionOverlay(state) { x, y -> vm.onIntent(LiveIntent.Tap(x, y)) }

        // 전면·후면 전환. 전면 카메라가 있는 기기에서만. 셀카로 내 얼굴을 비추고 눌러 갤러리에서 나를 찾는다
        if (hasFront) {
            Surface(
                onClick = { front = !front; vm.onIntent(LiveIntent.CameraSwitched) },
                shape = CircleShape,
                color = SCRIM,
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(48.dp),   // 48dp = 최소 터치 영역
            ) {
                Icon(
                    Icons.Outlined.Cameraswitch,
                    contentDescription = if (front) "후면 카메라로 바꾸기" else "셀카 모드로 바꾸기",
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // 박스가 안 뜨는 음식(한식 등)이 화면에 있으면 칩으로 검색할 수 있게 한다
        if (state.foodScene && state.detections.none { it.kind == DetectionKind.FOOD }) {
            Surface(
                onClick = { vm.onIntent(LiveIntent.SearchFoodScene) },
                shape = MaterialTheme.shapes.small,
                color = SCRIM,
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
            ) {
                Text(
                    "음식이 보여요 · 비슷한 음식 찾기",
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        // 아래쪽: 투명 → 검정 그라데이션 위에 안내 한 줄과 "지금 화면과 비슷한 사진" 띠 (높이 약 136~160dp).
        // 그라데이션·글자 부분은 터치를 받지 않아 뒤의 박스가 눌린다. 썸네일 줄(64dp, 사이 여백·좌우 여백 포함)은
        // LazyRow 가 터치를 받아 그 높이의 박스는 누를 수 없다(원본도 같다).
        val showHint = state.detections.isNotEmpty()
        if (showHint || state.preview.isNotEmpty()) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // 위쪽 20%(= 위 여백 32dp 근처)에서 이미 55% 까지 어두워진다 → 글자는 항상 다 깔린 어둠 위에 놓인다.
                    // 밝은 흰 벽에서도 흰 글자 대비 약 4.7:1 + 그림자. 선형으로 끝까지 깔면 글자 자리가 14~23% 라 1.3~1.7:1 이었다.
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.2f to SCRIM,
                            1f to SCRIM
                        )
                    )
                    .padding(top = 32.dp, bottom = 12.dp),
            ) {
                // 미리보기 띠가 있으면 안내 줄 자리를 고정하고 투명도만 바꾼다: 박스가 프레임마다 생겼다 사라져도 띠가 들썩이지 않게
                Text(
                    "박스를 누르면 갤러리에서 찾아요",
                    Modifier
                        .padding(start = 16.dp, bottom = 8.dp)
                        .alpha(if (showHint) 1f else 0f),
                    style = MaterialTheme.typography.labelMedium.copy(shadow = TEXT_SHADOW),
                    color = Color.White.copy(alpha = 0.9f),
                )
                if (state.preview.isNotEmpty()) {
                    Text(
                        "지금 화면과 비슷한 사진",
                        Modifier.padding(start = 16.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.labelLarge.copy(shadow = TEXT_SHADOW),
                        color = Color.White,
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.preview, key = { it }) { id ->
                            AsyncImage(
                                model = mediaUri(id),
                                contentDescription = "비슷한 사진",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .border(
                                        1.5.dp,
                                        Color.White.copy(alpha = 0.8f),
                                        MaterialTheme.shapes.small
                                    )
                                    .clickable(onClickLabel = "갤러리에서 열기") {
                                        openInGallery(
                                            context,
                                            id
                                        )
                                    },
                            )
                        }
                    }
                }
            }
        }
    }

    val selected = state.selected
    if (selected != null) {
        ModalBottomSheet(
            onDismissRequest = { vm.onIntent(LiveIntent.DismissResults) },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            ResultHeader(state, selected)
            when {
                state.isSearching -> LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )

                state.notice != null -> Text(
                    noticeText(state.notice!!),
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> PhotoGrid(state.results, Modifier.heightIn(max = 480.dp))
            }
        }
    }
}

/** 시트 머리: 탭할 때 잘라 둔 대상 + 제목 + 장 수. 카메라가 움직여도 무엇으로 찾았는지 보인다. */
@Composable
private fun ResultHeader(state: LiveUiState, selected: LiveDetection) {
    val title = when (selected.kind) {
        DetectionKind.FACE, DetectionKind.PERSON -> "이 사람이 나온 사진"
        DetectionKind.FOOD -> "비슷한 음식 사진"
        DetectionKind.OBJECT -> "비슷한 사진"
    }
    val subtitle = when {
        state.isSearching -> "찾는 중이에요…"
        state.results.isNotEmpty() -> "누른 순간의 화면으로 찾았어요 · ${state.results.size}장"
        else -> "누른 순간의 화면으로 찾았어요"
    }
    val thumb = remember(state.queryCrop) { state.queryCrop?.toBitmap()?.asImageBitmap() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        thumb?.let {
            Image(
                it, contentDescription = "검색한 대상", contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(MaterialTheme.shapes.small),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun noticeText(notice: LiveNotice): String = when (notice) {
    LiveNotice.NO_FACE -> "얼굴을 찾지 못했어요. 조금 더 가까이 비춰 주세요."
    LiveNotice.FACE_UNAVAILABLE -> "인물 찾기는 아직 준비 중이에요. 음식·물체 박스를 눌러 보세요."
    LiveNotice.FACES_DISABLED -> "인물 기능이 꺼져 있어요. 아래 인물 탭에서 켤 수 있어요."
    LiveNotice.NO_RESULTS -> "갤러리에서 찾지 못했어요."
    LiveNotice.FAILED -> "검색하지 못했어요. 다시 탭해 주세요."
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Composable
private fun DetectionOverlay(state: LiveUiState, onTap: (Float, Float) -> Unit) {
    val measurer = rememberTextMeasurer()
    val currentState by rememberUpdatedState(state)
    val labelStyle =
        TextStyle(color = LABEL_INK, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { p ->
                    val s = currentState
                    if (s.frameW == 0) return@detectTapGestures
                    toImagePoint(
                        p.x,
                        p.y,
                        s.frameW,
                        s.frameH,
                        size.width.toFloat(),
                        size.height.toFloat()
                    )
                        ?.let { (x, y) -> onTap(x, y) }
                }
            }
            .semantics {
                // TalkBack 은 캔버스 안 박스를 누를 수 없다 → 박스마다 "… 찾기" 동작으로 낸다 (박스 가운데를 누른 것과 같다).
                // 박스와 Tap 은 같은 분석 이미지 좌표다. 사람 박스 가운데에 얼굴이 있으면 pick 이 얼굴을 고른다 (같은 사람 검색).
                contentDescription =
                    if (state.detections.isEmpty()) "카메라 화면" else "카메라 화면, 박스 ${state.detections.size}개"
                customActions = state.detections.map { d ->
                    CustomAccessibilityAction("${detectionName(d)} 찾기") {
                        onTap((d.box.left + d.box.right) / 2, (d.box.top + d.box.bottom) / 2)
                        true
                    }
                }
            },
    ) {
        if (state.frameW == 0) return@Canvas
        val corner = CornerRadius(12.dp.toPx())
        val padH = 8.dp.toPx()
        val padV = 3.dp.toPx()
        val gap = 4.dp.toPx()
        state.detections.forEach { d ->
            val r = toViewRect(d.box, state.frameW, state.frameH, size.width, size.height)
            // FILL_CENTER 는 4:3 영상을 긴 화면에 맞추며 양옆을 자른다 → 박스가 화면 밖일 수 있다. 안 보이면 건너뛴다.
            if (r[2] <= 0f || r[0] >= size.width || r[3] <= 0f || r[1] >= size.height) return@forEach
            val color = when (d.kind) {
                DetectionKind.FACE -> FACE_COLOR
                DetectionKind.PERSON -> PERSON_COLOR
                DetectionKind.FOOD -> FOOD_COLOR
                DetectionKind.OBJECT -> OBJECT_COLOR
            }
            drawRoundRect(
                color,
                Offset(r[0], r[1]),
                Size(r[2] - r[0], r[3] - r[1]),
                corner,
                style = Stroke(2.5.dp.toPx())
            )

            val name = detectionName(d)
            // 라벨 알약: 박스 바로 위. 위에 자리가 없으면 박스 안쪽 위. 화면 밖으로 나가지 않게 당긴다.
            // 글자를 먼저 재서(크기 제한 없음) 알약 크기를 정한다 → 예전처럼 남은 공간이 음수가 될 일이 없다.
            val text = measurer.measure(name, labelStyle, maxLines = 1)
            val pillW = text.size.width + padH * 2
            val pillH = text.size.height + padV * 2
            val x = r[0].coerceIn(0f, (size.width - pillW).coerceAtLeast(0f))
            val above = r[1] - pillH - gap
            val y = (if (above >= 0f) above else r[1] + gap).coerceIn(
                0f,
                (size.height - pillH).coerceAtLeast(0f)
            )
            drawRoundRect(color, Offset(x, y), Size(pillW, pillH), CornerRadius(pillH / 2))
            drawText(text, topLeft = Offset(x + padH, y + padV))
        }
    }
}

// ponytail: OBJECT 는 COCO 영문 라벨 그대로다(예: "cup 찾기"). 한글이 필요하면 kindOf 옆에 라벨 사전을 둔다.
/** 박스 라벨과 TalkBack 동작 이름 (같은 말을 쓴다). */
private fun detectionName(d: LiveDetection): String = when (d.kind) {
    DetectionKind.FACE -> "얼굴"
    DetectionKind.PERSON -> "사람"
    DetectionKind.FOOD -> "음식 · ${d.label}"
    DetectionKind.OBJECT -> d.label
}
// 실제 앱에서는 화면 문구를 strings.xml 로 옮긴다.