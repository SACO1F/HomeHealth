package com.example.homehealth.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.homehealth.R
import com.example.homehealth.data.local.entity.FamilyMember
import com.example.homehealth.ui.navigation.FLOATING_CORNER
import com.example.homehealth.util.DateUtils
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 关系 code（数据库存储值）。旧版本直接存中文，读取时经 [normalize] 归一化 */
object Relationships {
    val CODES = listOf(
        "self", "spouse", "father", "mother", "father_in_law", "mother_in_law",
        "wife_father", "wife_mother", "elder_brother", "elder_sister",
        "younger_brother", "younger_sister", "son", "daughter", "other"
    )

    /** 旧版中文存储值 → code */
    private val legacyZhToCode = mapOf(
        "本人" to "self", "配偶" to "spouse", "父亲" to "father", "母亲" to "mother",
        "公公" to "father_in_law", "婆婆" to "mother_in_law",
        "岳父" to "wife_father", "岳母" to "wife_mother",
        "哥哥" to "elder_brother", "姐姐" to "elder_sister",
        "弟弟" to "younger_brother", "妹妹" to "younger_sister",
        "儿子" to "son", "女儿" to "daughter", "其他" to "other"
    )

    /** 存储值归一化为 code（已是 code 或未知值则原样返回） */
    fun normalize(stored: String): String = legacyZhToCode[stored] ?: stored
}

/** 关系显示名（本地化） */
@Composable
fun relationshipLabel(code: String): String = when (Relationships.normalize(code)) {
    "self" -> stringResource(R.string.rel_self)
    "spouse" -> stringResource(R.string.rel_spouse)
    "father" -> stringResource(R.string.rel_father)
    "mother" -> stringResource(R.string.rel_mother)
    "father_in_law" -> stringResource(R.string.rel_father_in_law)
    "mother_in_law" -> stringResource(R.string.rel_mother_in_law)
    "wife_father" -> stringResource(R.string.rel_wife_father)
    "wife_mother" -> stringResource(R.string.rel_wife_mother)
    "elder_brother" -> stringResource(R.string.rel_elder_brother)
    "elder_sister" -> stringResource(R.string.rel_elder_sister)
    "younger_brother" -> stringResource(R.string.rel_younger_brother)
    "younger_sister" -> stringResource(R.string.rel_younger_sister)
    "son" -> stringResource(R.string.rel_son)
    "daughter" -> stringResource(R.string.rel_daughter)
    else -> stringResource(R.string.rel_other)
}

/** 性别 code → 显示名（本地化） */
@Composable
fun genderLabel(code: String?): String = when (code) {
    "male" -> stringResource(R.string.gender_male)
    "female" -> stringResource(R.string.gender_female)
    "other" -> stringResource(R.string.gender_other)
    else -> ""
}

/** 成员下拉选项文本：「姓名（关系）」，显示与匹配统一使用本格式 */
@Composable
fun memberPickerLabel(name: String, relationship: String): String =
    stringResource(R.string.member_picker_pattern, name, relationshipLabel(relationship))

/** 性别选项（label ↔ code） */
val GENDERS = listOf("male" to "male", "female" to "female", "other" to "other")

/** 添加 / 编辑家庭成员对话框（含头像设置与健康档案） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberEditDialog(
    member: FamilyMember? = null,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        relationship: String,
        dateOfBirth: String?,
        gender: String?,
        heightCm: Double?,
        weightKg: Double?,
        avatarUrl: String?,
        health: MemberHealthInfo
    ) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(member?.name ?: "") }
    var relationship by remember { mutableStateOf(Relationships.normalize(member?.relationship ?: "self")) }
    var dob by remember { mutableStateOf(member?.dateOfBirth ?: "") }
    var genderCode by remember { mutableStateOf(member?.gender ?: "male") }
    var height by remember { mutableStateOf(member?.heightCm?.let { trimNum(it) } ?: "") }
    var weight by remember { mutableStateOf(member?.weightKg?.let { trimNum(it) } ?: "") }
    var avatarPath by remember { mutableStateOf(member?.avatarUrl ?: "") }
    var nameError by remember { mutableStateOf(false) }
    var dobError by remember { mutableStateOf(false) }
    var showDobPicker by remember { mutableStateOf(false) }

    // ---- 健康档案（均可空） ----
    var bloodType by remember { mutableStateOf(member?.bloodType) }
    var waist by remember { mutableStateOf(member?.waistCm?.let { trimNum(it) } ?: "") }
    var exercise by remember { mutableStateOf(member?.exercise) }
    var diet by remember { mutableStateOf(member?.diet) }
    var smoking by remember { mutableStateOf(member?.smoking) }
    var drinking by remember { mutableStateOf(member?.drinking) }
    var chronic by remember { mutableStateOf(member?.chronicConditions ?: "") }
    var surgery by remember { mutableStateOf(member?.surgeryHistory ?: "") }

    val pickAvatar = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { avatarPath = copyAvatarToPrivate(context, it) }
    }

    // 根据出生日期实时计算年龄（输入合法即显示）
    val ageText = remember(dob) {
        DateUtils.age(dob.trim())?.let { it.toString() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (member == null) stringResource(R.string.member_add_title) else stringResource(R.string.member_edit_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // 头像：点击选择照片
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MemberAvatar(
                        name = name.ifBlank { "?" },
                        avatarUrl = avatarPath.ifBlank { null },
                        size = 64
                    )
                    TextButton(onClick = {
                        pickAvatar.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) {
                        Icon(
                            Icons.Filled.AddAPhoto,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(stringResource(R.string.member_avatar_add))
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameError = false },
                    label = { Text(stringResource(R.string.member_name_label)) },
                    isError = nameError,
                    supportingText = { if (nameError) Text(stringResource(R.string.member_name_error)) },
                    singleLine = true,
                    shape = RoundedCornerShape(FLOATING_CORNER),
                    modifier = Modifier.fillMaxWidth()
                )
                val relOptions = Relationships.CODES.map { it to relationshipLabel(it) }
                val genderOptions = GENDERS.map { it.first to genderLabel(it.first) }
                // 关系 + 性别并排，省一行高度
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DropdownSelector(
                        options = relOptions.map { it.second },
                        selected = relationshipLabel(relationship),
                        label = stringResource(R.string.member_relationship_label),
                        onSelect = { label -> relationship = relOptions.first { it.second == label }.first },
                        modifier = Modifier.weight(1f)
                    )
                    DropdownSelector(
                        options = genderOptions.map { it.second },
                        selected = genderLabel(genderCode),
                        label = stringResource(R.string.member_gender_label),
                        onSelect = { label -> genderCode = genderOptions.first { it.second == label }.first },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { showDobPicker = true },
                        shape = RoundedCornerShape(FLOATING_CORNER),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (dob.isBlank()) stringResource(R.string.member_dob_pick)
                            else stringResource(R.string.member_dob_selected, dob))
                    }
                    if (dob.isNotBlank()) {
                        TextButton(onClick = { dob = ""; dobError = false }) {
                            Text(stringResource(R.string.member_dob_clear))
                        }
                    }
                }
                if (dobError) Text(stringResource(R.string.member_dob_error))
                else if (ageText != null) Text(stringResource(R.string.member_age_hint, ageText))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = height,
                        onValueChange = { height = filterNumber(it) },
                        label = { Text(stringResource(R.string.member_height_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(FLOATING_CORNER),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = weight,
                        onValueChange = { weight = filterNumber(it) },
                        label = { Text(stringResource(R.string.member_weight_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(FLOATING_CORNER),
                        modifier = Modifier.weight(1f)
                    )
                }

                // ---- 健康信息（参与健康问答上下文） ----
                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                Text(
                    stringResource(R.string.member_health_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                // 血型 + 腰围并排
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NullableDropdown(
                        label = stringResource(R.string.member_blood_type),
                        kind = ProfileKind.BLOOD_TYPE,
                        value = bloodType,
                        onSelect = { bloodType = it },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = waist,
                        onValueChange = { waist = filterNumber(it) },
                        label = { Text(stringResource(R.string.member_waist_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(FLOATING_CORNER),
                        modifier = Modifier.weight(1f)
                    )
                }
                // 运动 + 饮食并排
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NullableDropdown(
                        label = stringResource(R.string.member_exercise_label),
                        kind = ProfileKind.EXERCISE,
                        value = exercise,
                        onSelect = { exercise = it },
                        modifier = Modifier.weight(1f)
                    )
                    NullableDropdown(
                        label = stringResource(R.string.member_diet_label),
                        kind = ProfileKind.DIET,
                        value = diet,
                        onSelect = { diet = it },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NullableDropdown(
                        label = stringResource(R.string.member_smoking_label),
                        kind = ProfileKind.SMOKING,
                        value = smoking,
                        onSelect = { smoking = it },
                        modifier = Modifier.weight(1f)
                    )
                    NullableDropdown(
                        label = stringResource(R.string.member_drinking_label),
                        kind = ProfileKind.DRINKING,
                        value = drinking,
                        onSelect = { drinking = it },
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = chronic,
                    onValueChange = { chronic = it },
                    label = { Text(stringResource(R.string.member_chronic_label)) },
                    supportingText = { Text(stringResource(R.string.member_chronic_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(FLOATING_CORNER),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = surgery,
                    onValueChange = { surgery = it },
                    label = { Text(stringResource(R.string.member_surgery_label)) },
                    supportingText = { Text(stringResource(R.string.member_surgery_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(FLOATING_CORNER),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmedName = name.trim()
                val trimmedDob = dob.trim()
                val validDob = trimmedDob.isEmpty() ||
                    (DateUtils.parseDate(trimmedDob)?.let { it <= System.currentTimeMillis() } == true)
                if (trimmedName.isEmpty()) {
                    nameError = true
                } else if (!validDob) {
                    dobError = true
                } else {
                    onSave(
                        trimmedName,
                        relationship,
                        trimmedDob.ifBlank { null },
                        genderCode,
                        height.trim().toDoubleOrNull(),
                        weight.trim().toDoubleOrNull(),
                        avatarPath.ifBlank { null },
                        MemberHealthInfo(
                            bloodType = bloodType,
                            waistCm = waist.trim().toDoubleOrNull(),
                            exercise = exercise,
                            diet = diet,
                            smoking = smoking,
                            drinking = drinking,
                            chronicConditions = chronic.trim().ifBlank { null },
                            surgeryHistory = surgery.trim().ifBlank { null }
                        )
                    )
                }
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )

    if (showDobPicker) {
        val initial = runCatching {
            LocalDate.parse(dob).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { showDobPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { selected ->
                        val date = Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate()
                        if (!date.isAfter(LocalDate.now())) {
                            dob = date.toString()
                            dobError = false
                            showDobPicker = false
                        } else dobError = true
                    }
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDobPicker = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        ) { DatePicker(state = pickerState, showModeToggle = false) }
    }
}

/** 可空单选下拉：首位固定为「未填写」，选择它即清空该字段 */
@Composable
private fun NullableDropdown(
    label: String,
    kind: ProfileKind,
    value: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = buildList {
        add(null to profileUnsetLabel())
        kind.codes.forEach { code -> add(code to profileOptionLabel(kind, code)) }
    }
    val selectedLabel = options.firstOrNull { it.first == value }?.second
        ?: options.first().second
    DropdownSelector(
        options = options.map { it.second },
        selected = selectedLabel,
        label = label,
        onSelect = { picked -> onSelect(options.first { it.second == picked }.first) },
        modifier = modifier
    )
}

/** 把选中的头像复制到应用私有目录，返回本地路径（持久可用） */
private fun copyAvatarToPrivate(context: Context, uri: Uri): String {
    return try {
        val dir = File(context.filesDir, "avatars").apply { mkdirs() }
        val file = File(dir, "avatar_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: return ""
        file.absolutePath
    } catch (_: Exception) {
        ""
    }
}

/** 只保留数字与一个小数点 */
private fun filterNumber(input: String): String {
    val cleaned = input.filter { it.isDigit() || it == '.' }
    val firstDot = cleaned.indexOf('.')
    return if (firstDot >= 0) {
        cleaned.substring(0, firstDot + 1) + cleaned.substring(firstDot + 1).replace(".", "")
    } else cleaned
}

/** Double 转字符串去掉多余小数位（175.0 → 175） */
private fun trimNum(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

/** 下拉选择器 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DropdownSelector(
    options: List<T>,
    selected: T,
    label: String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected.toString(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            // 圆角与底部悬浮导航栏一致
            shape = RoundedCornerShape(FLOATING_CORNER),
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            // 弹窗圆角与底部悬浮导航栏一致
            shape = RoundedCornerShape(FLOATING_CORNER)
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.toString()) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
