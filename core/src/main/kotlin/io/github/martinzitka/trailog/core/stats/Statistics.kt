package io.github.martinzitka.trailog.core.stats

import io.github.martinzitka.trailog.core.geo.Geo
import io.github.martinzitka.trailog.core.model.Activity
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.model.Segment
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Activity statistics. Every function here is **segment-aware**: it computes within each
 * segment and sums across segments, and never joins the last point of one segment to the
 * first point of the next. That single discipline is what stops a reboot or a signal
 * blackout from silently adding kilometres and phantom speed (CLAUDE.md).
 */
object Statistics {

    /** Convenience: segment an activity's raw points and compute everything at once. */
    fun compute(activity: Activity): ActivityStats =
        compute(activity.segments(), activity.type)

    fun compute(segments: List<Segment>, type: ActivityType): ActivityStats {
        val distance = distance(segments)
        val moving = movingTime(segments, type)
        val movingSeconds = moving.toDouble(DurationUnit.SECONDS)
        return ActivityStats(
            distance = distance,
            elapsedTime = elapsedTime(segments),
            movingTime = moving,
            averageSpeed = if (movingSeconds > 0) distance / movingSeconds else 0.0,
            maxSpeed = maxSpeed(segments),
            segmentCount = segments.size,
            pointCount = segments.sumOf { it.points.size },
        )
    }

    /** Total distance in metres: haversine hops summed within each segment, never across. */
    fun distance(segments: List<Segment>): Double =
        segments.sumOf { seg ->
            seg.points.zipWithNext { a, b -> hop(a, b) }.sum()
        }

    /**
     * Wall-clock elapsed time from the first fix to the last, across the whole activity —
     * gaps between segments included. This is the "how long were you out" number.
     */
    fun elapsedTime(segments: List<Segment>): Duration {
        val times = segments.flatMap { it.points }.map { it.time }
        if (times.size < 2) return Duration.ZERO
        return times.max() - times.min()
    }

    /**
     * Time spent actually moving. Within each segment, a consecutive pair contributes its
     * time delta only if the implied speed is at least the activity type's threshold.
     * Boundaries between segments are never counted, so a paused signal or a reboot gap is
     * excluded rather than billed as slow movement.
     */
    fun movingTime(segments: List<Segment>, type: ActivityType): Duration {
        var total = Duration.ZERO
        for (seg in segments) {
            for ((a, b) in seg.points.zipWithNext()) {
                val dt = b.time - a.time
                val seconds = dt.toDouble(DurationUnit.SECONDS)
                if (seconds <= 0) continue
                val speed = hop(a, b) / seconds
                if (speed >= type.movingSpeedThreshold) total += dt
            }
        }
        return total
    }

    /** Fastest instantaneous speed (m/s) between two consecutive in-segment fixes. */
    fun maxSpeed(segments: List<Segment>): Double {
        var max = 0.0
        for (seg in segments) {
            for ((a, b) in seg.points.zipWithNext()) {
                val seconds = (b.time - a.time).toDouble(DurationUnit.SECONDS)
                if (seconds <= 0) continue
                val speed = hop(a, b) / seconds
                if (speed > max) max = speed
            }
        }
        return max
    }

    private fun hop(a: RawPoint, b: RawPoint): Double =
        Geo.haversine(a.latitude, a.longitude, b.latitude, b.longitude)
}
