package io.github.martinzitka.trailog.core.stats

import io.github.martinzitka.trailog.core.geo.Geo
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.model.Segment
import kotlin.math.roundToInt
import kotlin.time.DurationUnit

/**
 * One plotted sample: a value at a cumulative distance along the activity.
 *
 * @property distance metres from the activity's start, accumulated **within segments only** — a
 *   recording gap adds no distance, exactly as [Statistics.distance] treats it.
 * @property value the plotted quantity in SI units: metres for an elevation profile, metres per
 *   second for a speed profile. Formatting happens at the display edge, never here.
 */
data class ProfileSample(
    val distance: Double,
    val value: Double,
)

/**
 * A chart-ready series, **one polyline per segment**. Keeping the segments apart is the whole
 * point: a renderer draws each list as its own path, so the break at a recording gap can never
 * become a straight line joining the ends (CLAUDE.md). Flattening this into a single list would
 * reintroduce exactly the bug every statistics function here is careful to avoid.
 *
 * The x-axis is cumulative distance, which does not advance across a gap, so a break shows as a
 * vertical discontinuity rather than a horizontal one. That is the honest picture: no distance
 * was covered, and no line is drawn between the two sides.
 *
 * Empty segments are omitted, so an empty [segments] list means there is nothing to plot at all.
 */
data class ProfileSeries(
    val segments: List<List<ProfileSample>>,
) {
    val isEmpty: Boolean = segments.all { it.isEmpty() }

    /** Total number of plotted samples — the figure the downsampling budget caps. */
    val sampleCount: Int = segments.sumOf { it.size }

    /** Smallest plotted value, or null when there is nothing to plot. */
    val minValue: Double? = segments.flatten().minOfOrNull { it.value }

    /** Largest plotted value, or null when there is nothing to plot. */
    val maxValue: Double? = segments.flatten().maxOfOrNull { it.value }

    /** The x-extent: cumulative distance at the last plotted sample, in metres. */
    val maxDistance: Double = segments.flatten().maxOfOrNull { it.distance } ?: 0.0

    companion object {
        val EMPTY = ProfileSeries(emptyList())
    }
}

/**
 * Elevation and speed profiles for charting — derived views over raw points, like every other
 * statistic here, and segment-aware in the same way.
 *
 * Two design decisions worth stating, because both are load-bearing:
 *
 * **The series reuse the statistics pipeline's own smoothing.** Altitude is plotted after the
 * same centered moving average [Elevation] accumulates gain over, and speed after an equivalent
 * one. A chart drawn from a differently-smoothed series would visibly disagree with the summary
 * figures printed above it, and a user would be right to trust neither.
 *
 * **Downsampling preserves the envelope, not every nth point.** A four-hour ride at 1 Hz is
 * ~14,400 points; drawing all of them is wasted work at any realistic chart width. Plain stride
 * sampling would clip peaks — the summit of a climb lands between strides and vanishes. Instead
 * each bucket contributes its minimum and maximum, so the profile's shape survives at a fraction
 * of the samples. This is a *rendering* concern only: no statistic is ever computed from a
 * downsampled series.
 */
object TrackProfile {

    /**
     * Default cap on plotted samples per series. Comfortably above the pixel width of any phone
     * chart, and ~20× smaller than a four-hour 1 Hz recording.
     */
    const val DEFAULT_MAX_SAMPLES: Int = 800

    /**
     * Points in the centered moving average applied to speed before plotting. Speed derived from
     * consecutive 1 Hz fixes is far noisier than altitude — a metre of horizontal wander between
     * two fixes reads as 3.6 km/h — so it gets a wider window than [ElevationParams] uses.
     */
    const val DEFAULT_SPEED_SMOOTHING_WINDOW: Int = 15

    /**
     * Altitude against cumulative distance, smoothed exactly as [Elevation] smooths it.
     *
     * Points that carry no altitude are skipped (they still contribute their distance, since the
     * x-axis is the full track's distance). A segment with fewer than two altitude-bearing points
     * is dropped rather than plotted as a dot.
     *
     * Note this is raw GPS altitude relative to the WGS84 ellipsoid — the geoid separation is a
     * near-constant offset over one activity, so it shifts the whole curve without changing its
     * shape. Absolute-altitude anchoring is a separate concern (CLAUDE.md).
     */
    fun elevation(
        segments: List<Segment>,
        params: ElevationParams = ElevationParams(),
        maxSamples: Int = DEFAULT_MAX_SAMPLES,
    ): ProfileSeries {
        val raw = perSegmentSamples(segments) { points, distances ->
            val altitudes = ArrayList<Double>(points.size)
            val xs = ArrayList<Double>(points.size)
            points.forEachIndexed { i, point ->
                val altitude = point.altitude
                if (altitude != null) {
                    altitudes.add(altitude)
                    xs.add(distances[i])
                }
            }
            if (altitudes.size < 2) {
                emptyList()
            } else {
                Elevation.movingAverage(altitudes, params.smoothingWindow)
                    .mapIndexed { i, value -> ProfileSample(xs[i], value) }
            }
        }
        return downsampled(raw, maxSamples)
    }

    /**
     * Ground speed against cumulative distance, computed from the distance and time between
     * consecutive **in-segment** fixes — the same hop-based definition [Statistics.maxSpeed] uses,
     * so the curve's peak and the reported maximum describe the same thing.
     *
     * The OS-reported per-fix speed is deliberately not used: it is absent on some fixes and
     * derived differently by different providers, which would make the chart's shape depend on
     * hardware. Each hop's speed is attributed to the fix that ends it, and the first fix of a
     * segment repeats the first hop's speed so the polyline starts at the segment's start.
     */
    fun speed(
        segments: List<Segment>,
        smoothingWindow: Int = DEFAULT_SPEED_SMOOTHING_WINDOW,
        maxSamples: Int = DEFAULT_MAX_SAMPLES,
    ): ProfileSeries {
        require(smoothingWindow >= 1) { "smoothingWindow must be >= 1, was $smoothingWindow" }

        val raw = perSegmentSamples(segments) { points, distances ->
            if (points.size < 2) {
                emptyList()
            } else {
                val speeds = ArrayList<Double>(points.size)
                for ((a, b) in points.zipWithNext()) {
                    val seconds = (b.time - a.time).toDouble(DurationUnit.SECONDS)
                    val hop = Geo.haversine(a.latitude, a.longitude, b.latitude, b.longitude)
                    // A non-advancing or backwards clock cannot yield a speed; carry the last
                    // known value rather than dividing by zero or inventing a spike.
                    speeds.add(if (seconds > 0) hop / seconds else speeds.lastOrNull() ?: 0.0)
                }
                // The opening fix has no hop of its own; it takes the first one's speed.
                speeds.add(0, speeds.first())
                Elevation.movingAverage(speeds, smoothingWindow)
                    .mapIndexed { i, value -> ProfileSample(distances[i], value) }
            }
        }
        return downsampled(raw, maxSamples)
    }

    /**
     * Runs [build] over each segment's points alongside their cumulative distances. The running
     * total carries across segments **without** adding the gap between them, so the x-axis matches
     * [Statistics.distance] and a gap advances it by nothing.
     */
    private inline fun perSegmentSamples(
        segments: List<Segment>,
        build: (points: List<RawPoint>, distances: List<Double>) -> List<ProfileSample>,
    ): List<List<ProfileSample>> {
        var cumulative = 0.0
        val out = ArrayList<List<ProfileSample>>(segments.size)
        for (segment in segments) {
            val points = segment.points
            if (points.isEmpty()) continue
            val distances = ArrayList<Double>(points.size)
            distances.add(cumulative)
            for ((a, b) in points.zipWithNext()) {
                cumulative += Geo.haversine(a.latitude, a.longitude, b.latitude, b.longitude)
                distances.add(cumulative)
            }
            val samples = build(points, distances)
            if (samples.isNotEmpty()) out.add(samples)
        }
        return out
    }

    /**
     * Caps the total sample count at [maxSamples] while keeping each segment's own polyline.
     * The budget is shared out in proportion to segment length, with a floor of two samples so a
     * short segment still draws as a line rather than disappearing.
     */
    private fun downsampled(
        segments: List<List<ProfileSample>>,
        maxSamples: Int,
    ): ProfileSeries {
        require(maxSamples >= 2) { "maxSamples must be >= 2, was $maxSamples" }
        if (segments.isEmpty()) return ProfileSeries.EMPTY

        val total = segments.sumOf { it.size }
        if (total <= maxSamples) return ProfileSeries(segments)

        return ProfileSeries(
            segments.map { segment ->
                val share = (maxSamples.toDouble() * segment.size / total).roundToInt()
                envelope(segment, share.coerceAtLeast(MIN_SAMPLES_PER_SEGMENT))
            },
        )
    }

    /**
     * Reduces one segment to at most roughly [budget] samples, keeping the shape of the curve.
     *
     * The samples are cut into `budget / 2` equal buckets and each bucket contributes its lowest
     * and highest value, emitted in their original order. Every extreme in the series is therefore
     * still an emitted sample — a summit or a sprint survives the reduction, where stride sampling
     * would have thrown it away whenever it fell between strides. First and last samples are
     * always kept so the polyline still spans the segment's full distance.
     *
     * Emitted distances are strictly increasing. Standing still produces many fixes at one
     * distance, and a line chart can only draw one value per x anyway, so coincident samples
     * collapse into the first of them rather than folding the path back on itself.
     */
    private fun envelope(samples: List<ProfileSample>, budget: Int): List<ProfileSample> {
        if (samples.size <= budget) return samples

        val buckets = (budget / 2).coerceAtLeast(1)
        val out = ArrayList<ProfileSample>(budget + 2)
        out.add(samples.first())
        for (bucket in 0 until buckets) {
            val from = (bucket.toLong() * samples.size / buckets).toInt()
            val to = ((bucket + 1).toLong() * samples.size / buckets).toInt()
            if (to <= from) continue
            var lowIndex = from
            var highIndex = from
            for (i in from until to) {
                if (samples[i].value < samples[lowIndex].value) lowIndex = i
                if (samples[i].value > samples[highIndex].value) highIndex = i
            }
            val first = minOf(lowIndex, highIndex)
            val second = maxOf(lowIndex, highIndex)
            if (samples[first].distance > out.last().distance) out.add(samples[first])
            if (second != first && samples[second].distance > out.last().distance) {
                out.add(samples[second])
            }
        }
        if (samples.last().distance > out.last().distance) out.add(samples.last())
        return out
    }

    /** Floor on any one segment's sample budget: two points still draw as a line. */
    private const val MIN_SAMPLES_PER_SEGMENT = 2
}
