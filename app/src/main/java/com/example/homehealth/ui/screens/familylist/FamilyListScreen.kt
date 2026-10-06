package com.example.homehealth.ui.screens.familylist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
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
import com.example.homehealth.ui.components.relationshipLabel
import com.example.homehealth.ui.navigation.Routes
import com.example.homehealth.util.DateUtils
import com.example.homehealth.util.HealthTypes

/** 家庭列表页：成员卡片 + 添加成员 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyListScreen(
    navController: NavHostController,
    viewModel: FamilyListViewModel = hiltViewModel()
) {
    val members by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        // 同 SettingsScreen：外层已处理系统栏 inset，内层不再叠加（避免底部空带）
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(stringResource(R.string.family_app_title)) },
                actions = {
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.nav_settings))
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.family_add_member)) }
            )
        }
    ) { padding ->
        if (members.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    stringResource(R.string.family_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.family_empty_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(members, key = { it.member.id }) { card ->
                    MemberCard(
                        card = card,
                        onClick = { navController.navigate(Routes.member(card.member.id)) }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        MemberEditDialog(
            onDismiss = { showAddDialog = false },
            onSave = { name, relationship, dob, gender, heightCm, weightKg, avatarUrl, health ->
                viewModel.addMember(name, relationship, dob, gender, heightCm, weightKg, avatarUrl, health)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun MemberCard(card: MemberCardUi, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MemberAvatar(name = card.member.name, avatarUrl = card.member.avatarUrl, size = 52)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = card.member.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.size(8.dp))
                    val ageText = DateUtils.age(card.member.dateOfBirth)
                        ?.let { stringResource(R.string.age_suffix, it) }
                    Text(
                        text = listOfNotNull(
                            relationshipLabel(card.member.relationship),
                            ageText
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (card.unreadAlerts > 0) {
                        Spacer(Modifier.size(8.dp))
                        Badge {
                            Text("${card.unreadAlerts}")
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = latestSummary(card.latestByType),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 最近指标摘要行：展示最近记录的任意指标（不限血压/血糖/体重） */
@Composable
private fun latestSummary(latestByType: Map<String, HealthRecord>): String {
    if (latestByType.isEmpty()) return stringResource(R.string.family_no_records)
    // 常见指标优先展示，其余按记录日期取最近的补足 3 项
    val priority = listOf(HealthTypes.BLOOD_PRESSURE, HealthTypes.BLOOD_GLUCOSE, HealthTypes.WEIGHT)
    val ordered = latestByType.entries.sortedWith(
        compareBy<Map.Entry<String, HealthRecord>> { (type, _) ->
            val idx = priority.indexOf(type)
            if (idx >= 0) idx else priority.size
        }.thenByDescending { (_, r) -> r.recordDate }
    )
    val parts = ordered.take(3).map { (type, r) ->
        val unit = r.unit.trim().takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        "${com.example.homehealth.ui.components.metricLabel(type)} ${r.value}$unit"
    }
    return stringResource(R.string.family_recent_prefix) + parts.joinToString("  ·  ")
}
