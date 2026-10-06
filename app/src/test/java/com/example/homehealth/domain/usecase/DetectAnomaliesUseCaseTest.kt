package com.example.homehealth.domain.usecase

import com.example.homehealth.data.local.dao.AlertWithMemberName
import com.example.homehealth.data.local.entity.Alert
import com.example.homehealth.data.local.entity.AlertSeverity
import com.example.homehealth.data.local.entity.FamilyMember
import com.example.homehealth.data.local.entity.HealthRecord
import com.example.homehealth.domain.repository.AlertRepository
import com.example.homehealth.domain.repository.FamilyRepository
import com.example.homehealth.domain.repository.HealthRecordRepository
import com.example.homehealth.util.DetectionConfig
import com.example.homehealth.util.HealthTypes
import com.example.homehealth.util.SchemaNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2 检测引擎三规则（越界 / 趋势 / 个体基线）JVM 单测。
 *
 * 覆盖：正常样本、阈值边界（参考边界值本身不报警）、严重度分级（超限 50% 升 HIGH）、
 * 性别分层（血红蛋白男女区间不同）、区间型比较符的保守判断（`<0.1` 不报确定结论）、
 * 趋势时间窗（180 天）与单调性、基线样本数门槛、方向好坏的严重度区分、
 * 去重窗口内的抑制与严重度升级放行。
 */
class DetectAnomaliesUseCaseTest {

    private val dayMs = DetectionConfig.DAY_MS
    private val now = System.currentTimeMillis()

    private fun member(gender: String?) = FamilyMember(
        id = "m1", name = "测试成员", relationship = "本人", gender = gender
    )

    private fun record(
        type: String,
        value: String,
        numericValue: Double?,
        unit: String,
        daysAgo: Long,
        comparator: String? = null
    ): HealthRecord = HealthRecord(
        id = "$type-$daysAgo",
        memberId = "m1",
        type = type,
        value = value,
        numericValue = numericValue,
        unit = unit,
        recordDate = now - daysAgo * dayMs,
        comparator = comparator
    )

    /** 单指标场景：其余 48 项指标一律无记录 */
    private class FakeRecordRepository(
        private val type: String,
        private val records: List<HealthRecord> // 已按时间倒序
    ) : HealthRecordRepository {
        override fun observeRecordsByType(memberId: String, type: String): Flow<List<HealthRecord>> = flowOf(emptyList())
        override fun observeAllByMember(memberId: String): Flow<List<HealthRecord>> = flowOf(emptyList())
        override fun observeAllRecords(): Flow<List<HealthRecord>> = flowOf(emptyList())
        override suspend fun getRecentRecords(memberId: String, type: String, limit: Int): List<HealthRecord> =
            if (type == this.type) records.take(limit) else emptyList()
        override suspend fun getRecentByMember(memberId: String, limit: Int): List<HealthRecord> = emptyList()
        override suspend fun getAllByMember(memberId: String): List<HealthRecord> = records
        override suspend fun getLatest(memberId: String, type: String): HealthRecord? = null
        override suspend fun addRecord(record: HealthRecord) {}
        override suspend fun addRecords(records: List<HealthRecord>) {}
        override suspend fun updateRecord(record: HealthRecord) {}
        override suspend fun deleteRecord(record: HealthRecord) {}
        override suspend fun getAllRecords(): List<HealthRecord> = records
    }

    private class FakeFamilyRepository(private val member: FamilyMember?) : FamilyRepository {
        override fun observeMembers(): Flow<List<FamilyMember>> = flowOf(emptyList())
        override suspend fun getMember(id: String): FamilyMember? = member
        override suspend fun getMembers(): List<FamilyMember> = member?.let { listOf(it) } ?: emptyList()
        override suspend fun upsertMember(member: FamilyMember) {}
        override suspend fun deleteMember(member: FamilyMember) {}
    }

    private class FakeAlertRepository(existing: List<Alert> = emptyList()) : AlertRepository {
        val created = mutableListOf<Alert>()
        private val existing = existing.toList()
        override fun observeAll(): Flow<List<AlertWithMemberName>> = flowOf(emptyList())
        override fun observeUnread(): Flow<List<AlertWithMemberName>> = flowOf(emptyList())
        override fun observeUnreadCount(memberId: String): Flow<Int> = flowOf(0)
        override suspend fun createAlert(alert: Alert) {
            created.add(alert)
        }
        override suspend fun markRead(id: String) {}
        override suspend fun markAllRead() {}
        override suspend fun deleteAlert(id: String) {}
        override suspend fun getByMemberSince(memberId: String, since: Long): List<Alert> = this.existing
        override suspend fun getAllAlerts(): List<Alert> = this.existing
    }

    private fun runDetection(
        gender: String? = "male",
        type: String = HealthTypes.BLOOD_GLUCOSE,
        records: List<HealthRecord>,
        existing: List<Alert> = emptyList()
    ): Pair<Int, List<Alert>> {
        val alerts = FakeAlertRepository(existing)
        val useCase = DetectAnomaliesUseCase(
            FakeFamilyRepository(member(gender)),
            FakeRecordRepository(type, records),
            alerts
        )
        val created = runBlocking { useCase("m1") }
        return created to alerts.created
    }

    private fun existingAlert(title: String, severity: AlertSeverity) = Alert(
        id = "old", memberId = "m1", type = "out_of_range",
        title = title, description = "旧预警", severity = severity, createdDate = now - dayMs
    )

    // ---------- 越界规则 ----------

    @Test
    fun `参考范围内的读数不报警`() {
        val (created, alerts) = runDetection(records = listOf(record(HealthTypes.BLOOD_GLUCOSE, "5.5", 5.5, "mmol/L", 1)))
        assertEquals(0, created)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `未换算的单位不参与标准区间判断`() {
        val (created, alerts) = runDetection(records = listOf(
            record(HealthTypes.BLOOD_GLUCOSE, "100", 100.0, "mg/dL", 1)
        ))
        assertEquals(0, created)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `参考边界值本身不报警（严格大于小于）`() {
        // 6.1 == high、3.9 == low：都不应触发
        val (high, _) = runDetection(records = listOf(record(HealthTypes.BLOOD_GLUCOSE, "6.1", 6.1, "mmol/L", 1)))
        val (low, _) = runDetection(records = listOf(record(HealthTypes.BLOOD_GLUCOSE, "3.9", 3.9, "mmol/L", 1)))
        assertEquals(0, high)
        assertEquals(0, low)
    }

    @Test
    fun `越界严重度分级-超限50pct升HIGH`() {
        val (medium, mediumAlerts) = runDetection(records = listOf(record(HealthTypes.BLOOD_GLUCOSE, "6.7", 6.7, "mmol/L", 1)))
        assertEquals(1, medium)
        assertEquals(AlertSeverity.MEDIUM, mediumAlerts.single().severity)
        assertEquals("空腹血糖 偏高", mediumAlerts.single().title)

        val (high, highAlerts) = runDetection(records = listOf(record(HealthTypes.BLOOD_GLUCOSE, "9.2", 9.2, "mmol/L", 1)))
        assertEquals(1, high)
        assertEquals(AlertSeverity.HIGH, highAlerts.single().severity)
    }

    @Test
    fun `低于下限报偏低`() {
        val (created, alerts) = runDetection(records = listOf(record(HealthTypes.BLOOD_GLUCOSE, "3.5", 3.5, "mmol/L", 1)))
        assertEquals(1, created)
        assertEquals(Alert.DIRECTION_LOW, alerts.single().direction)
        assertEquals(AlertSeverity.MEDIUM, alerts.single().severity)
    }

    @Test
    fun `性别分层-同一血红蛋白读数男女结论不同`() {
        // 血红蛋白：男 130-175 / 女 115-150。120 对男性偏低，对女性正常
        val hb = { daysAgo: Long -> record("hemoglobin", "120", 120.0, "g/L", daysAgo) }
        val (maleCreated, maleAlerts) = runDetection(gender = "male", type = "hemoglobin", records = listOf(hb(1)))
        assertEquals(1, maleCreated)
        assertEquals("血红蛋白 偏低", maleAlerts.single().title)

        val (femaleCreated, _) = runDetection(gender = "female", type = "hemoglobin", records = listOf(hb(1)))
        assertEquals(0, femaleCreated)
    }

    @Test
    fun `区间型结果-比较符保守判断`() {
        // "<3.4"（LT）：实际值可能远小于 3.4，但边界本身未越下界……这里下限为 null，
        // LT 记录只可能因上限报警，而 LT 永不判「高于」—— 必须静默
        val lt = runDetection(
            type = HealthTypes.LDL,
            records = listOf(record(HealthTypes.LDL, "<3.4", 3.4, "mmol/L", 1, comparator = SchemaNormalizer.COMPARATOR_LT))
        )
        assertEquals(0, lt.first)

        // ">4.0"（GT）：4.0 已越过上限 3.4，即便实际值可能更高也确定越界 → 报警
        val gt = runDetection(
            type = HealthTypes.LDL,
            records = listOf(record(HealthTypes.LDL, ">4.0", 4.0, "mmol/L", 1, comparator = SchemaNormalizer.COMPARATOR_GT))
        )
        assertEquals(1, gt.first)
        assertEquals(Alert.DIRECTION_HIGH, gt.second.single().direction)
    }

    @Test
    fun `血压走收缩压舒张压分支`() {
        val high = runDetection(
            type = HealthTypes.BLOOD_PRESSURE,
            records = listOf(record(HealthTypes.BLOOD_PRESSURE, "150/95", 150.0, "mmHg", 1))
        )
        assertEquals(1, high.first)
        assertEquals("血压偏高", high.second.single().title)
        assertEquals(AlertSeverity.HIGH, high.second.single().severity)

        val low = runDetection(
            type = HealthTypes.BLOOD_PRESSURE,
            records = listOf(record(HealthTypes.BLOOD_PRESSURE, "85/55", 85.0, "mmHg", 1))
        )
        assertEquals(1, low.first)
        assertEquals("血压偏低", low.second.single().title)

        val normal = runDetection(
            type = HealthTypes.BLOOD_PRESSURE,
            records = listOf(record(HealthTypes.BLOOD_PRESSURE, "120/80", 120.0, "mmHg", 1))
        )
        assertEquals(0, normal.first)
    }

    // ---------- 趋势规则 ----------

    @Test
    fun `三次同向且累计变化超阈值-报持续上升趋势`() {
        val records = listOf(
            record(HealthTypes.BLOOD_GLUCOSE, "5.8", 5.8, "mmol/L", 5),
            record(HealthTypes.BLOOD_GLUCOSE, "5.2", 5.2, "mmol/L", 25),
            record(HealthTypes.BLOOD_GLUCOSE, "4.6", 4.6, "mmol/L", 45)
        )
        val (created, alerts) = runDetection(records = records)
        assertEquals(1, created)
        val alert = alerts.single()
        assertEquals("空腹血糖 呈持续上升趋势", alert.title)
        assertEquals(Alert.DIRECTION_RISING, alert.direction)
        assertEquals(AlertSeverity.MEDIUM, alert.severity)
        assertTrue(alert.spanText!!.contains("40"))
    }

    @Test
    fun `三次读数超出180天时间窗不构成趋势`() {
        val records = listOf(
            record(HealthTypes.BLOOD_GLUCOSE, "6.0", 6.0, "mmol/L", 5),
            record(HealthTypes.BLOOD_GLUCOSE, "4.8", 4.8, "mmol/L", 100),
            record(HealthTypes.BLOOD_GLUCOSE, "4.0", 4.0, "mmol/L", 200)
        )
        val (created, alerts) = runDetection(records = records)
        assertEquals(0, created)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `区间读数与异单位读数不构成趋势`() {
        val base = listOf(
            record(HealthTypes.BLOOD_GLUCOSE, "5.8", 5.8, "mmol/L", 5),
            record(HealthTypes.BLOOD_GLUCOSE, "5.2", 5.2, "mmol/L", 25),
            record(HealthTypes.BLOOD_GLUCOSE, "4.6", 4.6, "mmol/L", 45)
        )
        assertEquals(0, runDetection(records = base.toMutableList().apply {
            this[1] = this[1].copy(value = "<5.2", comparator = SchemaNormalizer.COMPARATOR_LT)
        }).first)
        assertEquals(0, runDetection(records = base.toMutableList().apply {
            this[1] = this[1].copy(unit = "mg/dL")
        }).first)
    }

    @Test
    fun `非单调或累计变化不足阈值不报趋势`() {
        val nonMonotonic = runDetection(
            records = listOf(
                record(HealthTypes.BLOOD_GLUCOSE, "5.5", 5.5, "mmol/L", 5),
                record(HealthTypes.BLOOD_GLUCOSE, "6.0", 6.0, "mmol/L", 25),
                record(HealthTypes.BLOOD_GLUCOSE, "4.5", 4.5, "mmol/L", 45)
            )
        )
        assertEquals(0, nonMonotonic.first)

        val belowThreshold = runDetection(
            records = listOf(
                record(HealthTypes.BLOOD_GLUCOSE, "5.6", 5.6, "mmol/L", 5),
                record(HealthTypes.BLOOD_GLUCOSE, "5.3", 5.3, "mmol/L", 25),
                record(HealthTypes.BLOOD_GLUCOSE, "5.0", 5.0, "mmol/L", 45)
            )
        )
        assertEquals(0, belowThreshold.first)
    }

    // ---------- 个体基线规则 ----------

    @Test
    fun `偏离个人中位数超阈值-报警且方向更差时MEDIUM`() {
        val records = listOf(
            record(HealthTypes.BLOOD_GLUCOSE, "6.0", 6.0, "mmol/L", 1)
        ) + (1..5).map { record(HealthTypes.BLOOD_GLUCOSE, "5.0", 5.0, "mmol/L", it * 10L) }
        val (created, alerts) = runDetection(records = records)
        assertEquals(1, created)
        val alert = alerts.single()
        assertEquals(Alert.DIRECTION_BASELINE_UP, alert.direction)
        assertEquals(AlertSeverity.MEDIUM, alert.severity)
        assertEquals("5.0", alert.baselineText)
    }

    @Test
    fun `向好的方向变化只给LOW`() {
        // 血糖下降属于改善（higherIsWorse=true，下降 = 变好）→ LOW
        val records = listOf(
            record(HealthTypes.BLOOD_GLUCOSE, "4.0", 4.0, "mmol/L", 1)
        ) + (1..5).map { record(HealthTypes.BLOOD_GLUCOSE, "5.5", 5.5, "mmol/L", it * 10L) }
        val (created, alerts) = runDetection(records = records)
        assertEquals(1, created)
        assertEquals(Alert.DIRECTION_BASELINE_DOWN, alerts.single().direction)
        assertEquals(AlertSeverity.LOW, alerts.single().severity)
    }

    @Test
    fun `升高的好方向指标只给LOW`() {
        // HDL 升高是好事（higherIsWorse=false）→ LOW
        val records = listOf(
            record(HealthTypes.HDL, "1.5", 1.5, "mmol/L", 1)
        ) + (1..5).map { record(HealthTypes.HDL, "1.0", 1.0, "mmol/L", it * 10L) }
        val (created, alerts) = runDetection(gender = "male", type = HealthTypes.HDL, records = records)
        assertEquals(1, created)
        assertEquals(AlertSeverity.LOW, alerts.single().severity)
    }

    @Test
    fun `历史样本不足基线门槛不报警`() {
        val records = listOf(
            record(HealthTypes.BLOOD_GLUCOSE, "6.0", 6.0, "mmol/L", 1)
        ) + (1..(DetectionConfig.BASELINE_MIN_SAMPLES - 1)).map {
            record(HealthTypes.BLOOD_GLUCOSE, "5.0", 5.0, "mmol/L", it * 10L)
        }
        val (created, alerts) = runDetection(records = records)
        assertEquals(0, created)
        assertTrue(alerts.isEmpty())
    }

    // ---------- 去重与升级 ----------

    @Test
    fun `去重窗口内同严重度被抑制`() {
        val records = listOf(record(HealthTypes.BLOOD_GLUCOSE, "6.7", 6.7, "mmol/L", 1))
        val (created, alerts) = runDetection(
            records = records,
            existing = listOf(existingAlert("空腹血糖 偏高", AlertSeverity.MEDIUM))
        )
        assertEquals(0, created)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `严重度升级允许穿透`() {
        // 已有 MEDIUM，本次 9.2 判 HIGH → 放行
        val records = listOf(record(HealthTypes.BLOOD_GLUCOSE, "9.2", 9.2, "mmol/L", 1))
        val (created, alerts) = runDetection(
            records = records,
            existing = listOf(existingAlert("空腹血糖 偏高", AlertSeverity.MEDIUM))
        )
        assertEquals(1, created)
        assertEquals(AlertSeverity.HIGH, alerts.single().severity)
    }
}
