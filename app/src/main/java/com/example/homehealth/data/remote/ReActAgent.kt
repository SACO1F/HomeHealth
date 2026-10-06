package com.example.homehealth.data.remote

import com.example.homehealth.data.local.entity.profileSummary
import com.example.homehealth.domain.tool.GetAlertsTool
import com.example.homehealth.domain.tool.SearchRecordsTool
import com.example.homehealth.domain.tool.ToolContext
import com.example.homehealth.domain.tool.ToolProvider
import com.example.homehealth.domain.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 手写的 ReAct 循环：`Thought → Action → Observation` 直到产出最终回答。
 *
 * 不引 LangGraph / AutoGen 是有意的：这里是单进程单循环，框架带来的编排抽象
 * 换不来任何东西，却会把这套"什么时候该停、失败了怎么办"的判断藏进框架内部。
 *
 * 几处刻意的设计：
 * - **最后一轮不提供工具**：与其在轮数用尽后拿着一堆没回填的工具调用收场，
 *   不如提前把工具声明去掉，逼模型用已有证据作答 —— 这样"轮数用尽"和"正常收尾"
 *   走同一条路径，不会出现"没有回答"的空档；
 * - **最后一轮的正文整段提交，不逐字上屏**：只有一轮结束后才知道它是不是最终回答，
 *   而"先把中间轮的推理流到答案区、发现是工具轮再撤回"对用户是明显的抖动。
 *   快路径仍然逐字上屏 —— 这是 Agent 路径为正确性付出的代价，已如实记在设计文档里；
 * - **工具连续失败会被禁用**：模型很容易对着同一个错参数反复重试，必须由代码刹车；
 * - **失败即交给上层降级**：本类不自己编答案。没有证据就回退本地规则引擎，
 *   比丢给用户一份半截 observation 更有用。
 */
@Singleton
class ReActAgent @Inject constructor(
    private val gateway: AgentLlmGateway,
    private val registry: ToolProvider
) {

    /**
     * 跑一次完整的 ReAct 提问。
     *
     * @param recordsOverview 该成员已有记录的范围概览（"共 N 条，覆盖血糖(3)、LDL(4)…"）。
     *   只给范围不给明细：明细要靠工具去取，否则等于把全量摘要又塞回上下文，Agent 就没有意义了。
     */
    fun run(context: ToolContext, recordsOverview: String?): Flow<AgentEvent> = flow {
        val entries = mutableListOf<AgentEntry>()
        entries += AgentEntry.System(SYSTEM_PROMPT)
        // 附图仅由 read_report_image 工具送往视觉模型一次；Agent 多轮始终是纯文本。
        entries += AgentEntry.User(userPrompt(context, recordsOverview), null)

        val answer = StringBuilder()
        val thinking = StringBuilder()
        val evidence = mutableListOf<String>()
        val usedTools = mutableListOf<String>()
        val disabled = mutableSetOf<String>()
        val failures = mutableMapOf<String, Int>()
        var toolCallsUsed = 0
        var truncated = false
        var lastFailure: Throwable? = null

        for (turnIndex in 1..AgentConfig.MAX_TURNS) {
            // 最后一轮撤掉工具：让"轮数用尽"与"正常收尾"合流，不会出现没有回答的空档
            val isFinalTurn = turnIndex == AgentConfig.MAX_TURNS
            val tools = if (isFinalTurn) {
                emptyList()
            } else {
                registry.availableFor(context).filterNot { it.name in disabled }
            }

            val turnText = StringBuilder()
            val turn = try {
                gateway.turn(
                    entries = entries,
                    tools = tools,
                    // 先缓冲：这一轮是"最终回答"还是"我只是说一句要查什么"，要到本轮结束才知道
                    onDelta = { delta -> turnText.append(delta) },
                    onThinking = { delta ->
                        thinking.append(delta)
                        emit(AgentEvent.Thinking(delta))
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastFailure = e
                break
            }

            if (turn.truncated) truncated = true

            if (turn.toolCalls.isEmpty()) {
                // 本轮没有工具调用 = 最终回答
                val text = turnText.toString()
                answer.append(text)
                if (text.isNotEmpty()) emit(AgentEvent.Answer(text))
                emit(
                    AgentEvent.Completed(
                        text = answer.toString(),
                        thinking = thinking.toString().takeIf { it.isNotBlank() },
                        truncated = truncated,
                        evidence = evidence,
                        toolNames = usedTools.distinct()
                    )
                )
                return@flow
            }

            // 工具轮：本轮的正文（通常是"我先查一下血糖"）不是答案，并入可折叠的思考轨迹
            if (turnText.isNotBlank()) {
                thinking.append(turnText)
                emit(AgentEvent.Thinking(turnText.toString()))
            }
            entries += AgentEntry.AssistantToolCalls(turn.toolCalls, turnText.toString().takeIf { it.isNotBlank() })

            val results = mutableListOf<AgentToolResultEntry>()
            for (call in turn.toolCalls) {
                usedTools += call.name
                if (toolCallsUsed >= AgentConfig.MAX_TOOL_CALLS) {
                    results += AgentToolResultEntry(
                        call.id, call.name,
                        "已达到本次提问的工具调用次数上限（${AgentConfig.MAX_TOOL_CALLS}），请用已有数据作答。",
                        ok = false
                    )
                    continue
                }
                toolCallsUsed++
                emit(AgentEvent.ToolCallStarted(call.name, call.argsJson.take(ARGS_SUMMARY_CHARS)))

                val tool = registry.byName(call.name)
                val result = when {
                    tool == null -> ToolResult.fail("没有名为 ${call.name} 的工具。")
                    call.name in disabled -> ToolResult.fail("工具 ${call.name} 因连续失败已被禁用，请换个方式。")
                    else -> withTimeoutOrNull(timeoutMsFor(tool)) {
                        // 工具内部必须自行容错；这里再兜一层，保证任何异常都不会掀翻整个循环
                        try {
                            tool.execute(context, call.argsJson)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            ToolResult.fail("执行异常：${e.message ?: e.javaClass.simpleName}")
                        }
                    } ?: ToolResult.fail("工具 ${call.name} 执行超时。")
                }

                if (result.ok) {
                    failures[call.name] = 0
                    // 检索类工具的返回就是「数据依据」的原文来源
                    if (call.name in EVIDENCE_TOOL_NAMES) evidence += result.text
                } else {
                    val count = (failures[call.name] ?: 0) + 1
                    failures[call.name] = count
                    if (count >= AgentConfig.MAX_TOOL_CONSECUTIVE_FAILURES) disabled += call.name
                }

                emit(AgentEvent.ToolCallFinished(call.name, result.ok, result.text.take(SUMMARY_CHARS)))
                results += AgentToolResultEntry(
                    toolCallId = call.id,
                    name = call.name,
                    text = truncateObservation(result.text),
                    ok = result.ok
                )
            }
            entries += AgentEntry.ToolResults(results)
        }

        // 走到这里说明中途失败了（正常情况下最后一轮必定产出回答）
        throw lastFailure ?: IllegalStateException("Agent 未能产出回答")
    }.flowOn(Dispatchers.IO)

    /**
     * observation 截断：一次 search_records 返回多项 × 多条就能把上下文撑爆。
     * 截断时明确标注省略了多少，而不是静默砍掉 —— 模型需要知道"还有更多"。
     */
    private fun truncateObservation(text: String): String {
        if (text.length <= AgentConfig.OBSERVATION_CHAR_BUDGET) return text
        val omitted = text.length - AgentConfig.OBSERVATION_CHAR_BUDGET
        return text.take(AgentConfig.OBSERVATION_CHAR_BUDGET) + "\n…（因上下文预算省略约 $omitted 字符）"
    }

    private fun timeoutMsFor(tool: com.example.homehealth.domain.tool.HealthTool): Long =
        if (tool.name == com.example.homehealth.domain.tool.ReadReportImageTool.NAME) {
            AgentConfig.VISION_TOOL_TIMEOUT_MS
        } else {
            AgentConfig.TOOL_TIMEOUT_MS
        }

    private fun userPrompt(context: ToolContext, recordsOverview: String?): String = buildString {
        appendLine("家庭成员：${context.member.name}")
        context.member.gender?.takeIf { it.isNotBlank() }?.let { appendLine("性别：$it") }
        // 个人健康档案（血型 / 腰围 / 运动 / 饮食 / 吸烟饮酒 / 慢性病 / 手术史）：静态背景信息，
        // 直接进首轮上下文；记录明细仍必须靠工具取，两者不冲突
        context.member.profileSummary().takeIf { it.isNotBlank() }?.let {
            appendLine("个人健康档案（用户自述，静态背景）：")
            it.lineSequence().forEach { line -> appendLine("  $line") }
        }
        recordsOverview?.takeIf { it.isNotBlank() }?.let {
            appendLine("已保存记录概览：$it")
        }
        context.imageBase64?.takeIf { it.isNotBlank() }?.let {
            appendLine("本轮附带了一张报告图片，可用 ${com.example.homehealth.domain.tool.ReadReportImageTool.NAME} 读取。")
        }
        appendLine()
        append("用户问题：${context.question.ifBlank { "请根据这张报告图片给出解读。" }}")
    }

    private companion object {
        const val ARGS_SUMMARY_CHARS = 200
        const val SUMMARY_CHARS = 160

        /** 这些工具的返回构成「数据依据」 */
        val EVIDENCE_TOOL_NAMES = setOf(SearchRecordsTool.NAME, GetAlertsTool.NAME)

        val SYSTEM_PROMPT = """
            你是「家庭健康管家」应用的健康问答助手。你可以调用工具查询这位成员的健康数据，然后据此回答。

            工作方式：
            1. 需要具体数值、趋势、参考范围或异常判断时，先调用工具获取；不要凭记忆或推测作答。
            2. 可以连续调用多个工具，每次只做一件明确的事；拿到足够数据后直接给出最终回答，不要再调用工具。
            3. 「是否异常」的结论必须来自 get_alerts，不要自行判断某项指标算不算异常。
            4. 只依据工具返回的数据与图片内容回答，不要编造不存在的数值。
            5. 引用具体数值时，在该数值后标注来源记录的编号（如 [2]），使每个数字都能追溯。
            6. 回答简洁实用；涉及疾病诊断、用药调整时，提醒用户咨询医生。
            7. 回答末尾固定附上：「以上内容由 AI 基于已保存记录生成，仅供参考，不构成医疗建议。」
        """.trimIndent()
    }
}
