package com.example.homehealth.data.repository

import com.example.homehealth.data.local.entity.FamilyMember
import com.example.homehealth.data.local.entity.HealthRecord
import com.example.homehealth.data.remote.QaRetriever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「数据依据」文本构建的单测（纯 JVM）。
 *
 * 这段文本是用户核对 AI 答案的依据，几条不变量值得被测试守住：
 * - 档案-only 时依据块**不为空**（本次改动的直接目的）；
 * - 有记录时同时给出指标范围与档案；
 * - 档案与记录都为空时返回空串（调用方据此不展示依据块）。
 */
class QaReferencesTest {

    private fun member(
        name: String = "张三",
        bloodType: String? = null,
        diet: String? = null,
        chronic: String? = null,
        surgery: String? = null
    ) = FamilyMember(
        id = "m1", name = name, relationship = "self",
        bloodType = bloodType, diet = diet,
        chronicConditions = chronic, surgeryHistory = surgery
    )

    private fun record(
        type: String,
        value: String,
        unit: String,
        numeric: Double?
    ) = HealthRecord(
        id = "r-$type", memberId = "m1", type = type, value = value,
        numericValue = numeric, unit = unit, recordDate = 1_700_000_000_000L
    )

    // ---------- profileBlock ----------

    @Test
    fun `空档案返回空串`() {
        assertEquals("", QaReferences.profileBlock(""))
        assertEquals("", QaReferences.profileBlock("   "))
    }

    @Test
    fun `非空档案渲染为带标题的块`() {
        val block = QaReferences.profileBlock("血型：O 型")
        assertTrue(block.contains("个人健康档案"))
        assertTrue(block.contains("血型：O 型"))
    }

    // ---------- summary（全量摘要路径） ----------

    @Test
    fun `档案为空且无记录时依据块为空`() {
        assertEquals("", QaReferences.summary(member(), emptyMap()))
    }

    @Test
    fun `仅档案无记录时依据块不为空并列出各项档案`() {
        val text = QaReferences.summary(
            member(bloodType = "O", diet = "salty", chronic = "高血压", surgery = "阑尾切除"),
            emptyMap()
        )
        assertTrue(text.isNotBlank())
        assertTrue(text.contains("个人健康档案"))
        assertTrue(text.contains("血型：O 型"))
        assertTrue(text.contains("饮食习惯：口味偏咸"))
        assertTrue(text.contains("慢性病史：高血压"))
        assertTrue(text.contains("手术史：阑尾切除"))
        assertTrue(text.contains("暂无健康记录"))
    }

    @Test
    fun `有记录时列出指标并同时带上档案`() {
        val text = QaReferences.summary(
            member(bloodType = "A"),
            mapOf("blood_glucose" to listOf(record("blood_glucose", "6.2", "mmol/L", 6.2)))
        )
        assertTrue(text.contains("参与分析的指标"))
        assertTrue(text.contains("空腹血糖×1"))
        assertTrue(text.contains("血型：A 型"))
        assertFalse(text.contains("暂无健康记录"))
    }

    // ---------- agent（Agent 路径） ----------

    @Test
    fun `Agent 依据同时含档案与工具片段`() {
        val text = QaReferences.agent(
            listOf("已找到 1 条记录（成员：张三）：\n[1] 空腹血糖 · 2023-11-15 · 6.2 mmol/L"),
            member(bloodType = "B", chronic = "糖尿病")
        )
        assertTrue(text.contains("个人健康档案"))
        assertTrue(text.contains("血型：B 型"))
        assertTrue(text.contains("慢性病史：糖尿病"))
        assertTrue(text.contains("[1] 空腹血糖"))
    }

    @Test
    fun `Agent 无证据且无档案时依据块为空`() {
        assertEquals("", QaReferences.agent(emptyList(), member()))
    }

    // ---------- retrieved（检索路径） ----------

    @Test
    fun `检索依据带引用编号并同时带上档案`() {
        val hits = listOf(
            QaRetriever.Scored(record("ldl", "3.9", "mmol/L", 3.9), score = 1.0, rank = 1)
        )
        val text = QaReferences.retrieved(member(bloodType = "O"), hits)
        assertTrue(text.contains("[1]"))
        assertTrue(text.contains("低密度脂蛋白"))
        assertTrue(text.contains("血型：O 型"))
    }
}
