package com.example.homehealth.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 指标字典的「本地化契约」测试。
 *
 * 背景：`HealthTypes.labelRes` 对未收录类型返回 0，而 `stringResource(0)` 会在运行时抛
 * `Resources$NotFoundException`（这是历史上真实出现过的崩溃）。所有 UI 调用点现在都经由
 * 带兜底的 `ui/components/CommonComponents.metricLabel()`；这里再从数据侧加一道闸：
 * **凡是定义进字典的指标，都必须有对应的字符串资源**，否则一旦有人往 `DEFS` 里加指标
 * 却忘了补 `labelRes` 映射，测试会立刻拦下，而不是等到用户看到崩溃。
 */
class HealthTypesTest {

    @Test
    fun `every defined metric has a non-zero label resource`() {
        HealthTypes.ALL.forEach { type ->
            assertNotEquals(
                "指标「$type」缺少字符串资源映射（labelRes 返回 0），会在 UI 触发崩溃",
                0,
                HealthTypes.labelRes(type)
            )
        }
    }

    @Test
    fun `unknown metric type returns zero so callers must fall back`() {
        // 契约：字典外类型返回 0，调用方（metricLabel）据此回退显示原始类型名
        assertEquals(0, HealthTypes.labelRes("__not_a_defined_metric__"))
    }
}
