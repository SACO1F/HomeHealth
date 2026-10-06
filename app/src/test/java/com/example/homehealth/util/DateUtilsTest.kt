package com.example.homehealth.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DateUtilsTest {
    @Test
    fun `闰年出生者在平年生日当天年龄正确`() {
        assertEquals(25, DateUtils.ageAt("2000-09-24", LocalDate.of(2026, 9, 23)))
        assertEquals(26, DateUtils.ageAt("2000-09-24", LocalDate.of(2026, 9, 24)))
    }
}
