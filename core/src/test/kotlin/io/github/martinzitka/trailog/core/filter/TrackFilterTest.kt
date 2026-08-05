package io.github.martinzitka.trailog.core.filter

import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.model.Segment
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrackFilterTest {

    private fun pt(
        sec: Long,
        seg: Int,
        lat: Double,
        lon: Double,
        accuracy: Double? = null,
    ) = RawPoint(
        latitude = lat,
        longitude = lon,
        altitude = null,
        accuracy = accuracy,
        time = Instant.fromEpochSeconds(sec),
        segmentIndex = seg,
    )

    @Test
    fun `accuracy filter drops fixes worse than the threshold and keeps unknown ones`() {
        val pts = listOf(
            pt(0, 0, 50.0, 14.0000, accuracy = 3.0),
            pt(1, 0, 50.0, 14.0001, accuracy = 80.0), // too uncertain -> dropped
            pt(2, 0, 50.0, 14.0002, accuracy = null), // unknown -> kept
            pt(3, 0, 50.0, 14.0003, accuracy = 5.0),
        )
        val filtered = AccuracyFilter(maxAccuracy = 50.0).apply(Segment.segmentsOf(pts))
        val times = filtered.single().points.map { it.time.epochSeconds }
        assertEquals(listOf(0L, 2L, 3L), times)
    }

    @Test
    fun `speed filter removes a single teleport spike but keeps the good fixes around it`() {
        // Steady ~7 m/s line with one fix flung ~1.1 km east (impossible in 1 s) then back.
        val pts = listOf(
            pt(0, 0, 50.0, 14.0000),
            pt(1, 0, 50.0, 14.0001),
            pt(2, 0, 50.0, 14.0200), // spike: ~1.4 km in 1 s
            pt(3, 0, 50.0, 14.0002), // back on track relative to the last GOOD fix
            pt(4, 0, 50.0, 14.0003),
        )
        val filtered = SpeedFilter(ActivityType.CYCLING.maxPlausibleSpeed).apply(Segment.segmentsOf(pts))
        val lons = filtered.single().points.map { it.longitude }
        assertEquals(listOf(14.0000, 14.0001, 14.0002, 14.0003), lons)
    }

    @Test
    fun `speed filter judges against the last accepted fix, not the rejected one`() {
        // If the filter anchored on the spike, the fix after it would also look implausible.
        val pts = listOf(
            pt(0, 0, 50.0, 14.0000),
            pt(1, 0, 50.0, 14.0500), // huge spike ~3.6 km
            pt(2, 0, 50.0, 14.0001), // fine vs point 0, absurd vs the spike
        )
        val filtered = SpeedFilter(ActivityType.CYCLING.maxPlausibleSpeed).apply(Segment.segmentsOf(pts))
        assertEquals(listOf(14.0000, 14.0001), filtered.single().points.map { it.longitude })
    }

    @Test
    fun `filters never merge across a segment boundary`() {
        // Two segments; a cross-boundary "jump" is huge but must not be filtered as a spike,
        // because filtering is within-segment only. Each segment keeps its own anchor.
        val pts = listOf(
            pt(0, 0, 50.0, 14.0000),
            pt(1, 0, 50.0, 14.0001),
            pt(600, 1, 50.0, 14.5000), // different segment, far away, long after
            pt(601, 1, 50.0, 14.5001),
        )
        val filtered = Filters.default(ActivityType.CYCLING).apply(Segment.segmentsOf(pts))
        assertEquals(2, filtered.size)
        assertEquals(2, filtered[0].points.size)
        assertEquals(2, filtered[1].points.size)
    }

    @Test
    fun `default chain applies accuracy then speed`() {
        val pts = listOf(
            pt(0, 0, 50.0, 14.0000, accuracy = 3.0),
            pt(1, 0, 50.0, 14.0001, accuracy = 3.0),
            pt(2, 0, 50.0, 14.0200, accuracy = 99.0), // dropped by accuracy AND a spike
            pt(3, 0, 50.0, 14.0002, accuracy = 3.0),
        )
        val filtered = Filters.default(ActivityType.CYCLING).apply(Segment.segmentsOf(pts))
        assertEquals(listOf(14.0000, 14.0001, 14.0002), filtered.single().points.map { it.longitude })
    }

    @Test
    fun `a segment fully filtered away is dropped`() {
        val pts = listOf(
            pt(0, 0, 50.0, 14.0, accuracy = 200.0),
            pt(1, 0, 50.0, 14.0, accuracy = 300.0),
            pt(600, 1, 50.0, 14.0, accuracy = 2.0),
        )
        val filtered = AccuracyFilter(50.0).apply(Segment.segmentsOf(pts))
        assertEquals(1, filtered.size)
        assertEquals(1, filtered.single().index) // the surviving segment keeps its original index
    }

    @Test
    fun `NONE filter is a pass-through`() {
        val segments = Segment.segmentsOf(listOf(pt(0, 0, 50.0, 14.0), pt(1, 0, 50.0, 14.0001)))
        assertEquals(segments, Filters.NONE.apply(segments))
    }

    @Test
    fun `FilterParams rejects a non-positive accuracy`() {
        assertFailsWith<IllegalArgumentException> { FilterParams(maxAccuracy = 0.0) }
    }

    @Test
    fun `filtering reduces GPS-noise distance inflation`() {
        // A straight run with every other fix jittered sideways inflates raw distance; the
        // filter should bring the measured distance closer to the true straight-line length.
        val clean = (0..20).map { pt(it.toLong(), 0, 50.0, 14.0 + it * 0.0001) }
        val jittered = clean.mapIndexed { i, p ->
            if (i % 2 == 1) p.copy(latitude = 50.0 + 0.0003) else p // ~33 m sideways spikes
        }
        val trueLen = io.github.martinzitka.trailog.core.stats.Statistics.distance(Segment.segmentsOf(clean))
        val rawLen = io.github.martinzitka.trailog.core.stats.Statistics.distance(Segment.segmentsOf(jittered))
        val filteredLen = io.github.martinzitka.trailog.core.stats.Statistics.distance(
            SpeedFilter(ActivityType.CYCLING.maxPlausibleSpeed).apply(Segment.segmentsOf(jittered)),
        )
        assertTrue(rawLen > trueLen, "sanity: jitter should inflate raw distance")
        assertTrue(filteredLen < rawLen, "filtering should reduce the inflation")
    }
}
