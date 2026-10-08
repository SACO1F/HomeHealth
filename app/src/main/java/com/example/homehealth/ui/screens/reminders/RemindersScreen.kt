package com.example.homehealth.ui.screens.reminders

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import com.example.homehealth.R
import com.example.homehealth.data.local.entity.FamilyMember
import com.example.homehealth.data.local.entity.MedicationReminder
import com.example.homehealth.ui.components.DropdownSelector
import com.example.homehealth.ui.components.memberPickerLabel
import com.example.homehealth.ui.navigation.FLOATING_CORNER
import com.example.homehealth.ui.navigation.FLOATING_NAV_RESERVE
import com.example.homehealth.ui.navigation.floatingListBottomPadding
import com.example.homehealth.util.CalendarEventHelper
import com.example.homehealth.util.DateUtils
import com.example.homehealth.worker.MedicationAlarmScheduler
import com.example.homehealth.ui.components.FloatingCard
import com.example.homehealth.ui.components.AppleIconButton
import com.example.homehealth.ui.components.AppleOutlinedButton
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 用药提醒页：列表 + 增删改 + 启停 + 写入本地日历 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(
    navController: NavHostController,
    viewModel: RemindersViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editTarget by remember { mutableStateOf<MedicationReminder?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember {
        mutableStateOf<com.example.homehealth.data.local.dao.ReminderWithMemberName?>(null)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val calendarDeniedText = stringResource(R.string.reminders_calendar_denied)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var exactAvailable by remember { mutableStateOf(MedicationAlarmScheduler.exactAvailable(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exactAvailable = MedicationAlarmScheduler.exactAvailable(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(viewModel) {
        viewModel.errors.collect { snackbarHostState.showSnackbar(it) }
    }

    // 待写入日历的提醒（权限通过后继续执行）
    var pendingCalendarTarget by remember {
        mutableStateOf<com.example.homehealth.data.local.dao.ReminderWithMemberName?>(null)
    }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val target = pendingCalendarTarget
        pendingCalendarTarget = null
        if (grants.values.all { it }) {
            if (target != null) writeReminderToCalendar(
                context, viewModel, scope, snackbarHostState, target
            )
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(calendarDeniedText)
            }
        }
    }

    /** 请求权限（已有权限直接写入） */
    fun exportToCalendar(item: com.example.homehealth.data.local.dao.ReminderWithMemberName) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            writeReminderToCalendar(context, viewModel, scope, snackbarHostState, item)
        } else {
            pendingCalendarTarget = item
            calendarPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
        }
    }

    Scaffold(
        // 同 SettingsScreen：外层已处理系统栏 inset，内层不再叠加（避免底部空带）
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            androidx.compose.material3.TopAppBar(title = { Text(stringResource(R.string.reminders_title)) })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                // 抬到悬浮底部导航栏之上：Scaffold 的 FAB 默认贴底，会被浮层导航栏遮住。
                // navigationBarsPadding 单独避让系统导航栏（NavHost 层不再统一补该 inset）。
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = FLOATING_NAV_RESERVE),
                // 圆角与底部悬浮导航栏一致（FLOATING_CORNER）
                shape = RoundedCornerShape(FLOATING_CORNER),
                // 半透明底 + 模块主题色图标，与悬浮导航栏同一套观感
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.primary,
                onClick = { showAddDialog = true }
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.reminders_add_cd))
            }
        }
    ) { padding ->
        if (state.reminders.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    stringResource(R.string.reminders_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.reminders_empty_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp,
                    bottom = floatingListBottomPadding()
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!exactAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    item {
                        Column {
                            Text(stringResource(R.string.reminders_exact_alarm_needed))
                            OutlinedButton(onClick = {
                                runCatching {
                                    context.startActivity(Intent(
                                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        Uri.parse("package:${context.packageName}")
                                    ))
                                }
                            }) { Text(stringResource(R.string.reminders_exact_alarm_grant)) }
                        }
                    }
                }
                items(state.reminders, key = { it.reminder.id }) { item ->
                    ReminderCard(
                        item = item,
                        onEdit = { editTarget = item.reminder },
                        onDelete = { deleteTarget = item },
                        onToggle = { viewModel.toggleActive(item.reminder) },
                        onExportCalendar = { exportToCalendar(item) }
                    )
                }
            }
        }
    }

    if (showAddDialog || editTarget != null) {
        ReminderEditDialog(
            existing = editTarget,
            members = state.members,
            onDismiss = {
                showAddDialog = false
                editTarget = null
            },
            onSave = { memberId, name, dosage, times ->
                viewModel.saveReminder(editTarget, memberId, name, dosage, times)
                showAddDialog = false
                editTarget = null
            }
        )
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.reminders_delete_title)) },
            text = {
                Text(stringResource(R.string.reminders_delete_confirm, item.reminder.medicationName))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteReminder(item)
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
private fun ReminderCard(
    item: com.example.homehealth.data.local.dao.ReminderWithMemberName,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
    onExportCalendar: () -> Unit
) {
    FloatingCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.reminder.medicationName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${item.memberName} · ${item.reminder.dosage}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    if (item.reminder.active) stringResource(R.string.reminders_enabled)
                    else stringResource(R.string.reminders_disabled),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = item.reminder.active,
                    onCheckedChange = { onToggle() }
                )
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item.reminder.dailyTimes().forEach { time ->
                    AssistChip(onClick = { }, label = { Text(time) })
                }
            }
            Text(
                text = stringResource(R.string.reminders_since, DateUtils.formatDate(item.reminder.startDate)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                // 写入系统日历（每日重复 + 提前提醒）
                // iOS 风格：无灰色水波纹，按下只把内容压暗（圆角 / 描边与悬浮层一致）
                AppleOutlinedButton(
                    onClick = onExportCalendar,
                    enabled = item.reminder.active
                ) {
                    Icon(
                        Icons.Filled.DateRange,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text(stringResource(R.string.reminders_write_calendar))
                }
                Spacer(Modifier.weight(1f))
                AppleIconButton(onClick = onEdit) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.common_edit),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AppleIconButton(onClick = onDelete) {
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

/** 执行写入：先按签名清理旧日历事件（含旧版无 ID 的，避免重复），再写入新事件并持久化事件 ID */
private fun writeReminderToCalendar(
    context: android.content.Context,
    viewModel: RemindersViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbarHostState: SnackbarHostState,
    item: com.example.homehealth.data.local.dao.ReminderWithMemberName
) {
    scope.launch {
        var newEventIds = emptyList<Long>()
        try {
            val isRewrite = item.reminder.calendarEventIdList().isNotEmpty()
            newEventIds = withContext(Dispatchers.IO) {
                CalendarEventHelper.insertMedicationEvents(
                    context = context,
                    medicationName = item.reminder.medicationName,
                    dosage = item.reminder.dosage,
                    memberName = item.memberName,
                    times = item.reminder.dailyTimes(),
                    reminderId = item.reminder.id
                )
            }
            if (newEventIds.isEmpty()) {
                snackbarHostState.showSnackbar(context.getString(R.string.reminders_calendar_invalid))
                return@launch
            }
            withContext(Dispatchers.IO) {
                CalendarEventHelper.deleteMedicationEvents(
                    context = context,
                    medicationName = item.reminder.medicationName,
                    memberName = item.memberName,
                    storedEventIds = item.reminder.calendarEventIdList(),
                    reminderId = item.reminder.id,
                    protectedEventIds = newEventIds
                )
            }
            // 持久化事件 ID，删除提醒时据此同步清理日历
            viewModel.updateCalendarEventIds(item.reminder, newEventIds)
            val eventCount = newEventIds.size
            newEventIds = emptyList()
            val refreshed = context.getString(
                if (isRewrite) R.string.reminders_calendar_updated
                else R.string.reminders_calendar_new
            )
            snackbarHostState.showSnackbar(
                context.getString(
                    R.string.reminders_calendar_done,
                    item.reminder.medicationName, eventCount, refreshed
                )
            )
        } catch (e: Exception) {
            if (newEventIds.isNotEmpty()) withContext(NonCancellable + Dispatchers.IO) {
                runCatching { CalendarEventHelper.deleteCalendarEvents(context, newEventIds) }
            }
            if (e is CancellationException) throw e
            snackbarHostState.showSnackbar(
                context.getString(R.string.reminders_calendar_failed, e.message ?: "")
            )
        }
    }
}

/** 添加/编辑用药提醒对话框 */
@Composable
private fun ReminderEditDialog(
    existing: MedicationReminder?,
    members: List<FamilyMember>,
    onDismiss: () -> Unit,
    onSave: (memberId: String, name: String, dosage: String, times: List<String>) -> Unit
) {
    // 成员下拉选项（显示与匹配统一使用 memberPickerLabel 格式）
    val memberOptions = members.map { it to memberPickerLabel(it.name, it.relationship) }
    val initialLabel = memberOptions.firstOrNull { it.first.id == existing?.memberId }?.second
        ?: memberOptions.firstOrNull()?.second ?: ""
    var memberLabel by remember { mutableStateOf(initialLabel) }
    var name by remember { mutableStateOf(existing?.medicationName ?: "") }
    var dosage by remember { mutableStateOf(existing?.dosage ?: "") }
    var timesText by remember {
        mutableStateOf(existing?.dailyTimes()?.joinToString(", ") ?: "08:00, 20:00")
    }
    var error by remember { mutableStateOf(false) }
    val defaultDosage = stringResource(R.string.reminders_default_dosage)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (existing == null) R.string.reminders_add_title
                    else R.string.reminders_edit_title
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (members.isNotEmpty()) {
                    DropdownSelector(
                        options = memberOptions.map { it.second },
                        selected = memberLabel,
                        label = stringResource(R.string.reminders_member_label),
                        onSelect = { memberLabel = it }
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = false },
                    label = { Text(stringResource(R.string.reminders_med_name)) },
                    isError = error,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dosage,
                    onValueChange = { dosage = it },
                    label = { Text(stringResource(R.string.reminders_dosage)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = timesText,
                    onValueChange = { timesText = it; error = false },
                    label = { Text(stringResource(R.string.reminders_times)) },
                    supportingText = { Text(stringResource(R.string.reminders_times_hint)) },
                    isError = error,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error) {
                    Text(
                        stringResource(R.string.reminders_form_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val member = memberOptions.firstOrNull { it.second == memberLabel }?.first
                val times = timesText.split("，", ",").map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (name.isBlank() || member == null || times.isEmpty() ||
                    times.any { !MedicationReminder.isValidTime(it) }) {
                    error = true
                } else {
                    onSave(member.id, name.trim(), dosage.trim().ifBlank { defaultDosage }, times)
                }
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
