package io.github.martinzitka.trailog.ui.format

import androidx.compose.runtime.staticCompositionLocalOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

/**
 * The single client-side formatting utility (CLAUDE.md: "Conversion and formatting happen only
 * at the display edge, in a single formatting utility"). Everything internal is SI — metres,
 * seconds, metres per second, Pascals; this is the one place those become human strings.
 *
 * It is a class rather than an object because the unit system is now a user preference. That
 * preference reaches the screens through [LocalFormatter] and changes nothing else: no field, no
 * column, no API payload, no ViewModel, no Composable arithmetic. That is the entire point of
 * routing every displayed number through here, and it is why switching to imperial is a change to
 * this file plus a provider, rather than a change to every screen.
 *
 * No formatted string is ever stored or transmitted; call these at render time only. Unit symbols
 * (km, mi, m/s, ft) are conventional symbols, not translatable prose — prose lives in string
 * resources.
 */
class Formatter(val units: UnitSystem = UnitSystem.METRIC) {

    private val imperial: Boolean get() = units == UnitSystem.IMPERIAL

    /**
     * A distance in metres. Metric renders kilometres with two decimals from 1 km up and whole
     * metres below (a 0.42 km readout is less legible than 420 m for short efforts); imperial
     * mirrors that rule about the mile, showing whole feet below it.
     */
    fun distance(meters: Double): String {
        val m = meters.coerceAtLeast(0.0)
        return if (imperial) {
            if (m >= METERS_PER_MILE) {
                String.format(Locale.getDefault(), "%.2f mi", m / METERS_PER_MILE)
            } else {
                String.format(Locale.getDefault(), "%.0f ft", m / METERS_PER_FOOT)
            }
        } else {
            if (m >= 1000.0) {
                String.format(Locale.getDefault(), "%.2f km", m / 1000.0)
            } else {
                String.format(Locale.getDefault(), "%.0f m", m)
            }
        }
    }

    /**
     * A split interval stated in whole laps of the display unit: "1 km", "5 mi".
     *
     * Takes a lap count rather than metres because that is what it is — the ride is re-lapped at
     * that distance, not converted (ADR 0014). Formatting `5000.0` through [distance] would print
     * "5.00 km", which is a measurement of something; a chip needs a name for an interval.
     */
    fun laps(count: Int): String = String.format(
        Locale.getDefault(),
        if (imperial) "%d mi" else "%d km",
        count,
    )

    /** An elevation figure (gain, loss, altitude) in metres, to the nearest whole metre or foot. */
    fun elevation(meters: Double): String = if (imperial) {
        String.format(Locale.getDefault(), "%.0f ft", meters / METERS_PER_FOOT)
    } else {
        String.format(Locale.getDefault(), "%.0f m", meters)
    }

    /**
     * A short length to one decimal — reported GPS accuracy on the diagnostics screen, where the
     * difference between 4 m and 4.8 m is worth seeing. Travelled distances use [distance].
     */
    fun length(meters: Double): String {
        val m = meters.coerceAtLeast(0.0)
        return if (imperial) {
            String.format(Locale.getDefault(), "%.1f ft", m / METERS_PER_FOOT)
        } else {
            String.format(Locale.getDefault(), "%.1f m", m)
        }
    }

    /**
     * A pressure in Pascals, rendered in the unit that barometric readings are actually quoted in:
     * hectopascals, or inches of mercury for imperial. Storage stays Pascals.
     */
    fun pressure(pascals: Double): String = if (imperial) {
        String.format(Locale.getDefault(), "%.2f inHg", pascals / PASCALS_PER_INHG)
    } else {
        String.format(Locale.getDefault(), "%.1f hPa", pascals / 100.0)
    }

    /** A ground speed in metres per second, rendered in km/h or mph to one decimal. */
    fun speed(metersPerSecond: Double): String {
        val mps = metersPerSecond.coerceAtLeast(0.0)
        return if (imperial) {
            String.format(Locale.getDefault(), "%.1f mph", mps * MPH_PER_MPS)
        } else {
            String.format(Locale.getDefault(), "%.1f km/h", mps * 3.6)
        }
    }

    // ---- unit-independent -----------------------------------------------------------------
    // Time and file names read the same in both systems. They live here so there is one call
    // surface at the display edge rather than two.

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

    /**
     * A local date and time for an epoch-millis instant, medium/short style in the device locale.
     * Used where the time of day matters — an activity's header, where two rides on one day need
     * telling apart.
     */
    fun dateTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DATE_TIME.withZone(zone).format(Instant.ofEpochMilli(epochMillis))

    /**
     * The suggested file name for an export, e.g. `trailog-cycling-2026-08-16-0742.gpx`.
     *
     * Deliberately locale-neutral and sortable rather than localised: it is a file name a user
     * will scroll past in a file manager or hand to another tool, not prose. [slug] is lowercased
     * and stripped of anything a file system might object to.
     */
    fun exportFileName(
        slug: String,
        epochMillis: Long,
        extension: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val stamp = FILE_STAMP.withZone(zone).format(Instant.ofEpochMilli(epochMillis))
        val safe = slug.lowercase(Locale.ROOT).replace(UNSAFE_FILE_CHARS, "-").trim('-')
        return if (safe.isEmpty()) {
            "trailog-$stamp.$extension"
        } else {
            "trailog-$safe-$stamp.$extension"
        }
    }

    /**
     * A file size, for the region packs on the Map data screen: "1.3 GB", "812 MB", "45 kB".
     *
     * Decimal units, matching what Android's own storage screens and every file manager report —
     * a user comparing the figure against the file they downloaded needs the same number, and
     * binary units would show a 1.3 GB archive as 1.2 GiB. Gigabytes carry a decimal because the
     * difference between 1.3 and 1.9 GB is the difference between fitting and not.
     *
     * Not affected by the unit preference: a byte is a byte in both systems.
     */
    fun fileSize(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        val locale = Locale.getDefault()
        return when {
            b >= 1_000_000_000L -> String.format(locale, "%.1f GB", b / 1_000_000_000.0)
            b >= 1_000_000L -> String.format(locale, "%.0f MB", b / 1_000_000.0)
            b >= 1_000L -> String.format(locale, "%.0f kB", b / 1_000.0)
            else -> String.format(locale, "%d B", b)
        }
    }

    /**
     * The area a region pack covers, as its corners: "48.1°N–51.4°N, 11.9°E–19.0°E".
     *
     * One decimal, because this answers "does this pack reach where I am going" and a tenth of a
     * degree is about 11 km. Hemispheres are letters rather than signs — a minus in front of a
     * coordinate is easy to miss and reverses the meaning.
     *
     * Degrees are degrees in both unit systems, so this too ignores the preference.
     */
    fun coverage(
        minLatitude: Double,
        minLongitude: Double,
        maxLatitude: Double,
        maxLongitude: Double,
    ): String = "${latitude(minLatitude)}–${latitude(maxLatitude)}, " +
        "${longitude(minLongitude)}–${longitude(maxLongitude)}"

    private fun latitude(degrees: Double): String =
        String.format(Locale.getDefault(), "%.1f°%s", abs(degrees), if (degrees < 0) "S" else "N")

    private fun longitude(degrees: Double): String =
        String.format(Locale.getDefault(), "%.1f°%s", abs(degrees), if (degrees < 0) "W" else "E")

    private companion object {
        // Exact definitions, not approximations: the international mile and foot are defined in
        // metres, so these conversions are lossless by construction.
        const val METERS_PER_MILE = 1609.344
        const val METERS_PER_FOOT = 0.3048
        const val MPH_PER_MPS = 3600.0 / METERS_PER_MILE

        /** Conventional (0 °C) inch of mercury, the unit US barometric readings are quoted in. */
        const val PASCALS_PER_INHG = 3386.389

        val DATE: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

        val DATE_TIME: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

        /** `Locale.ROOT`, so the stamp is Latin digits and Gregorian regardless of device locale. */
        val FILE_STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.ROOT)

        /** Anything outside a-z, 0-9 becomes a hyphen, so the name is safe on any file system. */
        val UNSAFE_FILE_CHARS = Regex("[^a-z0-9]+")
    }
}

/**
 * The formatter every screen renders through. Provided once, at the root of the app, from the
 * user's unit preference.
 *
 * `static` because the formatter changes rarely (only when the preference does) and is read from
 * a great many leaf Composables — a static local swaps the whole subtree instead of tracking
 * per-reader invalidation, which is the cheaper shape for this access pattern.
 *
 * The default is metric so previews, tests and any composable rendered outside the app root still
 * format correctly rather than crashing or blanking.
 */
val LocalFormatter = staticCompositionLocalOf { Formatter(UnitSystem.METRIC) }
