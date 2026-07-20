package io.github.martinzitka.trailog.core.stats

import kotlin.time.Duration

/**
 * Derived statistics for an activity — a recomputable view over its raw points. All units
 * are SI: metres and metres per second. Times are [Duration] and convert to seconds at the
 * display edge.
 *
 * Elevation is intentionally absent for now; it needs geoid correction and barometric
 * fusion and lands in a later slice of M1.2.
 *
 * @property distance total distance in metres, summed within segments only.
 * @property elapsedTime wall-clock span from first to last fix, gaps included.
 * @property movingTime time spent moving above the activity type's threshold, gaps and
 *   pauses excluded.
 * @property averageSpeed distance divided by moving time, in m/s (0 if not moving).
 * @property maxSpeed fastest instantaneous speed between two consecutive in-segment fixes,
 *   in m/s. Noisy until the filtering pipeline lands; treat as provisional.
 */
data class ActivityStats(
    val distance: Double,
    val elapsedTime: Duration,
    val movingTime: Duration,
    val averageSpeed: Double,
    val maxSpeed: Double,
    val segmentCount: Int,
    val pointCount: Int,
)
