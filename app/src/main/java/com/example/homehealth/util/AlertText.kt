package com.example.homehealth.util

import android.content.Context
import com.example.homehealth.R
import com.example.homehealth.data.local.entity.Alert
import com.example.homehealth.data.local.entity.AlertSeverity

/**
 * 告警文案渲染器。
 *
 * 告警不再只读落库的成品文本：结构化字段齐全时（新告警），标题与正文按**当前语言**
 * 现场生成。这解决了两件事：
 * 1. UI 切英文后告警正文仍是中文；
 * 2. 参考范围或阈值调整后，历史告警无法按新口径重算。
 *
 * 结构化字段缺失（老数据、或将来新增未覆盖的 kind）时，**回退到落库的成品文本** ——
 * `Alert` 同时保留成品文本正是为这个兜底，二者互为补充、缺一不可。
 *
 * **渲染永不抛异常**：模板与参数不匹配（MissingFormatArgumentException）、
 * 未知指标（labelRes 返回 0）这类错误一律降级为落库文本。
 * 教训来源：2026-09-15 真机闪退 —— 越界类文案漏传日期参数，
 * 占位符 %5$s 找不到实参直接把预警页带崩。文案渲染层必须有最后一道兜底。
 */
object AlertText {

    fun title(context: Context, alert: Alert): String {
        val metricType = alert.metricType ?: return alert.title
        val resId = HealthTypes.labelRes(metricType)
        if (resId == 0) return alert.title
        val label = context.getString(resId)
        return when (alert.direction) {
            Alert.DIRECTION_HIGH ->
                fmt(context, R.string.alert_title_metric_high, alert.title, label)
            Alert.DIRECTION_LOW ->
                fmt(context, R.string.alert_title_metric_low, alert.title, label)
            Alert.DIRECTION_RISING ->
                fmt(context, R.string.alert_title_trend_rising, alert.title, label)
            Alert.DIRECTION_FALLING ->
                fmt(context, R.string.alert_title_trend_falling, alert.title, label)
            Alert.DIRECTION_BASELINE_UP ->
                fmt(context, R.string.alert_title_baseline_up, alert.title, label)
            Alert.DIRECTION_BASELINE_DOWN ->
                fmt(context, R.string.alert_title_baseline_down, alert.title, label)
            else -> alert.title
        }
    }

    fun description(context: Context, alert: Alert): String {
        val metricType = alert.metricType ?: return alert.description
        val resId = HealthTypes.labelRes(metricType)
        if (resId == 0) return alert.description
        val label = context.getString(resId)
        val value = alert.valueText.orEmpty()
        val unit = alert.unitText.orEmpty()
        val ref = alert.refText.orEmpty()
        val span = when (val stored = alert.spanText.orEmpty()) {
            "same_day", "同日" -> context.getString(R.string.alert_span_same_day)
            else -> {
                val days = stored.removePrefix("days:").removePrefix("近 ").removeSuffix(" 天").toIntOrNull()
                if (days != null) context.getString(R.string.alert_span_days, days) else stored
            }
        }
        // 越界类文案的第 4 个占位符是「检测日期」，来自告警的创建时间
        val date = DateUtils.formatDate(alert.createdDate)

        return when (alert.direction) {
            Alert.DIRECTION_HIGH ->
                if (alert.severity == AlertSeverity.HIGH) {
                    fmt(
                        context, R.string.alert_desc_metric_high_severe, alert.description,
                        label, value, unit, date, ref
                    )
                } else {
                    fmt(
                        context, R.string.alert_desc_metric_high, alert.description,
                        label, value, unit, date, ref
                    )
                }
            Alert.DIRECTION_LOW ->
                fmt(
                    context, R.string.alert_desc_metric_low, alert.description,
                    label, value, unit, date, ref
                )
            Alert.DIRECTION_RISING ->
                fmt(context, R.string.alert_desc_trend, alert.description, label, value, unit, span)
            Alert.DIRECTION_FALLING ->
                fmt(context, R.string.alert_desc_trend, alert.description, label, value, unit, span)
            Alert.DIRECTION_BASELINE_UP, Alert.DIRECTION_BASELINE_DOWN ->
                fmt(
                    context, R.string.alert_desc_baseline, alert.description,
                    label, value, unit, alert.baselineText.orEmpty()
                )
            else -> alert.description
        }
    }

    /** 格式化失败的兜底：占位符与实参不匹配时降级为落库成品文本，绝不抛异常 */
    private fun fmt(context: Context, resId: Int, fallback: String, vararg args: String): String =
        runCatching { context.getString(resId, *args) }.getOrDefault(fallback)
}
