package com.example.homehealth.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.example.homehealth.ui.navigation.FLOATING_CORNER

/**
 * 悬浮卡片：与底部悬浮导航栏同一套观感的 [Card] —— **半透明底 + FLOATING_CORNER 大圆角 + 外圈阴影**。
 *
 * 关键：底色必须与底部悬浮导航栏**完全一致**（`surface @ 0.92`），并且**必须有外圈阴影**。
 * 本主题里 `surface == background`，只加半透明底而不加阴影时，卡片会与页面背景同色、
 * 看起来像"全透明"；导航栏之所以能浮起来，靠的正是 `shadowElevation`。
 *
 * 参数与 M3 的 `Card` 一一对应（`shape` / `colors` / `border` / `onClick` 都可覆盖），
 * 只是把默认值换成了悬浮风格；需要强调色的卡片（如 AI 服务选中态）传入自己的 `colors` 即可。
 */
@Composable
fun FloatingCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(FLOATING_CORNER),
    colors: CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    ),
    // 与底部悬浮导航栏相同的阴影高度，卡片才能从同色背景里浮起来
    elevation: CardElevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick == null) {
        Card(
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            content = content
        )
    } else {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            content = content
        )
    }
}
