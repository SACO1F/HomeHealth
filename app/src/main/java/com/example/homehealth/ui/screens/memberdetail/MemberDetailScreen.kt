package com.example.homehealth.ui.screens.memberdetail

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import com.example.homehealth.ui.components.MemberAvatar
import com.example.homehealth.ui.components.MemberEditDialog
import com.example.homehealth.ui.components.RecordInputDialog
import com.example.homehealth.ui.components.TrendIndicator
import com.example.homehealth.ui.components.dietLabel
import com.example.homehealth.ui.components.drinkingLabel
import com.example.homehealth.ui.components.exerciseLabel
import com.example.homehealth.ui.components.genderLabel
import com.example.homehealth.ui.components.relationshipLabel
import com.example.homehealth.ui.components.relativeTime
import com.example.homehealth.ui.components.smokingLabel
import com.example.homehealth.ui.navigation.Routes
import com.example.homehealth.ui.navigation.navigateToTopLevel
import com.example.homehealth.util.DateUtils
import com.example.homehealth.util.HealthTypes

/** 个人档案页：指标概览 + 最近记录 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberDetailScreen(
    navController: NavHostController,
    viewModel: MemberDetailViewModel = hiltViewModel()
) {
    val member by viewModel.member.collectAsStateWithLifecycle()
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()
    val recentRecords by viewModel.recentRecords.collectAsStateWithLifecycle()
    val unreadAlerts by viewModel.unreadAlerts.collectAsStateWithLifecycle()
    var showEditDialog by remember { mutableStateOf(false) }
    var showAddRecordDialog by remember { mutableStateOf(false) }

    Scaffold(
        // 同 SettingsScreen：外层已处理系统栏 inset，内层不再叠加（避免底部空带）
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(member?.name ?: stringResource(R.string.detail_title_default)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // 编辑个人信息
                    IconButton(
                        onClick = { showEditDialog = true },
                        enabled = member != null
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.detail_edit_cd))
                    }
                    IconButton(onClick = { navController.navigateToTopLevel(Routes.ALERTS) }) {
                        Icon(Icons.Filled.Notifications, contentDescription = stringResource(R.string.detail_alerts_cd))
                        if (unreadAlerts > 0) Badge { Text("$unreadAlerts") }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { navController.navigate(Routes.upload(viewModel.memberId)) },
                icon = { Icon(Icons.Filled.CloudUpload, contentDescription = null) },
                text = { Text(stringResource(R.string.detail_upload_report)) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 成员信息头
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MemberAvatar(name = member?.name ?: "?", avatarUrl = member?.avatarUrl, size = 56)
                        Column(modifier = Modifier.padding(start = 14.dp)) {
                            Text(
                                text = member?.name ?: "",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(2.dp))
                            val ageText = DateUtils.age(member?.dateOfBirth)
                                ?.let { stringResource(R.string.age_suffix, it) }
                            val genderText = genderLabel(member?.gender).ifBlank { null }
                            Text(
                                text = listOfNotNull(
                                    member?.relationship?.takeIf { it.isNotBlank() }
                                        ?.let { relationshipLabel(it) },
                                    ageText,
                                    genderText
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // 健康档案摘要（仅展示已填写项）
                            member?.let { m ->
                                val bodyLine = listOfNotNull(
                                    m.bloodType?.let { "${stringResource(R.string.member_blood_type)} $it" },
                                    m.waistCm?.takeIf { it > 0 }
                                        ?.let { stringResource(R.string.detail_waist_cm, trimNum(it)) }
                                ).joinToString(" · ")
                                if (bodyLine.isNotBlank()) {
                                    Text(
                                        text = bodyLine,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                val lifeStyle = listOfNotNull(
                                    m.exercise?.let { "${stringResource(R.string.member_exercise_label)} ${exerciseLabel(it)}" },
                                    m.diet?.let { "${stringResource(R.string.member_diet_label)} ${dietLabel(it)}" },
                                    m.smoking?.let { "${stringResource(R.string.member_smoking_label)} ${smokingLabel(it)}" },
                                    m.drinking?.let { "${stringResource(R.string.member_drinking_label)} ${drinkingLabel(it)}" }
                                ).joinToString(" · ")
                                if (lifeStyle.isNotBlank()) {
                                    Text(
                                        text = lifeStyle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                m.chronicConditions?.trim()?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        text = "${stringResource(R.string.detail_chronic)}：$it",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                m.surgeryHistory?.trim()?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        text = "${stringResource(R.string.detail_surgery)}：$it",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 未读预警提示
            if (unreadAlerts > 0) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { navController.navigateToTopLevel(Routes.ALERTS) }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                stringResource(R.string.detail_unread_alerts, unreadAlerts),
                                modifier = Modifier.padding(start = 10.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // 指标概览（支持手动录入）
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader(stringResource(R.string.detail_metrics_header))
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showAddRecordDialog = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(stringResource(R.string.detail_add_metric))
                    }
                }
            }
            if (metrics.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.detail_metrics_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(metrics, key = { it.type }) { metric ->
                    MetricCard(
                        metric = metric,
                        higherIsWorse = HealthTypes.higherIsWorse(metric.type),
                        gender = member?.gender,
                        onClick = {
                            navController.navigate(
                                Routes.record(viewModel.memberId, metric.type)
                            )
                        }
                    )
                }
            }

            // 最近记录
            if (recentRecords.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.detail_recent_header)) }
                items(recentRecords, key = { it.id }) { record ->
                    RecentRecordRow(record)
                }
            }
        }
    }

    // 编辑个人信息对话框
    member?.let { current ->
        if (showEditDialog) {
            MemberEditDialog(
                member = current,
                onDismiss = { showEditDialog = false },
                onSave = { name, relationship, dob, gender, heightCm, weightKg, avatarUrl, health ->
                    viewModel.updateMember(current, name, relationship, dob, gender, heightCm, weightKg, avatarUrl, health)
                    showEditDialog = false
                }
            )
        }
    }

    // 手动添加健康指标（先选指标类型，再输入数值）
    if (showAddRecordDialog) {
        RecordInputDialog(
            gender = member?.gender,
            onDismiss = { showAddRecordDialog = false },
            onConfirm = { type, primary, secondary, dateText, notes ->
                viewModel.addRecord(type, primary, secondary, dateText, notes)
                showAddRecordDialog = false
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun MetricCard(
    metric: MetricSummary,
    higherIsWorse: Boolean,
    gender: String?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = com.example.homehealth.ui.components.metricLabel(metric.type),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = metric.latest.value,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = " ${metric.latest.unit}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Text(
                    text = listOf(
                        relativeTime(metric.latest.recordDate),
                        stringResource(R.string.detail_record_count, metric.count),
                        stringResource(R.string.detail_reference, HealthTypes.range(metric.type, gender))
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            TrendIndicator(delta = metric.delta, higherIsWorse = higherIsWorse)
        }
    }
}

@Composable
private fun RecentRecordRow(record: HealthRecord) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = com.example.homehealth.ui.components.metricLabel(record.type),
                    style = MaterialTheme.typography.bodyMedium
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
            Text(
                text = "${record.value} ${record.unit}",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

/** 85.0 → 85（去掉无意义的小数位，健康档案展示用） */
private fun trimNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
