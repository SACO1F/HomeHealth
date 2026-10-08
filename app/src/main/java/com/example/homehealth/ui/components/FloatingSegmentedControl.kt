package com.example.homehealth.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.homehealth.ui.navigation.FLOATING_CORNER

/**
 * 悬浮分段选择器：与底部悬浮导航栏同款外观（半透明胶囊 + 阴影 + FLOATING_CORNER 圆角），
 * 切换时选中指示块**滑动**到目标段（而不是瞬移）。
 *
 * @param options 各段显示文字
 * @param selectedIndex 当前选中段下标（越界时按 0 处理）
 * @param onSelect 点击某段回调
 */
@Composable
fun FloatingSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (options.isEmpty()) return
    val current = selectedIndex.coerceIn(0, options.lastIndex)

    Surface(
        shape = RoundedCornerShape(FLOATING_CORNER),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 8.dp,
        modifier = modifier
    ) {
        BoxWithConstraints(modifier = Modifier.padding(4.dp)) {
            val segmentWidth = maxWidth / options.size
            // 指示块滑动：位置随选中段平滑过渡
            val indicatorOffset by animateDpAsState(
                targetValue = segmentWidth * current,
                label = "segmentIndicator"
            )
            // 指示块：套一层 matchParentSize 的 Box，避免 fillMaxHeight 反过来撑高容器。
            // （放在 LazyColumn 里时 maxHeight 无限、暂时不会出问题；一旦被放进有界高度的容器
            //   就会被拉长，所以统一用 matchParentSize 隔离。）
            Box(modifier = Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(FLOATING_CORNER))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f))
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, label ->
                    val selected = index == current
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(FLOATING_CORNER))
                            // 同悬浮导航栏：去掉按下时的灰色水波纹，避免在滑动指示块之外
                            // 先冒出一个灰色圆角块
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onSelect(index) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
