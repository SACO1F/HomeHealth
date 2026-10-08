package com.example.homehealth.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * iOS 风格图标按钮：**没有 Material 的灰色水波纹**，按下时内容整体轻微淡出。
 *
 * 为什么需要它：Material 的 `IconButton` 按下会在图标外画一圈灰色圆点（ripple），
 * 在本应用「悬浮卡片 + 大圆角 + 半透明」的视觉里非常突兀；iOS 的做法是只把内容压暗一点，
 * 不额外画形状。
 *
 * 参数与 `IconButton` 对齐（`onClick` / `modifier` / `enabled`），可直接一对一替换；
 * 内容色沿用环境色（与 `IconButton` 默认行为一致），未显式指定 `tint` 的图标不会变样。
 * 默认最小点击区域 44dp，显式传入 `Modifier.size(...)` 时以传入值为准。
 */
@Composable
fun AppleIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // 按下压暗，替代水波纹
    val alpha by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.35f else 1f,
        label = "appleIconAlpha"
    )
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .graphicsLayer(alpha = alpha),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
