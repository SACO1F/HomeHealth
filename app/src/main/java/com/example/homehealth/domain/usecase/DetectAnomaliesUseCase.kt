package com.example.homehealth.domain.usecase

import com.example.homehealth.data.local.entity.Alert
import com.example.homehealth.data.local.entity.AlertSeverity
import com.example.homehealth.data.local.entity.HealthRecord
import com.example.homehealth.domain.repository.AlertRepository
import com.example.homehealth.domain.repository.FamilyRepository
import com.example.homehealth.domain.repository.HealthRecordRepository
import com.example.homehealth.util.DetectionConfig
import com.example.homehealth.util.HealthTypes
import com.example.homehealth.util.SchemaNormalizer
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs

/**
 * 异常检测用例，三条规则：
 * 1. **越界**：与参考范围比较（参考范围按性别取值；区间型结果如 `<0.1` 按比较符保守判断）；
 * 2. **趋势**：最近三次同向且累计变化超阈值，且三次读数须落在
 *    [DetectionConfig.TREND_MAX_SPAN_MS] 时间窗内；
 * 3. **个体基线**：与「该成员自身历史的中位数」比较 —— 群体参考范围回答不了"对你来说是否异常"。
 *
 * 去重策略：同一 type+title 的预警在 [DetectionConfig.DEDUPE_WINDOW_MS] 时间窗口内只生成一次；
 * 窗口外允许重新生成（症状复发要能再次报警），窗口内严重度升级允许穿透（病情恶化要能升级报警）。
 *
 * **结构化告警**：除了成品文本（中文兜底），同时写入结构化事实
 * （[AlertFacts]），展示层据此按当前语言生成标题与正文 ——
 * 否则界面切英文后告警仍是中文，且参考范围调整后历史告警无法按新口径重算。
 */
class DetectAnomaliesUseCase @Inject constructor(
    private val familyRepository: FamilyRepository,
    private val healthRecordRepository: HealthRecordRepository,
    private val alertRepository: AlertRepository
) {

    /** 告警的结构化事实：展示层据此按当前语言生成标题与正文 */
    private data class AlertFacts(
        val metricType: String?,
        val direction: String,
        val valueText: String?,
        val unitText: String?,
        val refText: String?,
        /** 趋势 / 基线用的附加信息 */
        val fromText: String? = null,
        val toText: String? = null,
        val spanText: String? = null,
        val baselineText: String? = null,
        val historyCount: Int = 0
    )

    /** 对单个成员执行检测，返回新生成的预警数量 */
    suspend operator fun invoke(memberId: String): Int {
        // 取成员本身：异常判定需要性别（血红蛋白 / 肌酐 / 尿酸等参考区间男女不同）
        val member = familyRepository.getMember(memberId) ?: return 0

        // 去重只看窗口内的预警，而不是全部历史，也不是只看到未读：
        // - 只对未读去重（旧实现）：用户读掉后每日任务会重复生成同一条，形成骚扰；
        // - 对全历史去重：症状复发将永远不会再报警，且病情恶化会被吞掉。
        val since = System.currentTimeMillis() - DetectionConfig.DEDUPE_WINDOW_MS
        // 同键可能有多条（严重度升级会再生成一条），必须取「窗口内最高严重度」：
        // associate 在同键时保留哪一条取决于查询返回的行序，而该查询没有 ORDER BY —— 那是未定义行为
        val recentSeverity: Map<Pair<String, String>, AlertSeverity> =
            alertRepository.getByMemberSince(memberId, since)
                .groupBy({ it.type to it.title }, { it.severity })
                .mapValues { (_, severities) -> severities.maxBy { it.ordinal } }

        var created = 0

        /**
         * 落库。成品文本（[AlertFacts] 的中文渲染）作为兜底与老客户端兼容，
         * 结构化字段才是展示层真正用来本地化渲染的来源。
         */
        suspend fun createAlert(facts: AlertFacts, title: String, description: String, severity: AlertSeverity) {
            val kind = type_for(facts)
            val prev = recentSeverity[kind to title]
            // 窗口内已报过且严重度未升级 → 抑制；严重度升级必须放行
            if (prev != null && prev.ordinal >= severity.ordinal) return
            alertRepository.createAlert(
                Alert(
                    id = UUID.randomUUID().toString(),
                    memberId = memberId,
                    type = kind,
                    title = title,
                    description = description,
                    severity = severity,
                    createdDate = System.currentTimeMillis(),
                    isRead = false,
                    metricType = facts.metricType,
                    direction = facts.direction,
                    valueText = facts.valueText,
                    unitText = facts.unitText,
                    refText = facts.refText,
                    spanText = facts.spanText,
                    baselineText = facts.baselineText
                )
            )
            created++
        }

        for (metric in HealthTypes.ALL) {
            val recent = healthRecordRepository.getRecentRecords(memberId, metric, 10)
            if (recent.isEmpty()) continue

            // ---- 越界规则 ----
            checkOutOfRange(metric, recent.first(), member.gender, ::createAlert)

            // ---- 趋势规则：最近 3 条（升序），且三次读数须落在时间窗内 ----
            val asc = recent.take(3).reversed()
            if (asc.size == 3) checkTrend(metric, asc, ::createAlert)

            // ---- 个体基线规则：与自身历史中位数比较 ----
            checkPersonalBaseline(metric, recent, ::createAlert)
        }
        return created
    }

    /** 对所有成员执行检测 */
    suspend fun invokeAll(): Int {
        var total = 0
        for (member in familyRepository.getMembers()) {
            total += invoke(member.id)
        }
        return total
    }

    private suspend fun checkOutOfRange(
        metric: String,
        record: HealthRecord,
        gender: String?,
        create: suspend (AlertFacts, String, String, AlertSeverity) -> Unit
    ) {
        // 未换算的单位不能拿标准参考区间比较，否则会产生虚假的健康预警。
        if (SchemaNormalizer.normalizeUnit(record.unit) != HealthTypes.unit(metric)) return
        val dateStr = com.example.homehealth.util.DateUtils.formatDate(record.recordDate)

        // 血压特殊：收缩压/舒张压分别判断
        if (metric == HealthTypes.BLOOD_PRESSURE) {
            val parts = record.value.split("/")
            val sys = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: record.numericValue
            val dia = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
            val sysText = parts.getOrNull(0)?.trim() ?: sys?.toString() ?: "-"
            val diaText = parts.getOrNull(1)?.trim() ?: dia?.toString() ?: "-"
            if (sys != null && (sys >= 140 || (dia ?: 0.0) >= 90)) {
                create(
                    AlertFacts(
                        metricType = metric,
                        direction = Alert.DIRECTION_HIGH,
                        valueText = record.value.trim(),
                        unitText = "mmHg",
                        refText = "<140/90 mmHg"
                    ),
                    "血压偏高",
                    "收缩压 $sysText / 舒张压 $diaText mmHg（$dateStr）超出正常范围（<140/90 mmHg），建议关注并咨询医生",
                    AlertSeverity.HIGH
                )
            } else if (sys != null && sys < 90) {
                create(
                    AlertFacts(
                        metricType = metric,
                        direction = Alert.DIRECTION_LOW,
                        valueText = record.value.trim(),
                        unitText = "mmHg",
                        refText = "<140/90 mmHg"
                    ),
                    "血压偏低",
                    "收缩压 $sysText mmHg（$dateStr）低于 90 mmHg，如伴有头晕乏力请及时就医",
                    AlertSeverity.MEDIUM
                )
            }
            return
        }

        // 通用规则：按指标定义的参考上下限判断（覆盖全部 49 项指标）
        val def = HealthTypes.def(metric) ?: return
        val v = record.numericValue ?: return
        val label = def.label
        val unit = def.unit
        // 按性别取参考区间：血红蛋白 / 肌酐 / 尿酸等男女不同，用男女合并区间会系统性漏报
        val ref = def.rangeFor(gender)
        // 区间型结果（"<0.1" / ">100"）：只有边界值本身已越界时才敢下结论，
        // 否则会把"不确定是否越界"误报成确定结论
        val isLt = record.comparator == SchemaNormalizer.COMPARATOR_LT
        val isGt = record.comparator == SchemaNormalizer.COMPARATOR_GT
        val shown = record.value.trim().ifBlank { v.toString() }

        val aboveHigh = when {
            ref.high == null || isLt -> false
            isGt -> v >= ref.high
            else -> v > ref.high
        }
        val belowLow = when {
            ref.low == null || isGt -> false
            isLt -> v <= ref.low
            else -> v < ref.low
        }

        if (aboveHigh && ref.high != null) {
            val overRatio = (v - ref.high) / ref.high
            val severity = if (overRatio >= 0.5) AlertSeverity.HIGH else AlertSeverity.MEDIUM
            create(
                AlertFacts(
                    metricType = metric,
                    direction = Alert.DIRECTION_HIGH,
                    valueText = shown,
                    unitText = unit,
                    refText = ref.text
                ),
                "$label 偏高",
                "$label $shown $unit（$dateStr）超出参考范围（${ref.text}）" +
                    if (severity == AlertSeverity.HIGH) "，明显偏高，建议尽快就医复查" else "，建议关注并复查",
                severity
            )
        } else if (belowLow) {
            create(
                AlertFacts(
                    metricType = metric,
                    direction = Alert.DIRECTION_LOW,
                    valueText = shown,
                    unitText = unit,
                    refText = ref.text
                ),
                "$label 偏低",
                "$label $shown $unit（$dateStr）低于参考范围（${ref.text}），建议复查，如伴有不适请就医",
                AlertSeverity.MEDIUM
            )
        }
    }

    private suspend fun checkTrend(
        metric: String,
        asc: List<HealthRecord>,
        create: suspend (AlertFacts, String, String, AlertSeverity) -> Unit
    ) {
        val threshold = HealthTypes.TREND_THRESHOLDS[metric] ?: return
        if (asc.any { it.comparator != null ||
                SchemaNormalizer.normalizeUnit(it.unit) != HealthTypes.unit(metric) }) return
        val nums = asc.mapNotNull { it.numericValue }
        if (nums.size < 3) return

        // 时间窗：三次读数跨度过大时，「持续上升」这个结论没有意义 ——
        // 跨两年的三次读数与跨一周的三次读数不是同一件事
        val spanMs = asc.last().recordDate - asc.first().recordDate
        if (spanMs > DetectionConfig.TREND_MAX_SPAN_MS) return
        val spanDays = (spanMs / DetectionConfig.DAY_MS).coerceAtLeast(0)
        val spanText = if (spanDays <= 0) "同日" else "近 $spanDays 天"

        val rising = nums[0] < nums[1] && nums[1] < nums[2]
        val falling = nums[0] > nums[1] && nums[1] > nums[2]
        val totalDelta = nums[2] - nums[0]
        val label = HealthTypes.label(metric)
        val unit = HealthTypes.unit(metric)
        val from = formatDate(asc.first().recordDate)
        val to = formatDate(asc.last().recordDate)
        if (rising && totalDelta >= threshold) {
            create(
                AlertFacts(
                    metricType = metric,
                    direction = Alert.DIRECTION_RISING,
                    // 起止读数合并成一段，避免为文案再开两列
                    valueText = "${nums[0]} → ${nums[2]}",
                    unitText = unit,
                    refText = null,
                    spanText = if (spanDays <= 0) "same_day" else "days:$spanDays"
                ),
                "$label 呈持续上升趋势",
                "$from 至 $to（$spanText）期间，$label 从 ${nums[0]} 升至 ${nums[2]} $unit（累计上升 ${"%.1f".format(totalDelta)} $unit），请关注变化趋势",
                AlertSeverity.MEDIUM
            )
        } else if (falling && abs(totalDelta) >= threshold) {
            create(
                AlertFacts(
                    metricType = metric,
                    direction = Alert.DIRECTION_FALLING,
                    valueText = "${nums[0]} → ${nums[2]}",
                    unitText = unit,
                    refText = null,
                    spanText = if (spanDays <= 0) "same_day" else "days:$spanDays"
                ),
                "$label 呈持续下降趋势",
                "$from 至 $to（$spanText）期间，$label 从 ${nums[0]} 降至 ${nums[2]} $unit（累计下降 ${"%.1f".format(abs(totalDelta))} $unit），请关注变化趋势",
                AlertSeverity.MEDIUM
            )
        }
    }

    /**
     * 个体基线规则。
     *
     * 群体参考范围回答的是「是否超出正常值」，回答不了「对**你**来说是否异常」。
     * 这里用该成员自身历史的中位数作基线，捕捉在群体范围内看不到的显著变化。
     *
     * 有意**不**实现「长期稳定偏高就不再告警」这类抑制：血压长期 135 本身就是要关注的信息，
     * 不该因为"一直是这个值"而被静音 —— 那条路会掩盖真正的慢性问题。
     * 本规则是**补充**信号，不替代越界判断。
     */
    private suspend fun checkPersonalBaseline(
        metric: String,
        recentDesc: List<HealthRecord>,
        create: suspend (AlertFacts, String, String, AlertSeverity) -> Unit
    ) {
        val threshold = HealthTypes.TREND_THRESHOLDS[metric] ?: return
        val latest = recentDesc.firstOrNull() ?: return
        if (latest.comparator != null ||
            SchemaNormalizer.normalizeUnit(latest.unit) != HealthTypes.unit(metric)) return
        val current = latest.numericValue ?: return
        // 至少要有足够历史才谈得上"基线"，否则等于拿两三个点硬编一个基准出来
        val history = recentDesc.drop(1)
            .filter { it.comparator == null &&
                SchemaNormalizer.normalizeUnit(it.unit) == HealthTypes.unit(metric) }
            .mapNotNull { it.numericValue }
        if (history.size < DetectionConfig.BASELINE_MIN_SAMPLES) return

        val baseline = median(history)
        val delta = current - baseline
        if (abs(delta) < threshold) return

        val label = HealthTypes.label(metric)
        val unit = HealthTypes.unit(metric)
        val rising = delta > 0
        val directionText = if (rising) "升高" else "降低"
        // 偏离方向"更差"时给 MEDIUM，方向"变好"时只给 LOW —— 后者同样值得记录，但不该同等告警
        val worse = if (rising) {
            HealthTypes.higherIsWorse(metric)
        } else {
            !HealthTypes.higherIsWorse(metric)
        }
        val deltaText = "%.1f".format(abs(delta))
        val baselineText = "%.1f".format(baseline)
        create(
            AlertFacts(
                metricType = metric,
                direction = if (rising) Alert.DIRECTION_BASELINE_UP else Alert.DIRECTION_BASELINE_DOWN,
                valueText = latest.value.trim(),
                unitText = unit,
                refText = null,
                baselineText = baselineText,
                historyCount = history.size
            ),
            "$label 较个人基线明显$directionText",
            "$label 当前 ${latest.value.trim()} $unit，" +
                "较该成员自身近 ${history.size} 次记录的中位值 $baselineText $unit" +
                "$directionText $deltaText $unit。" +
                "这是相对自身历史的变化，请结合参考范围一并判断",
            if (worse) AlertSeverity.MEDIUM else AlertSeverity.LOW
        )
    }

    /** 中位数（偶数个取中间两者平均） */
    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** 兼容旧调用的告警种类推导（越界 / 趋势 / 基线） */
    private fun type_for(facts: AlertFacts): String = when (facts.direction) {
        Alert.DIRECTION_HIGH, Alert.DIRECTION_LOW -> "out_of_range"
        Alert.DIRECTION_RISING, Alert.DIRECTION_FALLING -> "trend_anomaly"
        else -> "baseline_shift"
    }

    private fun formatDate(ts: Long): String =
        com.example.homehealth.util.DateUtils.formatDate(ts)
}
