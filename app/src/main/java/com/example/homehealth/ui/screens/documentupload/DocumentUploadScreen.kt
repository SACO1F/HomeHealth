package com.example.homehealth.ui.screens.documentupload

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.example.homehealth.R
import com.example.homehealth.data.local.entity.MedicalDocument
import com.example.homehealth.ui.components.ParseStatusBadge
import com.example.homehealth.ui.components.memberPickerLabel
import com.example.homehealth.util.DateUtils
import com.example.homehealth.util.HealthTypes
import java.io.File

/** 文档上传页：拍照/相册选择 → 解析 → 编辑确认 → 入库 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentUploadScreen(
    navController: NavHostController,
    viewModel: DocumentUploadViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val savedDoneText = stringResource(R.string.upload_saved_done)
    var showRawText by remember { mutableStateOf(false) }

    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            viewModel.pendingCameraUri?.let { viewModel.onImageReady(it) }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.prepareCameraCapture(context)?.let { takePictureLauncher.launch(it) }
        } else {
            // 无相机权限时提示
        }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.onImageReady(it) }
    }

    // 保存完成后返回
    LaunchedEffect(state.phase) {
        when (state.phase) {
            UploadPhase.DONE -> {
                snackbarHostState.showSnackbar(savedDoneText)
                viewModel.resetToIdle()
                navController.popBackStack()
            }
            else -> Unit
        }
    }

    Scaffold(
        // 同 SettingsScreen：外层已处理系统栏 inset，内层不再叠加（避免底部空带）
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(stringResource(R.string.upload_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 成员选择
            if (state.members.size > 1) {
                item {
                    val memberOptions = state.members.map { it to memberPickerLabel(it.name, it.relationship) }
                    if (state.currentDocument != null) {
                        Text(memberOptions.firstOrNull { it.first.id == state.currentDocument?.memberId }
                            ?.second.orEmpty())
                    } else com.example.homehealth.ui.components.DropdownSelector(
                        options = memberOptions.map { it.second },
                        selected = memberOptions
                            .firstOrNull { it.first.id == state.selectedMemberId }
                            ?.second ?: "",
                        label = stringResource(R.string.upload_archive_member),
                        onSelect = { label ->
                            memberOptions.firstOrNull { it.second == label }
                                ?.let { viewModel.selectMember(it.first.id) }
                        }
                    )
                }
            }

            // 图片预览
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (state.imagePath != null) {
                            AsyncImage(
                                model = File(state.imagePath!!),
                                contentDescription = stringResource(R.string.upload_image_cd),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(R.string.upload_placeholder),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.CAMERA
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) {
                                        viewModel.prepareCameraCapture(context)
                                            ?.let { takePictureLauncher.launch(it) }
                                    } else {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = state.phase == UploadPhase.IDLE || state.phase == UploadPhase.ERROR
                            ) {
                                Icon(
                                    Icons.Filled.PhotoCamera,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                                Text(stringResource(R.string.upload_take_photo))
                            }
                            OutlinedButton(
                                onClick = {
                                    pickImageLauncher.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                enabled = state.phase == UploadPhase.IDLE || state.phase == UploadPhase.ERROR
                            ) {
                                Icon(
                                    Icons.Filled.PhotoLibrary,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                                Text(stringResource(R.string.upload_pick_photo))
                            }
                        }
                    }
                }
            }

            // 解析进度
            if (state.phase == UploadPhase.SAVING || state.phase == UploadPhase.PARSING ||
                state.phase == UploadPhase.CONFIRMING
            ) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                when (state.phase) {
                                    UploadPhase.SAVING -> stringResource(R.string.upload_saving)
                                    UploadPhase.PARSING -> stringResource(R.string.upload_parsing)
                                    else -> stringResource(R.string.upload_confirming)
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            // 标明解析引擎：Vision 大模型（含模型名）+ JSON 结构化提取
                            if (state.phase == UploadPhase.PARSING && state.parseEngine.isNotBlank()) {
                                Text(
                                    state.parseEngine,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            // 错误提示
            if (state.errorMessage != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                state.errorMessage ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (state.phase == UploadPhase.ERROR) {
                                viewModel.pendingCameraUri?.let { source ->
                                    TextButton(onClick = { viewModel.onImageReady(source) }) {
                                        Text(stringResource(R.string.upload_retry_save))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 可编辑解析结果
            if (state.phase == UploadPhase.PARSED && state.editableRecords.isNotEmpty()) {
                item {
                    Column {
                        Text(
                            stringResource(R.string.upload_result_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        val engine = state.parseEngine.ifBlank {
                            stringResource(R.string.upload_engine_fallback)
                        }
                        val converted = state.editableRecords.count { it.normalizationNote != null }
                        val normalized = if (converted > 0) {
                            stringResource(R.string.upload_normalized_count, converted)
                        } else {
                            stringResource(R.string.upload_normalized_default)
                        }
                        Text(
                            stringResource(
                                R.string.upload_count_summary,
                                engine, state.editableRecords.size, normalized
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                items(state.editableRecords, key = { it.id }) { record ->
                    EditableRecordCard(
                        record = record,
                        onChange = { newRecord ->
                            viewModel.updateEditableRecord(record.id, { newRecord })
                            newRecord
                        },
                        onRemove = { viewModel.removeEditableRecord(record.id) }
                    )
                }

                item {
                    Button(
                        onClick = { viewModel.confirmRecords() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.upload_confirm_save, state.editableRecords.size))
                    }
                }
                if (state.rawText.isNotBlank()) {
                    item {
                        TextButton(onClick = { showRawText = !showRawText }) {
                            Text(
                                stringResource(
                                    if (showRawText) R.string.upload_hide_raw
                                    else R.string.upload_show_raw
                                )
                            )
                        }
                    }
                    if (showRawText) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    state.rawText,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 解析历史
            if (state.documents.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.upload_history),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(state.documents, key = { it.id }) { document ->
                    DocumentHistoryRow(
                        document = document,
                        onRetry = { viewModel.retryParse(document) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EditableRecordCard(
    record: EditableRecord,
    onChange: (EditableRecord) -> EditableRecord,
    onRemove: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val typeOptions = HealthTypes.ALL.map { it to com.example.homehealth.ui.components.metricLabel(it) }
                com.example.homehealth.ui.components.DropdownSelector(
                    options = typeOptions.map { it.second },
                    selected = com.example.homehealth.ui.components.metricLabel(record.type),
                    label = stringResource(R.string.upload_metric_label),
                    onSelect = { label ->
                        val type = typeOptions.first { it.second == label }.first
                        onChange(record.copy(type = type))
                    },
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.common_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 归一化换算说明（如「nmol/L 已换算为 ng/mL」）
            record.normalizationNote?.let { note ->
                Text(
                    stringResource(R.string.upload_normalized_note, note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = record.value,
                    onValueChange = { onChange(record.copy(value = it)) },
                    label = { Text(stringResource(R.string.upload_value_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1.4f)
                )
                OutlinedTextField(
                    value = record.unit,
                    onValueChange = { onChange(record.copy(unit = it)) },
                    label = { Text(stringResource(R.string.upload_unit_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = record.date,
                onValueChange = { onChange(record.copy(date = it)) },
                label = { Text(stringResource(R.string.upload_date_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DocumentHistoryRow(
    document: MedicalDocument,
    onRetry: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    document.fileName,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    DateUtils.formatDateTime(document.uploadDate),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ParseStatusBadge(status = document.parseStatus)
            when (document.parseStatus) {
                com.example.homehealth.data.local.entity.ParseStatus.FAILED ->
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                // 已完成的文档也允许重新解析（如记录被误删后恢复）
                com.example.homehealth.data.local.entity.ParseStatus.COMPLETED ->
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.upload_reparse)) }
                else -> {}
            }
        }
    }
}
