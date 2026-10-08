package com.example.homehealth.data.repository

import com.example.homehealth.data.local.entity.FamilyMember
import com.example.homehealth.data.local.entity.HealthRecord
import com.example.homehealth.data.local.entity.profileSummary
import com.example.homehealth.data.remote.QaRetriever
import com.example.homehealth.util.DateUtils
import com.example.homehealth.util.HealthTypes

/**
 * 问答「数据依据」文本构建。
 *
 * 抽成纯函数对象（与仓库 / IO 无关）是为了可单测：这段文本是用户核对答案的依据，
 * 「档案-only 时依据块不为空」「编号与上文 [n] 对应」这类不变量值得被测试守住。
 */
internal object QaReferences {

    /**
     * 个人健康档案块。档案是用户自述的静态背景，参与了解读就应当让用户看得见 ——
     * 与指标记录并列展示。档案为空时返回空串。
     */
    fun profileBlock(profile: String): String {
        if (profile.isBlank()) return ""
        return buildString {
            appendLine("　【个人健康档案】（用户自述，静态背景）：")
            profile.lineSequence().forEach { line -> appendLine("　　$line") }
        }
    }

    /**
     * 全量摘要路径：泛化问题（"整体健康状况怎么样"）按「数据范围」给出依据 —— 多少条记录、覆盖哪些指标。
     * 没有任何记录但有档案时，档案就是这次回答的全部依据。两者皆空时返回空串（依据块为空）。
     */
    fun summary(member: FamilyMember, recordsByType: Map<String, List<HealthRecord>>): String = buildString {
        val total = recordsByType.values.sumOf { it.size }
        val profile = member.profileSummary()
        if (total == 0 && profile.isBlank()) return@buildString
        appendLine()
        appendLine("———")
        appendLine("📎 数据依据（该问题未指向具体指标，按全部已保存记录汇总）：")
        append(profileBlock(profile))
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
     * 检索路径：列出 BM25 召回的 Top-K 记录，编号与上下文里的 `[n]` 一一对应。
     * 带参考范围与偏高/偏低标注，用户可以直接拿它核对模型引用的每一个数字。
     */
    fun retrieved(member: FamilyMember, hits: List<QaRetriever.Scored>): String = buildString {
        appendLine()
        appendLine("———")
        appendLine("📎 数据依据（按相关度从已保存记录中检索出 ${hits.size} 条，编号与上文 [n] 对应）：")
        append(profileBlock(member.profileSummary()))
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
     * Agent 路径：直接复用检索类工具返回的明细原文（模型看到的就是这段文本，逐字复用才能对得上），
     * 并附上本次所用的个人档案。
     */
    fun agent(evidence: List<String>, member: FamilyMember): String {
        val blocks = evidence.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val profile = member.profileSummary()
        if (blocks.isEmpty() && profile.isBlank()) return ""
        return buildString {
            appendLine()
            appendLine("———")
            appendLine("📎 数据依据（Agent 通过工具获取，共 ${blocks.size} 段）：")
            append(profileBlock(profile))
            blocks.forEach { block ->
                block.lineSequence().forEach { line -> appendLine("　$line") }
            }
        }
    }

    /**
     * 记录相对参考范围的高/低标注。
     * 单位与字典预设不一致时**不下结论**（如维生素 D 记录是 nmol/L、参考范围按 ng/mL 标注），
     * 跨单位比较必然误判 —— 与问答上下文的口径保持一致。
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
}
