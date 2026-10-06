package com.example.homehealth.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.homehealth.R
import com.example.homehealth.data.local.entity.AlertSeverity
import com.example.homehealth.data.local.entity.ParseStatus
import com.example.homehealth.ui.theme.HighSeverity
import com.example.homehealth.ui.theme.LowSeverity
import com.example.homehealth.ui.theme.MediumSeverity
import com.example.homehealth.util.DateUtils
import com.example.homehealth.util.HealthTypes
import java.io.File

/** 当前应用语言是否为英文（per-app locale 生效后 Configuration 随之更新） */
@Composable
fun isEnglish(): Boolean =
    LocalConfiguration.current.locales[0]?.language == "en"

/** 未收录的报告指标保留原名，避免 stringResource(0) 使页面崩溃。 */
@Composable
fun metricLabel(type: String): String {
    val resId = HealthTypes.labelRes(type)
    return if (resId != 0) stringResource(resId) else type
}

/** 严重程度徽章 */
@Composable
fun SeverityBadge(severity: AlertSeverity, modifier: Modifier = Modifier) {
    val (color, label) = when (severity) {
        AlertSeverity.HIGH -> HighSeverity to stringResource(R.string.severity_high)
        AlertSeverity.MEDIUM -> MediumSeverity to stringResource(R.string.severity_medium)
        AlertSeverity.LOW -> LowSeverity to stringResource(R.string.severity_low)
    }
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 文档解析状态徽章 */
@Composable
fun ParseStatusBadge(status: ParseStatus, modifier: Modifier = Modifier) {
    val (color, label) = when (status) {
        ParseStatus.PENDING -> Color(0xFF757575) to stringResource(R.string.parse_status_pending)
        ParseStatus.PROCESSING -> MediumSeverity to stringResource(R.string.parse_status_processing)
        ParseStatus.COMPLETED -> Color(0xFF2E7D32) to stringResource(R.string.parse_status_completed)
        ParseStatus.FAILED -> HighSeverity to stringResource(R.string.parse_status_failed)
    }
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 成员头像：优先显示已设置的图片（圆形裁剪），否则显示姓名首字 */
@Composable
fun MemberAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Int = 48,
    avatarUrl: String? = null
) {
    if (!avatarUrl.isNullOrBlank()) {
        AsyncImage(
            model = File(avatarUrl),
            contentDescription = stringResource(R.string.family_avatar_cd),
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size.dp)
                .clip(CircleShape)
        )
        return
    }
    val palette = listOf(
        Color(0xFF00897B), Color(0xFF5C6BC0), Color(0xFF8D6E63),
        Color(0xFF43A047), Color(0xFFF4511E), Color(0xFF6D4C41)
    )
    val color = palette[((name.hashCode() % palette.size) + palette.size) % palette.size]
    Box(
        modifier = modifier
            .size(size.dp)
            .background(color, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.firstOrNull()?.toString() ?: "?",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 趋势变化指示（上升/下降/持平） */
@Composable
fun TrendIndicator(
    delta: Double?,
    higherIsWorse: Boolean = true,
    showText: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (delta == null || abs(delta) < 1e-9) {
        if (showText) Text(
            "—",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
        return
    }
    val up = delta > 0
    val bad = if (higherIsWorse) up else !up
    val color = if (bad) HighSeverity else Color(0xFF2E7D32)
    val arrow = if (up) "↑" else "↓"
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (showText) "$arrow ${"%.1f".format(abs(delta))}" else arrow,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 信息统计卡片 */
@Composable
fun StatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 格式化时间戳为「今天 / 昨天 / N天前」（本地化） */
@Composable
fun relativeTime(ts: Long): String = DateUtils.relative(ts, isEnglish())

private fun abs(x: Int): Int = if (x < 0) -x else x
private fun abs(x: Double): Double = if (x < 0) -x else x
