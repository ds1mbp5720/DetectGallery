package lee.project.detectgallery.core.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class BottomItem(val key: String, val label: String, val icon: ImageVector)

/** 56dp 하단 탭 (기본 NavigationBar 80dp). 고른 탭은 진한 글자색 + 굵게, 나머지는 회색. */
@Composable
fun CompactBottomBar(items: List<BottomItem>, selectedKey: String?, onSelect: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surface) {
        Column {
            HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)   // 제스처 바는 56dp 밖에 더한다
                    .height(56.dp)
                    .selectableGroup(),
            ) {
                items.forEach { item ->
                    val on = item.key == selectedKey
                    val tint = if (on) colors.onSurface else colors.onSurfaceVariant   // 투명도를 낮추면 11sp 글자 대비가 기준 아래로 떨어진다
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(selected = on, role = Role.Tab, onClick = { onSelect(item.key) }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(item.icon, contentDescription = null, Modifier.size(22.dp), tint = tint)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            item.label,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                            color = tint,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}