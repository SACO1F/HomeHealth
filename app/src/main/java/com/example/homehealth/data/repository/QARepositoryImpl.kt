package com.example.homehealth.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.homehealth.data.SettingsPrefs
import com.example.homehealth.data.local.dao.HealthRecordDao
import com.example.homehealth.data.local.dao.QAHistoryDao
import com.example.homehealth.data.local.entity.FamilyMember
import com.example.homehealth.data.local.entity.profileSummary
import com.example.homehealth.data.local.entity.HealthRecord
import com.example.homehealth.data.local.entity.QAHistory
import com.example.homehealth.data.remote.AgentEvent
import com.example.homehealth.data.remote.LlmClient
import com.example.homehealth.data.remote.LlmProviders
import com.example.homehealth.data.remote.LocalQaEngine
import com.example.homehealth.data.remote.QaRetriever
import com.example.homehealth.data.remote.ReActAgent
import com.example.homehealth.domain.repository.QARepository
import com.example.homehealth.domain.repository.QaStreamEvent
import com.example.homehealth.domain.tool.ToolContext
import com.example.homehealth.util.DateUtils
import com.example.homehealth.util.FileUtils
import com.example.homehealth.util.HealthTypes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QARepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val qaHistoryDao: QAHistoryDao,
    private val healthRecordDao: HealthRecordDao,
    private val settingsPrefs: SettingsPrefs,
    private val localQaEngine: LocalQaEngine,
    private val llmClient: LlmClient,
    private val reActAgent: ReActAgent
) : QARepository {

    override fun observeHistory(memberId: String): Flow<List<QAHistory>> =
        qaHistoryDao.observeByMember(memberId)

    override suspend fun getAllHistory(): List<QAHistory> = qaHistoryDao.getAll()

    override suspend fun clearHistory(memberId: String) = qaHistoryDao.clearByMember(memberId)

    /**
     * 流式提问。
     *
     * 事件顺序：**依据 → （工具轨迹）→ 正文 → 落库完成**。
     * 依据放在第一步不只是为了好看：它来自本地检索、不依赖网络，所以总能最先就绪；
     * 而模型侧失败时依据依然有效，用户至少知道"系统手里有哪些数据"。
     *
     * 两条路径（见 [useAgent] 处的注释）：命中强指标词走快路径，否则才交给 Agent。
     */
    override fun askStream(
        member: FamilyMember,
        question: String,
        imagePath: String?
    ): Flow<QaStreamEvent> = flow {
        // 汇总成员健康记录（供 AI 上下文与本地引擎共用）：
        // 单次查询取「每指标最近 N 条」+ 内存分组，避免逐指标 N+1；
        // 查询本身带每组 Top-N 限制，控制结果集上限。
        // 注意：**不按 HealthTypes.def 过滤**——解析确认页入库的都是用户核对过的真实数据，
        // 字典外指标（少见项目）被静默丢弃曾导致「导入多个指标、问答只见一个」；
        // label / 单位 / 参考范围对未知类型均有兜底渲染（显示原始类型名、参考范围"—"）。
        val recordsByType = healthRecordDao
            .getRecentPerTypeByMember(member.id, RECORDS_PER_TYPE)
            .groupBy { it.type }
            .mapValues { (_, list) -> list.take(RECORDS_PER_TYPE) }

        // ---- A1 真实检索：BM25 Top-K 召回 + 引用溯源 ----
        // 闸门：问题里必须出现「强指标词」（指标名 / 别名 / 成组词，见 QaRetriever.strongTermsOf）
        // 才走检索。泛化总结类问题（"整体健康状况怎么样"）没有可靠的指标词，若放行检索，
        // 弱字匹配（"身体"的"体"撞上体重记录）会把上下文窄化成一两个指标——
        // 此时回退「每指标最近 N 条」全量摘要，行为与检索上线前完全一致。
        val strongTerms = QaRetriever.strongTermsOf(question)
        val corpus = healthRecordDao.getAllByMember(member.id)
        val hits = if (corpus.isEmpty() || strongTerms.isEmpty()) {
            emptyList()
        } else {
            QaRetriever.index(corpus).search(question, RETRIEVE_TOP_K, restrictTo = strongTerms)
        }

        // 快路径的「数据依据」：检索命中给记录明细，泛化问题给数据范围（两条都不留空）
        val searchReferences = if (hits.isEmpty()) {
            buildSummaryReferences(member, recordsByType)
        } else {
            buildRetrievedReferences(member, hits)
        }

        // 附图：压缩成 base64 交给视觉模型。压缩放在数据层而不是界面层 ——
        // 界面就不必持有几 MB 的字符串，进程重建时也只需要一个文件路径。
        val imageBase64 = imagePath?.let { path ->
            runCatching { FileUtils.compressImageToBase64(File(path)) }
                .onFailure { Log.w(TAG, "附图读取失败，本轮按纯文本提问", it) }
                .getOrNull()
        }
        // 带图时先确认有没有可用的视觉模型：**有图却发不出去必须明确告知**，
        // 静默降级成纯文本回答会让用户以为模型看过图了 —— 健康场景里这个误会的代价很高。
        val visionRoute = if (imageBase64 != null) llmClient.qaVisionRoute() else null
        val noVision = imageBase64 != null && visionRoute == null
        val imageUsed = imageBase64 != null && !noVision

        val llmReady = LlmProviders.isDirect(settingsPrefs.qaProvider) && llmClient.qaConfigured()

        // 个人健康档案（血型 / 生活方式 / 慢性病史 / 手术史，用户自述）同样是一份可用上下文：
        // 即便该成员还没有任何记录，只要档案非空就值得让模型结合它作答
        // （如「我这种饮食习惯要注意什么」）—— 否则会落到离线引擎、档案被完全忽略。
        val profileText = member.profileSummary()
        val hasProfile = profileText.isNotBlank()
        val hasData = recordsByType.isNotEmpty() || imageBase64 != null || hasProfile

        // 路由：命中强指标词 → 快路径；没有可靠指标词（泛化 / 需要多步交叉验证）→ Agent 路径。
        // 快路径**刻意保留**：对"指向具体指标"的问题，Agent 只会把 1 次请求变成 2~5 次而质量不变
        // —— 用 Agent 替换整条链路是负优化，这是本设计里最要紧的一条判断。
        // 记录、附图、个人档案三者都没有时才交给本地引擎：那种情况下一句"请先录入"就够了。
        val useAgent = strongTerms.isEmpty() && !noVision && llmReady && hasData

        val answerText = StringBuilder()
        val thinkingText = StringBuilder()
        var references = searchReferences
        var llmFailed = false
        var truncated = false
        val sources: List<String>

        when {
            noVision -> {
                // 有图但没有可用的视觉模型：明确告知，而不是把图片丢掉、让用户以为模型看过图
                if (references.isNotBlank()) emit(QaStreamEvent.References(references))
                answerText.append(NO_VISION_MESSAGE)
                emit(QaStreamEvent.Answer(NO_VISION_MESSAGE))
                sources = listOf("未发送图片：当前配置没有可用的视觉模型")
            }

            useAgent -> {
                // ---- Agent 路径：依据要等工具跑完才知道，因此这里先置空 ----
                references = ""
                var evidence: List<String> = emptyList()
                var usedTools: List<String> = emptyList()
                try {
                    reActAgent
                        .run(
                            context = ToolContext(member, question, imageBase64),
                            // 只给"有哪些数据"的范围概览，不给明细：明细要靠工具去取，
                            // 否则等于把全量摘要又塞回上下文，Agent 就没有意义了
                            recordsOverview = buildRecordsOverview(recordsByType)
                        )
                        .collect { event ->
                            when (event) {
                                is AgentEvent.Answer -> answerText.append(event.delta)

                                is AgentEvent.Thinking -> {
                                    thinkingText.append(event.delta)
                                    emit(QaStreamEvent.Thinking(event.delta))
                                }

                                is AgentEvent.ToolCallStarted ->
                                    emit(QaStreamEvent.ToolCall(event.name, event.argsSummary))

                                is AgentEvent.ToolCallFinished ->
                                    emit(QaStreamEvent.ToolResult(event.name, event.ok, event.summary))

                                is AgentEvent.Completed -> {
                                    evidence = event.evidence
                                    usedTools = event.toolNames
                                    if (event.truncated) truncated = true
                                }
                            }
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    llmFailed = true
                    Log.w(TAG, "Agent 路径失败，回退本地规则引擎", e)
                }

                if (answerText.isEmpty()) {
                    // Agent 没产出回答（请求失败）：交给本地引擎，而不是把半截 observation 丢给用户
                    val (text, src) = localQaEngine.answer(member.name, recordsByType, question)
                    references = searchReferences
                    if (references.isNotBlank()) emit(QaStreamEvent.References(references))
                    answerText.append(text)
                    emit(QaStreamEvent.Answer(text))
                    sources = src + AGENT_FALLBACK_NOTE
                } else {
                    // 依据直接复用工具返回的明细原文，与模型看到的内容逐字一致
                    references = buildAgentReferences(evidence, member)
                    if (references.isNotBlank()) emit(QaStreamEvent.References(references))
                    if (truncated) {
                        answerText.append("\n\n").append(TRUNCATED_NOTICE)
                    }
                    emit(QaStreamEvent.Answer(answerText.toString()))
                    val toolNote = if (usedTools.isEmpty()) {
                        "未调用工具"
                    } else {
                        "工具 ${usedTools.size} 次：${usedTools.joinToString("、")}"
                    }
                    sources = listOf("${LlmProviders.nameOf(settingsPrefs.qaProvider)} · Agent · $toolNote")
                }
            }

            // ---- 快路径：与 Agent 上线前完全一致（回归基准）----
            llmReady && hasData -> {
                if (references.isNotBlank()) emit(QaStreamEvent.References(references))
                try {
                    llmClient.askHealthQuestionStream(
                        memberName = member.name,
                        recordsSummary = if (hits.isEmpty()) {
                            buildRecordsSummary(member, recordsByType)
                        } else {
                            buildRetrievedSummary(member, hits)
                        },
                        question = question,
                        // 检索路径下要求模型在数值后标注 [n]，让答案里的每个数字都能追到具体记录
                        requireCitation = hits.isNotEmpty(),
                        imageBase64 = imageBase64
                    ).collect { chunk ->
                        when (chunk) {
                            is LlmClient.LlmStreamChunk.Answer -> {
                                answerText.append(chunk.delta)
                                emit(QaStreamEvent.Answer(chunk.delta))
                            }
                            is LlmClient.LlmStreamChunk.Thinking -> {
                                thinkingText.append(chunk.delta)
                                emit(QaStreamEvent.Thinking(chunk.delta))
                            }
                            is LlmClient.LlmStreamChunk.ToolCalls -> Unit // 快路径不带工具
                            LlmClient.LlmStreamChunk.Truncated -> truncated = true
                        }
                    }
                } catch (e: CancellationException) {
                    // 用户主动取消（退出页面）：不算失败，也不该落库半截回答
                    throw e
                } catch (e: Exception) {
                    llmFailed = true
                    Log.w(TAG, "LLM(${LlmProviders.nameOf(settingsPrefs.qaProvider)}) 流式调用失败", e)
                }

                if (answerText.isEmpty()) {
                    val (text, src) = localQaEngine.answer(member.name, recordsByType, question)
                    answerText.append(text)
                    emit(QaStreamEvent.Answer(text))
                    // 明确标注：回答并非来自 AI，避免用户误以为读到的是大模型结论
                    sources = if (llmFailed) src + LOCAL_FALLBACK_NOTE else src
                } else {
                    // 已经收到内容：**中途失败或截断都不丢弃已生成的回答**（用户已经读到了），
                    // 改为在末尾追加显式说明 —— 比清空重来或静默截断都更诚实
                    val notice = when {
                        truncated -> TRUNCATED_NOTICE
                        llmFailed -> INTERRUPTED_NOTICE
                        else -> null
                    }
                    if (notice != null) {
                        answerText.append("\n\n").append(notice)
                        emit(QaStreamEvent.Answer("\n\n$notice"))
                    }
                    val mode = if (hits.isEmpty()) "全量摘要" else "关键词检索 Top-${hits.size}"
                    // 用了别家的视觉模型就必须说清：用户配了 A 家却在回答来源里看到 B 家，不说会被当成 bug
                    val visionNote = if (visionRoute?.source == LlmClient.VisionSource.PARSE_SERVICE) {
                        " · 图片由报告解析服务（${LlmProviders.nameOf(visionRoute.providerId)} · ${visionRoute.model}）读取"
                    } else {
                        ""
                    }
                    sources = listOf("${LlmProviders.nameOf(settingsPrefs.qaProvider)} · 流式 · $mode$visionNote")
                }
            }

            else -> {
                // 本地模式 / 未配置 Key / 该成员还没有记录：离线规则引擎
                if (references.isNotBlank()) emit(QaStreamEvent.References(references))
                val (text, src) = localQaEngine.answer(member.name, recordsByType, question)
                answerText.append(text)
                emit(QaStreamEvent.Answer(text))
                sources = src
            }
        }

        val history = QAHistory(
            id = UUID.randomUUID().toString(),
            memberId = member.id,
            question = question,
            // 依据统一追加在答案末尾：三条路径行为一致，历史条目自带出处
            answer = answerText.toString() + references,
            timestamp = System.currentTimeMillis(),
            // 只记住真正送进模型的那张图：没送出去的图留在历史里会让人以为它参与了分析
            imagePath = if (imageUsed) imagePath else null,
            sources = sources.joinToString("\n").ifBlank { null },
            thinking = thinkingText.toString().takeIf { it.isNotBlank() }
        )
        qaHistoryDao.insert(history)
        emit(QaStreamEvent.Finished(history))
    }.flowOn(Dispatchers.IO)

    // ---------- 附件的文件生命周期 ----------

    override suspend fun saveAttachment(uri: Uri): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, ATTACHMENT_DIR).apply { mkdirs() }
        val dest = File(dir, "qa_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("无法读取所选图片")
        dest.absolutePath
    }

    override suspend fun discardAttachment(path: String) = withContext(Dispatchers.IO) {
        // 只删自己目录内的文件：路径来自上层，目录外的路径一律不动
        val file = File(path).absoluteFile
        if (file.parentFile == File(context.filesDir, ATTACHMENT_DIR).absoluteFile && file.exists()) {
            runCatching { file.delete() }
        }
        Unit
    }

    override suspend fun cleanupOrphanAttachments() = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, ATTACHMENT_DIR)
        if (!dir.isDirectory) return@withContext
        val referenced = runCatching { qaHistoryDao.getAllImagePaths() }
            .getOrDefault(emptyList())
            .mapTo(HashSet<String>()) { File(it).absolutePath }
        val cutoff = System.currentTimeMillis() - ATTACHMENT_GRACE_MS
        dir.listFiles().orEmpty().forEach { file ->
            // 静置期：不误删「刚选中、正准备发送」的那张
            if (!file.isFile || file.absolutePath in referenced || file.lastModified() > cutoff) {
                return@forEach
            }
            runCatching { file.delete() }
        }
        Unit
    }

    // ---------- 上下文构建 ----------

    /**
     * 把健康记录整理为 AI 可读的文本摘要：
     * 优先使用每条记录实际保存的单位（报告原始单位，如维生素 D 的 nmol/L），
     * 仅在记录无单位时回退到预设单位；两者不一致时明确提示参考范围不可直接比较，
     * 防止模型把 nmol/L 读数当成 ng/mL 来解读。
     * 受 [SUMMARY_CHAR_BUDGET] 字符预算约束：超预算时停止追加并显式标注省略，
     * 避免 49 项 × 10 条记录一次性超出模型上下文窗口。
     */
    private fun buildRecordsSummary(
        member: FamilyMember,
        recordsByType: Map<String, List<HealthRecord>>
    ): String =
        buildString {
            var remaining = SUMMARY_CHAR_BUDGET
            // 个人健康档案（血型 / 腰围 / 生活方式 / 病史）：静态背景，先于记录明细给出，
            // 让模型在解读数值时能结合吸烟饮酒、慢性病等背景
            member.profileSummary().takeIf { it.isNotBlank() }?.let { profile ->
                appendLine("【个人健康档案】（用户自述，静态背景）")
                profile.lineSequence().forEach { line ->
                    appendLine("  $line")
                    remaining -= line.length + 3
                }
                appendLine()
                remaining -= 20
            }
            for ((type, list) in recordsByType) {
                if (remaining <= 0) break
                val def = HealthTypes.def(type)
                val defaultUnit = HealthTypes.unit(type)
                val actualUnit = list.firstOrNull { it.unit.isNotBlank() }
                    ?.unit?.trim()?.takeIf { it.isNotBlank() }
                // 无单位指标（如骨密度 T 值）与字典外类型（defaultUnit 为空串）不算单位不一致
                val unitMismatch = actualUnit != null && defaultUnit.isNotBlank() && actualUnit != defaultUnit

                // 参考范围按性别取：血红蛋白 / 肌酐 / 尿酸等男女不同，用合并区间会误导模型与标注
                val ref = def?.rangeFor(member.gender)
                val header =
                    "【${HealthTypes.label(type)}】（单位：${actualUnit ?: defaultUnit}，参考范围：${ref?.text ?: HealthTypes.range(type)}）"
                if (!appendWithinBudget(header, remaining)) break
                remaining -= header.length + 1

                if (unitMismatch) {
                    val note =
                        "  注意：记录单位为「$actualUnit」，参考范围基于「$defaultUnit」，两者单位不同，数值不可直接比较。"
                    if (!appendWithinBudget(note, remaining)) break
                    remaining -= note.length + 1
                }

                for (r in list.take(10)) {
                    // 单位与参考范围不一致时跳过偏高/偏低标注，避免跨单位误判；
                    // 参考区间按成员性别取，标注口径与异常检测保持一致
                    val flag = if (unitMismatch) "" else {
                        val v = r.numericValue
                        when {
                            v == null -> ""
                            ref?.high != null && v > ref.high -> "（偏高）"
                            ref?.low != null && v < ref.low -> "（偏低）"
                            else -> ""
                        }
                    }
                    val unitText = r.unit.trim().takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
                    val line = "  ${DateUtils.formatDate(r.recordDate)}：${r.value}$unitText$flag"
                    if (line.length + 1 > remaining) {
                        appendLine("  …其余记录因上下文预算已省略")
                        remaining = 0
                        break
                    }
                    appendLine(line)
                    remaining -= line.length + 1
                }
            }
        }

    /**
     * A1 真实检索路径：把 BM25 召回的 Top-K 记录整理为带引用编号（[n]）的上下文。
     * 与 [buildRecordsSummary] 的差异：只包含与问题相关的记录，且每条都有稳定编号，
     * 模型可以在回答里引用编号，答案末尾的引用明细（[buildRetrievedReferences]）与之对应。
     */
    private fun buildRetrievedSummary(
        member: FamilyMember,
        hits: List<QaRetriever.Scored>
    ): String = buildString {
        var remaining = SUMMARY_CHAR_BUDGET
        appendLine("以下是与问题相关度最高的 ${hits.size} 条健康记录（成员：${member.name}），行首 [n] 为引用编号：")
        // 个人健康档案：检索路径同样给出静态背景，口径与全量摘要路径一致
        member.profileSummary().takeIf { it.isNotBlank() }?.let { profile ->
            appendLine("个人健康档案（用户自述，静态背景）：")
            profile.lineSequence().forEach { line -> appendLine("  $line") }
            remaining -= profile.length
        }
        for (hit in hits) {
            if (remaining <= 0) break
            val r = hit.record
            val label = HealthTypes.label(r.type)
            val ref = HealthTypes.def(r.type)?.rangeFor(member.gender)
            val unitText = r.unit.trim().ifBlank { HealthTypes.unit(r.type) }
            val line = buildString {
                append("[${hit.rank}] 【$label】${DateUtils.formatDate(r.recordDate)}：${r.value}")
                if (unitText.isNotBlank()) append(" $unitText")
                ref?.let { append("（参考 ${it.text}）") }
                r.notes?.trim()?.takeIf { it.isNotBlank() }?.let { append("；备注：$it") }
            }
            if (line.length + 1 > remaining) {
                appendLine("…其余记录因上下文预算已省略")
                break
            }
            appendLine(line)
            remaining -= line.length + 1
        }
    }

    /**
     * Agent 路径的范围概览：只告诉模型"手里有多少数据、覆盖哪些指标"，不给明细。
     * 明细必须靠工具取 —— 否则等于把全量摘要又塞回上下文，Agent 就白做了。
     */
    private fun buildRecordsOverview(recordsByType: Map<String, List<HealthRecord>>): String {
        val total = recordsByType.values.sumOf { it.size }
        if (total == 0) return ""
        val metrics = recordsByType.keys.sortedBy { HealthTypes.label(it) }
            .joinToString("、") { "${HealthTypes.label(it)}(${recordsByType.getValue(it).size})" }
        return "$total 条记录，覆盖指标：$metrics"
    }

    /**
     * 「数据依据」——检索路径：列出 BM25 召回的 Top-K 记录，编号与上下文里的 `[n]` 一一对应。
     * 带参考范围与偏高/偏低标注，用户可以直接拿它核对模型引用的每一个数字。
     */
    private fun buildRetrievedReferences(member: FamilyMember, hits: List<QaRetriever.Scored>): String =
        buildString {
            appendLine()
            appendLine("———")
            appendLine("📎 数据依据（按相关度从已保存记录中检索出 ${hits.size} 条，编号与上文 [n] 对应）：")
            appendProfile(member.profileSummary())
            for (hit in hits) {
                val r = hit.record
                val unitText = r.unit.trim().ifBlank { HealthTypes.unit(r.type) }
                append("[${hit.rank}] ${HealthTypes.label(r.type)} · ${DateUtils.formatDate(r.recordDate)} · ${r.value}")
                if (unitText.isNotBlank()) append(" $unitText")
                HealthTypes.def(r.type)?.rangeFor(member.gender)?.let { append("（参考 ${it.text}）") }
                append(rangeFlag(r, member))
                appendLine()
            }
        }

    /**
     * 「数据依据」——全量摘要路径：泛化问题（"整体健康状况怎么样"）无法只依据少数记录回答，
     * 逐条列出几十项明细只会稀释重点，因此改为把**数据范围**说清楚：多少条记录、覆盖哪些指标。
     */
    private fun buildSummaryReferences(
        member: FamilyMember,
        recordsByType: Map<String, List<HealthRecord>>
    ): String = buildString {
        val total = recordsByType.values.sumOf { it.size }
        val profile = member.profileSummary()
        // 有档案也算「有依据」：成员还没录任何记录时，档案就是这次回答的全部上下文
        if (total == 0 && profile.isBlank()) return@buildString
        appendLine()
        appendLine("———")
        appendLine("📎 数据依据（该问题未指向具体指标，按全部已保存记录汇总）：")
        appendProfile(profile)
        if (total > 0) {
            appendLine("　成员：${member.name} ｜ 记录 $total 条 ｜ 覆盖 ${recordsByType.size} 项指标")
            append("　参与分析的指标：")
            appendLine(
                recordsByType.keys.sortedBy { HealthTypes.label(it) }
                    .joinToString("、") { "${HealthTypes.label(it)}×${recordsByType.getValue(it).size}" }
            )
        } else {
            appendLine("　成员：${member.name} ｜ 暂无健康记录，本次仅依据个人健康档案")
        }
    }

    /**
     * 「数据依据」——Agent 路径：直接复用检索类工具返回的明细原文。
     *
     * 为什么不重新组织一遍：**模型看到的就是这段文本**，逐字复用才能保证
     * "答案里引用的数字"与"依据里列出的数字"完全对得上；另起一条数据通路
     * 很容易出现两边口径不一致，而用户是拿依据去核对答案的。
     */
    private fun buildAgentReferences(evidence: List<String>, member: FamilyMember): String {
        val blocks = evidence.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val profile = member.profileSummary()
        if (blocks.isEmpty() && profile.isBlank()) return ""
        return buildString {
            appendLine()
            appendLine("———")
            appendLine("📎 数据依据（Agent 通过工具获取，共 ${blocks.size} 段）：")
            appendProfile(profile)
            blocks.forEach { block ->
                block.lineSequence().forEach { line -> appendLine("　$line") }
            }
        }
    }

    /**
     * 记录相对参考范围的高/低标注。
     *
     * 单位与字典预设不一致时**不下结论**（如维生素 D 记录是 nmol/L、参考范围按 ng/mL 标注），
     * 跨单位比较必然误判 —— 与 [buildRecordsSummary] 的口径保持一致。
     */
    private fun rangeFlag(record: HealthRecord, member: FamilyMember): String {
        val value = record.numericValue ?: return ""
        val actual = record.unit.trim().takeIf { it.isNotBlank() }
        val preset = HealthTypes.unit(record.type)
        if (actual != null && preset.isNotBlank() && actual != preset) return ""
        val ref = HealthTypes.def(record.type)?.rangeFor(member.gender) ?: return ""
        return when {
            ref.high != null && value > ref.high -> " ↑偏高"
            ref.low != null && value < ref.low -> " ↓偏低"
            else -> ""
        }
    }

    /**
     * 把个人健康档案追加进「数据依据」块。
     * 档案是用户自述的静态背景，既然参与了解读，就应当让用户看得见 —— 与指标记录并列展示。
     */
    private fun StringBuilder.appendProfile(profile: String) {
        if (profile.isBlank()) return
        appendLine("　【个人健康档案】（用户自述，静态背景）：")
        profile.lineSequence().forEach { line -> appendLine("　　$line") }
    }

    /** 折行追加，并返回是否成功（超预算返回 false） */
    private fun StringBuilder.appendWithinBudget(line: String, remaining: Int): Boolean {
        if (line.length + 1 > remaining) {
            appendLine("…更多数据因上下文预算已省略")
            return false
        }
        appendLine(line)
        return true
    }

    companion object {
        private const val TAG = "QARepositoryImpl"

        /** 每个指标取最近多少条记录参与问答上下文 */
        private const val RECORDS_PER_TYPE = 10

        /** 单次提问拼接给模型的健康记录摘要字符上限 */
        private const val SUMMARY_CHAR_BUDGET = 6_000

        /** BM25 检索的最大召回条数（见 [QaRetriever]） */
        private const val RETRIEVE_TOP_K = QaRetriever.DEFAULT_TOP_K

        /** 流式回答被长度上限截断时的追加说明（正文已上屏，不回退、不丢弃） */
        private const val TRUNCATED_NOTICE =
            "⚠️ 本次回答因长度上限被截断，可换个更具体的问法继续追问。"

        /** 流式回答中途失败：保留已生成部分并说明，避免用户误以为回答已经完整 */
        private const val INTERRUPTED_NOTICE =
            "⚠️ 回答在生成过程中中断（网络或服务异常），以上为已生成的部分内容。"

        /** AI 服务不可用时回退本地规则引擎的标注（避免用户误以为读到的是模型结论） */
        private const val LOCAL_FALLBACK_NOTE =
            "本地规则引擎（AI 服务暂不可用，以下为离线兜底回答）"

        /** Agent 路径失败后回退本地引擎的标注 */
        private const val AGENT_FALLBACK_NOTE =
            "本地规则引擎（多步分析未能完成，以下为离线兜底回答）"

        /** 随提问附带的报告图片落盘目录（与报告原图 documents/ 分开，两条清理链路互不干扰） */
        private const val ATTACHMENT_DIR = "qa_images"

        /** 孤儿附图的静置期：窗口内不删，避免误删「刚选中、正准备发送」的那张 */
        private const val ATTACHMENT_GRACE_MS = 60 * 60 * 1000L

        /** 问答侧与解析侧都没有视觉模型时的明确提示（而不是让图片静默不生效） */
        private const val NO_VISION_MESSAGE =
            "这条提问附带了图片，但当前没有可用的视觉模型，因此图片不会被发送。" +
                "请在「设置 → 健康问答服务」把模型换成视觉模型（如智谱的 glm-5.3-flash），" +
                "或在「报告解析服务」中配置一个视觉模型后重试。"
    }
}
