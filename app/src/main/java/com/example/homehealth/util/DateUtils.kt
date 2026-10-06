package com.example.homehealth.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 日期工具 */
object DateUtils {

    // SimpleDateFormat 可变且非线程安全；UI 与 Worker 会并发调用。
    fun formatDate(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(timestamp))

    fun formatDateTime(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))

    fun today(): Long = System.currentTimeMillis()

    /** 解析日期字符串，支持 yyyy-MM-dd / yyyy/MM/dd / yyyy.MM.dd，失败返回 null */
    fun parseDate(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val patterns = listOf("yyyy-MM-dd", "yyyy/MM/dd", "yyyy.MM.dd", "yyyyMMdd")
        for (p in patterns) {
            try {
                val sdf = SimpleDateFormat(p, Locale.CHINA)
                sdf.isLenient = false
                val date = sdf.parse(t) ?: continue
                return date.time
            } catch (_: Exception) {
                // 尝试下一个格式
            }
        }
        return null
    }

    /** 相对时间：今天 / 昨天 / N天前（english=true 时返回英文，供 UI 本地化显示） */
    fun relative(timestamp: Long, english: Boolean = false): String {
        val target = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        val diffDays = ChronoUnit.DAYS.between(target, LocalDate.now()).toInt()
        return when {
            diffDays <= 0 -> if (english) "Today" else "今天"
            diffDays == 1 -> if (english) "Yesterday" else "昨天"
            diffDays in 2..30 -> if (english) "$diffDays days ago" else "${diffDays}天前"
            else -> formatDate(timestamp)
        }
    }

    /** 根据出生日期计算年龄 */
    fun age(dateOfBirth: String?): Int? = ageAt(dateOfBirth, LocalDate.now())

    internal fun ageAt(dateOfBirth: String?, onDate: LocalDate): Int? {
        val dob = parseDate(dateOfBirth ?: return null) ?: return null
        val birth = Calendar.getInstance().apply { timeInMillis = dob }
        var age = onDate.year - birth.get(Calendar.YEAR)
        val birthMonth = birth.get(Calendar.MONTH)
        val nowMonth = onDate.monthValue - 1
        if (nowMonth < birthMonth ||
            (nowMonth == birthMonth && onDate.dayOfMonth < birth.get(Calendar.DAY_OF_MONTH))
        ) age--
        return if (age >= 0) age else null
    }
}
