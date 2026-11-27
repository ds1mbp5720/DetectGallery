package lee.project.detectgallery.feature.moment

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lee.project.detectgallery.core.domain.logic.Moment
import lee.project.detectgallery.core.ui.PhotoGrid
import lee.project.detectgallery.core.ui.components.NoticeCard
import lee.project.detectgallery.core.ui.mediaUri
import lee.project.detectgallery.core.ui.openInGallery
import coil.compose.AsyncImage

/** 추억 탭 (C12). 시간·장소로 묶은 사진 묶음을 최신순 카드로. 누르면 상세. */
@Composable
fun MomentsScreen(onOpen: (Long) -> Unit, vm: MomentsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.onIntent(MomentsIntent.Resumed)
        onPauseOrDispose { }
    }
    val moments = state.moments
    val progress = state.indexProgress
    when {
        state.failed && moments == null -> NoticeCard("추억을 만들지 못했어요", "잠시 뒤 다시 시도해 주세요.") {
            TextButton(onClick = { vm.onIntent(MomentsIntent.Retry) }) { Text("다시 시도") }
        }
        moments == null -> Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("사진을 추억으로 묶는 중이에요", style = MaterialTheme.typography.bodyMedium)
        }
        moments.isEmpty() -> NoticeCard(
            "아직 추억이 없어요",
            if (progress != null) "분석이 끝난 사진부터 추억으로 묶어요. 지금 ${progress.done} / ${progress.total}"
            else "같은 날 여러 장(8장 이상) 찍은 사진이 모이면 추억이 생겨요.",
        )
        else -> LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.failed) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Hint("추억을 다시 묶지 못했어요.", Modifier.weight(1f))
                    TextButton(onClick = { vm.onIntent(MomentsIntent.Retry) }) { Text("다시 시도") }
                }
            }
            // 위치가 없는 이유를 갈라 알린다: 앱 권한이 없는가, 카메라가 위치를 안 남기는가.
            // 권한이 없어도 전에 읽어 둔 위치는 쓰인다. 분석 중엔 위치를 아직 다 못 읽었을 수 있어 "위치 태그" 안내를 미룬다
            if (!state.locationAllowed) item {
                Hint(if (state.located == 0) "사진 위치 접근이 허용되지 않아 장소 없이 시간으로만 묶었어요."
                else "사진 위치 접근이 꺼져 있어 새 사진은 장소 없이 묶여요.")
            }
            else if (progress == null && state.total > 0 && state.located == 0) item { Hint("카메라 설정에서 위치 태그를 켜면 장소별로 묶이고 이름이 붙어요.") }
            if (progress != null) item { Hint("분석 중이라 분석된 사진만 묶었어요 (${progress.done} / ${progress.total}).") }
            items(moments, key = { it.key }) { m -> MomentCard(m, onClick = { onOpen(m.key) }) }
        }
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 표지(16:9) + 제목·부제 + 대표 사진 4장. 카드 전체가 버튼이라 TalkBack 이 제목·부제를 한 번에 읽는다. */
@Composable
private fun MomentCard(m: Moment, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column {
            AsyncImage(
                model = mediaUri(m.highlights.firstOrNull() ?: m.key),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(m.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            val rest = m.highlights.drop(1).take(4)
            if (rest.isNotEmpty()) {
                Row(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rest.forEach { id ->
                        AsyncImage(
                            model = mediaUri(id),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).aspectRatio(1f).clip(MaterialTheme.shapes.small),
                        )
                    }
                    repeat(4 - rest.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * 추억 상세: 부제·대표 사진 줄·전체 사진 그리드. 사진을 누르면 기본 갤러리 앱으로.
 * 머리(부제·대표 사진)도 그리드 안의 전체 폭 항목으로 둬 함께 스크롤된다 — 가로 화면·큰 글꼴에서 그리드 높이가 0 이 되지 않게.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentDetailScreen(onBack: () -> Unit, vm: MomentDetailViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val m = state.moment
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(m?.title ?: "추억", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !state.loaded -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                state.failed -> NoticeCard("추억을 불러오지 못했어요", "뒤로 갔다가 다시 열어 주세요.")
                m == null -> NoticeCard("이 추억을 찾지 못했어요", "분석이 다시 돌면서 묶음이 바뀌었을 수 있어요. 추억 목록에서 다시 골라 주세요.")
                else -> PhotoGrid(m.ids, Modifier.fillMaxSize(), header = {
                    item(span = { GridItemSpan(maxLineSpan) }) { MomentHeader(m) }
                })
            }
        }
    }
}

@Composable
private fun MomentHeader(m: Moment) {
    val context = LocalContext.current
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(m.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (m.estimated > 0) {
            Text("위치가 없는 ${m.estimated}장은 앞뒤 사진으로 장소를 추정했어요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("대표 사진", Modifier.padding(top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(m.highlights, key = { _, id -> id }) { i, id ->
                AsyncImage(
                    model = mediaUri(id),
                    contentDescription = "대표 사진 ${i + 1}/${m.highlights.size}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(120.dp).clip(MaterialTheme.shapes.small).clickable { openInGallery(context, id) },
                )
            }
        }
        Text("사진 ${m.ids.size}장", Modifier.padding(top = 12.dp, bottom = 2.dp), style = MaterialTheme.typography.titleSmall)
    }
}