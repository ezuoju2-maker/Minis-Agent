package com.openminis.app.sandbox.offload

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarDateParsingTest {

    private val handler = CalendarOffloadHandler.Companion

    private fun fmt(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }
            .format(java.util.Date(ms))


    @Test
    fun `out-of-range date and time components are rejected, not rolled over`() {
        assertNull("month 13 / day 45 must not roll into the next year", handler.parseDate("2026-13-45"))
        assertNull("99:99:99 must not roll into the next day", handler.parseDate("2026-12-01T99:99:99"))
        assertNull(handler.parseDate("2026-02-30"))
        assertNull(handler.parseDate("2026-00-10"))
    }

    @Test
    fun `trailing garbage is rejected rather than matched on its prefix`() {
        assertNull(handler.parseDate("2026-12-01nonsense"))
        assertNull(handler.parseDate("2026-12-01T10:30junk"))
    }

    @Test
    fun `malformed and empty input returns null instead of throwing`() {
        for (bad in listOf("", "   ", "not-a-date", "12-01", "2026/12/01", "T10:30", "--", "0")) {
            assertNull("'$bad' must parse to null", handler.parseDate(bad))
        }
    }

    @Test
    fun `the valid formats still parse`() {
        assertNotNull(handler.parseDate("2026-12-01"))
        assertNotNull(handler.parseDate("2026-12-01T10:30"))
        assertNotNull(handler.parseDate("2026-12-01T10:30:15"))
        assertNotNull(handler.parseDate("2026-12-01T10:30:15Z"))
        assertNotNull(handler.parseDate("2026-12-01T10:30:15+08:00"))
        assertNotNull(handler.parseDate("-7d"))
        assertNotNull(handler.parseDate("+30m"))
        assertNotNull(handler.parseDate("  2026-12-01  "))
    }


    @Test
    fun `only a bare YYYY-MM-DD counts as date-only`() {
        assertTrue(handler.isDateOnly("2026-12-01"))
        assertFalse(handler.isDateOnly("2026-12-01T10:30"))
        assertFalse(handler.isDateOnly("2026-12-01T00:00:00"))
        assertFalse(handler.isDateOnly(null))
        assertFalse(handler.isDateOnly(""))
        assertFalse(handler.isDateOnly("2026-12-1"))
        assertFalse(handler.isDateOnly("-7d"))
        assertFalse(handler.isDateOnly("2026-13-45"))
    }


    private fun ms(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        Calendar.getInstance().apply {
            clear(); set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis

    @Test
    fun `bounds snap to local midnight and the last second of the end day`() {
        val (s, e) = handler.allDayBounds(ms(2026, 12, 1, 15, 42), ms(2026, 12, 3, 9, 5))
        assertEquals("2026-12-01 00:00:00", fmt(s))
        assertEquals("2026-12-03 23:59:59", fmt(e))
    }

    @Test
    fun `a single day spans that whole day`() {
        val (s, e) = handler.allDayBounds(ms(2026, 12, 1, 15, 42), null)
        assertEquals("2026-12-01 00:00:00", fmt(s))
        assertEquals("2026-12-01 23:59:59", fmt(e))
    }

    @Test
    fun `an end before the start collapses to the start day`() {
        val (s, e) = handler.allDayBounds(ms(2026, 12, 5), ms(2026, 12, 1))
        assertEquals("2026-12-05 00:00:00", fmt(s))
        assertEquals("2026-12-05 23:59:59", fmt(e))
        assertTrue("end must never precede start", e > s)
    }

    @Test
    fun `a DST transition day still ends at its own last second`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val (s, e) = handler.allDayBounds(ms(2026, 3, 8, 12, 0), null)
            assertEquals("2026-03-08 00:00:00", fmt(s))
            assertEquals("2026-03-08 23:59:59", fmt(e))
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
