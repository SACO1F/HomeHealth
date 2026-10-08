package com.example.homehealth.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** 按下时轻微缩小的比例（可调；只改几何尺寸，不动颜色 / 透明度） */
private const val PRESSED_SCALE = 0.97f

/**
 * 卡片按下反馈：**不叠灰色方块、不改透明度**，只在按下时轻微缩小（iOS 手感）。
 *
 * 为什么不用水波纹 / 压暗：
 * - 水波纹是**未被圆角裁切**的灰色矩形，和悬浮卡片的 32dp 圆角对不上；
 * - 压暗（降 alpha）会改变卡片的**半透明观感**，同样"破坏样式"。
 *
 * 缩放只改变几何尺寸，卡片的颜色、透明度、阴影风格全程不变 —— 既**有反馈**又**不破坏样式**。
 */
@Composable
private fun Modifier.pressScaleFeedback(interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "pressScale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** 无水波纹点击：按下只轻微缩小 */
@Composable
fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this
        .pressScaleFeedback(interactionSource)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
}

/** 无水波纹的「点击 + 长按」：按下只轻微缩小 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.noRippleCombinedClickable(
    onClick: () -> Unit,
    onLongClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this
        .pressScaleFeedback(interactionSource)
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick
        )
}
