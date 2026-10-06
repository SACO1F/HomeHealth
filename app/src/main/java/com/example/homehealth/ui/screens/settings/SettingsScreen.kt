package com.example.homehealth.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.example.homehealth.R
import com.example.homehealth.data.SettingsPrefs
import com.example.homehealth.data.local.entity.FamilyMember
import com.example.homehealth.data.remote.LlmProviders
import com.example.homehealth.domain.model.LlmCallStats
import com.example.homehealth.ui.components.MemberEditDialog
import com.example.homehealth.ui.components.DropdownSelector
import com.example.homehealth.ui.components.StatItem
import com.example.homehealth.ui.components.relationshipLabel
import com.example.homehealth.util.DateUtils
import com.example.homehealth.util.FileUtils
import kotlinx.coroutines.delay

/** 设置页：成员管理 / 解析服务 / 数据导出 / 健康检查 / 关于 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavHostController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val members by viewModel.members.collectAsStateWithLifecycle()
    val parseProvider by viewModel.parseProvider.collectAsStateWithLifecycle()
    val parseApiKey by viewModel.parseApiKey.collectAsStateWithLifecycle()
    val parseKeyUnreadable by viewModel.parseKeyUnreadable.collectAsStateWithLifecycle()
    val parseModel by viewModel.parseModel.collectAsStateWithLifecycle()
    val qaProvider by viewModel.qaProvider.collectAsStateWithLifecycle()
    val qaApiKey by viewModel.qaApiKey.collectAsStateWithLifecycle()
    val qaKeyUnreadable by viewModel.qaKeyUnreadable.collectAsStateWithLifecycle()
    val qaModel by viewModel.qaModel.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val languageMode by viewModel.languageMode.collectAsStateWithLifecycle()
    val llmStats by viewModel.llmStats.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 进入设置页时刷新一次调用统计
    val exportShareTitle = stringResource(R.string.settings_export_share_title)
    LaunchedEffect(Unit) { viewModel.refreshLlmStats() }

    var showAddMember by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<FamilyMember?>(null) }
    var deleteTarget by remember { mutableStateOf<FamilyMember?>(null) }
    var editingParseService by rememberSaveable { mutableStateOf(true) }

    // 事件处理
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.ExportReady -> {
                    FileUtils.shareFile(
                        context = context,
                        file = event.file,
                        mime = "application/json",
                        title = exportShareTitle
                    )
                }
                is SettingsEvent.CheckEnqueued -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        // 外层 RootApp Scaffold 已处理系统栏 inset；内层默认再叠加 navigationBars inset，
        // 会在内容底部垫出一条与背景同色的空带（即底部导航上方的“白条”）
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { androidx.compose.material3.TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- 家庭成员管理 ----
            item {
                SectionTitle(stringResource(R.string.settings_member_section))
            }
            items(members, key = { it.id }) { member ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        com.example.homehealth.ui.components.MemberAvatar(
                            name = member.name,
                            avatarUrl = member.avatarUrl,
                            size = 40
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp)
                        ) {
                            Text(member.name, style = MaterialTheme.typography.bodyLarge)
                            val ageText = DateUtils.age(member.dateOfBirth)
                                ?.let { stringResource(R.string.age_suffix, it) }
                            Text(
                                text = listOfNotNull(
                                    relationshipLabel(member.relationship),
                                    ageText
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { editTarget = member }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.common_edit),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { deleteTarget = member }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.common_delete),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { showAddMember = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text(stringResource(R.string.settings_add_member))
                }
            }

            // ---- 外观 ----
            item {
                SectionTitle(stringResource(R.string.settings_theme_section))
            }
            item {
                ModeOptionCard(
                    title = stringResource(R.string.settings_theme_system),
                    desc = stringResource(R.string.settings_theme_system_desc),
                    selected = themeMode == SettingsPrefs.THEME_SYSTEM,
                    onClick = { viewModel.setThemeMode(SettingsPrefs.THEME_SYSTEM) }
                )
            }
            item {
                ModeOptionCard(
                    title = stringResource(R.string.settings_theme_light),
                    desc = stringResource(R.string.settings_theme_light_desc),
                    selected = themeMode == SettingsPrefs.THEME_LIGHT,
                    onClick = { viewModel.setThemeMode(SettingsPrefs.THEME_LIGHT) }
                )
            }
            item {
                ModeOptionCard(
                    title = stringResource(R.string.settings_theme_dark),
                    desc = stringResource(R.string.settings_theme_dark_desc),
                    selected = themeMode == SettingsPrefs.THEME_DARK,
                    onClick = { viewModel.setThemeMode(SettingsPrefs.THEME_DARK) }
                )
            }

            // ---- 语言 ----
            item {
                SectionTitle(stringResource(R.string.settings_language_section))
            }
            item {
                ModeOptionCard(
                    title = stringResource(R.string.settings_language_system),
                    desc = stringResource(R.string.settings_language_system_desc),
                    selected = languageMode == SettingsPrefs.LANGUAGE_SYSTEM,
                    onClick = { viewModel.setLanguageMode(SettingsPrefs.LANGUAGE_SYSTEM) }
                )
            }
            item {
                ModeOptionCard(
                    title = stringResource(R.string.settings_language_zh),
                    desc = stringResource(R.string.settings_language_zh_desc),
                    selected = languageMode == SettingsPrefs.LANGUAGE_ZH,
                    onClick = { viewModel.setLanguageMode(SettingsPrefs.LANGUAGE_ZH) }
                )
            }
            item {
                ModeOptionCard(
                    title = stringResource(R.string.settings_language_en),
                    desc = stringResource(R.string.settings_language_en_desc),
                    selected = languageMode == SettingsPrefs.LANGUAGE_EN,
                    onClick = { viewModel.setLanguageMode(SettingsPrefs.LANGUAGE_EN) }
                )
            }

            // ---- AI 服务：两个用途共用一套清晰的配置界面，配置仍分别保存 ----
            item {
                SectionTitle(stringResource(R.string.settings_ai_section))
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            stringResource(R.string.settings_ai_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AiServiceTile(
                                title = stringResource(R.string.settings_parse_section),
                                providerName = providerDisplayName(parseProvider),
                                status = serviceStatus(parseProvider, parseApiKey, parseKeyUnreadable, true),
                                icon = Icons.Filled.Description,
                                selected = editingParseService,
                                onClick = { editingParseService = true },
                                modifier = Modifier.weight(1f)
                            )
                            AiServiceTile(
                                title = stringResource(R.string.settings_qa_section),
                                providerName = providerDisplayName(qaProvider),
                                status = serviceStatus(qaProvider, qaApiKey, qaKeyUnreadable, false),
                                icon = Icons.Filled.QuestionAnswer,
                                selected = !editingParseService,
                                onClick = { editingParseService = false },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        HorizontalDivider()
                        Text(
                            stringResource(
                                if (editingParseService) R.string.settings_parse_desc
                                else R.string.settings_qa_desc
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        key(editingParseService) {
                            ProviderSettingsFields(
                                provider = if (editingParseService) parseProvider else qaProvider,
                                apiKey = if (editingParseService) parseApiKey else qaApiKey,
                                model = if (editingParseService) parseModel else qaModel,
                                vision = editingParseService,
                                keyUnreadable = if (editingParseService) parseKeyUnreadable else qaKeyUnreadable,
                                onProviderChange = if (editingParseService) viewModel::setParseProvider else viewModel::setQaProvider,
                                onApiKeyChange = if (editingParseService) viewModel::setParseApiKey else viewModel::setQaApiKey,
                                onModelChange = if (editingParseService) viewModel::setParseModel else viewModel::setQaModel
                            )
                        }
                    }
                }
            }

            // ---- LLM 调用统计 ----
            item {
                SectionTitle(stringResource(R.string.settings_llm_stats_section))
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.settings_llm_stats_window),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatItem(stringResource(R.string.settings_llm_calls), llmStats.total.toString())
                            StatItem(stringResource(R.string.settings_llm_failures), llmStats.failures.toString())
                            StatItem(
                                stringResource(R.string.settings_llm_avg_latency),
                                stringResource(R.string.settings_llm_latency_ms, llmStats.avgLatencyMs)
                            )
                        }
                        Text(
                            stringResource(
                                R.string.settings_llm_chars,
                                llmStats.totalPromptChars,
                                llmStats.totalCompletionChars
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        llmStats.byProvider.forEach { stat ->
                            Text(
                                stringResource(
                                    R.string.settings_llm_provider_line,
                                    stat.provider, stat.total, stat.failures
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ---- 数据 ----
            item {
                SectionTitle(stringResource(R.string.settings_data_section))
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Button(onClick = { viewModel.exportData() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                            Text(stringResource(R.string.settings_export))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.settings_export_desc),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---- 健康检查 ----
            item {
                SectionTitle(stringResource(R.string.settings_check_section))
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedButton(onClick = { viewModel.runCheckNow() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.settings_check_now))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.settings_check_desc),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---- 关于 ----
            item {
                SectionTitle(stringResource(R.string.settings_about_section))
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.settings_app_version), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.settings_privacy),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showAddMember) {
        MemberEditDialog(
            onDismiss = { showAddMember = false },
            onSave = { name, relationship, dob, gender, heightCm, weightKg, avatarUrl, health ->
                viewModel.addMember(name, relationship, dob, gender, heightCm, weightKg, avatarUrl, health)
                showAddMember = false
            }
        )
    }

    editTarget?.let { member ->
        MemberEditDialog(
            member = member,
            onDismiss = { editTarget = null },
            onSave = { name, relationship, dob, gender, heightCm, weightKg, avatarUrl, health ->
                viewModel.updateMember(member, name, relationship, dob, gender, heightCm, weightKg, avatarUrl, health)
                editTarget = null
            }
        )
    }

    deleteTarget?.let { member ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.settings_delete_member_title)) },
            text = { Text(stringResource(R.string.settings_delete_member_confirm, member.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMember(member)
                    deleteTarget = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp)
    )
}

/** 服务模式单选项 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeOptionCard(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.padding(4.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                    desc,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun providerDisplayName(provider: String): String =
    when (provider) {
        LlmProviders.LOCAL -> stringResource(R.string.provider_local)
        LlmProviders.ZHIPU.id -> stringResource(R.string.settings_provider_zhipu)
        LlmProviders.KIMI.id -> stringResource(R.string.settings_provider_kimi)
        LlmProviders.QWEN.id -> stringResource(R.string.settings_provider_qwen)
        else -> LlmProviders.byId(provider)?.name ?: provider
    }

@Composable
private fun serviceStatus(
    provider: String,
    apiKey: String,
    keyUnreadable: Boolean,
    vision: Boolean
): String = when {
    provider == LlmProviders.LOCAL && vision -> stringResource(R.string.settings_ai_manual_only)
    provider == LlmProviders.LOCAL -> stringResource(R.string.settings_ai_local_analysis)
    keyUnreadable -> stringResource(R.string.settings_ai_key_invalid)
    apiKey.isBlank() -> stringResource(R.string.settings_ai_key_missing)
    else -> stringResource(R.string.settings_ai_ready)
}

/** 两项服务的概览入口，显示供应商和配置状态。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiServiceTile(
    title: String,
    providerName: String,
    status: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            Text(
                providerName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 共用表单，视觉服务仅列出支持图像输入的供应商。 */
@Composable
private fun ProviderSettingsFields(
    provider: String,
    apiKey: String,
    model: String,
    vision: Boolean,
    keyUnreadable: Boolean,
    onProviderChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit
) {
    val choices = listOf(LlmProviders.LOCAL to stringResource(R.string.provider_local)) +
        LlmProviders.ALL.filter { !vision || it.visionModels.isNotEmpty() }
            .map { it.id to providerDisplayName(it.id) }
    DropdownSelector(
        options = choices.map { it.second },
        selected = choices.firstOrNull { it.first == provider }?.second ?: provider,
        label = stringResource(R.string.settings_ai_provider_label),
        onSelect = { name -> choices.firstOrNull { it.second == name }?.let { onProviderChange(it.first) } }
    )

    if (provider == LlmProviders.LOCAL) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(
                    if (vision) R.string.provider_local_vision_desc
                    else R.string.provider_local_qa_desc
                ),
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
    } else {
        val selectedProvider = LlmProviders.byId(provider)
        Text(
            stringResource(R.string.settings_ai_key_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ApiKeyField(
            apiKey = apiKey,
            keyUnreadable = keyUnreadable,
            onApiKeyChange = onApiKeyChange
        )
        val models = if (vision) selectedProvider?.visionModels else selectedProvider?.chatModels
        if (!models.isNullOrEmpty()) {
            ModelSelector(
                models = models,
                model = model,
                vision = vision,
                onModelChange = onModelChange
            )
        }
    }
}

/**
 * 模型配置控件。
 *
 * 刻意做成「可自由输入的文本框 + 预设快捷填入」，而不是只读下拉：
 * 供应商的模型型号更新频繁，硬编码的预设清单必然滞后，
 * 只给下拉会让用户在预设过期后完全无法使用该供应商的新模型。
 * 留空表示使用该供应商的默认模型（沿用原有语义）。
 *
 * 输入采用**本地草稿 + 400ms 防抖**提交：逐字符落盘既浪费，也会在进程被杀时存下半截模型名。
 */
@Composable
private fun ModelSelector(
    models: List<String>,
    model: String,
    vision: Boolean,
    onModelChange: (String) -> Unit
) {
    var draft by remember { mutableStateOf(model) }
    // 外部改动（预设芯片、切换供应商清空）时同步回输入框
    LaunchedEffect(model) {
        if (model != draft) draft = model
    }
    // 防抖提交：停止输入 400ms 后才写入，避免逐字符落盘
    LaunchedEffect(draft) {
        if (draft == model) return@LaunchedEffect
        delay(MODEL_INPUT_DEBOUNCE_MS)
        if (draft != model) onModelChange(draft)
    }

    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = {
            Text(
                stringResource(
                    if (vision) R.string.settings_vision_model_label
                    else R.string.settings_text_model_label
                )
            )
        },
        supportingText = {
            Text(
                if (model.isBlank()) {
                    stringResource(R.string.settings_model_default, models.first())
                } else {
                    stringResource(R.string.settings_model_custom_hint)
                }
            )
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = stringResource(R.string.settings_model_presets),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        models.forEach { preset ->
            FilterChip(
                selected = model == preset,
                onClick = {
                    draft = preset
                    onModelChange(preset)
                },
                label = { Text(preset) }
            )
        }
    }
}

/** 模型输入防抖时长（毫秒） */
private const val MODEL_INPUT_DEBOUNCE_MS = 400L

/** API Key 输入（密码遮罩 + 显示切换）；密钥库失效导致解不开时给出专门提示 */
@Composable
private fun ApiKeyField(
    apiKey: String,
    keyUnreadable: Boolean,
    onApiKeyChange: (String) -> Unit
) {
    var key by remember(apiKey) { mutableStateOf(apiKey) }
    var keyVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = key,
        onValueChange = {
            key = it
            onApiKeyChange(it)
        },
        label = { Text("API Key") },
        singleLine = true,
        isError = keyUnreadable,
        supportingText = if (keyUnreadable) {
            { Text(stringResource(R.string.settings_key_unreadable)) }
        } else {
            null
        },
        visualTransformation = if (keyVisible) {
            androidx.compose.ui.text.input.VisualTransformation.None
        } else {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        },
        trailingIcon = {
            TextButton(onClick = { keyVisible = !keyVisible }) {
                Text(stringResource(if (keyVisible) R.string.settings_key_hide else R.string.settings_key_show))
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}
