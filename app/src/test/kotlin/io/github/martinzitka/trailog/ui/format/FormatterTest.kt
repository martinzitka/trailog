package io.github.martinzitka.trailog.ui.format

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * Unit tests for [Formatter] — the single display edge, and the only place the user's unit
 * preference has any effect.
 *
 * The point of these is not that `%.2f` works. It is that the same SI input produces the right
 * figure in either system, that the switch-over thresholds behave, and above all that **nothing
 * upstream of this class needs to change**: every test feeds metres, seconds and Pascals no matter
 * which system it asserts.
 */
class FormatterTest {

    private val metric = Formatter(UnitSystem.METRIC)
    private val imperial = Formatter(UnitSystem.IMPERIAL)

    private lateinit var originalLocale: Locale

    /** A known dot-decimal locale, so assertions test the conversion, not the test machine. */
    @Before fun fixLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.UK)
    }

    @After fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    // ---- distance ----

    @Test fun `metric distance switches from metres to kilometres at 1 km`() {
        assertEquals("420 m", metric.distance(420.0))
        assertEquals("999 m", metric.distance(999.0))
        assertEquals("1.00 km", metric.distance(1000.0))
        assertEquals("12.35 km", metric.distance(12_345.0))
    }

    @Test fun `imperial distance switches from feet to miles at 1 mile`() {
        assertEquals("1378 ft", imperial.distance(420.0))
        assertEquals("3278 ft", imperial.distance(999.0))
        assertEquals("1.00 mi", imperial.distance(1609.344))
        assertEquals("7.67 mi", imperial.distance(12_345.0))
    }

    @Test fun `just under a mile is still feet, so the threshold is the mile and not the kilometre`() {
        assertEquals("5279 ft", imperial.distance(1609.0))
        assertEquals("1.61 km", metric.distance(1609.0))
    }

    @Test fun `a marathon reads correctly in both systems`() {
        assertEquals("26.22 mi", imperial.distance(42_195.0))
        assertEquals("42.00 km", metric.distance(42_000.0))
    }

    @Test fun `negative distance clamps to zero in both systems`() {
        assertEquals("0 m", metric.distance(-5.0))
        assertEquals("0 ft", imperial.distance(-5.0))
    }

    // ---- elevation, length, speed, pressure ----

    @Test fun `elevation converts metres to feet`() {
        assertEquals("1434 m", metric.elevation(1434.0))
        assertEquals("4705 ft", imperial.elevation(1434.0))
    }

    @Test fun `elevation keeps its sign, unlike distance`() {
        assertEquals("-12 m", metric.elevation(-12.0))
        assertEquals("-39 ft", imperial.elevation(-12.0))
    }

    @Test fun `length keeps one decimal in both systems`() {
        assertEquals("4.5 m", metric.length(4.5))
        assertEquals("14.8 ft", imperial.length(4.5))
    }

    @Test fun `speed converts to km per hour or miles per hour`() {
        assertEquals("36.0 km/h", metric.speed(10.0))
        assertEquals("22.4 mph", imperial.speed(10.0))
        assertEquals("0.0 km/h", metric.speed(-1.0))
        assertEquals("0.0 mph", imperial.speed(-1.0))
    }

    @Test fun `pressure renders hectopascals or inches of mercury`() {
        assertEquals("983.2 hPa", metric.pressure(98_320.0))
        assertEquals("29.03 inHg", imperial.pressure(98_320.0))
        // Standard sea-level pressure, the figure both units are usually sanity-checked against.
        // 1013.25 rounds half-up, so the last digit is 3 rather than the 2 a half-even rounder gives.
        assertEquals("1013.3 hPa", metric.pressure(101_325.0))
        assertEquals("29.92 inHg", imperial.pressure(101_325.0))
    }

    // ---- unit-independent ----

    @Test fun `duration, elapsed age and file names do not vary by unit system`() {
        listOf(metric, imperial).forEach { f ->
            assertEquals("1:02:03", f.duration(3723))
            assertEquals("2:03", f.duration(123))
            assertEquals("0:00", f.duration(-1))
            assertEquals("45s", f.elapsedSince(45))
            assertEquals("5m", f.elapsedSince(300))
            assertEquals("2h 5m", f.elapsedSince(7500))
            assertEquals(
                "trailog-cycling-2026-08-17-0742.gpx",
                f.exportFileName("Cycling", RIDE_START, "gpx", UTC),
            )
        }
    }

    @Test fun `the default formatter is metric, so nothing changes until the user opts in`() {
        assertEquals(UnitSystem.METRIC, Formatter().units)
        assertEquals("12.35 km", Formatter().distance(12_345.0))
    }

    private companion object {
        val UTC: ZoneId = ZoneId.of("UTC")

        /** 2026-08-17 07:42 UTC. */
        const val RIDE_START = 1_786_952_520_000L
    }
}
