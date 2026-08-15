package io.github.martinzitka.trailog.ui.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The single client-side formatting utility (CLAUDE.md: "Conversion and formatting happen only
 * at the display edge, in a single formatting utility"). Everything internal is SI — metres,
 * seconds, metres per second; this is the one place those become human strings.
 *
 * Metric only for now. A user preference for imperial units lands later and must change nothing
 * but this file — no field, no column, no ViewModel, no Composable. That is the entire point of
 * routing every displayed number through here.
 *
 * No formatted string is ever stored or transmitted; call these at render time only. Unit
 * symbols (km, m, m/s) are conventional SI symbols, not translatable prose — prose lives in
 * string resources.
 */
object Format {

    /**
     * A distance in metres. Renders as kilometres with two decimals from 1 km up, and as whole
     * metres below that (a 0.42 km readout is less legible than 420 m for short efforts).
     */
    fun distance(meters: Double): String {
        val m = meters.coerceAtLeast(0.0)
        return if (m >= 1000.0) {
            String.format(Locale.getDefault(), "%.2f km", m / 1000.0)
        } else {
            String.format(Locale.getDefault(), "%.0f m", m)
        }
    }

    /** An elevation figure (gain, loss, altitude) in metres, to the nearest whole metre. */
    fun elevation(meters: Double): String =
        String.format(Locale.getDefault(), "%.0f m", meters)

    /** A ground speed in metres per second, rendered in km/h to one decimal. */
    fun speed(metersPerSecond: Double): String =
        String.format(Locale.getDefault(), "%.1f km/h", metersPerSecond.coerceAtLeast(0.0) * 3.6)

    /**
     * A whole-second duration as `H:MM:SS`, dropping the hours field below an hour (`M:SS`).
     * Negative inputs clamp to zero. Digits only, so it is locale-neutral.
     */
    fun duration(totalSeconds: Long): String {
        val t = totalSeconds.coerceAtLeast(0)
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.ROOT, "%d:%02d", m, s)
        }
    }

    /**
     * A coarse "how long ago" for fix age and recovery gaps: seconds under a minute, whole
     * minutes under an hour, then hours and minutes. Kept deliberately rough — this is a
     * freshness hint, not a stopwatch.
     */
    fun elapsedSince(seconds: Long): String {
        val t = seconds.coerceAtLeast(0)
        return when {
            t < 60 -> "${t}s"
            t < 3600 -> "${t / 60}m"
            else -> "${t / 3600}h ${(t % 3600) / 60}m"
        }
    }

    /** A local calendar date for an epoch-millis instant, medium style in the device locale. */
    fun date(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DATE.withZone(zone).format(Instant.ofEpochMilli(epochMillis))

    private val DATE: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
}
