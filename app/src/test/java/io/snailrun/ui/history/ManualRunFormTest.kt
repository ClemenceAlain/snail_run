package io.snailrun.ui.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualRunFormTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val now = Instant.parse("2026-09-23T16:00:00Z")
    private val today = LocalDate.of(2026, 9, 23)

    private fun parse(
        date: LocalDate = today,
        time: String = "07:30",
        hours: String = "",
        minutes: String = "25",
        seconds: String = "10",
        km: String = "5",
    ) = ManualRunForm.parse(date, time, hours, minutes, seconds, km, zone, now)

    @Test
    fun `a plain entry becomes a run`() {
        val run = (parse() as ManualRunEntry.Valid).run
        assertEquals(5_000.0, run.distanceMeters, 0.0)
        assertEquals((25 * 60 + 10) * 1000L, run.durationMs)
        assertEquals(Instant.parse("2026-09-23T05:30:00Z").toEpochMilli(), run.startedAtEpochMs)
    }

    @Test
    fun `a comma is a decimal point`() {
        val run = (parse(km = "10,5") as ManualRunEntry.Valid).run
        assertEquals(10_500.0, run.distanceMeters, 0.001)
    }

    @Test
    fun `a time without a colon still reads`() {
        val run = (parse(time = "0730") as ManualRunEntry.Valid).run
        assertEquals(Instant.parse("2026-09-23T05:30:00Z").toEpochMilli(), run.startedAtEpochMs)
    }

    @Test
    fun `empty or zero fields are refused`() {
        assertTrue(parse(km = "") is ManualRunEntry.Invalid)
        assertTrue(parse(km = "0") is ManualRunEntry.Invalid)
        assertTrue(parse(minutes = "", seconds = "") is ManualRunEntry.Invalid)
        assertTrue(parse(minutes = "75") is ManualRunEntry.Invalid)
        assertTrue(parse(time = "25:00") is ManualRunEntry.Invalid)
    }

    @Test
    fun `a run cannot start in the future`() {
        assertTrue(parse(date = today.plusDays(1)) is ManualRunEntry.Invalid)
        assertTrue(parse(time = "19:00") is ManualRunEntry.Invalid)
    }
}
