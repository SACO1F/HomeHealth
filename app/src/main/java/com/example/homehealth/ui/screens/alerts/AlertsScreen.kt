package com.example.homehealth.ui.screens.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.example.homehealth.R
import com.example.homehealth.data.local.dao.AlertWithMemberName
import com.example.homehealth.ui.components.SeverityBadge
import com.example.homehealth.ui.navigation.FLOATING_CORNER
import com.example.homehealth.ui.navigation.floatingListBottomPadding
import com.example.homehealth.util.AlertText
import com.example.homehealth.util.DateUtils
import com.example.homehealth.ui.components.FloatingCard
import com.example.homehealth.ui.components.noRippleCombinedClickable
import com.example.homehealth.ui.components.AppleIconButton

/** 预警中心：按严重程度排序，支持未读过滤与立即检测 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(
    navController: NavHostController,
    viewModel: AlertsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val hapticFeedback = LocalHapticFeedback.current
    val context = LocalContext.current
    var deleteTarget by remember { mutableStateOf<AlertWithMemberName?>(null) }
    var detectedCount by remember { mutableStateOf<Int?>(null) }
    val detectionMessage = detectedCount?.let { count ->
        if (count > 0) stringResource(R.string.alerts_detected_new, count)
        else stringResource(R.string.alerts_detected_none)
    }
    LaunchedEffect(detectionMessage) {
        detectionMessage?.let {
            snackbarHostState.showSnackbar(it)
            detectedCount = null
        }
    }

    // 进入预警中心时静默检测一次，保证预警与最新数据同步（新预警自动出现，列表随 Room 流刷新）
    LaunchedEffect(Unit) { viewModel.refreshOnEnter() }

    Scaffold(
        // 同 SettingsScreen：外层已处理系统栏 inset，内层不再叠加（避免底部空带）
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(stringResource(R.string.alerts_title)) },
                actions = {
                    AppleIconButton(onClick = {
                        viewModel.runDetection { created -> detectedCount = created }
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.alerts_run_now_cd))
                    }
                    AppleIconButton(onClick = { viewModel.markAllRead() }) {
                        Icon(Icons.Filled.DoneAll, contentDescription = stringResource(R.string.alerts_mark_all_read_cd))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        // 全屏列表 + 顶部悬浮筛选条：预警内容从半透明筛选条下方穿过（透视效果），
        // 筛选条圆角与底部悬浮导航栏保持一致。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.detecting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (state.alerts.isEmpty()) {
                Text(
                    if (state.filter == AlertFilter.UNREAD) stringResource(R.string.alerts_no_unread)
                    else stringResource(R.string.alerts_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp,
                        top = FILTER_BAR_CLEARANCE,
                        bottom = floatingListBottomPadding()
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.alerts, key = { it.alert.id }) { item ->
                        AlertCard(
                            item = item,
                            onClick = { if (!item.alert.isRead) viewModel.markRead(item.alert.id) },
                            onLongPress = {
                                // 长按删除预警（震动反馈 + 确认）
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                deleteTarget = item
                            }
                        )
                    }
                }
            }

            // 悬浮筛选按钮：各自独立成一个半透明胶囊（分开悬浮），圆角与底部导航栏一致
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FloatingFilterChip(
                    label = stringResource(R.string.alerts_filter_all, state.alerts.size),
                    selected = state.filter == AlertFilter.ALL,
                    onClick = { viewModel.setFilter(AlertFilter.ALL) }
                )
                FloatingFilterChip(
                    label = stringResource(R.string.alerts_filter_unread, state.unreadCount),
                    selected = state.filter == AlertFilter.UNREAD,
                    onClick = { viewModel.setFilter(AlertFilter.UNREAD) }
                )
            }
        }
    }

    // 长按删除确认
    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            // 与底部悬浮导航栏同一套观感：半透明底 + 大圆角 + 外圈阴影
            modifier = Modifier.shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(FLOATING_CORNER)
            ),
            shape = RoundedCornerShape(FLOATING_CORNER),
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            title = { Text(stringResource(R.string.alerts_delete_title)) },
            text = { Text(stringResource(R.string.alerts_delete_confirm, AlertText.title(context, item.alert))) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAlert(item.alert.id)
                    deleteTarget = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** 顶部悬浮筛选条在列表顶部需要让出的高度（条高 + 上下外边距），使首条不被遮挡 */
private val FILTER_BAR_CLEARANCE = 64.dp

/** 单个悬浮筛选按钮：半透明胶囊 + 阴影，圆角与底部悬浮导航栏一致（各按钮分开悬浮） */
@Composable
private fun FloatingFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(FLOATING_CORNER),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        },
        shadowElevation = 8.dp,
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun AlertCard(
    item: AlertWithMemberName,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    FloatingCard(
        modifier = Modifier
            .fillMaxWidth()
            // 无水波纹：按下不叠灰色方块、也不压暗卡片，样式全程与悬浮卡片一致
            .noRippleCombinedClickable(onClick = onClick, onLongClick = onLongPress)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            val context = LocalContext.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                SeverityBadge(severity = item.alert.severity)
                Text(
                    // 告警正文按当前语言渲染；结构化字段缺失的老数据回退到落库文本
                    text = AlertText.title(context, item.alert),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                )
                if (!item.alert.isRead) {
                    androidx.compose.material3.Badge { }
                }
            }
            Text(
                text = AlertText.description(context, item.alert),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                text = "${item.memberName} · ${DateUtils.formatDateTime(item.alert.createdDate)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
