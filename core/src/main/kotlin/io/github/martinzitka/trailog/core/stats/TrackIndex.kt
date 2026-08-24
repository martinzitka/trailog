package io.github.martinzitka.trailog.core.stats

import io.github.martinzitka.trailog.core.geo.Geo
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.model.Segment
import kotlinx.datetime.Instant
import kotlin.math.abs

/**
 * One located point on a track: the answer to "what was happening at this distance", or "which
 * point is this tap nearest to".
 *
 * A position is always a real recorded fix, never an interpolation between two of them. That is
 * deliberate: interpolating would have to decide what to do at a segment boundary, and the only
 * correct answer there is "nothing was recorded here" (CLAUDE.md). Snapping to the nearer real
 * fix says that honestly.
 *
 * @property segmentIndex which recording segment the fix belongs to, so a caller can tell that a
 *   cursor has landed on the far side of a gap.
 * @property distance metres from the activity's start, accumulated within segments only — the
 *   same x-axis [TrackProfile] plots against.
 * @property elapsedSeconds seconds from the activity's first fix, by fix timestamps rather than
 *   the device clock. Unlike [distance] this *does* advance across a gap: the time passed.
 * @property altitude raw GPS altitude in metres, or null where the fix carried none.
 */
data class TrackPosition(
    val segmentIndex: Int,
    val distance: Double,
    val time: Instant,
    val elapsedSeconds: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
)

/**
 * A track prepared for lookup in both directions: distance → point, and coordinate → point.
 *
 * This is what links a chart to a map. Touching the elevation profile yields a distance, which
 * has to become a place; touching the map yields a place, which has to become a distance the
 * chart can mark. Both directions must agree, so both are answered from one structure built once
 * per activity rather than from two ad-hoc searches that could disagree at a segment boundary.
 *
 * It holds references to the raw points it was built from and one parallel `DoubleArray` of
 * cumulative distances — nothing is copied, so indexing a four-hour ride costs an array of
 * ~14,400 doubles. Lookups are a binary search by distance and a linear scan by coordinate; both
 * are comfortably sub-millisecond at that size, which is what the plan's "responsive on a track
 * of ~15,000 points" asks for.
 *
 * Distances accumulate **within segments only**, exactly as [TrackProfile] and
 * [Statistics.distance] accumulate them, so a recording gap advances the axis by nothing.
 */
class TrackIndex private constructor(
    private val points: List<RawPoint>,
    private val distances: DoubleArray,
) {

    /** Number of indexed fixes. */
    val size: Int get() = points.size

    val isEmpty: Boolean get() = points.isEmpty()

    /** Cumulative distance at the last fix, in metres — the chart's x-extent. */
    val totalDistance: Double = if (distances.isEmpty()) 0.0 else distances[distances.size - 1]

    private val startTime: Instant? = points.firstOrNull()?.time

    /** The indexed fix at [index], counting across every segment in order. */
    operator fun get(index: Int): TrackPosition {
        val point = points[index]
        return TrackPosition(
            segmentIndex = point.segmentIndex,
            distance = distances[index],
            time = point.time,
            elapsedSeconds = startTime?.let { (point.time - it).inWholeSeconds } ?: 0L,
            latitude = point.latitude,
            longitude = point.longitude,
            altitude = point.altitude,
        )
    }

    /**
     * The fix nearest [distance] along the track, or null if there is nothing indexed.
     *
     * Out-of-range values clamp to the first or last fix rather than returning null: a finger
     * dragged off the end of a chart should hold the cursor at the end of the ride, not make it
     * vanish.
     *
     * Across a recording gap two fixes share the same cumulative distance — no ground was
     * covered — and the earlier one wins. Deterministic rather than arbitrary: the cursor stays
     * on the segment that ends at that distance until the drag moves past it.
     */
    fun atDistance(distance: Double): TrackPosition? {
        if (points.isEmpty()) return null

        // Lower bound: the first index whose distance is >= the target.
        var low = 0
        var high = distances.size - 1
        while (low < high) {
            val mid = (low + high) / 2
            if (distances[mid] < distance) low = mid + 1 else high = mid
        }

        // The predecessor may be the closer of the two; `low` is only the first one past.
        val candidate = low
        val previous = (low - 1).coerceAtLeast(0)
        val nearest = if (
            abs(distances[previous] - distance) <= abs(distances[candidate] - distance)
        ) {
            previous
        } else {
            candidate
        }
        return get(nearest)
    }

    /**
     * The fix nearest the given coordinate, or null if there is nothing indexed.
     *
     * A plain linear scan over haversine distances. There is no spatial index because a track is
     * not a map: at ~14,400 points one scan is a fraction of a millisecond, and a quadtree would
     * be a structure to build, invalidate and get wrong for no measurable gain.
     *
     * Note this snaps to the nearest *fix*, not the nearest point on the drawn line. On a 1 Hz
     * recording the two differ by at most half the distance between consecutive fixes — metres
     * at cycling speed — and a tap is nowhere near that precise anyway.
     */
    fun nearestTo(latitude: Double, longitude: Double): TrackPosition? {
        if (points.isEmpty()) return null
        var best = 0
        var bestDistance = Double.MAX_VALUE
        for (i in points.indices) {
            val point = points[i]
            val candidate = Geo.haversine(latitude, longitude, point.latitude, point.longitude)
            if (candidate < bestDistance) {
                bestDistance = candidate
                best = i
            }
        }
        return get(best)
    }

    companion object {

        /** An index over no track at all. Every lookup returns null. */
        val EMPTY: TrackIndex = TrackIndex(emptyList(), DoubleArray(0))

        /**
         * Indexes a track. Segments are walked in the order given, and the running distance
         * carries across a segment boundary **without** adding the gap that separates them.
         */
        fun of(segments: List<Segment>): TrackIndex {
            val total = segments.sumOf { it.points.size }
            if (total == 0) return EMPTY

            val points = ArrayList<RawPoint>(total)
            val distances = DoubleArray(total)
            var cumulative = 0.0
            var i = 0
            for (segment in segments) {
                segment.points.forEachIndexed { indexInSegment, point ->
                    if (indexInSegment > 0) {
                        val previous = segment.points[indexInSegment - 1]
                        cumulative += Geo.haversine(
                            previous.latitude,
                            previous.longitude,
                            point.latitude,
                            point.longitude,
                        )
                    }
                    points.add(point)
                    distances[i] = cumulative
                    i++
                }
            }
            return TrackIndex(points, distances)
        }
    }
}
