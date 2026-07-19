package io.github.martinzitka.trailog.core.model

import kotlinx.datetime.Instant

/**
 * A single location fix, persisted exactly as the OS delivered it. Raw points are
 * immutable and sacred (CLAUDE.md): never filtered, smoothed or deleted. Every derived
 * statistic is a recomputable view over these.
 *
 * All units are SI. Field names carry no unit suffix because there is only one unit.
 *
 * @property latitude degrees, WGS84.
 * @property longitude degrees, WGS84.
 * @property altitude metres above the WGS84 ellipsoid, as reported by GPS. This is NOT
 *   mean-sea-level altitude; geoid correction happens in a derived view, never here.
 * @property accuracy horizontal accuracy radius in metres, as reported by the OS.
 * @property time timestamp taken from the location fix, not the device clock (clocks jump).
 * @property speed instantaneous ground speed in metres per second, or null if unreported.
 * @property bearing degrees clockwise from true north, or null if unreported.
 * @property pressure barometric pressure in Pascals, or null if the device has no barometer.
 * @property segmentIndex which segment of the activity this fix belongs to. A gap (crash,
 *   kill, reboot, signal blackout) ends one segment and begins the next. Statistics never
 *   interpolate across a segment boundary.
 */
data class RawPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracy: Double?,
    val time: Instant,
    val speed: Double? = null,
    val bearing: Double? = null,
    val pressure: Double? = null,
    val segmentIndex: Int = 0,
)
