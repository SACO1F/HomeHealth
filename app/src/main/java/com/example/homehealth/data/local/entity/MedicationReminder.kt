package com.example.homehealth.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 用药提醒 */
@Entity(tableName = "medication_reminders")
data class MedicationReminder(
    @PrimaryKey val id: String,
    val memberId: String,
    val medicationName: String,
    val dosage: String,
    val schedule: String, // 如 "daily:08:00,20:00"
    val startDate: Long,
    val endDate: Long? = null,
    val active: Boolean = true,
    /** 日历事件 ID（逗号分隔）；null=未启用日历同步，空串=暂停期间已清理事件。 */
    val calendarEventIds: String? = null
) {
    /** 解析出每日时间点列表，如 ["08:00", "20:00"] */
    fun dailyTimes(): List<String> =
        schedule.removePrefix("daily:")
            .split(",")
            .map { it.trim() }
            .filter { isValidTime(it) }

    /** 已写入日历的事件 ID 列表 */
    fun calendarEventIdList(): List<Long> =
        calendarEventIds?.split(",")
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?: emptyList()

    companion object {
        fun isValidTime(value: String): Boolean {
            val parts = value.split(':')
            return parts.size == 2 && parts[0].length in 1..2 && parts[1].length == 2 &&
                parts[0].all(Char::isDigit) && parts[1].all(Char::isDigit) &&
                (parts[0].toIntOrNull() ?: 24) in 0..23 &&
                (parts[1].toIntOrNull() ?: 60) in 0..59
        }

        fun buildSchedule(times: List<String>): String = "daily:" + times.joinToString(",")

        fun buildCalendarEventIds(ids: List<Long>): String? =
            ids.takeIf { it.isNotEmpty() }?.joinToString(",")
    }
}
