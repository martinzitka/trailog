package io.github.martinzitka.trailog.core.model

import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One sensor reading at one instant — a heart rate, a cadence, a power figure, a temperature.
 *
 * **Samples are their own stream, not fields on a [RawPoint].** A field on a raw point can only
 * carry a reading when a location fix arrives, so nothing would be recorded during a signal
 * blackout, a tunnel, or a pause — while a chest strap keeps emitting at about 1 Hz regardless of
 * what the GPS is doing. Sensor data shares the track's clock and nothing else.
 *
 * Samples are raw data and are as immutable and sacred as raw points (CLAUDE.md): stored exactly
 * as delivered, never smoothed on ingest, never deleted except when the user deletes the whole
 * activity. Averages and chart lines are recomputable views over them.
 *
 * Like fixes, samples are assumed to arrive out of order and duplicated — [SensorStream] sorts and
 * de-duplicates on read rather than constraining what may be written.
 *
 * @property time when the reading was taken, on the same clock as [RawPoint.time].
 * @property type which sensor produced it; also fixes the unit of [value].
 * @property value the reading, in [SensorType.unit]. There is exactly one unit per type and
 *   conversion happens only at the display edge.
 */
data class SensorSample(
    val time: Instant,
    val type: SensorType,
    val value: Double,
)

/**
 * Reading a list of [SensorSample]s: ordering, de-duplication, and alignment onto the segments of
 * a track.
 *
 * **Alignment is by timestamp, and never crosses a segment boundary.** A sample falling in the gap
 * between two segments — during a blackout, a reboot, or a pause — belongs to neither, and is not
 * attributed to whichever segment happens to be nearest. This is the same rule the statistics
 * already follow, for the same reason: a boundary is where a naive implementation invents data.
 */
object SensorStream {

    /**
     * How far from a location fix a sample may be and still be reported as that fix's reading by
     * [nearestTo]. Three seconds: wide enough to absorb the mismatch between a ~1 Hz strap and a
     * ~1 Hz GPS whose timestamps never line up exactly, narrow enough that a fix in a sparse
     * stretch is not labelled with a stale heart rate.
     *
     * This tolerance exists only for the *lossy projection* onto GPX track points that third-party
     * tools can read. Trailog's own storage and its own GPX extension keep every sample's exact
     * timestamp.
     */
    val MATCH_TOLERANCE: Duration = 3.seconds

    /**
     * Samples in ascending time order, with samples that share both a type and a timestamp
     * collapsed to the first seen. Different types at the same instant are all kept — a strap and
     * a power meter reporting together is normal, not a duplicate.
     */
    fun normalise(samples: List<SensorSample>): List<SensorSample> =
        samples.sortedBy { it.time }.distinctBy { it.type to it.time }

    /** Only the samples of one [type], normalised. */
    fun ofType(samples: List<SensorSample>, type: SensorType): List<SensorSample> =
        normalise(samples.filter { it.type == type })

    /** Every sensor type actually present in [samples], in declaration order. */
    fun typesIn(samples: List<SensorSample>): List<SensorType> =
        SensorType.entries.filter { type -> samples.any { it.type == type } }

    /**
     * Groups samples onto the segments they were taken during, keyed by [Segment.index].
     *
     * A sample belongs to a segment when its timestamp lies within that segment's own span — from
     * its first fix to its last, inclusive. Samples before the first fix, after the last, or in the
     * gap between two segments belong to no segment and are **omitted**, because there is no
     * segment they could honestly be charted against.
     *
     * Segment spans cannot overlap in a real recording (a segment ends before the next begins), but
     * if they did, the first matching segment wins.
     */
    fun bySegment(
        samples: List<SensorSample>,
        segments: List<Segment>,
    ): Map<Int, List<SensorSample>> {
        if (samples.isEmpty() || segments.isEmpty()) return emptyMap()
        val spans = segments
            .filter { it.points.isNotEmpty() }
            .map { it.index to (it.points.first().time..it.points.last().time) }
        if (spans.isEmpty()) return emptyMap()

        val grouped = LinkedHashMap<Int, MutableList<SensorSample>>()
        for (sample in normalise(samples)) {
            val index = spans.firstOrNull { sample.time in it.second }?.first ?: continue
            grouped.getOrPut(index) { ArrayList() }.add(sample)
        }
        return grouped
    }

    /**
     * The sample of [type] closest in time to [time], or null when the nearest one is further away
     * than [MATCH_TOLERANCE] (or there is none at all).
     *
     * [samples] must already be sorted by time — pass the output of [normalise] or [ofType]. The
     * search is a binary search, so projecting a whole track's fixes onto a whole track's samples
     * stays linearithmic rather than quadratic.
     */
    fun nearestTo(
        samples: List<SensorSample>,
        time: Instant,
        tolerance: Duration = MATCH_TOLERANCE,
    ): SensorSample? {
        if (samples.isEmpty()) return null

        // binarySearchBy returns (-insertionPoint - 1) when absent; either neighbour may be nearer.
        val found = samples.binarySearchBy(time) { it.time }
        if (found >= 0) return samples[found]
        val after = -found - 1
        val candidates = listOfNotNull(
            samples.getOrNull(after - 1),
            samples.getOrNull(after),
        )
        return candidates
            .minByOrNull { (it.time - time).absoluteValue }
            ?.takeIf { (it.time - time).absoluteValue <= tolerance }
    }
}
