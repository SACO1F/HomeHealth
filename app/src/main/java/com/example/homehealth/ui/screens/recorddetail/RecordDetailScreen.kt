package com.example.homehealth.ui.screens.recorddetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.example.homehealth.R
import com.example.homehealth.data.local.entity.HealthRecord
import com.example.homehealth.ui.components.RecordInputDialog
import com.example.homehealth.ui.components.StatItem
import com.example.homehealth.ui.components.TrendLineChart
import com.example.homehealth.util.DateUtils
import com.example.homehealth.util.HealthTypes

/** 指标详情页：趋势图 + 统计 + 历史记录 + 手动添加 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordDetailScreen(
    navController: NavHostController,
    viewModel: RecordDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val memberGender by viewModel.memberGender.collectAsStateWithLifecycle()
    val label = com.example.homehealth.ui.components.metricLabel(viewModel.type)
    var showAddDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<HealthRecord?>(null) }
    var deleteTarget by remember { mutableStateOf<HealthRecord?>(null) }

    Scaffold(
        // 同 SettingsScreen：外层已处理系统栏 inset，内层不再叠加（避免底部空带）
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(label) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.record_add_cd))
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 趋势图
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.record_detail_trend),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        if (state.chartPoints.isEmpty()) {
                            Text(
                                stringResource(R.string.record_detail_no_data),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            TrendLineChart(points = state.chartPoints)
                        }
                    }
                }
            }

            // 统计
            if (state.records.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatItem(stringResource(R.string.stat_latest), formatNum(state.latest))
                            StatItem(stringResource(R.string.stat_average), formatNum(state.average))
                            StatItem(stringResource(R.string.stat_highest), formatNum(state.highest))
                            StatItem(stringResource(R.string.stat_lowest), formatNum(state.lowest))
                        }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.record_reference_range, HealthTypes.range(viewModel.type, memberGender)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 记录列表
            item {
                Text(
                    stringResource(R.string.record_all_records, state.records.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            items(state.records, key = { it.id }) { record ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${record.value} ${record.unit}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = DateUtils.formatDateTime(record.recordDate) + " · " +
                                    stringResource(
                                        if (record.sourceDocumentId != null) R.string.detail_from_report
                                        else R.string.detail_manual
                                    ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { editTarget = record }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.common_edit),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { deleteTarget = record }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.common_delete),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        RecordInputDialog(
            fixedType = viewModel.type,
            gender = memberGender,
            onDismiss = { showAddDialog = false },
            onConfirm = { _, primary, secondary, dateText, notes ->
                viewModel.addRecord(primary, secondary, dateText, notes)
                showAddDialog = false
            }
        )
    }

    // 编辑已有记录（预填现有值）
    editTarget?.let { record ->
        RecordInputDialog(
            fixedType = viewModel.type,
            existing = record,
            gender = memberGender,
            onDismiss = { editTarget = null },
            onConfirm = { _, primary, secondary, dateText, notes ->
                viewModel.updateRecord(record, primary, secondary, dateText, notes)
                editTarget = null
            }
        )
    }

    deleteTarget?.let { record ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.record_delete_title)) },
            text = { Text(stringResource(R.string.record_delete_confirm, DateUtils.formatDate(record.recordDate), record.value)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRecord(record)
                    deleteTarget = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

private fun formatNum(v: Double?): String = v?.let { "%.1f".format(it) } ?: "—"
