package com.example.homehealth.data.repository

import com.example.homehealth.data.local.entity.FamilyMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 问答路径决策的单测（纯 JVM）。
 *
 * 这段判断原先内联在 `QARepositoryImpl.askStream` 的巨大 `when` 里、无法测试；
 * 抽成纯函数后，把「什么情况下走哪条路径」这条最容易改坏的逻辑钉死。
 * 重点覆盖个人健康档案带来的新行为：**没有记录、没有附图，只有档案时也应走 LLM**。
 */
class QaRoutingTest {

    private fun member(
        bloodType: String? = null,
        diet: String? = null,
        chronic: String? = null
    ) = FamilyMember(
        id = "m1", name = "张三", relationship = "self",
        bloodType = bloodType, diet = diet, chronicConditions = chronic
    )

    // ---------- routeQa ----------

    @Test
    fun `有图但无视觉模型时优先告知`() {
        assertEquals(
            QaRoute.NO_VISION,
            routeQa(hasStrongTerms = true, noVision = true, llmReady = true, hasData = true)
        )
        // 即便没有任何上下文、Key 也没配，只要附图但发不出去，也必须如实告知
        assertEquals(
            QaRoute.NO_VISION,
            routeQa(hasStrongTerms = false, noVision = true, llmReady = false, hasData = false)
        )
    }

    @Test
    fun `命中强指标词走快路径`() {
        assertEquals(
            QaRoute.FAST,
            routeQa(hasStrongTerms = true, noVision = false, llmReady = true, hasData = true)
        )
    }

    @Test
    fun `无强指标词但有上下文走 Agent`() {
        assertEquals(
            QaRoute.AGENT,
            routeQa(hasStrongTerms = false, noVision = false, llmReady = true, hasData = true)
        )
    }

    @Test
    fun `无任何上下文回退离线引擎`() {
        assertEquals(
            QaRoute.OFFLINE,
            routeQa(hasStrongTerms = true, noVision = false, llmReady = true, hasData = false)
        )
        assertEquals(
            QaRoute.OFFLINE,
            routeQa(hasStrongTerms = false, noVision = false, llmReady = true, hasData = false)
        )
    }

    @Test
    fun `未配置 Key 时一律离线`() {
        assertEquals(
            QaRoute.OFFLINE,
            routeQa(hasStrongTerms = true, noVision = false, llmReady = false, hasData = true)
        )
        assertEquals(
            QaRoute.OFFLINE,
            routeQa(hasStrongTerms = false, noVision = false, llmReady = false, hasData = true)
        )
    }

    // ---------- hasQaContext：档案是否算「可用上下文」 ----------

    @Test
    fun `仅个人档案无记录无图也算有上下文`() {
        assertTrue(
            hasQaContext(
                member = member(bloodType = "O", diet = "salty", chronic = "高血压"),
                recordCount = 0,
                hasImage = false
            )
        )
    }

    @Test
    fun `空档案且无记录无图则无上下文`() {
        assertFalse(hasQaContext(member = member(), recordCount = 0, hasImage = false))
    }

    @Test
    fun `有记录或有图即有上下文`() {
        assertTrue(hasQaContext(member = member(), recordCount = 1, hasImage = false))
        assertTrue(hasQaContext(member = member(), recordCount = 0, hasImage = true))
    }

    @Test
    fun `仅档案时端到端路由到 LLM 而非离线`() {
        // 复现 askStream 的决策：有档案 → hasData 为真 → 无强指标词时走 Agent
        val hasData = hasQaContext(member = member(bloodType = "A"), recordCount = 0, hasImage = false)
        assertEquals(
            QaRoute.AGENT,
            routeQa(hasStrongTerms = false, noVision = false, llmReady = true, hasData = hasData)
        )
    }
}
