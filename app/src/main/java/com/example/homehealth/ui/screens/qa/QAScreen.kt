package com.example.homehealth.ui.screens.qa

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.example.homehealth.R
import com.example.homehealth.data.local.entity.QAHistory
import com.example.homehealth.ui.components.MarkdownText
import com.example.homehealth.ui.components.memberPickerLabel
import com.example.homehealth.ui.navigation.FLOATING_NAV_RESERVE
import com.example.homehealth.util.DateUtils
import java.io.File

/** 健康问答页：聊天式界面，基于成员健康数据回答 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QAScreen(
    navController: NavHostController,
    viewModel: QAViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current

    // 附图：影像 / 病理这类叙述性报告没有对应的结构化指标，只能以图片提问
    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.attachImage(it) }
    }

    // LazyColumn 实际条目数：历史对话 + 待回答问题气泡 + 回答气泡（加载中 / 流式中）
    val bubbleCount = state.history.size +
        (if (state.pendingQuestion != null) 1 else 0) +
        (if (state.loading) 1 else 0)

    // 新消息（含待回答问题）时自动滚动到底部；流式生成期间跟随正文增长。
    // 生成中用 scrollToItem 而不是动画：每来一个 token 就打断上一次动画会让列表持续抖动。
    LaunchedEffect(bubbleCount, state.streamingAnswer.length) {
        if (bubbleCount <= 0) return@LaunchedEffect
        val last = bubbleCount - 1
        if (state.streamingAnswer.isEmpty()) {
            listState.animateScrollToItem(last)
        } else {
            listState.scrollToItem(last)
        }
    }

    Scaffold(
        // 同 SettingsScreen：外层已处理系统栏 inset，内层不再叠加（避免底部空带）
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            androidx.compose.material3.TopAppBar(title = { Text(stringResource(R.string.qa_title)) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 关键：消费掉 Scaffold 已使用的 inset，imePadding 只补键盘剩余高度——
                // 否则「导航栏 inset + 键盘 inset」叠加，输入框会悬空在键盘上方一截
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            // 成员选择 + 提示
            if (state.members.isNotEmpty()) {
                val memberOptions = state.members.map { it to memberPickerLabel(it.name, it.relationship) }
                com.example.homehealth.ui.components.DropdownSelector(
                    options = memberOptions.map { it.second },
                    selected = memberOptions
                        .firstOrNull { it.first.id == state.selectedMemberId }
                        ?.second ?: "",
                    label = stringResource(R.string.qa_member_label),
                    onSelect = { label ->
                        memberOptions.firstOrNull { it.second == label }
                            ?.let { viewModel.selectMember(it.first.id) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Text(
                    stringResource(R.string.qa_disclaimer),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // 消息列表
            Box(modifier = Modifier.weight(1f)) {
                if (state.history.isEmpty() && !state.loading) {
                    Text(
                        if (state.members.isEmpty()) stringResource(R.string.qa_empty_members)
                        else stringResource(R.string.qa_try_ask),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp)
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.history, key = { it.id }) { item ->
                        ChatBubble(item)
                    }
                    // 已发送待回答的问题：立即上屏，无需等待 LLM 返回
                    state.pendingQuestion?.let { q ->
                        item {
                            PendingQuestionBubble(q, state.streamingImagePath)
                        }
                    }
                    if (state.loading) {
                        item {
                            // 依据先到（本地检索），所以通常一闪就进流式气泡；只有检索阶段才显示纯加载态
                            if (state.hasStreaming) {
                                StreamingAnswerBubble(
                                    answer = state.streamingAnswer,
                                    thinking = state.streamingThinking,
                                    references = state.streamingReferences,
                                    tools = state.streamingTools
                                )
                            } else {
                                AnalyzingBubble()
                            }
                        }
                    }
                }
            }

            state.error?.let { msg ->
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // 待发送的附图：先给缩略图让用户确认，再连同问题一起发出
            state.pendingImagePath?.let { path ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = File(path),
                        contentDescription = stringResource(R.string.qa_image_cd),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Text(
                        stringResource(R.string.qa_image_cd),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp)
                    )
                    IconButton(onClick = { viewModel.removeAttachment() }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.qa_remove_image_cd),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // 输入栏（紧凑：48dp 高 + 14sp 文字；imePadding 使其始终位于输入法之上）。
            // 悬浮底部导航栏不再预留 Scaffold 空间：键盘收起时输入栏需主动避让
            // （键盘弹出时导航栏隐藏，避让随之取消，输入栏贴平键盘上沿）
            val imeOpen = WindowInsets.isImeVisible
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .padding(bottom = if (imeOpen) 0.dp else FLOATING_NAV_RESERVE),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        pickImageLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = !state.loading
                ) {
                    Icon(
                        Icons.Filled.Image,
                        contentDescription = stringResource(R.string.qa_attach_cd),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = {
                        Text(stringResource(R.string.qa_input_hint), style = MaterialTheme.typography.bodyMedium)
                    },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                )
                IconButton(
                    onClick = {
                        // 发起成功才清空输入；未发起（无成员/加载中）时保留文字
                        if (viewModel.ask(input)) {
                            input = ""
                            // 收起输入法，输入框随 imePadding 释放自动下沉到底部
                            keyboardController?.hide()
                        }
                    },
                    // 只发图不提问也是合法操作：图里往往就是要问的东西
                    enabled = (input.isNotBlank() || state.pendingImagePath != null) &&
                        !state.loading && state.members.isNotEmpty()
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.qa_send_cd),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/** 纯加载态：只在检索尚未产出依据、或首个增量还没到达时短暂出现 */
@Composable
private fun AnalyzingBubble() {
    Surface(
        shape = RoundedCornerShape(
            topStart = 4.dp, topEnd = 16.dp,
            bottomStart = 16.dp, bottomEnd = 16.dp
        ),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.widthIn(max = 300.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
            Text(
                stringResource(R.string.qa_analyzing),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * 流式回答气泡：思考过程 → 正文逐字上屏 → 数据依据。
 *
 * 排版顺序与落库后的 [ChatBubble] 一致，回答结束时气泡被历史条目原地替换，视觉上不跳。
 * 依据在正文之前就可见，是为了让用户在读到结论前先确认「它建立在哪些记录上」。
 */
@Composable
private fun StreamingAnswerBubble(
    answer: String,
    thinking: String,
    references: String,
    tools: List<QaToolStep>
) {
    Surface(
        shape = RoundedCornerShape(
            topStart = 4.dp, topEnd = 16.dp,
            bottomStart = 16.dp, bottomEnd = 16.dp
        ),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.widthIn(max = 320.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 工具轨迹放在最前：它解释了"这个回答是怎么查出来的"
            if (tools.isNotEmpty()) {
                ToolTraceBlock(steps = tools)
            }
            if (thinking.isNotBlank()) {
                ThinkingBlock(thinking = thinking)
            }
            if (answer.isNotBlank()) {
                // 半截 Markdown（未闭合的 ** 或列表）按字面渲染，闭合后才成形，不会闪出错版式
                MarkdownText(markdown = answer)
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        stringResource(R.string.qa_analyzing),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (references.isNotBlank()) {
                Text(
                    // 依据文本自带前置空行（拼进最终答案时用来分段），气泡里单独成块则去掉
                    text = references.trimStart('\n'),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp
                )
                Text(
                    stringResource(R.string.qa_generating),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 待回答的用户问题气泡（样式与历史问题气泡一致）；可只带图不提问 */
@Composable
private fun PendingQuestionBubble(question: String, imagePath: String?) {
    Surface(
        shape = RoundedCornerShape(
            topStart = 16.dp, topEnd = 4.dp,
            bottomStart = 16.dp, bottomEnd = 16.dp
        ),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.widthIn(max = 300.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (!imagePath.isNullOrBlank()) {
                AttachedImage(path = imagePath)
                if (question.isNotBlank()) Spacer8()
            }
            if (question.isNotBlank()) {
                Text(
                    text = question,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/** 气泡内的附图缩略图 */
@Composable
private fun AttachedImage(path: String) {
    AsyncImage(
        model = File(path),
        contentDescription = stringResource(R.string.qa_image_cd),
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(8.dp))
    )
}

@Composable
private fun ChatBubble(item: QAHistory) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        // 用户问题（可带附图，也可只发图）
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 4.dp,
                bottomStart = 16.dp, bottomEnd = 16.dp
            ),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!item.imagePath.isNullOrBlank()) {
                    AttachedImage(path = item.imagePath!!)
                    if (item.question.isNotBlank()) Spacer8()
                }
                if (item.question.isNotBlank()) {
                    Text(
                        text = item.question,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        Spacer8()

        // 助手回答
        Surface(
            shape = RoundedCornerShape(
                topStart = 4.dp, topEnd = 16.dp,
                bottomStart = 16.dp, bottomEnd = 16.dp
            ),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .align(Alignment.Start)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // 思考过程（深度思考模型返回，默认收起，点击展开）
                if (!item.thinking.isNullOrBlank()) {
                    ThinkingBlock(thinking = item.thinking!!)
                }
                // Markdown 渲染：LLM 回答的标题/列表/粗体/代码不再以裸符号显示
                MarkdownText(markdown = item.answer)
                if (!item.sources.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.qa_sources, item.sources),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Text(
                    text = DateUtils.formatDateTime(item.timestamp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/**
 * 工具调用轨迹：默认收起，展开后是「工具名 → 参数 / 返回摘要」的列表。
 *
 * 让用户看到回答**是怎么查出来的**，而不只是看到结论 —— 这与展示思考过程是同一个动机：
 * 健康问答的可信度来自可核对，而不是来自模型说得笃定。
 */
@Composable
private fun ToolTraceBlock(steps: List<QaToolStep>) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Build,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = stringResource(
                    if (expanded) R.string.qa_tools_hide else R.string.qa_tools_show,
                    steps.size
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        androidx.compose.animation.AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(8.dp)
            ) {
                steps.forEach { step ->
                    Text(
                        // 尾部的省略号表示"还在执行中"，避免用户以为卡住了
                        text = if (step.done) step.name else "${step.name} …",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (step.ok) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    Text(
                        text = step.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp, bottom = 6.dp)
                    )
                }
            }
        }
    }
}

/** 思考过程块：默认收起，点击「查看思考过程」展开 */
@Composable
private fun ThinkingBlock(thinking: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = stringResource(
                    if (expanded) R.string.qa_thinking_hide
                    else R.string.qa_thinking_show
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        androidx.compose.animation.AnimatedVisibility(visible = expanded) {
            Text(
                text = thinking,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                    .padding(8.dp)
            )
        }
    }
}

@Composable
private fun Spacer8() {
    androidx.compose.foundation.layout.Spacer(
        modifier = Modifier.padding(top = 4.dp)
    )
}
