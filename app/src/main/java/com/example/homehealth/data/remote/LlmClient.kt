package com.example.homehealth.data.remote

import android.util.Log
import com.example.homehealth.data.SettingsPrefs
import com.example.homehealth.data.remote.dto.AnthropicResponse
import com.example.homehealth.data.remote.dto.ChatCompletionRequest
import com.example.homehealth.data.remote.dto.ChatCompletionResponse
import com.example.homehealth.data.remote.dto.ChatMessage
import com.example.homehealth.data.remote.dto.ContentPart
import com.example.homehealth.data.remote.dto.ParsedRecord
import com.example.homehealth.domain.model.LlmCallRecord
import com.example.homehealth.domain.model.LlmScene
import com.example.homehealth.domain.model.ParseResult
import com.example.homehealth.domain.repository.LlmCallLogRepository
import com.example.homehealth.domain.tool.HealthTool
import com.example.homehealth.domain.tool.VisionReader
import com.example.homehealth.util.DateUtils
import com.example.homehealth.util.HealthTypes
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 通用 LLM 客户端：
 * - 报告解析 → 视觉模型（visionModels，支持图片输入），配置取自「报告解析服务」；
 * - 健康问答 → 文本模型（chatModels），配置取自「健康问答服务」。
 * 支持两种协议：OpenAI 兼容 chat/completions、Anthropic Messages API。
 *
 * **流式策略是刻意不对称的**：问答走 SSE 流式（长文本渐进生成，用户等待感最强），
 * 报告解析走非流式单次请求-响应（要的是完整 JSON 才能入库，流式只增加状态机复杂度）；
 * 两条链路共用同一套端点解析、鉴权头与可观测性埋点。
 *
 * 每次调用会经 [LlmCallLogRepository] 落一条可观测性日志（供应商 / 模型 / 耗时 / 字符数 /
 * 重试次数 / 失败类型）。日志里**不含提示词与回复内容** —— 那是用户的体检数据。
 */
@Singleton
class LlmClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val settingsPrefs: SettingsPrefs,
    private val gson: Gson,
    private val callLogRepository: LlmCallLogRepository
) : AgentLlmGateway, VisionReader {

    /** 模型回复：正文 + 思考过程（深度思考模型才有，可为空） */
    data class LlmAnswer(
        val text: String,
        val thinking: String? = null
    )

    /** 流式增量：正文 / 思考过程 / 工具调用 / 截断告警 */
    sealed interface LlmStreamChunk {
        /** 正文增量（非累计，调用方自行拼接） */
        data class Answer(val delta: String) : LlmStreamChunk

        /** 思考过程增量（深度思考模型） */
        data class Thinking(val delta: String) : LlmStreamChunk

        /**
         * 本轮要求调用的工具。
         * **在流结束后一次性给出**：两家协议的调用参数都是分片返回的，
         * 只有累积完整才构成合法 JSON（见 [ToolCallAssembler]）。
         */
        data class ToolCalls(val calls: List<AgentToolCall>) : LlmStreamChunk

        /**
         * 输出达到长度上限被截断。
         * 与 [LlmAnswer] 路径不同，流式下**不抛异常**：正文已经上屏，抛异常会让上层
         * 回退到本地引擎、把用户已经看到的回答丢掉。改为软告警，由调用方追加提示。
         */
        object Truncated : LlmStreamChunk
    }

    /** 解析后的调用配置 */
    private data class ResolvedConfig(
        val providerId: String,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val protocol: String
    )

    /** 带图提问实际会用到的视觉能力来源 */
    enum class VisionSource {
        /** 问答供应商自己的模型就支持图片 */
        QA_PROVIDER,

        /** 问答模型不支持图片，改用「报告解析服务」的视觉模型 */
        PARSE_SERVICE
    }

    /** 带图提问的视觉模型（供上层在来源里如实标注，避免用户以为换了个供应商是 bug） */
    data class VisionRoute(val source: VisionSource, val providerId: String, val model: String)

    // ---------- 配置解析 ----------

    /** 解析服务是否已配置（供应商 + Key） */
    fun parseConfigured(): Boolean {
        val provider = settingsPrefs.parseProvider
        if (provider == LlmProviders.LOCAL) return false
        return settingsPrefs.parseApiKey.isNotBlank()
    }

    /** 问答服务是否已配置 */
    fun qaConfigured(): Boolean {
        val provider = settingsPrefs.qaProvider
        if (provider == LlmProviders.LOCAL) return false
        return settingsPrefs.qaApiKey.isNotBlank()
    }

    /**
     * 读取并校验调用配置。
     * vision=true（报告解析）：必须使用供应商的视觉模型，纯文本模型会被拒绝；
     * vision=false（健康问答）：使用文本模型。
     */
    private fun resolveConfig(vision: Boolean): ResolvedConfig {
        val provider = if (vision) settingsPrefs.parseProvider else settingsPrefs.qaProvider
        val apiKey = (if (vision) settingsPrefs.parseApiKey else settingsPrefs.qaApiKey).trim()
        val customModel = if (vision) settingsPrefs.parseModel else settingsPrefs.qaModel

        val preset = LlmProviders.byId(provider)
            ?: throw IllegalStateException("服务供应商配置无效，请在「设置」中重新选择")
        val model = customModel.ifBlank {
            if (vision) preset.visionModels.firstOrNull() else preset.chatModels.firstOrNull()
        }.orEmpty()

        if (apiKey.isBlank()) {
            // 区分「从未填写」与「填过但解不开」：后者若提示"未填写"，
            // 用户会反复重填同一把 Key 而找不到原因
            val unreadable =
                if (vision) settingsPrefs.parseKeyUnreadable else settingsPrefs.qaKeyUnreadable
            throw IllegalStateException(
                if (unreadable) {
                    "已保存的 API Key 无法解密（系统密钥库已失效），请重新填写"
                } else {
                    "API Key 未填写，请在「设置」中配置"
                }
            )
        }

        // 视觉 / 文本模型区分校验：纯文本模型无法识别报告图片
        if (vision) {
            if (preset.visionModels.isEmpty()) {
                throw IllegalStateException(
                    "「${preset.name}」没有可用的视觉模型，请在「报告解析服务」中更换供应商"
                )
            }
            if (model in preset.chatModels && model !in preset.visionModels) {
                throw IllegalStateException(
                    "「$model」是文本模型，无法识别报告图片；请在「报告解析服务」中选择视觉模型"
                )
            }
        }

        return ResolvedConfig(
            providerId = provider,
            baseUrl = preset.baseUrl,
            apiKey = apiKey,
            model = model,
            protocol = preset.protocol
        )
    }

    /**
     * 解析「带图提问」要用的调用配置。
     *
     * 问答服务配的是**文本**模型，而图片必须由视觉模型读取 —— 两者在本应用里是独立配置的，
     * 因此这里按三档回退：
     * 1. 问答模型本身就在该供应商的 `visionModels` 里（OpenAI / Gemini / Kimi 全系如此）→ 直接用；
     * 2. 否则回退到「报告解析服务」的视觉模型（用户已经配好，零新增配置）；
     * 3. 都没有 → 抛可操作错误，而不是让请求带着图片发给一个看不见图的模型。
     *
     * 刻意**不复用** [resolveConfig]`(vision = true)`：那条路径读的是解析服务的设置，
     * 且会把"在 chatModels 里但不在 visionModels 里"的模型直接拒绝；这里需要的是
     * "问答侧优先、解析侧兜底"的另一套判断。
     */
    private fun resolveVisionConfig(): Pair<ResolvedConfig, VisionSource> {
        val qaProvider = settingsPrefs.qaProvider
        val preset = LlmProviders.byId(qaProvider)
        if (preset != null && settingsPrefs.qaApiKey.isNotBlank()) {
            val model = settingsPrefs.qaModel.ifBlank { preset.chatModels.firstOrNull() }.orEmpty()
            if (model.isNotBlank() && model in preset.visionModels) {
                return ResolvedConfig(
                    providerId = qaProvider,
                    baseUrl = preset.baseUrl,
                    apiKey = settingsPrefs.qaApiKey.trim(),
                    model = model,
                    protocol = preset.protocol
                ) to VisionSource.QA_PROVIDER
            }
        }
        if (parseConfigured()) return resolveConfig(vision = true) to VisionSource.PARSE_SERVICE
        throw IllegalStateException(VISION_UNSUPPORTED_HINT)
    }

    /**
     * 带图提问会用到哪个视觉模型；`null` 表示当前配置读不了图。
     *
     * 供上层在回答的来源里如实标注（「图片由报告解析服务的视觉模型读取」）——
     * 用户配了 A 家却在回答里看到 B 家，不说清就会被当成 bug。
     * 纯配置读取、无副作用，所以可以先问一次再发起提问。
     */
    fun qaVisionRoute(): VisionRoute? = runCatching {
        val (cfg, source) = resolveVisionConfig()
        VisionRoute(source = source, providerId = cfg.providerId, model = cfg.model)
    }.getOrNull()

    // ---------- 核心调用 ----------

    /** 服务端返回非 2xx 时抛出，携带状态码用于判断是否值得重试 */
    private class HttpStatusException(val code: Int, message: String) : Exception(message)

    /** 仅超时/连接类 IO 异常、429、5xx 值得重试；其余 4xx 是配置错误，重试没有意义 */
    private fun isRetryable(e: Throwable): Boolean = when (e) {
        is HttpStatusException -> e.code == 429 || e.code in 500..599
        is IOException -> true
        else -> false
    }

    /**
     * 指数退避重试（含抖动）。
     * 用 delay 而非 Thread.sleep —— 调用方在协程中，阻塞线程会拖垮 Dispatchers.IO 线程池；
     * 协程取消（CancellationException）必须原样抛出，不能被重试逻辑吞掉。
     *
     * @param canRetry 额外闸门。流式调用用它实现「首个增量到达后不再重试」：
     *   回答已经部分上屏时重试会让内容重复，此时宁可失败也不能重发。
     */
    private suspend fun <T> withRetry(
        maxAttempts: Int = 3,
        canRetry: () -> Boolean = { true },
        block: suspend () -> T
    ): T {
        var attempt = 0
        while (true) {
            // 协程已取消时立刻退出：阻塞读被 call.cancel() 掐断后会以 IOException 结束，
            // 那在 isRetryable 眼里是"值得重试"，少了这道判断会白等一轮退避
            currentCoroutineContext().ensureActive()
            try {
                return block()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                attempt++
                if (attempt >= maxAttempts || !isRetryable(e) || !canRetry()) throw e
                // 500ms → 1s → 2s，叠加 0-250ms 抖动，避免多个请求同时重试
                delay((500L shl (attempt - 1)) + Random.nextLong(0, 250))
                Log.w(TAG, "LLM 调用失败，第 $attempt 次重试（上限 $maxAttempts）：${e.message}")
            }
        }
    }

    /** 提示词字符数只统计文本部分：图片 base64 会把统计完全淹没 */
    private fun promptTextChars(messages: List<ChatMessage>): Int = messages.sumOf { message ->
        when (val content = message.content) {
            is String -> content.length
            is List<*> -> content.filterIsInstance<ContentPart>().sumOf { it.text?.length ?: 0 }
            else -> 0
        }
    }

    private fun hasImageContent(messages: List<ChatMessage>): Boolean = messages.any { message ->
        (message.content as? List<*>)?.any { (it as? ContentPart)?.imageUrl != null } == true
    }

    /** 按协议分发调用，返回模型输出的正文与思考过程；同时落一条可观测性日志 */
    private suspend fun chatCompletion(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int,
        protocol: String,
        scene: String,
        providerId: String,
        maxAttempts: Int = RETRY_ATTEMPTS_QA
    ): LlmAnswer = withContext(Dispatchers.IO) {
        // 提示词字符数只统计文本部分：图片 base64 会把统计完全淹没
        val promptChars = promptTextChars(messages)
        val hasImage = hasImageContent(messages)
        val startedAt = System.currentTimeMillis()
        var attempts = 0
        var completionChars = 0
        try {
            val answer = withRetry(maxAttempts) {
                attempts++
                if (protocol == LlmProviders.PROTOCOL_ANTHROPIC) {
                    executeAnthropic(baseUrl, apiKey, model, messages, temperature, maxTokens)
                } else {
                    executeOpenAi(baseUrl, apiKey, model, messages, temperature, maxTokens)
                }
            }
            completionChars = answer.text.length
            recordCall(
                provider = providerId, model = model, scene = scene, startedAt = startedAt,
                promptChars = promptChars, completionChars = completionChars,
                hasImage = hasImage, attempts = attempts, ok = true, errorType = null
            )
            answer
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            // 协程取消是用户主动行为，不该记成一次失败调用
            if (e !is CancellationException) {
                recordCall(
                    provider = providerId, model = model, scene = scene, startedAt = startedAt,
                    promptChars = promptChars, completionChars = completionChars,
                    hasImage = hasImage, attempts = attempts, ok = false,
                    errorType = classifyError(e)
                )
            }
            throw e
        }
    }

    /** 错误归类：只留可聚合的短标签，方便统计"失败集中在哪" */
    private fun classifyError(e: Throwable): String = when {
        e is HttpStatusException -> when {
            e.code == 429 -> "http_429"
            e.code in 500..599 -> "http_5xx"
            else -> "http_${e.code}"
        }
        e is SocketTimeoutException -> "timeout"
        e is IOException -> "io"
        e.message == TRUNCATED_HINT -> "truncated"
        else -> e.javaClass.simpleName
    }

    /** 落日志。观测组件坏了绝不能拖垮主流程 —— 解析/问答照常进行 */
    private suspend fun recordCall(
        provider: String,
        model: String,
        scene: String,
        startedAt: Long,
        promptChars: Int,
        completionChars: Int,
        hasImage: Boolean,
        attempts: Int,
        ok: Boolean,
        errorType: String?
    ) {
        runCatching {
            callLogRepository.record(
                LlmCallRecord(
                    provider = provider,
                    model = model,
                    scene = scene,
                    latencyMs = System.currentTimeMillis() - startedAt,
                    promptChars = promptChars,
                    completionChars = completionChars,
                    hasImage = hasImage,
                    attempts = attempts,
                    ok = ok,
                    errorType = errorType,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    /** 配置类失败（Key 未填 / 供应商无效）发生在真正发请求之前，也记一条便于统计 */
    private suspend fun recordConfigFailure(scene: String, providerId: String, model: String, e: Exception) {
        recordCall(
            provider = providerId, model = model, scene = scene,
            startedAt = System.currentTimeMillis(),
            promptChars = 0, completionChars = 0, hasImage = false,
            attempts = 0, ok = false, errorType = "config"
        )
    }

    /** 非流式请求同样随协程取消，涵盖等待响应和读取响应体。 */
    private suspend fun executeCancellable(request: Request): Pair<Int, String> =
        suspendCancellableCoroutine { continuation ->
            val call = okHttpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { resp ->
                            continuation.resume(resp.code to resp.body?.string().orEmpty())
                        }
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        }

    /** OpenAI 兼容协议：POST {base}/chat/completions */
    private suspend fun executeOpenAi(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int
    ): LlmAnswer {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val body = gson.toJson(
            ChatCompletionRequest(model, messages, temperature, maxTokens)
        ).toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        val (code, text) = executeCancellable(request)
        if (code !in 200..299) {
            throw HttpStatusException(code, "HTTP $code：${extractErrMsg(text)}")
        }
            val parsed = runCatching {
                gson.fromJson(text, ChatCompletionResponse::class.java)
            }.getOrNull()
            val choice = parsed?.choices?.firstOrNull()
            val message = choice?.message
            val content = message?.content
                ?: throw IllegalStateException(parsed?.error?.message ?: EMPTY_RESPONSE_HINT)
            // 输出被长度上限截断：JSON 必然不完整，必须给出可诊断的提示，
            // 否则上层只会报「未能识别指标」，把模型问题误报成用户拍照问题
            if (choice.finishReason == "length") {
                throw IllegalStateException(TRUNCATED_HINT)
            }
            // 深度思考模型：思考过程在 reasoning_content（DeepSeek/智谱）或 thinking 字段
            val thinking = message.reasoningContent?.takeIf { it.isNotBlank() }
                ?: message.thinking?.takeIf { it.isNotBlank() }
        return LlmAnswer(content, thinking)
    }

    /** Anthropic Messages API：POST {base}/v1/messages（system 独立传参，图片用 base64 source） */
    private suspend fun executeAnthropic(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int
    ): LlmAnswer {
        val url = baseUrl.trimEnd('/') + "/v1/messages"

        suspend fun post(withTemperature: Boolean): Pair<Int, String> {
            val body = anthropicBody(
                model = model,
                messages = messages,
                temperature = if (withTemperature) temperature else null,
                maxTokens = maxTokens,
                stream = false
            )
            val request = Request.Builder()
                .url(url)
                .header("x-api-key", apiKey)
                .header(ANTHROPIC_VERSION_HEADER, ANTHROPIC_VERSION)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            return executeCancellable(request)
        }

        var (code, text) = post(withTemperature = true)
        // Claude 启用扩展思考时要求 temperature 必须为 1（或不传），被拒时降级为不传该参数重试一次
        if (code == 400 && text.contains("temperature", ignoreCase = true)) {
            Log.w(TAG, "Anthropic 拒绝 temperature 参数，降级为不传该参数重试")
            val fallback = post(withTemperature = false)
            code = fallback.first
            text = fallback.second
        }

        if (code !in 200..299) {
            throw HttpStatusException(code, "HTTP $code：${extractErrMsg(text)}")
        }

        val parsed = runCatching {
            gson.fromJson(text, AnthropicResponse::class.java)
        }.getOrNull()
        // 输出被长度上限截断：JSON 必然不完整，给出可诊断的提示
        if (parsed?.stopReason == "max_tokens") {
            throw IllegalStateException(TRUNCATED_HINT)
        }
        val blocks = parsed?.content.orEmpty()
        // Anthropic 扩展思考：type=thinking 块为思考过程，text 块为正文
        val answer = blocks.filter { it.type == "text" }
            .joinToString("") { it.text.orEmpty() }
        val thinking = blocks.filter { it.type == "thinking" }
            .joinToString("\n") { it.text.orEmpty() }
            .takeIf { it.isNotBlank() }
        if (answer.isBlank()) {
            throw IllegalStateException(parsed?.error?.message ?: EMPTY_RESPONSE_HINT)
        }
        return LlmAnswer(answer, thinking)
    }

    /**
     * Anthropic 请求体（非流式 / 流式共用同一份构建逻辑，避免两处措辞漂移）。
     *
     * @param temperature 传 `null` 表示不带该参数。Claude 启用扩展思考时要求 temperature 必须为 1，
     *   被拒后降级为不传该参数重发一次 —— 非流式与流式两条路径都走这里。
     */
    private fun anthropicBody(
        model: String,
        messages: List<ChatMessage>,
        temperature: Double?,
        maxTokens: Int,
        stream: Boolean
    ): String {
        val system = messages.filter { it.role == "system" }
            .mapNotNull { it.content as? String }
            .joinToString("\n")
        val converted = messages.filter { it.role != "system" }.map { m ->
            mapOf("role" to m.role, "content" to toAnthropicContent(m.content))
        }
        val bodyMap = buildMap<String, Any?> {
            put("model", model)
            put("max_tokens", maxTokens)
            if (temperature != null) put("temperature", temperature)
            if (stream) put("stream", true)
            put("messages", converted)
            if (system.isNotBlank()) put("system", system)
        }
        return gson.toJson(bodyMap)
    }

    /** 把 OpenAI 风格 content（String 或 ContentPart 列表）转为 Anthropic content 数组 */
    private fun toAnthropicContent(content: Any): List<Map<String, Any?>> = when (content) {
        is String -> listOf(mapOf("type" to "text", "text" to content))
        is List<*> -> content.filterIsInstance<ContentPart>().map { part ->
            if (part.type == "text") {
                mapOf("type" to "text", "text" to (part.text ?: ""))
            } else {
                val dataUri = part.imageUrl?.url.orEmpty()
                val mediaType = dataUri.removePrefix("data:")
                    .substringBefore(';')
                    .ifBlank { "image/jpeg" }
                val base64 = dataUri.substringAfter("base64,", "")
                mapOf(
                    "type" to "image",
                    "source" to mapOf(
                        "type" to "base64",
                        "media_type" to mediaType,
                        "data" to base64
                    )
                )
            }
        }
        else -> listOf(mapOf("type" to "text", "text" to content.toString()))
    }

    private fun extractErrMsg(body: String): String = try {
        val type = object : TypeToken<Map<String, Any?>>() {}.type
        val map: Map<String, Any?> = gson.fromJson(body, type)
        val err = map["error"] as? Map<*, *>
        (err?.get("message") as? String) ?: body.take(150)
    } catch (_: Exception) {
        body.take(150)
    }

    // ---------- 业务：报告解析 ----------

    /** 用视觉模型解析体检报告图片，提取全部支持的指标 */
    suspend fun parseHealthDocument(imageBase64: String): ParseResult =
        withContext(Dispatchers.IO) {
            val cfg = try {
                resolveConfig(vision = true)
            } catch (e: Exception) {
                recordConfigFailure(LlmScene.PARSE, "配置失败", "", e)
                throw e
            }
            val today = DateUtils.formatDate(System.currentTimeMillis())

            // 动态生成全部可识别指标清单（含分组）
            val metricList = HealthTypes.GROUPS.joinToString("\n") { (key, groupName) ->
                val items = HealthTypes.byGroup(key).joinToString("、") { "${it.type}(${it.label})" }
                "  $groupName：$items"
            }

            val prompt = """
                你是一名专业的体检报告解析助手。请从这张体检报告/化验单图片中提取所有能识别到的健康指标。

                可识别的指标类型（type 必须严格使用下列英文标识）：
                $metricList

                输出要求：
                1. 严格只输出一个 JSON 对象，禁止输出任何解释、前后缀或 Markdown 代码块标记，格式：
                   {"records":[{"type":"blood_pressure","value":"120/80","numeric_value":120,"unit":"mmHg","date":"$today"}]}
                2. value 保留报告原始读数，血压用 "收缩压/舒张压"（如 "120/80"）；numeric_value 取数值（血压取收缩压，比率类去掉 % 号）
                3. date 格式 yyyy-MM-dd，取报告上标注的日期；没有日期时用 "$today"
                4. unit 使用报告上的单位；报告未标注时用：血压 mmHg、血糖 mmol/L、血脂 mmol/L、体重 kg、心率 bpm、体温 ℃
                5. 报告中不存在的指标不要输出；整张图无可识别指标时输出 {"records":[]}
                6. 同一指标多次出现（如不同日期复查）只取最近一次
            """.trimIndent()

            val content = chatCompletion(
                baseUrl = cfg.baseUrl,
                apiKey = cfg.apiKey,
                model = cfg.model,
                protocol = cfg.protocol,
                messages = listOf(
                    ChatMessage(
                        role = "user",
                        content = listOf(
                            ContentPart.text(prompt),
                            ContentPart.image(imageBase64)
                        )
                    )
                ),
                temperature = 0.1,
                maxTokens = 4096,
                scene = LlmScene.PARSE,
                providerId = cfg.providerId,
                // 解析场景刻意减少重试：readTimeout 为 120s，3 次超时会让用户在解析页干等 6 分钟以上
                maxAttempts = RETRY_ATTEMPTS_PARSE
            )

            ParseResult(parseRecordsJson(stripCodeBlock(content.text)), content.text.trim())
        }

    // ---------- 业务：健康问答（SSE 流式） ----------

    /**
     * 用文本模型结合成员健康记录**流式**回答问题。
     *
     * **为什么问答流式、解析不流式**（刻意的选型，不是历史包袱）：
     * - 报告解析要的是**完整 JSON**，全部指标到齐才能解析入库；边收边解析只会让状态机复杂化，
     *   下游还要人工确认卡片，早几百毫秒看到半截 JSON 没有任何意义 —— 保持非流式；
     * - 问答是**长文本渐进生成**，等待感最强。流式把首字延迟从「整段生成完」降到「首个 token」，
     *   这是用户可感知的差别。
     *
     * 流式特有的两个坑（见 [executeStream] / [readStream]）：
     * - **只在首个增量到达前重试**：已有内容上屏时重发会让回答重复，宁可失败；
     * - **协程取消时主动掐断 socket**：阻塞读不会因协程取消而返回，不处理会一直挂到 readTimeout。
     *
     * @param requireCitation 上下文带检索引用编号（`[n]`）时置 true，要求模型在数值后标注来源，
     *   使答案里的每个数字都能追溯到具体记录
     * @param imageBase64 本轮附带的报告图片（base64，不含 data URI 前缀）。
     *   非空时改用视觉模型（见 [resolveVisionConfig]），并把图片作为多模态内容随提问发送
     */
    fun askHealthQuestionStream(
        memberName: String,
        recordsSummary: String,
        question: String,
        requireCitation: Boolean = false,
        imageBase64: String? = null
    ): Flow<LlmStreamChunk> = flow {
        val image = imageBase64?.takeIf { it.isNotBlank() }
        val cfg = try {
            if (image != null) resolveVisionConfig().first else resolveConfig(vision = false)
        } catch (e: Exception) {
            recordConfigFailure(LlmScene.QA, "配置失败", "", e)
            throw e
        }
        val userText = qaUserPrompt(memberName, recordsSummary, question)
        executeStream(
            cfg = cfg,
            messages = listOf(
                ChatMessage(
                    role = "system",
                    content = qaSystemPrompt(requireCitation = requireCitation, hasImage = image != null)
                ),
                ChatMessage(
                    role = "user",
                    // content 为 Any：无图时是纯文本，有图时是 [文本, 图片] 多模态片段
                    content = if (image != null) {
                        listOf(ContentPart.text(userText), ContentPart.image(image))
                    } else {
                        userText
                    }
                )
            ),
            temperature = QA_TEMPERATURE,
            maxTokens = QA_MAX_TOKENS
        ) { emit(it) }
    }.flowOn(Dispatchers.IO)

    /**
     * 问答系统提示词：仅「引用编号」与「读图」两条规则按上下文决定，
     * 其余措辞在所有路径下完全一致（避免同一套行为出现多种表述）。
     */
    private fun qaSystemPrompt(requireCitation: Boolean, hasImage: Boolean): String {
        val rules = buildList {
            if (hasImage) {
                add("本轮附带了一张报告图片：先读出图片里的关键信息（结论 / 异常项 / 建议），再结合已保存的记录回答。图片中读不到的内容不要推测；图片与记录不一致时以图片为准并指出这一点")
            }
            add(
                if (hasImage) "只依据记录与图片中的内容回答，不要编造不存在的数值"
                else "只依据记录中的数据分析，不要编造不存在的数值"
            )
            add("回答简洁实用，涉及趋势时做简单分析，涉及参考范围的解释要通俗")
            add("涉及疾病诊断、用药调整时，提醒用户咨询医生")
            if (requireCitation) {
                add("上下文里的记录带有 [n] 引用编号：每引用一个具体数值，就在该数值后紧跟标注对应的 [n]")
            }
            add("回答末尾固定附上：「以上内容由 AI 基于已保存记录生成，仅供参考，不构成医疗建议。」")
        }
        return buildString {
            appendLine("你是「家庭健康管家」应用的健康问答助手。请基于提供的家庭成员健康记录回答问题。")
            appendLine("规则：")
            rules.forEachIndexed { index, rule -> appendLine("${index + 1}. $rule") }
        }.trimEnd()
    }

    private fun qaUserPrompt(memberName: String, recordsSummary: String, question: String): String = """
        家庭成员：$memberName
        健康记录摘要（含参考范围与偏高偏低标注）：
        $recordsSummary

        用户问题：$question
    """.trimIndent()

    // ---------- 流式调用内核 ----------

    /**
     * 流式调用 + 可观测性埋点，与 [chatCompletion] 一一对应。
     * 日志字段口径刻意保持一致（供应商 / 模型 / 场景 / 耗时 / 提示与补全字符数 / 重试 / 失败类型），
     * 这样流式与非流式两条链路的统计能放进同一张表对比。
     */
    private suspend fun executeStream(
        cfg: ResolvedConfig,
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int,
        onChunk: suspend (LlmStreamChunk) -> Unit
    ) = executeStreamCore(
        cfg = cfg,
        promptChars = promptTextChars(messages),
        hasImage = hasImageContent(messages),
        bodyFor = { includeTemperature ->
            if (cfg.protocol == LlmProviders.PROTOCOL_ANTHROPIC) {
                anthropicBody(
                    model = cfg.model,
                    messages = messages,
                    temperature = if (includeTemperature) temperature else null,
                    maxTokens = maxTokens,
                    stream = true
                )
            } else {
                gson.toJson(
                    ChatCompletionRequest(cfg.model, messages, temperature, maxTokens, stream = true)
                )
            }
        },
        onChunk = onChunk
    )

    /** Agent 路径的一轮流式请求：请求体交给 [AgentRequestBody] 按协议翻译 */
    private suspend fun executeAgentStream(
        cfg: ResolvedConfig,
        entries: List<AgentEntry>,
        tools: List<HealthTool>,
        onChunk: suspend (LlmStreamChunk) -> Unit
    ) = executeStreamCore(
        cfg = cfg,
        promptChars = entries.sumOf { entryPromptChars(it) },
        hasImage = entries.any { it is AgentEntry.User && !it.imageBase64.isNullOrBlank() },
        bodyFor = { includeTemperature ->
            AgentRequestBody.build(
                gson = gson,
                protocol = cfg.protocol,
                model = cfg.model,
                entries = entries,
                tools = tools,
                temperature = if (includeTemperature) AgentConfig.ANSWER_TEMPERATURE else null,
                maxTokens = AgentConfig.ANSWER_MAX_TOKENS
            )
        },
        onChunk = onChunk
    )

    /** 条目字符数（日志用）：工具结果也算进 prompt 体量，否则观测不到上下文膨胀 */
    private fun entryPromptChars(entry: AgentEntry): Int = when (entry) {
        is AgentEntry.System -> entry.text.length
        is AgentEntry.User -> entry.text.length
        is AgentEntry.AssistantToolCalls ->
            (entry.text?.length ?: 0) + entry.calls.sumOf { it.argsJson.length }
        is AgentEntry.ToolResults -> entry.results.sumOf { it.text.length }
    }

    /**
     * 流式调用内核：日志埋点 + 重试策略 + 取消处理。两条路径（普通问答 / Agent 轮）共用。
     */
    private suspend fun executeStreamCore(
        cfg: ResolvedConfig,
        promptChars: Int,
        hasImage: Boolean,
        bodyFor: (includeTemperature: Boolean) -> String,
        onChunk: suspend (LlmStreamChunk) -> Unit
    ) {
        val startedAt = System.currentTimeMillis()
        var attempts = 0
        var completionChars = 0
        var received = false

        // 协程取消 → 立刻掐断 socket：阻塞读不会因为协程取消而返回，
        // 不处理的话用户退出问答页后连接仍会挂到 readTimeout(120s) 才释放。
        var activeCall: Call? = null
        val abortHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) activeCall?.cancel()
        }

        try {
            withRetry(maxAttempts = RETRY_ATTEMPTS_QA, canRetry = { !received }) {
                attempts++
                // 能走到下一轮说明上一轮没有任何增量上屏，计数归零即可
                received = false
                completionChars = 0
                runStreamOnce(cfg, bodyFor, { chunk ->
                    // 上屏过内容就不允许再重试（重发会让回答重复），因此这里必须记账
                    when (chunk) {
                        is LlmStreamChunk.Answer -> {
                            received = true
                            completionChars += chunk.delta.length
                        }
                        is LlmStreamChunk.Thinking -> received = true
                        else -> Unit
                    }
                    onChunk(chunk)
                }) { activeCall = it }
            }
            recordCall(
                provider = cfg.providerId, model = cfg.model, scene = LlmScene.QA, startedAt = startedAt,
                promptChars = promptChars, completionChars = completionChars,
                hasImage = hasImage, attempts = attempts, ok = true, errorType = null
            )
        } catch (e: Throwable) {
            if (e !is CancellationException) {
                recordCall(
                    provider = cfg.providerId, model = cfg.model, scene = LlmScene.QA, startedAt = startedAt,
                    promptChars = promptChars, completionChars = completionChars,
                    hasImage = hasImage, attempts = attempts, ok = false, errorType = classifyError(e)
                )
            }
            throw e
        } finally {
            abortHandle?.dispose()
        }
    }

    /**
     * 单次流式请求。Anthropic 的 temperature 降级需要重发一次，因此这里可能发出两次请求；
     * 两次都必须把 Call 交给 [onCallCreated]，取消才能掐到当前活着的那一个。
     *
     * @param bodyFor 按「是否带 temperature」构建请求体 —— 降级重发时只需换一个参数，
     *   不必把消息翻译逻辑再写一遍
     */
    private suspend fun runStreamOnce(
        cfg: ResolvedConfig,
        bodyFor: (includeTemperature: Boolean) -> String,
        onChunk: suspend (LlmStreamChunk) -> Unit,
        onCallCreated: (Call) -> Unit
    ) {
        val anthropic = cfg.protocol == LlmProviders.PROTOCOL_ANTHROPIC
        val url = cfg.baseUrl.trimEnd('/') + if (anthropic) "/v1/messages" else "/chat/completions"

        fun newCall(includeTemperature: Boolean): Call {
            val builder = Request.Builder()
                .url(url)
                .post(bodyFor(includeTemperature).toRequestBody(JSON_MEDIA_TYPE))
            if (anthropic) {
                builder.header("x-api-key", cfg.apiKey)
                    .header(ANTHROPIC_VERSION_HEADER, ANTHROPIC_VERSION)
            } else {
                builder.header("Authorization", "Bearer ${cfg.apiKey}")
            }
            return okHttpClient.newCall(builder.build())
        }

        val first = newCall(includeTemperature = true)
        onCallCreated(first)
        try {
            readStream(first, anthropic, onChunk)
        } catch (e: HttpStatusException) {
            // Claude 启用扩展思考时要求 temperature 必须为 1；被拒时降级为不传该参数重发一次。
            // 能走到这里说明还没收到任何内容（HTTP 状态码错误），重发不会产生重复输出。
            if (!anthropic || e.code != 400 ||
                !e.message.orEmpty().contains("temperature", ignoreCase = true)
            ) {
                throw e
            }
            Log.w(TAG, "Anthropic 拒绝 temperature 参数，降级为不传该参数重试")
            val retry = newCall(includeTemperature = false)
            onCallCreated(retry)
            readStream(retry, anthropic, onChunk)
        }
    }

    /** 执行请求并逐行消费 SSE；正常返回或抛异常时连接都已关闭 */
    private suspend fun readStream(
        call: Call,
        anthropic: Boolean,
        onChunk: suspend (LlmStreamChunk) -> Unit
    ) {
        call.execute().use { resp ->
            if (!resp.isSuccessful) {
                val text = resp.body?.string().orEmpty()
                throw HttpStatusException(resp.code, "HTTP ${resp.code}：${extractErrMsg(text)}")
            }
            val source = resp.body?.source() ?: throw IllegalStateException(EMPTY_RESPONSE_HINT)

            var produced = false
            // 第一条非 SSE 结构行：供应商把错误塞在正文里时（HTTP 200 但不是 event-stream），
            // 把它原样带进异常比笼统报「响应为空」可诊断得多
            var stray: String? = null
            // 工具调用参数是分片返回的，必须累积到流结束才是合法 JSON
            val toolCalls = ToolCallAssembler()

            while (true) {
                val line = source.readUtf8Line() ?: break
                val payload = LlmStreamParser.payloadOf(line)
                if (payload == null) {
                    if (stray == null && !LlmStreamParser.isStructuralLine(line)) stray = line.trim()
                    continue
                }
                val deltas =
                    if (anthropic) LlmStreamParser.parseAnthropic(payload)
                    else LlmStreamParser.parseOpenAi(payload)
                var done = false
                for (delta in deltas) {
                    when (delta) {
                        is LlmStreamParser.Delta.Content -> {
                            produced = true
                            onChunk(LlmStreamChunk.Answer(delta.text))
                        }
                        is LlmStreamParser.Delta.Thinking -> {
                            produced = true
                            onChunk(LlmStreamChunk.Thinking(delta.text))
                        }
                        is LlmStreamParser.Delta.ToolCallFragment -> {
                            // 纯工具调用轮没有正文，因此这里也要算"有产出"，否则会被误判成空响应
                            produced = true
                            toolCalls.accept(delta)
                        }
                        LlmStreamParser.Delta.Truncated -> onChunk(LlmStreamChunk.Truncated)
                        is LlmStreamParser.Delta.Failure -> throw IllegalStateException(delta.message)
                        LlmStreamParser.Delta.Done -> done = true
                    }
                }
                if (done) break
            }

            if (!toolCalls.isEmpty()) onChunk(LlmStreamChunk.ToolCalls(toolCalls.build()))

            if (!produced) {
                throw IllegalStateException(
                    stray?.let { "模型未返回流式内容：${it.take(150)}" } ?: EMPTY_RESPONSE_HINT
                )
            }
        }
    }

    // ---------- Agent 网关实现 ----------

    /**
     * 一轮带工具的流式请求。
     *
     * 与 [askHealthQuestionStream] 的差别：这里不做任何降级 —— 失败就直接抛出，
     * 由 [ReActAgent] 决定是继续下一轮、还是整体交给上层回退。降级策略只应有一处。
     */
    override suspend fun turn(
        entries: List<AgentEntry>,
        tools: List<HealthTool>,
        onDelta: suspend (String) -> Unit,
        onThinking: suspend (String) -> Unit
    ): AgentTurnResult {
        val cfg = resolveConfig(vision = false)
        val text = StringBuilder()
        val toolCalls = mutableListOf<AgentToolCall>()
        var truncated = false

        executeAgentStream(cfg, entries, tools) { chunk ->
            when (chunk) {
                is LlmStreamChunk.Answer -> {
                    text.append(chunk.delta)
                    onDelta(chunk.delta)
                }
                is LlmStreamChunk.Thinking -> onThinking(chunk.delta)
                is LlmStreamChunk.ToolCalls -> toolCalls += chunk.calls
                LlmStreamChunk.Truncated -> truncated = true
            }
        }
        return AgentTurnResult(
            text = text.toString(),
            thinking = null,
            toolCalls = toolCalls,
            truncated = truncated
        )
    }

    /**
     * 让视觉模型读一次图片并返回纯文本观察结果（供 Agent 的读图工具使用）。
     *
     * 走非流式：工具需要的是"完整的一段结论"，流式对工具调用没有意义，
     * 而且工具结果本来就要作为一整条 observation 回填给模型。
     */
    override suspend fun readImageText(imageBase64: String, focus: String?, question: String): String =
        withContext(Dispatchers.IO) {
            val cfg = resolveVisionConfig().first
            val prompt = buildString {
                appendLine("你正在阅读一张体检 / 检查报告图片。请提取图片中的关键信息，供后续健康问答使用。")
                appendLine("要求：")
                appendLine("1. 先用一句话说明这是什么报告（类型 / 机构 / 日期，识别不到就跳过）")
                appendLine("2. 列出所有可读到的检查项目与结果（名称 / 数值 / 单位 / 参考范围）")
                appendLine("3. 单独列出「结论」「印象」「建议」这类结论性文字，原文照录，不要改写")
                appendLine("4. 只写图片里确实存在的内容，不要推测、不要补充医学解释")
                focus?.takeIf { it.isNotBlank() }?.let { appendLine("5. 用户特别关心：$it，请优先提取相关内容") }
                if (question.isNotBlank()) appendLine("6. 用户的问题是：$question")
                append("输出简洁的纯文本，不要使用 Markdown 代码块。")
            }
            chatCompletion(
                baseUrl = cfg.baseUrl,
                apiKey = cfg.apiKey,
                model = cfg.model,
                protocol = cfg.protocol,
                messages = listOf(
                    ChatMessage(
                        role = "user",
                        content = listOf(ContentPart.text(prompt), ContentPart.image(imageBase64))
                    )
                ),
                temperature = 0.1,
                maxTokens = 1_500,
                scene = LlmScene.PARSE,
                providerId = cfg.providerId,
                maxAttempts = RETRY_ATTEMPTS_PARSE
            ).text
        }

    // ---------- 工具 ----------

    /**
     * 解析模型返回的 JSON（容忍 ```json 包裹与前后杂文字）。
     *
     * 先按括号配平扫描出所有顶层 JSON 对象，再逐个尝试解析，取第一个含 records 的结果。
     * 相比「首个 { 到末个 }」：后者在模型输出两段 JSON、或正文中夹带花括号时会截出非法串；
     * 而只取首个候选同样不够——正文里先出现 `{注}` 这类片段就会整体失败。
     *
     * 日志只记录结构化信息（长度 / 异常类型），**不记录响应内容**：响应里是用户的体检指标。
     */
    private fun parseRecordsJson(text: String): List<ParsedRecord> {
        val candidates = extractJsonObjects(text)
        if (candidates.isEmpty()) {
            Log.w(TAG, "模型响应中未找到完整 JSON 对象（响应长度 ${text.length} 字符）")
            return emptyList()
        }
        val type = object : TypeToken<Map<String, List<ParsedRecord>>>() {}.type
        for (json in candidates) {
            try {
                val map: Map<String, List<ParsedRecord>>? = gson.fromJson(json, type)
                if (map != null && map.containsKey("records")) return map["records"].orEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "候选 JSON 解析失败：${e.javaClass.simpleName}（长度 ${json.length} 字符）")
            }
        }
        Log.w(TAG, "${candidates.size} 个候选 JSON 均未解析出 records 字段")
        return emptyList()
    }

    /** 按括号配平扫描出文本中所有顶层 JSON 对象（正确处理字符串内的引号与反斜杠转义） */
    private fun extractJsonObjects(text: String): List<String> {
        val results = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            if (text[i] != '{') {
                i++
                continue
            }
            var depth = 0
            var inString = false
            var escaped = false
            var end = -1
            var j = i
            while (j < text.length) {
                val c = text[j]
                when {
                    escaped -> escaped = false
                    inString && c == '\\' -> escaped = true
                    c == '"' -> inString = !inString
                    inString -> Unit
                    c == '{' -> depth++
                    c == '}' -> {
                        depth--
                        if (depth == 0) {
                            end = j
                            break
                        }
                    }
                }
                j++
            }
            if (end < 0) break // 未闭合，后续也不可能再构成完整对象
            results += text.substring(i, end + 1)
            i = end + 1
        }
        return results
    }

    private fun stripCodeBlock(text: String): String =
        text.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```")
            .trim()

    companion object {
        private const val TAG = "LlmClient"

        /** 输出被长度上限截断时的统一提示（区别于"图片不清晰"，避免用户反复重拍） */
        private const val TRUNCATED_HINT = "模型输出达到长度上限被截断，请分张拍摄或减少输入后重试"

        /** 重试上限（含首次请求）。解析场景刻意只用 2 次，理由见 chatCompletion 调用处 */
        private const val RETRY_ATTEMPTS_PARSE = 2
        private const val RETRY_ATTEMPTS_QA = 3

        /** 问答采样参数：0.3 兼顾稳定与表达；2048 足够一次健康解读 */
        private const val QA_TEMPERATURE = 0.3
        private const val QA_MAX_TOKENS = 2048

        /** 空响应的统一提示：流式与非流式共用，避免同一类故障出现两套措辞 */
        private const val EMPTY_RESPONSE_HINT = "模型响应为空，请检查 API Key 与模型名称是否正确"

        /** 现有配置完全没有视觉能力时的提示：给两条可操作路径，而不是笼统报错 */
        private const val VISION_UNSUPPORTED_HINT =
            "当前配置无法读取图片：请把「健康问答服务」的模型换成视觉模型" +
                "（如智谱的 glm-5.3-flash），或在「报告解析服务」中配置一个视觉模型"

        /** Anthropic 鉴权头与版本号（协议常量集中一处，流式 / 非流式共用） */
        private const val ANTHROPIC_VERSION_HEADER = "anthropic-version"
        private const val ANTHROPIC_VERSION = "2023-06-01"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
