package io.github.martinzitka.trailog.core.stats

import io.github.martinzitka.trailog.core.model.Segment

/**
 * Total ascent and descent over an activity, in metres. Both are non-negative: [gain] is
 * the sum of climbs, [loss] the sum of descents.
 */
data class ElevationChange(
    val gain: Double,
    val loss: Double,
)

/**
 * Tunables for the elevation gain/loss algorithm. Kept in one place rather than scattered as
 * magic constants (CLAUDE.md). Defaults are **provisional** — final tuning happens against
 * real fixture tracks with published reference figures, within the 5% tolerance in CLAUDE.md.
 *
 * @property smoothingWindow number of points in the centered moving-average window applied to
 *   altitude before accumulation. Larger smooths harder. Expressed in points, not seconds, so
 *   it is sample-rate dependent; at the 1 Hz Trailog records this is a window in seconds.
 * @property threshold metres of net change that must accumulate, in one direction, before it
 *   is booked. This deadband (hysteresis) is what stops GPS altitude noise from inflating the
 *   most-compared and most-wrong statistic (CLAUDE.md). The threshold — not the window — is the
 *   dominant lever on the final number; raising it discards more noise-driven micro-climb.
 *
 * Defaults tuned (2026-08-05) against the first real reference ride with a trusted DEM
 * elevation profile (mapy.cz, 466 m over the same route): window 7, threshold 6 lands ~495 m,
 * roughly midway between that DEM figure and Sports Tracker's lightly-smoothed GPS figure
 * (552 m). Still PROVISIONAL — a barometer-equipped device or DEM anchoring, and more reference
 * rides, would tighten it. See `docs/adr/0006-elevation-smoothing-defaults.md`.
 */
data class ElevationParams(
    val smoothingWindow: Int = 7,
    val threshold: Double = 6.0,
) {
    init {
        require(smoothingWindow >= 1) { "smoothingWindow must be >= 1, was $smoothingWindow" }
        require(threshold >= 0.0) { "threshold must be >= 0, was $threshold" }
    }
}

/**
 * Elevation gain and loss over an activity.
 *
 * The algorithm is deliberately conservative because naively summing positive altitude deltas
 * over noisy GPS altitude inflates the number wildly (CLAUDE.md). Two defences, in order:
 *
 * 1. **Smooth** the altitude series with a centered moving average, per segment.
 * 2. **Accumulate with a deadband**: track a reference altitude and only book a climb (or
 *    descent) once the smoothed altitude has moved past the reference by more than the
 *    threshold. Wander smaller than the threshold is treated as noise and ignored.
 *
 * Everything is **segment-aware**: smoothing and accumulation reset at each segment boundary,
 * so the altitude of the last fix before a gap is never differenced against the first fix
 * after it. Interpolating across a segment boundary is exactly how a naive tracker invents
 * hundreds of metres of phantom climb.
 *
 * Note on the geoid: gain and loss are computed from altitude *differences*. The EGM2008
 * geoid separation is effectively constant over the span of a single activity, so it cancels
 * in every delta and is irrelevant here. It matters only for *absolute* altitude, which is a
 * separate concern.
 */
object Elevation {

    fun change(
        segments: List<Segment>,
        params: ElevationParams = ElevationParams(),
    ): ElevationChange {
        var gain = 0.0
        var loss = 0.0
        for (seg in segments) {
            // Only points that actually carry an altitude can contribute. Order is preserved.
            val altitudes = seg.points.mapNotNull { it.altitude }
            for (booking in bookings(altitudes, params)) {
                if (booking.delta >= 0.0) gain += booking.delta else loss += -booking.delta
            }
        }
        return ElevationChange(gain = gain, loss = loss)
    }

    /**
     * A single elevation change booked by the deadband, tied to the point that crossed the
     * threshold. [altitudeIndex] indexes the altitude-bearing points of a segment, in order
     * (i.e. the list handed to [bookings], not `seg.points` — points without altitude are
     * absent). [delta] is signed: positive is a climb, negative a descent.
     */
    internal data class Booking(val altitudeIndex: Int, val delta: Double)

    /**
     * The sequence of gain/loss bookings for one segment's altitude series, smoothing and
     * deadband applied. This is the single source of truth for elevation accumulation:
     * [change] sums the magnitudes; [Statistics.splits] attributes each booking to the split
     * its point falls in. Computing it here once is what stops the total and the per-split
     * sum from ever disagreeing (CLAUDE.md: a statistic is never computed two ways).
     */
    internal fun bookings(altitudes: List<Double>, params: ElevationParams): List<Booking> {
        if (altitudes.size < 2) return emptyList()
        val smoothed = movingAverage(altitudes, params.smoothingWindow)
        val out = ArrayList<Booking>()
        var reference = smoothed.first()
        for (i in smoothed.indices) {
            val delta = smoothed[i] - reference
            if (delta >= params.threshold) {
                out.add(Booking(i, delta))
                reference = smoothed[i]
            } else if (delta <= -params.threshold) {
                out.add(Booking(i, delta))
                reference = smoothed[i]
            }
        }
        return out
    }

    /**
     * Centered moving average. The window shrinks at the ends so the first and last points are
     * averaged over whatever neighbours exist, rather than being dropped.
     */
    private fun movingAverage(values: List<Double>, window: Int): List<Double> {
        if (window <= 1) return values
        val half = window / 2
        return values.indices.map { i ->
            val from = maxOf(0, i - half)
            val to = minOf(values.size - 1, i + half)
            var sum = 0.0
            for (j in from..to) sum += values[j]
            sum / (to - from + 1)
        }
    }
}
