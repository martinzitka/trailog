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

    /** Convenience: fixed-distance splits over an activity's segments. See [splits]. */
    fun splits(activity: Activity, splitDistance: Double = 1000.0): List<Split> =
        splits(activity.segments(), activity.type, splitDistance)

    fun compute(segments: List<Segment>, type: ActivityType): ActivityStats {
        val distance = distance(segments)
        val moving = movingTime(segments, type)
        val movingSeconds = moving.toDouble(DurationUnit.SECONDS)
        val elevation = Elevation.change(segments)
        return ActivityStats(
            distance = distance,
            elapsedTime = elapsedTime(segments),
            movingTime = moving,
            averageSpeed = if (movingSeconds > 0) distance / movingSeconds else 0.0,
            maxSpeed = maxSpeed(segments),
            elevationGain = elevation.gain,
            elevationLoss = elevation.loss,
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

    /**
     * The activity broken into fixed-distance splits (a "per-km" table, but the distance is a
     * parameter — pass 1609.344 for miles, 5000.0 for 5 km laps, anything).
     *
     * Distance accumulates **within segments only**: a hop between two consecutive in-segment
     * fixes is distributed across the split(s) it covers, interpolating at a split boundary
     * crossed mid-hop (that boundary is inside a segment, so interpolation is legitimate). A
     * gap between segments contributes no distance and is never bridged, so a split may span a
     * recording gap — its [Split.movingTime] stays correct because the dead gap was never
     * moving time.
     *
     * Elevation gain/loss per split reuses [Elevation]'s deadband bookings, each attributed to
     * the split its crossing point falls in; the per-split figures therefore sum exactly to the
     * activity totals from [Elevation.change].
     *
     * @param splitDistance the length of each split in metres; must be positive. Default 1 km.
     * @return one [Split] per interval in order; the last is usually shorter than
     *   [splitDistance]. Empty when the activity has no distance.
     */
    fun splits(
        segments: List<Segment>,
        type: ActivityType,
        splitDistance: Double = 1000.0,
        elevationParams: ElevationParams = ElevationParams(),
    ): List<Split> {
        require(splitDistance > 0.0) { "splitDistance must be > 0, was $splitDistance" }

        val accs = ArrayList<SplitAcc>()
        fun accFor(index: Int): SplitAcc {
            while (accs.size <= index) accs.add(SplitAcc())
            return accs[index]
        }

        // Pass 1 — distance and moving time. Distribute each hop across the splits it spans,
        // splitting time in proportion to distance where a hop crosses a split boundary. Also
        // record each altitude-bearing point's cumulative distance for the elevation pass.
        var cumulative = 0.0
        val altitudeCumulative = ArrayList<List<Double>>(segments.size)
        for (seg in segments) {
            val segAltCum = ArrayList<Double>()
            if (seg.points.first().altitude != null) segAltCum.add(cumulative)
            for ((a, b) in seg.points.zipWithNext()) {
                val hopDistance = hop(a, b)
                val dt = b.time - a.time
                val seconds = dt.toDouble(DurationUnit.SECONDS)
                val moving = seconds > 0 && hopDistance / seconds >= type.movingSpeedThreshold

                var start = cumulative
                var remaining = hopDistance
                // The split `start` currently falls in, carried across iterations rather than
                // recomputed from `start / splitDistance` each turn.
                //
                // That recomputation is only safe when every multiple of splitDistance is exactly
                // representable as a double, which is true of 1000.0 and false of a mile
                // (1609.344 m). When it is not, `(start / splitDistance).toInt()` can disagree with
                // `(index + 1) * splitDistance` by one ulp, `boundary - start` comes back negative,
                // and subtracting a negative take grows `remaining` — an infinite loop.
                var index = (start / splitDistance).toInt()
                while (remaining > 1e-9) {
                    val room = (index + 1) * splitDistance - start
                    if (room <= 1e-9) {
                        // `start` sits on, or a hair past, this split's closing boundary. Move to
                        // the next split rather than taking a zero- or negative-length bite; this
                        // is what guarantees the loop makes progress.
                        index++
                        continue
                    }
                    val take = minOf(remaining, room)
                    val acc = accFor(index)
                    acc.distance += take
                    if (moving) acc.movingTime += dt.times(take / hopDistance)
                    start += take
                    remaining -= take
                }
                cumulative += hopDistance
                if (b.altitude != null) segAltCum.add(cumulative)
            }
            altitudeCumulative.add(segAltCum)
        }

        // Pass 2 — elevation. Book gain/loss into the split each crossing point belongs to.
        for ((segIndex, seg) in segments.withIndex()) {
            val altitudes = seg.points.mapNotNull { it.altitude }
            val cumForAltitude = altitudeCumulative[segIndex]
            for (booking in Elevation.bookings(altitudes, elevationParams)) {
                val at = cumForAltitude[booking.altitudeIndex]
                // A crossing exactly on the final boundary belongs to the split just closed.
                val index = minOf((at / splitDistance).toInt(), maxOf(0, accs.size - 1))
                val acc = accFor(index)
                if (booking.delta >= 0.0) acc.gain += booking.delta else acc.loss += -booking.delta
            }
        }

        return accs.mapIndexed { index, acc ->
            val movingSeconds = acc.movingTime.toDouble(DurationUnit.SECONDS)
            Split(
                index = index,
                distance = acc.distance,
                movingTime = acc.movingTime,
                elevationGain = acc.gain,
                elevationLoss = acc.loss,
                averageSpeed = if (movingSeconds > 0) acc.distance / movingSeconds else 0.0,
            )
        }
    }

    /** Mutable per-split tally, collapsed into an immutable [Split] once the passes finish. */
    private class SplitAcc {
        var distance: Double = 0.0
        var movingTime: Duration = Duration.ZERO
        var gain: Double = 0.0
        var loss: Double = 0.0
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
