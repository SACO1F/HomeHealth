package com.example.homehealth.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.homehealth.R
import com.example.homehealth.data.local.entity.HealthRecord
import com.example.homehealth.ui.navigation.FLOATING_CORNER
import com.example.homehealth.util.HealthTypes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 添加 / 编辑健康记录对话框（共享组件）：
 * - [fixedType] 为空且非编辑时显示指标类型选择器（个人档案手动录入）；
 * - [fixedType] 非空时锁定指标类型（指标详情页）；
 * - 编辑（[existing] 非空）时预填现有值，血压自动拆分为高压/低压。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordInputDialog(
    fixedType: String? = null,
    existing: HealthRecord? = null,
    /** 成员性别：用于按性别取参考范围做「异常值」提醒；未知时回退通用区间 */
    gender: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (
        type: String,
        primary: String,
        secondary: String?,
        dateText: String,
        notes: String?
    ) -> Unit
) {
    val isEdit = existing != null
    val typeLocked = fixedType != null || isEdit

    var selectedType by remember(existing) {
        mutableStateOf(existing?.type ?: fixedType ?: HealthTypes.BLOOD_PRESSURE)
    }
    // 编辑：预填现有值（血压拆分为高压/低压）
    var primary by remember(existing) {
        mutableStateOf(existing?.value?.split("/")?.firstOrNull()?.trim() ?: "")
    }
    var secondary by remember(existing) {
        mutableStateOf(existing?.value?.split("/")?.getOrNull(1)?.trim() ?: "")
    }
    var dateText by remember(existing) {
        mutableStateOf(
            existing?.recordDate?.let { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(it)) }
                ?: SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        )
    }
    var notes by remember(existing) { mutableStateOf(existing?.notes ?: "") }
    var showDatePicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    // 数值与参考范围相差数倍时要求再确认一次（针对多打一位、漏小数点这类笔误）
    var needsConfirm by remember { mutableStateOf(false) }

    val isBloodPressure = selectedType == HealthTypes.BLOOD_PRESSURE

    AlertDialog(
        onDismissRequest = onDismiss,
        // 与底部悬浮导航栏同一套观感：半透明底 + 大圆角 + 外圈阴影
        // （本主题 surface == background，只给半透明底而不给阴影会看起来「全透明」）
        modifier = Modifier.shadow(
            elevation = 12.dp,
            shape = RoundedCornerShape(FLOATING_CORNER)
        ),
        shape = RoundedCornerShape(FLOATING_CORNER),
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        title = {
            Text(
                when {
                    isEdit -> stringResource(R.string.record_edit_title, metricLabel(selectedType))
                    typeLocked -> stringResource(R.string.record_add_typed_title, metricLabel(selectedType))
                    else -> stringResource(R.string.record_add_generic_title)
                }
            )
        },
        text = {
            // 表单内容变化（如切换到血压、出现错误提示）时高度平滑过渡，而不是生硬跳变
            Column(
                modifier = Modifier.animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 未锁定类型时可选指标（49 项标准指标体系）
                if (!typeLocked) {
                    val typeOptions = HealthTypes.ALL.map { it to metricLabel(it) }
                    DropdownSelector(
                        options = typeOptions.map { it.second },
                        selected = metricLabel(selectedType),
                        label = stringResource(R.string.record_type_label),
                        onSelect = { label ->
                            selectedType = typeOptions.first { it.second == label }.first
                            error = false
                        }
                    )
                }
                if (isBloodPressure) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = primary,
                            onValueChange = { primary = it; error = false; needsConfirm = false },
                            label = { Text(stringResource(R.string.record_systolic)) },
                            isError = error,
                            singleLine = true,
                            // 圆角与底部悬浮导航栏一致
                            shape = RoundedCornerShape(FLOATING_CORNER),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = secondary,
                            onValueChange = { secondary = it; error = false; needsConfirm = false },
                            label = { Text(stringResource(R.string.record_diastolic)) },
                            isError = error,
                            singleLine = true,
                            // 圆角与底部悬浮导航栏一致
                            shape = RoundedCornerShape(FLOATING_CORNER),
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = primary,
                        onValueChange = { primary = it; error = false; needsConfirm = false },
                        label = { Text(stringResource(R.string.record_value_unit, HealthTypes.unit(selectedType))) },
                        isError = error,
                        singleLine = true,
                        // 圆角与底部悬浮导航栏一致
                        shape = RoundedCornerShape(FLOATING_CORNER),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    // 圆角与底部悬浮导航栏一致
                    shape = RoundedCornerShape(FLOATING_CORNER)
                ) {
                    Text(stringResource(R.string.record_measure_date, dateText))
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.record_notes_label)) },
                    singleLine = true,
                    // 圆角与底部悬浮导航栏一致
                    shape = RoundedCornerShape(FLOATING_CORNER),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error) {
                    Text(
                        stringResource(R.string.record_invalid_value),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                if (needsConfirm) {
                    Text(
                        stringResource(
                            R.string.record_value_suspicious,
                            HealthTypes.def(selectedType)?.rangeFor(gender)?.text.orEmpty()
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = primary.trim()
                val s = secondary.trim()
                val primaryOk = p.toDoubleOrNull() != null
                val secondaryOk = !isBloodPressure || s.toDoubleOrNull() != null
                when {
                    p.isEmpty() || !primaryOk || !secondaryOk -> error = true
                    // 数值远离参考范围数倍 → 先要一次确认，避免笔误被当成真实读数入库
                    !needsConfirm && isSuspiciousValue(selectedType, p, gender) -> needsConfirm = true
                    else -> onConfirm(selectedType, p, s.ifBlank { null }, dateText, notes)
                }
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        dateText = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(ms))
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/**
 * 数值是否"可疑"：与参考范围相差 [SUSPICIOUS_FACTOR] 倍以上。
 *
 * 用途是抓**录入笔误**（多打一位、漏小数点），不是判断异常 —— 真正偏高偏低的值只要在
 * 合理量级就应当直接保存，健康管理工具的核心功能正是记录这些异常值。
 * 因此宁可放宽阈值，也不要在正常偏差上打扰用户。
 */
private fun isSuspiciousValue(type: String, primary: String, gender: String?): Boolean {
    val ref = HealthTypes.def(type)?.rangeFor(gender) ?: return false
    val v = primary.toDoubleOrNull() ?: return false
    val tooHigh = ref.high?.let { it > 0 && v > it * SUSPICIOUS_FACTOR } ?: false
    val tooLow = ref.low?.let { it > 0 && v < it / SUSPICIOUS_FACTOR } ?: false
    return tooHigh || tooLow
}

/** 偏离参考范围多少倍算"可疑"（针对笔误，故取值宽松） */
private const val SUSPICIOUS_FACTOR = 3.0
