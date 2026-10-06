package com.example.homehealth.util

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.example.homehealth.R
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.io.IOException
import com.example.homehealth.data.local.entity.MedicationReminder

/**
 * 本地日历接入：把用药提醒写入系统日历（每日重复事件 + 提前提醒）。
 * 无日历账户时抛出明确异常，由界面提示。
 */
object CalendarEventHelper {

    /** 找第一个可写日历，找不到返回 null */
    private fun firstWritableCalendarId(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "false")
            .build()
        context.contentResolver.query(
            uri, projection, null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val access = cursor.getInt(1)
                if (access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) return id
            }
        }
        return null
    }

    /**
     * 写入一个用药提醒：每个服药时间点一个每日重复日历事件（持续 30 分钟），
     * 事件开始时间为「今天或明天最近的该时刻」，并挂 5 分钟前的通知提醒。
     *
     * @param times 每日服药时间点，如 ["08:00", "20:00"]
     * @return 成功写入的日历事件 ID 列表（用于删除提醒时同步清理日历）
     */
    fun insertMedicationEvents(
        context: Context,
        medicationName: String,
        dosage: String,
        memberName: String,
        times: List<String>,
        reminderId: String
    ): List<Long> {
        val calendarId = firstWritableCalendarId(context)
            ?: throw IllegalStateException("设备上没有可写的日历账户")

        val eventIds = mutableListOf<Long>()
        val now = System.currentTimeMillis()

        try {
        times.filter(MedicationReminder::isValidTime).forEach { time ->
            val (hour, minute) = time.split(":").map { it.toInt() }
            // 下一次该时刻出现的时间（今天已过则从明天开始）
            val start = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
            val end = start + TimeUnit.MINUTES.toMillis(30)

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE,
                    context.getString(R.string.calendar_medication_title, medicationName))
                put(
                    CalendarContract.Events.DESCRIPTION,
                    context.getString(R.string.calendar_medication_description, memberName, dosage) +
                        " [homehealth:med:$reminderId]"
                )
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, end)
                // 用设备当前时区：硬编码 "Asia/Shanghai" 会让非东八区用户的事件时间整体偏移
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                put(CalendarContract.Events.RRULE, "FREQ=DAILY")
                put(CalendarContract.Events.HAS_ALARM, 1)
            }

            val eventUri = context.contentResolver
                .insert(CalendarContract.Events.CONTENT_URI, values)
                ?: throw IOException("无法创建日历事件")
            val eventId = eventUri.lastPathSegment?.toLongOrNull()
                ?: throw IOException("无法读取日历事件 ID")
            eventIds.add(eventId)

            // 事件提醒：开始前 5 分钟通知
            val reminderValues = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, 5)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            context.contentResolver
                .insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
                ?: throw IOException("无法创建日历通知")
        }
        } catch (e: Exception) {
            runCatching { deleteCalendarEvents(context, eventIds) }
            throw e
        }
        return eventIds
    }

    /** 删除已写入的日历事件（按事件 ID） */
    fun deleteCalendarEvents(context: Context, eventIds: List<Long>) {
        eventIds.forEach { id ->
            val eventUri = android.net.Uri.withAppendedPath(
                CalendarContract.Events.CONTENT_URI, id.toString()
            )
            context.contentResolver.delete(eventUri, null, null)
        }
    }

    /**
     * 删除某药品已写入的全部日历事件（删除用药提醒时同步清理日历）：
     * 1. 按记录的事件 ID 精确删除；
     * 2. 按事件签名兜底清理——标题为「服药：药名」且描述匹配该成员，
     *    覆盖旧版本写入、未记录事件 ID 的情况。
     * @return 实际删除的事件数
     */
    fun deleteMedicationEvents(
        context: Context,
        medicationName: String,
        memberName: String?,
        storedEventIds: List<Long> = emptyList(),
        reminderId: String? = null,
        protectedEventIds: List<Long> = emptyList()
    ): Int {
        var deleted = 0

        // 1) 已记录事件 ID 的精确删除
        storedEventIds.forEach { id ->
            val eventUri = android.net.Uri.withAppendedPath(
                CalendarContract.Events.CONTENT_URI, id.toString()
            )
            context.contentResolver.delete(eventUri, null, null)
            deleted++
        }

        // 2) 按签名兜底：查询本应用写入的同名药品事件
        val titles = listOf(
            "服药：$medicationName",
            context.getString(R.string.calendar_medication_title, medicationName)
        ).distinct()
        val descPattern = if (!memberName.isNullOrBlank()) {
            // 描述格式：「成员名 · 剂量：xx · 来自家庭健康管家」，匹配成员名紧跟的分隔符避免误删同名
            "%$memberName · 剂量：%"
        } else {
            "%来自家庭健康管家%"
        }
        val selection =
            "${CalendarContract.Events.TITLE} = ? AND ${CalendarContract.Events.DESCRIPTION} LIKE ?"
        val matchedIds = mutableListOf<Long>()
        titles.forEach { title ->
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID),
                selection,
                arrayOf(title, descPattern),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) matchedIds.add(cursor.getLong(0))
            }
        }
        if (reminderId != null) {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID),
                "${CalendarContract.Events.DESCRIPTION} LIKE ?",
                arrayOf("%[homehealth:med:$reminderId]%"),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) matchedIds.add(cursor.getLong(0))
            }
        }
        // 去掉已按 ID 删除过的，再删剩余
        matchedIds.distinct().filter { it !in storedEventIds && it !in protectedEventIds }.forEach { id ->
            val eventUri = android.net.Uri.withAppendedPath(
                CalendarContract.Events.CONTENT_URI, id.toString()
            )
            context.contentResolver.delete(eventUri, null, null)
            deleted++
        }
        return deleted
    }
}
