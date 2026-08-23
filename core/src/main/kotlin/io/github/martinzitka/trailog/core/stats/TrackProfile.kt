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
 * **Downsampling is bucketed, and how a bucket collapses depends on the quantity.** A four-hour
 * ride at 1 Hz is ~14,400 points; drawing all of them is wasted work at any realistic chart width.
 * Plain stride sampling is wrong for both series — it clips peaks, so the summit of a climb
 * vanishes whenever it lands between strides. Elevation therefore reduces by
 * [Reduction.ENVELOPE], keeping each bucket's extremes; speed reduces by [Reduction.MEAN],
 * because GPS-derived speed is noisier than its own trend and an envelope of it is a solid band
 * rather than a chart. This is a *rendering* concern only: no statistic is ever computed from a
 * downsampled series.
 */
object TrackProfile {

    /**
     * Default cap on plotted samples per series. Comfortably above the pixel width of any phone
     * chart, and ~20× smaller than a four-hour 1 Hz recording.
     */
    const val DEFAULT_MAX_SAMPLES: Int = 800

    /**
     * Points in the centered moving average applied to speed before plotting — about a minute at
     * the 1 Hz Trailog records. Expressed in points, not seconds, so it is sample-rate dependent,
     * like [ElevationParams.smoothingWindow].
     *
     * Far wider than the elevation window, for two compounding reasons. Speed derived from
     * consecutive 1 Hz fixes is much noisier than altitude — a metre of horizontal wander between
     * two fixes reads as 3.6 km/h. And the window only helps if it is **wider than the chart's own
     * output resolution**: at [DEFAULT_MAX_SAMPLES] over a long ride, adjacent plotted samples are
     * already ~15 s apart, so a 15-point window smooths almost nothing that survives to the screen.
     *
     * Tuned (2026-08-16) against three real rides on the developer's device, measuring the mean
     * step between adjacent plotted samples — how jagged the drawn line is:
     *
     * | ride | window 15 | window 61 |
     * |---|---|---|
     * | 67.6 km / 6h45 | 3.27 km/h | 1.77 km/h |
     * | 38.5 km / 3h45 | 2.10 km/h | 0.97 km/h |
     * | 14.3 km / 39 m | 0.91 km/h | 0.36 km/h |
     *
     * Coarsening the sample budget instead was measured and rejected: it makes the line *more*
     * jagged, because each remaining step then spans more ground.
     */
    const val DEFAULT_SPEED_SMOOTHING_WINDOW: Int = 61

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
        return downsampled(raw, maxSamples, Reduction.ENVELOPE)
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
        return downsampled(raw, maxSamples, Reduction.MEAN)
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
     * How a series is reduced to fit the sample budget. The right answer depends on what the
     * reader is looking for, so it is a property of the quantity being plotted rather than one
     * global policy.
     */
    enum class Reduction {
        /**
         * Each bucket contributes its lowest and highest value. Every extreme in the series
         * survives, which is what an **elevation** profile needs: the summit of a climb is the
         * information, and it also underpins the gain figure printed beside the chart.
         *
         * Only appropriate where the signal is larger than the noise. Applied to a noisy series
         * it plots the noise band rather than the trend — see [MEAN].
         */
        ENVELOPE,

        /**
         * Each bucket contributes its mean. This is what a **speed** profile needs: GPS-derived
         * speed at 1 Hz is noisier than the trend it describes, so keeping every extreme would
         * fill the chart with a solid band between a standstill and the fastest wobble instead of
         * showing how fast the ride actually was. Averaging within the bucket is a second stage of
         * smoothing on top of the moving average, and the peak the reader wants is already printed
         * as the max-speed figure.
         */
        MEAN,
    }

    /**
     * Caps the total sample count at [maxSamples] while keeping each segment's own polyline.
     * The budget is shared out in proportion to segment length, with a floor of two samples so a
     * short segment still draws as a line rather than disappearing.
     */
    private fun downsampled(
        segments: List<List<ProfileSample>>,
        maxSamples: Int,
        reduction: Reduction,
    ): ProfileSeries {
        require(maxSamples >= 2) { "maxSamples must be >= 2, was $maxSamples" }
        if (segments.isEmpty()) return ProfileSeries.EMPTY

        val total = segments.sumOf { it.size }
        if (total <= maxSamples) return ProfileSeries(segments)

        return ProfileSeries(
            segments.map { segment ->
                val share = (maxSamples.toDouble() * segment.size / total).roundToInt()
                reduce(segment, share.coerceAtLeast(MIN_SAMPLES_PER_SEGMENT), reduction)
            },
        )
    }

    /**
     * Reduces one segment to at most roughly [budget] samples, keeping the shape of the curve in
     * whichever sense [reduction] defines.
     *
     * **Buckets divide the distance axis, not the sample list**, and that distinction is the
     * whole reason this chart is readable. The x-axis is distance, but fixes arrive on a clock:
     * a rider stopped at a junction for ten minutes produces ~600 fixes that occupy almost no
     * x-width — and GPS wanders while stationary, so they occupy a *little*. Bucketing by index
     * would hand that standstill a fifth of the chart's samples crammed into a few pixels, drawn
     * as a dense vertical stripe. Bucketing by distance gives it the one bucket its distance
     * earns, and spends the rest of the budget on the part of the ride that moved.
     *
     * Emitted distances are strictly increasing, so the path never folds back on itself.
     */
    private fun reduce(
        samples: List<ProfileSample>,
        budget: Int,
        reduction: Reduction,
    ): List<ProfileSample> {
        if (samples.size <= budget) return samples

        val span = samples.last().distance - samples.first().distance
        // A segment that covers no ground has no distance axis to divide. Standing still for an
        // hour is one x position, so one sample is the honest reduction of it.
        if (span <= 0.0) return listOf(samples.first())

        // ENVELOPE emits two samples per bucket, MEAN one, so the same budget buys half as many
        // buckets for the former.
        val buckets = when (reduction) {
            Reduction.ENVELOPE -> budget / 2
            Reduction.MEAN -> budget
        }.coerceAtLeast(1)

        val start = samples.first().distance
        val lowIndex = IntArray(buckets) { EMPTY_BUCKET }
        val highIndex = IntArray(buckets) { EMPTY_BUCKET }
        val valueSum = DoubleArray(buckets)
        val distanceSum = DoubleArray(buckets)
        val count = IntArray(buckets)

        for (i in samples.indices) {
            val sample = samples[i]
            val bucket = (((sample.distance - start) / span) * buckets)
                .toInt().coerceIn(0, buckets - 1)
            if (count[bucket] == 0) {
                lowIndex[bucket] = i
                highIndex[bucket] = i
            } else {
                if (sample.value < samples[lowIndex[bucket]].value) lowIndex[bucket] = i
                if (sample.value > samples[highIndex[bucket]].value) highIndex[bucket] = i
            }
            valueSum[bucket] += sample.value
            distanceSum[bucket] += sample.distance
            count[bucket]++
        }

        val out = ArrayList<ProfileSample>(budget + 2)
        for (bucket in 0 until buckets) {
            if (count[bucket] == 0) continue
            when (reduction) {
                Reduction.ENVELOPE -> {
                    val first = minOf(lowIndex[bucket], highIndex[bucket])
                    val second = maxOf(lowIndex[bucket], highIndex[bucket])
                    out.addIfAdvancing(samples[first])
                    if (second != first) out.addIfAdvancing(samples[second])
                }

                Reduction.MEAN -> out.addIfAdvancing(
                    ProfileSample(
                        distance = distanceSum[bucket] / count[bucket],
                        value = valueSum[bucket] / count[bucket],
                    ),
                )
            }
        }
        if (out.isEmpty()) return samples
        return out.anchoredTo(samples, reduction)
    }

    /** Sentinel for a distance bucket no sample fell into. */
    private const val EMPTY_BUCKET = -1

    /**
     * Stretches a reduced series back out to the segment's true first and last distance, so the
     * polyline spans the whole track however the buckets happened to fall.
     *
     * The two strategies anchor differently, and the difference matters:
     *
     * - [Reduction.ENVELOPE] re-adds the real endpoint **samples**. Extremes are the point of that
     *   strategy and an endpoint is real data.
     * - [Reduction.MEAN] moves the outermost *means* out to the endpoint distances instead. Adding
     *   a raw endpoint back would undo the averaging at exactly one place — and since a single
     *   sample is enough to define the axis extreme, one noisy endpoint would set the chart's whole
     *   y-range and the min/max labels printed beside it.
     */
    private fun List<ProfileSample>.anchoredTo(
        samples: List<ProfileSample>,
        reduction: Reduction,
    ): List<ProfileSample> {
        val startDistance = samples.first().distance
        val endDistance = samples.last().distance
        val out = ArrayList(this)

        when (reduction) {
            Reduction.ENVELOPE -> {
                if (out.first().distance > startDistance) out.add(0, samples.first())
                if (endDistance > out.last().distance) out.add(samples.last())
            }

            Reduction.MEAN -> {
                out[0] = ProfileSample(startDistance, out.first().value)
                // Guarded on size: with a single bucket the two anchors are the same element,
                // and moving it to the end would silently drop the start of the line.
                if (out.size > 1 && endDistance > out.last().distance) {
                    out[out.lastIndex] = ProfileSample(endDistance, out.last().value)
                }
            }
        }
        return out
    }

    /** Appends only if it moves the x-axis forward, keeping emitted distances strictly increasing. */
    private fun MutableList<ProfileSample>.addIfAdvancing(sample: ProfileSample) {
        if (isEmpty() || sample.distance > last().distance) add(sample)
    }

    /** Floor on any one segment's sample budget: two points still draw as a line. */
    private const val MIN_SAMPLES_PER_SEGMENT = 2
}
