package io.github.martinzitka.trailog.core.filter

import io.github.martinzitka.trailog.core.geo.Geo
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.model.Segment
import kotlin.time.DurationUnit

/**
 * A recomputable cleaning pass over raw points, applied *before* statistics are integrated
 * (CLAUDE.md: filter on accuracy, reject implausible speed jumps, smooth before integrating).
 *
 * Filtering is never done on ingest and never mutates stored data — raw points are immutable
 * and sacred. A filter is a pure function from segments to segments: the output is a derived
 * view whose points are a *subset* of the raw ones (still exactly as the OS delivered them,
 * just fewer), so nothing here can corrupt the source of truth. Because it can only improve,
 * a better filter can be applied retroactively to old activities.
 *
 * The strategy is swappable and not baked into callers: distance and the rest are computed as
 * `Statistics.distance(filter.apply(rawSegments))`. Every filter is **segment-aware** —
 * it cleans within each segment and never joins across a boundary, so a rejected point cannot
 * cause the last fix of one segment to be differenced against the first of the next.
 */
fun interface TrackFilter {
    /** Returns cleaned segments. Segments emptied by filtering are dropped. */
    fun apply(segments: List<Segment>): List<Segment>
}

/**
 * Tunables for the default filter chain, in one place rather than scattered as magic constants
 * (CLAUDE.md). Defaults are **provisional** — final tuning happens against real reference
 * tracks, within the distance tolerance in CLAUDE.md.
 *
 * @property maxAccuracy the worst horizontal accuracy radius, in metres, a fix may report and
 *   still be trusted. Fixes reporting a larger radius are dropped as too uncertain. A fix that
 *   reports no accuracy at all is kept — absence of the number is not evidence of a bad fix.
 */
data class FilterParams(
    val maxAccuracy: Double = 50.0,
) {
    init {
        require(maxAccuracy > 0.0) { "maxAccuracy must be > 0, was $maxAccuracy" }
    }
}

/**
 * Drops fixes whose reported horizontal accuracy is worse than [maxAccuracy]. Fixes with no
 * reported accuracy are kept. Operates within each segment; segments left empty are dropped.
 */
class AccuracyFilter(private val maxAccuracy: Double) : TrackFilter {
    override fun apply(segments: List<Segment>): List<Segment> =
        segments
            .map { seg ->
                seg.copy(points = seg.points.filter { it.accuracy == null || it.accuracy <= maxAccuracy })
            }
            .filter { it.points.isNotEmpty() }
}

/**
 * Rejects GPS spikes: a fix that would require moving faster than [maxSpeed] from the last
 * accepted fix is discarded, and scanning continues from that last good fix. This removes the
 * momentary "teleport" a bad fix causes without deleting the surrounding good ones.
 *
 * The first fix of each segment is always kept as the anchor. Pairs with a non-positive time
 * delta are kept (their speed is undefined and cannot be judged); in practice
 * [Segment.segmentsOf] has already sorted and de-duplicated by time, so time strictly
 * increases within a segment.
 */
class SpeedFilter(private val maxSpeed: Double) : TrackFilter {
    override fun apply(segments: List<Segment>): List<Segment> =
        segments
            .map { seg -> seg.copy(points = keepPlausible(seg.points)) }
            .filter { it.points.isNotEmpty() }

    private fun keepPlausible(points: List<RawPoint>): List<RawPoint> {
        if (points.isEmpty()) return points
        val kept = ArrayList<RawPoint>(points.size)
        var anchor = points.first()
        kept.add(anchor)
        for (p in points.drop(1)) {
            val seconds = (p.time - anchor.time).toDouble(DurationUnit.SECONDS)
            if (seconds <= 0.0) {
                kept.add(p); anchor = p; continue
            }
            val speed = Geo.haversine(anchor.latitude, anchor.longitude, p.latitude, p.longitude) / seconds
            if (speed <= maxSpeed) {
                kept.add(p)
                anchor = p
            }
            // else: spike — drop p, keep anchor so the next fix is judged against the last good one.
        }
        return kept
    }
}

/**
 * Smooths the *position* of each fix with a centered moving average over [window] points,
 * within each segment, to stop GPS wander from inflating distance (CLAUDE.md: smooth before
 * integrating). Unlike [AccuracyFilter] and [SpeedFilter], which only *select* raw points, this
 * *moves* coordinates — so its output is a derived view, not fixes "as the OS delivered".
 *
 * It never mutates the input: it returns new [Segment]/[RawPoint] instances for in-memory
 * statistics only, and those must never be written back to storage (raw points are immutable and
 * sacred). Only latitude and longitude change; altitude, time, accuracy, segment index and the
 * rest are carried through unchanged — note that after smoothing a point's own `accuracy`/`speed`
 * describe the underlying raw fix, not the smoothed position. Reusing [RawPoint] as the carrier
 * (rather than a dedicated smoothed-geometry type) is a deliberate scope choice — see
 * `docs/adr/0007-position-smoothing.md`.
 *
 * The average shrinks at each segment's ends (like [io.github.martinzitka.trailog.core.stats.Elevation]'s
 * altitude smoothing) and never reaches across a segment boundary, so a gap is never bridged.
 */
class PositionSmoother(private val window: Int) : TrackFilter {
    init {
        require(window >= 1) { "window must be >= 1, was $window" }
    }

    override fun apply(segments: List<Segment>): List<Segment> {
        if (window <= 1) return segments
        return segments.map { seg ->
            if (seg.points.size < 3) seg else seg.copy(points = smooth(seg.points))
        }
    }

    private fun smooth(points: List<RawPoint>): List<RawPoint> {
        val half = window / 2
        val lats = DoubleArray(points.size) { points[it].latitude }
        val lons = DoubleArray(points.size) { points[it].longitude }
        return points.mapIndexed { i, p ->
            val from = maxOf(0, i - half)
            val to = minOf(points.size - 1, i + half)
            var sumLat = 0.0
            var sumLon = 0.0
            for (j in from..to) { sumLat += lats[j]; sumLon += lons[j] }
            val n = to - from + 1
            p.copy(latitude = sumLat / n, longitude = sumLon / n)
        }
    }
}

/** Applies filters in order, each seeing the previous one's output. */
class ChainedFilter(private val filters: List<TrackFilter>) : TrackFilter {
    constructor(vararg filters: TrackFilter) : this(filters.toList())

    override fun apply(segments: List<Segment>): List<Segment> =
        filters.fold(segments) { acc, filter -> filter.apply(acc) }
}

/** Factories for the standard filter chain. */
object Filters {
    /** A no-op filter, for computing statistics over unfiltered raw points (e.g. diagnostics). */
    val NONE: TrackFilter = TrackFilter { it }

    /**
     * The default chain for an activity type: reject too-inaccurate fixes, then reject
     * implausible speed spikes. Accuracy first, so a wildly inaccurate fix is gone before it
     * can anchor or distort the speed check.
     */
    fun default(type: ActivityType, params: FilterParams = FilterParams()): TrackFilter =
        ChainedFilter(
            AccuracyFilter(params.maxAccuracy),
            SpeedFilter(type.maxPlausibleSpeed),
        )

    /**
     * The default chain plus position smoothing as a final step. **Opt-in**, because position
     * smoothing is not yet in [default]: on clean tracks it barely changes distance (the real
     * reference ride moved <0.1% with it), and its benefit — and the right [window] — can only
     * be tuned against a genuinely noisy track, which does not exist in the fixture set yet.
     * Until then the app should prefer [default]; this is here so the strategy is available and
     * tested. [window] is provisional.
     */
    fun smoothed(
        type: ActivityType,
        params: FilterParams = FilterParams(),
        window: Int = 5,
    ): TrackFilter =
        ChainedFilter(
            AccuracyFilter(params.maxAccuracy),
            SpeedFilter(type.maxPlausibleSpeed),
            PositionSmoother(window),
        )
}
