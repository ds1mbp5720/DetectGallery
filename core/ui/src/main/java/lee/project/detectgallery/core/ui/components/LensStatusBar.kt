package lee.project.detectgallery.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale

internal data class StatusText(val main: String, val badge: String, val ratio: Float)

/**
 * 현황 줄 문구. galleryCount == null 은 아직 첫 값이 오지 않은 상태.
 * partial = "선택한 사진만 허용". 그때의 사진 수는 갤러리 전체가 아니라 고른 사진 수라 "고른 사진" 으로 쓴다.
 */
internal fun statusText(galleryCount: Int?, analyzedCount: Int, indexing: Boolean, partial: Boolean = false): StatusText {
    if (galleryCount == null) return StatusText("갤러리 확인 중", "", 0f)
    val analyzed = analyzedCount.coerceIn(0, galleryCount)   // 지운 사진이 정리되기 전엔 분석 수가 더 클 수 있다
    val pending = galleryCount - analyzed
    val badge = when {
        galleryCount == 0 -> "사진 없음"
        indexing -> "분석 중"
        pending == 0 -> "모두 분석됨"
        else -> "대기 ${fmt(pending)}장"                       // 다음 실행 대기·분석 실패 포함
    }
    val ratio = if (galleryCount == 0) 0f else analyzed / galleryCount.toFloat()
    val noun = if (partial) "고른 사진" else "사진"
    return StatusText("$noun ${fmt(galleryCount)}장 · 분석 ${fmt(analyzed)}장", badge, ratio)
}

private fun fmt(n: Int): String = NumberFormat.getIntegerInstance(Locale.KOREA).format(n)

/**
 * 모든 탭 위에 항상 보이는 현황 줄. 카드 48dp + 위아래 2dp = 52dp 고정 (부분 접근이어도 같다).
 * 진행 막대는 카드 아래 가장자리에 3dp 로 붙여 줄을 늘리지 않는다.
 * partial = "선택한 사진만 허용" 상태: 카드 전체가 "사진 더 고르기" 버튼이 된다
 * (따로 버튼을 두면 최소 터치 영역 48dp 때문에 줄이 커진다).
 */
@Composable
fun LensStatusBar(
    galleryCount: Int?,
    analyzedCount: Int,
    indexing: Boolean,
    partial: Boolean,
    onRequestMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = statusText(galleryCount, analyzedCount, indexing, partial)
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .height(48.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surfaceVariant)
            .then(
                if (partial) {
                    Modifier
                        .clickable(onClickLabel = "사진 더 고르기", role = Role.Button, onClick = onRequestMore)
                        .semantics { stateDescription = "선택한 사진만 허용" }   // TalkBack: 문구 + 상태 + 동작
                } else Modifier,
            ),
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 14.dp, end = 12.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                t.main,   // 부분 접근이면 "고른 사진 N장" (긴 접두어를 붙이면 360dp 폭에서 숫자가 잘린다). 넘치면 끝이 … 로 줄어든다
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (t.badge.isNotEmpty()) StatusBadge(t.badge, highlighted = indexing)
            if (partial) {
                Text(
                    "더 고르기 ›",
                    Modifier.padding(start = 8.dp).clearAndSetSemantics {},   // "›" 를 기호 이름으로 읽지 않게. 동작은 카드의 onClickLabel 이 알린다
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary,
                    maxLines = 1,
                )
            }
        }
        LinearProgressIndicator(
            progress = { t.ratio },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),   // 카드 모서리 clip 이 끝을 둥글게 한다
            color = colors.primary,
            trackColor = colors.outlineVariant,
            gapSize = 0.dp,
            drawStopIndicator = {},                 // 끝점 표시 없이 막대만
        )
    }
}

@Composable
private fun StatusBadge(text: String, highlighted: Boolean) {
    val colors = MaterialTheme.colorScheme
    Text(
        text,
        Modifier
            .padding(start = 8.dp)
            .clip(CircleShape)
            .background(if (highlighted) colors.primaryContainer else colors.surface)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = if (highlighted) colors.onPrimaryContainer else colors.onSurfaceVariant,
        maxLines = 1,
    )
}