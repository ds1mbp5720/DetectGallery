package lee.project.detectgallery.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 한 줄 고정 칩. 넘치면 가로로 밀어서 본다. 칩이 늘어도 줄 수는 1줄 그대로다 (높이는 터치 영역 48dp, 아주 큰 글꼴에서는 글자만큼 커진다).
 * selected 는 강조만 한다 (토글 상태를 들고 있지 않는다).
 */
@Composable
fun ChipRow(
    items: List<String>,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    selected: String? = null,
    edgePadding: Dp = 20.dp,
) {
    LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = edgePadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it }) { label -> PillChip(label, label == selected) { onClick(label) } }
    }
}

@Composable
fun PillChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        selected = selected,                   // TalkBack 이 "선택됨" 을 읽는다 (색만으로는 전달되지 않는다)
        onClick = onClick,                     // 최소 터치 영역(48dp)도 Surface 가 맞춘다
        shape = MaterialTheme.shapes.small,
        color = if (selected) colors.primary else colors.surfaceVariant,
        contentColor = if (selected) colors.onPrimary else colors.onSurface,
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

/**
 * 폭을 n 등분하는 선택 탭. 고른 칸은 흰 카드 + 테두리 + 파란 글자.
 * 흰색과 회색 바탕의 차이(1.10:1)만으로는 선택이 안 보여, 상태 표시 기준(3:1)을 테두리(4.19:1)와 글자색(5.41:1)으로 맞춘다.
 */
@Composable
fun <T> SegmentedTabs(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceVariant)
            .padding(4.dp)
            .selectableGroup(),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)              // 최소 터치 영역
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) colors.surface else Color.Transparent)
                    .then(if (on) Modifier.border(1.dp, colors.outline, RoundedCornerShape(9.dp)) else Modifier)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(value) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    Modifier.padding(horizontal = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) colors.primary else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}