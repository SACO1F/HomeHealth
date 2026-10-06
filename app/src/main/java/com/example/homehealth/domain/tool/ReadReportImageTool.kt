package com.example.homehealth.domain.tool

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * 工具 ④：读取本轮附带的报告图片。
 *
 * **为什么它在 ReAct 下是合理的，而在单轮问答下不合理**：
 * - 单轮场景：图片直接内联进 user message 更简单也更省（一次请求，模型自己看）；
 * - 多轮循环：base64 图片会**随每一轮重发**，成本按轮数放大。工具形式把图片
 *   一次转成文本，后续轮次的上下文保持纯文本 —— 这才是它存在的理由。
 *
 * 如果将来只做单轮问答，应当改用内联图片并砍掉本工具。
 */
@Singleton
class ReadReportImageTool @Inject constructor(
    private val vision: VisionReader
) : HealthTool {

    override val name = NAME

    override val description =
        "读取本轮提问附带的报告图片，返回图片里的关键信息（结论 / 异常项 / 建议）。" +
            "影像报告、病理报告、出院小结这类叙述性内容无法结构化入库，" +
            "需要先调用本工具读取。仅当本轮确实附带了图片时可用。"

    override val parametersJsonSchema = """
        {
          "type": "object",
          "properties": {
            "focus": {
              "type": "string",
              "description": "想聚焦的内容，如「结论」「建议」「异常项」；留空则整体读取"
            }
          }
        }
    """.trimIndent()

    override suspend fun execute(context: ToolContext, argsJson: String): ToolResult {
        val image = context.imageBase64
            ?: return ToolResult.fail("本轮提问没有附带图片，无法读取。请先请用户提供报告图片。")

        val focus = ToolArgs.str(ToolArgs.parse(argsJson), "focus")
        return try {
            val text = vision.readImageText(
                imageBase64 = image,
                focus = focus,
                question = context.question
            )
            if (text.isBlank()) {
                ToolResult.fail("图片读取结果为空，可能是图片不清晰或报告类型无法识别。")
            } else {
                ToolResult.ok(text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.fail("读图失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        const val NAME = "read_report_image"
    }
}
