package com.example.homehealth.worker

import com.example.homehealth.data.local.entity.MedicationReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class MedicationAlarmSchedulerTest {
    private val reminder = MedicationReminder(
        id = "r1", memberId = "m1", medicationName = "药", dosage = "一片",
        schedule = "daily:08:00,20:00", startDate = 0L
    )

    @Test
    fun `每个时间点分别安排下一次`() {
        val after = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 24, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val todayEvening = Calendar.getInstance().apply {
            timeInMillis = after
            set(Calendar.HOUR_OF_DAY, 20)
        }.timeInMillis
        val tomorrowMorning = Calendar.getInstance().apply {
            timeInMillis = after
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 8)
        }.timeInMillis
        assertEquals(todayEvening, MedicationAlarmScheduler.nextTrigger(reminder, "20:00", after))
        assertEquals(tomorrowMorning, MedicationAlarmScheduler.nextTrigger(reminder, "08:00", after))
    }

    @Test
    fun `过期与非法时间不安排闹钟`() {
        val expired = reminder.copy(endDate = 1L)
        assertNull(MedicationAlarmScheduler.nextTrigger(expired, "08:00", 1000L))
        assertNull(MedicationAlarmScheduler.nextTrigger(reminder, "25:99", 1000L))
    }
}
