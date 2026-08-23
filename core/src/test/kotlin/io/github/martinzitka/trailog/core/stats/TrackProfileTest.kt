package io.github.martinzitka.trailog.core.stats

import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.model.Segment
import kotlinx.datetime.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class TrackProfileTest {

    private fun pt(
        sec: Long,
        seg: Int,
        lat: Double,
        lon: Double,
        altitude: Double? = null,
    ) = RawPoint(
        latitude = lat,
        longitude = lon,
        altitude = altitude,
        accuracy = null,
        time = Instant.fromEpochSeconds(sec),
        segmentIndex = seg,
    )

    /**
     * A straight eastward line at 50°N, one fix per second, ~7.2 m apart. [altitudeAt] supplies
     * the altitude for each index so a test can shape the profile it wants.
     */
    private fun line(
        count: Int,
        segment: Int = 0,
        startSecond: Long = 0,
        startLon: Double = 14.0,
        altitudeAt: (Int) -> Double? = { null },
    ): List<RawPoint> = (0 until count).map { i ->
        pt(
            sec = startSecond + i,
            seg = segment,
            lat = 50.0,
            lon = startLon + i * 0.0001,
            altitude = altitudeAt(i),
        )
    }

    /**
     * A ride that alternates a fast second with a slow one, at 1 Hz. Hop lengths alternate
     * between ~3.6 m and ~14.3 m at 50°N, so the derived speed swings between roughly 3.6 and
     * 14.3 m/s around a ~9 m/s mean — the noise-dominated shape real GPS speed has.
     */
    private fun alternatingPaceRide(count: Int): List<RawPoint> {
        var lon = 14.0
        return (0 until count).map { i ->
            if (i > 0) lon += if (i % 2 == 0) 0.00005 else 0.0002
            RawPoint(
                latitude = 50.0,
                longitude = lon,
                altitude = null,
                accuracy = null,
                time = Instant.fromEpochSeconds(i.toLong()),
                segmentIndex = 0,
            )
        }
    }

    // ---- segment awareness -----------------------------------------------------------------

    @Test
    fun `each segment becomes its own polyline`() {
        val points = line(10, segment = 0, altitudeAt = { 300.0 + it }) +
            line(10, segment = 1, startSecond = 600, startLon = 14.05, altitudeAt = { 400.0 + it })

        val profile = TrackProfile.elevation(Segment.segmentsOf(points))

        assertEquals(2, profile.segments.size, "two segments must stay two polylines")
        assertTrue(profile.segments.all { it.isNotEmpty() })
    }

    @Test
    fun `the distance axis does not advance across a segment gap`() {
        // Segment 1 restarts ~3.5 km east of where segment 0 ended. That jump is not distance.
        val points = line(10, segment = 0, altitudeAt = { 300.0 }) +
            line(10, segment = 1, startSecond = 600, startLon = 14.05, altitudeAt = { 300.0 })
        val segments = Segment.segmentsOf(points)

        val profile = TrackProfile.elevation(segments)
        val endOfFirst = profile.segments[0].last().distance
        val startOfSecond = profile.segments[1].first().distance

        assertEquals(
            endOfFirst,
            startOfSecond,
            1e-6,
            "the gap covered no distance, so the axis must not advance over it",
        )
        // And the axis as a whole agrees with the distance statistic, which excludes the jump.
        assertEquals(Statistics.distance(segments), profile.maxDistance, 1e-6)
    }

    @Test
    fun `elevation is never differenced across a segment boundary`() {
        // A 500 m altitude cliff between segments. Nothing may be plotted between the two ends.
        val points = line(10, segment = 0, altitudeAt = { 300.0 }) +
            line(10, segment = 1, startSecond = 600, startLon = 14.05, altitudeAt = { 800.0 })

        val profile = TrackProfile.elevation(Segment.segmentsOf(points))

        val first = profile.segments[0]
        val second = profile.segments[1]
        assertTrue(first.all { it.value < 400.0 }, "segment 0 must stay at its own altitude")
        assertTrue(second.all { it.value > 700.0 }, "segment 1 must stay at its own altitude")
        // No sample bridges the two — the cliff exists only as the break between polylines.
        assertTrue(profile.segments.none { seg -> seg.any { it.value in 400.0..700.0 } })
    }

    // ---- elevation profile -----------------------------------------------------------------

    @Test
    fun `elevation plots the same smoothed series the gain figure accumulates`() {
        val altitudes = listOf(
            300.0, 302.0, 301.0, 305.0, 308.0, 307.0, 312.0, 315.0, 314.0, 320.0,
        )
        val points = line(altitudes.size, altitudeAt = { altitudes[it] })
        val params = ElevationParams(smoothingWindow = 5, threshold = 6.0)

        val profile = TrackProfile.elevation(Segment.segmentsOf(points), params)
        val expected = Elevation.movingAverage(altitudes, params.smoothingWindow)

        assertEquals(expected.size, profile.segments.single().size)
        expected.forEachIndexed { i, value ->
            assertEquals(value, profile.segments.single()[i].value, 1e-9)
        }
    }

    @Test
    fun `points with no altitude are skipped but still carry the distance axis`() {
        // Only every third fix reports an altitude; the x-axis must still reach the full distance.
        val points = line(12) { if (it % 3 == 0) 300.0 + it else null }
        val segments = Segment.segmentsOf(points)

        val profile = TrackProfile.elevation(segments, ElevationParams(smoothingWindow = 1))

        assertEquals(4, profile.segments.single().size)
        val lastAltitudeIndex = 9 // the last index where i % 3 == 0
        val fullDistance = Statistics.distance(segments)
        assertTrue(
            profile.maxDistance < fullDistance,
            "the last altitude-bearing fix is not the last fix, so the series ends short",
        )
        assertTrue(profile.maxDistance > fullDistance * lastAltitudeIndex / 12.0)
    }

    @Test
    fun `a segment with fewer than two altitudes is dropped rather than plotted as a dot`() {
        val points = line(6) { if (it == 0) 300.0 else null }

        val profile = TrackProfile.elevation(Segment.segmentsOf(points))

        assertTrue(profile.isEmpty)
        assertEquals(0, profile.sampleCount)
        assertEquals(null, profile.minValue)
    }

    @Test
    fun `an activity with no points profiles as empty rather than throwing`() {
        assertTrue(TrackProfile.elevation(emptyList()).isEmpty)
        assertTrue(TrackProfile.speed(emptyList()).isEmpty)
        assertEquals(0.0, TrackProfile.speed(emptyList()).maxDistance)
    }

    // ---- speed profile ---------------------------------------------------------------------

    @Test
    fun `speed is derived from in-segment hops, matching the max speed statistic`() {
        // Constant 7.2 m per second: one 0.0001 deg longitude step per second at 50N.
        val segments = Segment.segmentsOf(line(30))

        // No smoothing, so each sample is exactly its hop's speed.
        val profile = TrackProfile.speed(segments, smoothingWindow = 1)
        val expected = Statistics.maxSpeed(segments)

        assertNotNull(profile.maxValue)
        assertEquals(expected, profile.maxValue!!, 1e-6)
        assertTrue(profile.segments.single().all { abs(it.value - expected) < 1e-6 })
    }

    @Test
    fun `the first fix of a segment gets a speed so the polyline starts at its start`() {
        val segments = Segment.segmentsOf(line(5))

        val profile = TrackProfile.speed(segments, smoothingWindow = 1)
        val samples = profile.segments.single()

        assertEquals(5, samples.size, "every fix gets a sample, including the opening one")
        assertEquals(0.0, samples.first().distance, 1e-9)
        assertEquals(samples[1].value, samples[0].value, 1e-9)
    }

    @Test
    fun `a repeated timestamp yields no speed spike`() {
        // Two fixes sharing a second would divide by zero. Segment dedupe drops one of them;
        // a clock that jumps backwards inside a segment must not produce a spike either.
        val points = listOf(
            pt(0, 0, 50.0, 14.0000),
            pt(1, 0, 50.0, 14.0001),
            pt(1, 0, 50.0, 14.0002),
            pt(2, 0, 50.0, 14.0003),
        )

        val profile = TrackProfile.speed(Segment.segmentsOf(points), smoothingWindow = 1)

        assertNotNull(profile.maxValue)
        assertTrue(
            profile.maxValue!! < 100.0,
            "a duplicate timestamp must not read as a ${profile.maxValue} m/s spike",
        )
    }

    @Test
    fun `speed smoothing flattens a single-fix wobble`() {
        // A steady line with one fix displaced far east and back — classic GPS noise.
        val points = line(40).mapIndexed { i, p ->
            if (i == 20) p.copy(longitude = p.longitude + 0.0005) else p
        }
        val segments = Segment.segmentsOf(points)

        val unsmoothed = TrackProfile.speed(segments, smoothingWindow = 1).maxValue!!
        val smoothed = TrackProfile.speed(segments, smoothingWindow = 15).maxValue!!

        assertTrue(
            smoothed < unsmoothed / 2,
            "smoothing must damp the wobble: $smoothed should be well under $unsmoothed",
        )
    }

    @Test
    fun `a zero smoothing window is rejected rather than silently ignored`() {
        assertFailsWith<IllegalArgumentException> {
            TrackProfile.speed(Segment.segmentsOf(line(5)), smoothingWindow = 0)
        }
    }

    // ---- downsampling ----------------------------------------------------------------------

    @Test
    fun `a four-hour recording is reduced to the sample budget`() {
        // 14,400 fixes — 4 hours at 1 Hz, the size CLAUDE.md calls out.
        val points = line(14_400, altitudeAt = { 300.0 + it % 50 })

        val profile = TrackProfile.elevation(Segment.segmentsOf(points), maxSamples = 800)

        assertTrue(
            profile.sampleCount <= 800,
            "expected at most 800 samples, got ${profile.sampleCount}",
        )
        assertTrue(profile.sampleCount > 100, "the reduction must not gut the profile")
    }

    @Test
    fun `downsampling keeps the peak instead of clipping it`() {
        // A flat 3,000-point ride with one sharp summit. Stride sampling would likely miss it.
        val summitIndex = 1_234
        val points = line(3_000) { if (it == summitIndex) 900.0 else 300.0 }

        val profile = TrackProfile.elevation(
            Segment.segmentsOf(points),
            // No smoothing, so the summit stays a single-sample spike — the hardest case.
            params = ElevationParams(smoothingWindow = 1),
            maxSamples = 200,
        )

        assertEquals(900.0, profile.maxValue!!, 1e-9, "the summit must survive downsampling")
        assertEquals(300.0, profile.minValue!!, 1e-9)
    }

    @Test
    fun `downsampling keeps the distance axis spanning the whole track`() {
        val points = line(5_000, altitudeAt = { 300.0 + it % 30 })
        val segments = Segment.segmentsOf(points)

        val profile = TrackProfile.elevation(segments, maxSamples = 300)
        val samples = profile.segments.single()

        assertEquals(0.0, samples.first().distance, 1e-9)
        assertEquals(Statistics.distance(segments), samples.last().distance, 1e-6)
    }

    @Test
    fun `downsampled distances stay strictly increasing so the path never folds back`() {
        val points = line(6_000, altitudeAt = { 300.0 + (it % 100) })

        val profile = TrackProfile.elevation(Segment.segmentsOf(points), maxSamples = 400)

        for (segment in profile.segments) {
            segment.zipWithNext { a, b ->
                assertTrue(
                    b.distance > a.distance,
                    "distance must increase: ${a.distance} then ${b.distance}",
                )
            }
        }
    }

    @Test
    fun `a short segment keeps at least a drawable line when the budget is shared out`() {
        // One long segment and one tiny one. Proportional budgeting would round the small
        // segment down to nothing; the floor must keep it drawable.
        val points = line(5_000, segment = 0, altitudeAt = { 300.0 }) +
            line(4, segment = 1, startSecond = 9_000, startLon = 14.9, altitudeAt = { 400.0 })

        val profile = TrackProfile.elevation(Segment.segmentsOf(points), maxSamples = 100)

        assertEquals(2, profile.segments.size)
        assertTrue(
            profile.segments[1].size >= 2,
            "the short segment must keep at least two samples, had ${profile.segments[1].size}",
        )
    }

    @Test
    fun `speed downsampling plots the trend, not a band between its extremes`() {
        // A rider alternating hard and easy every second — the shape of real 1 Hz GPS speed,
        // where the noise is larger than the trend. Reducing this by keeping each bucket's
        // minimum and maximum fills the chart with a solid floor-to-ceiling band; averaging
        // within the bucket shows the ~8 m/s the ride was actually done at.
        val points = alternatingPaceRide(3_000)
        val segments = Segment.segmentsOf(points)

        val raw = TrackProfile.speed(segments, smoothingWindow = 1, maxSamples = 4_000)
        val reduced = TrackProfile.speed(segments, smoothingWindow = 1, maxSamples = 100)

        val rawSpread = raw.maxValue!! - raw.minValue!!
        val reducedSpread = reduced.maxValue!! - reduced.minValue!!

        assertTrue(rawSpread > 5.0, "the raw series must really be noisy, spread was $rawSpread")
        assertTrue(
            reducedSpread < rawSpread / 4,
            "a reduced speed series must show the trend: spread $reducedSpread " +
                "should be far under the raw $rawSpread",
        )
        // And the trend it shows is the actual average pace, not one of the extremes.
        val mean = (raw.maxValue!! + raw.minValue!!) / 2
        assertEquals(mean, reduced.maxValue!!, 1.0)
    }

    @Test
    fun `elevation downsampling still keeps extremes that speed deliberately averages away`() {
        // The same noisy input, read as altitude. Elevation keeps its envelope — the two
        // reductions are chosen per quantity, not globally.
        val altitudes = { i: Int -> if (i % 2 == 0) 300.0 else 340.0 }
        val points = line(3_000, altitudeAt = altitudes)

        val profile = TrackProfile.elevation(
            Segment.segmentsOf(points),
            params = ElevationParams(smoothingWindow = 1),
            maxSamples = 100,
        )

        assertEquals(340.0, profile.maxValue!!, 1e-9)
        assertEquals(300.0, profile.minValue!!, 1e-9)
    }

    @Test
    fun `a mean-reduced speed series still spans the whole track`() {
        val points = alternatingPaceRide(3_000)
        val segments = Segment.segmentsOf(points)

        val profile = TrackProfile.speed(segments, maxSamples = 200)
        val samples = profile.segments.single()

        assertEquals(0.0, samples.first().distance, 1e-9)
        assertEquals(Statistics.distance(segments), samples.last().distance, 1e-6)
        samples.zipWithNext { a, b ->
            assertTrue(b.distance > a.distance, "distances must stay strictly increasing")
        }
    }

    @Test
    fun `a track under the budget is passed through untouched`() {
        val points = line(50, altitudeAt = { 300.0 + it })

        val profile = TrackProfile.elevation(
            Segment.segmentsOf(points),
            params = ElevationParams(smoothingWindow = 1),
            maxSamples = 800,
        )

        assertEquals(50, profile.sampleCount)
    }

    @Test
    fun `an unusable sample budget is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            TrackProfile.elevation(Segment.segmentsOf(line(10) { 300.0 }), maxSamples = 1)
        }
    }
}
