package nu.staldal.mymail.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleDateTest {

    private val zone = ZoneId.of("Europe/Stockholm")
    private val now = ZonedDateTime.of(2026, 10, 18, 12, 0, 0, 0, zone)

    private fun format(date: ZonedDateTime) =
        formatScheduleDate(date.toOffsetDateTime(), now.toOffsetDateTime(), zone)

    @Test
    fun `a time within the hour reads forwards or backwards`() {
        assertEquals("in 20 min", format(now.plusMinutes(20)))
        assertEquals("20 min ago", format(now.minusMinutes(20)))
    }

    @Test
    fun `the minute either side of now is never zero`() {
        assertEquals("in 1 min", format(now.plusSeconds(30)))
        assertEquals("1 min ago", format(now.minusSeconds(30)))
        assertEquals("in 1 min", format(now))
    }

    @Test
    fun `the last minutes before the hour do not round up to the hour`() {
        assertEquals("in 59 min", format(now.plusMinutes(59).plusSeconds(59)))
        assertEquals("Today 13:00", format(now.plusMinutes(60)))
    }

    @Test
    fun `nearby days are named`() {
        assertEquals("Today 15:30", format(now.plusHours(3).plusMinutes(30)))
        assertEquals("Tomorrow 12:00", format(now.plusDays(1)))
        assertEquals("Yesterday 12:00", format(now.minusDays(1)))
    }

    @Test
    fun `within a week the weekday is named in both directions`() {
        val ahead = format(now.plusDays(3))
        val behind = format(now.minusDays(3))

        // The weekday name itself is locale-dependent; what this pins is the shape.
        assertTrue(ahead.endsWith(" 12:00"))
        assertTrue(behind.endsWith(" 12:00"))
        assertFalse(ahead.startsWith("Today"))
        assertFalse(ahead.startsWith("Tomorrow"))
        assertFalse(ahead.startsWith("in "))
        assertTrue(ahead != behind)
    }

    @Test
    fun `beyond a week the date is spelled out, and the time is kept at every distance`() {
        val sameYear = format(now.plusDays(20))
        val otherYear = format(now.plusYears(1))

        assertTrue(sameYear.endsWith(", 12:00"))
        assertTrue(otherYear.endsWith(", 12:00"))
        assertTrue(otherYear.contains(now.plusYears(1).year.toString()))
        assertFalse(sameYear.contains(now.year.toString()))
    }

    @Test
    fun `calendar days preserve local noon across both DST transitions`() {
        for (eve in listOf(
            ZonedDateTime.of(2026, 3, 28, 12, 0, 0, 0, zone),
            ZonedDateTime.of(2026, 10, 24, 12, 0, 0, 0, zone),
        )) {
            val nextDay = eve.plusDays(1)
            assertTrue(eve.offset != nextDay.offset)
            assertEquals(
                "Tomorrow 12:00",
                formatScheduleDate(nextDay.toOffsetDateTime(), eve.toOffsetDateTime(), zone),
            )
            assertEquals(
                "Yesterday 12:00",
                formatScheduleDate(eve.toOffsetDateTime(), nextDay.toOffsetDateTime(), zone),
            )
        }
    }

    @Test
    fun `fixed offset timestamps display the local time after a DST transition`() {
        val fixedOffsetDate = now.toOffsetDateTime().plusDays(20)
        assertTrue(
            formatScheduleDate(fixedOffsetDate, now.toOffsetDateTime(), zone).endsWith(", 11:00"),
        )
    }
}
