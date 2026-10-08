package com.example.homehealth.data.repository

import com.example.homehealth.data.local.entity.FamilyMember
import com.example.homehealth.data.local.entity.profileSummary

/**
 * 问答路径。由 `QARepositoryImpl.askStream` 的分支决策抽出，
 * 便于用纯 JVM 单测覆盖「什么情况下走哪条路径」——这类判断最容易被改坏，却最难用真机验证。
 */
internal enum class QaRoute {
    /** 附了图但没有可用视觉模型：明确告知，不发图 */
    NO_VISION,

    /** 多步交叉验证：手写 ReAct 循环 */
    AGENT,

    /** 命中强指标词：BM25 检索 + SSE 流式 */
    FAST,

    /** 本地模式 / 未配置 Key / 无任何可用上下文：离线规则引擎 */
    OFFLINE
}

/**
 * 决定一次提问走哪条路径。
 *
 * @param hasStrongTerms 问题里是否含「强指标词」（命中则优先快路径，而不是交给 Agent）
 * @param noVision 本轮附了图，但当前配置没有可用的视觉模型
 * @param llmReady 问答供应商已配置（非本地模式且有 Key）
 * @param hasData 是否有可用上下文（见 [hasQaContext]）
 */
internal fun routeQa(
    hasStrongTerms: Boolean,
    noVision: Boolean,
    llmReady: Boolean,
    hasData: Boolean
): QaRoute = when {
    // 「有图但发不出去」优先级最高：必须先如实告知，不能静默降级成纯文本回答
    noVision -> QaRoute.NO_VISION
    // 没有可靠指标词（泛化 / 需要多步）且有上下文 → Agent
    !hasStrongTerms && llmReady && hasData -> QaRoute.AGENT
    // 有强指标词（或 Agent 不可用）但有上下文 → 快路径
    llmReady && hasData -> QaRoute.FAST
    else -> QaRoute.OFFLINE
}

/**
 * 是否存在可用上下文。
 *
 * **个人健康档案也算**：成员还没录任何记录、也没附图时，
 * 「我这种饮食习惯要注意什么」这类问题依然应当由 LLM 结合档案作答 ——
 * 否则会落到离线引擎、档案被完全忽略。
 *
 * @param recordCount 该成员已保存的记录条数
 * @param hasImage 本轮是否附带了报告图片
 */
internal fun hasQaContext(
    member: FamilyMember,
    recordCount: Int,
    hasImage: Boolean
): Boolean = recordCount > 0 || hasImage || member.profileSummary().isNotBlank()
