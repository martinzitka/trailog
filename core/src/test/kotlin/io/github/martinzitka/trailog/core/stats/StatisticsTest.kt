package io.github.martinzitka.trailog.core.stats

import io.github.martinzitka.trailog.core.geo.Geo
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.model.Segment
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatisticsTest {

    private fun pt(sec: Long, seg: Int, lat: Double, lon: Double) =
        RawPoint(
            latitude = lat,
            longitude = lon,
            altitude = null,
            accuracy = null,
            time = Instant.fromEpochSeconds(sec),
            segmentIndex = seg,
        )

    @Test
    fun `distance sums haversine hops along a single segment`() {
        // Three points ~0.0001 deg of longitude apart at 50N (~7.2 m each).
        val pts = listOf(
            pt(0, 0, 50.0, 14.0000),
            pt(1, 0, 50.0, 14.0001),
            pt(2, 0, 50.0, 14.0002),
        )
        val expected = Geo.haversine(50.0, 14.0000, 50.0, 14.0001) +
            Geo.haversine(50.0, 14.0001, 50.0, 14.0002)
        assertEquals(expected, Statistics.distance(Segment.segmentsOf(pts)), 1e-6)
    }

    @Test
    fun `distance never bridges the gap between two segments`() {
        // Segment 0 near lon 14.000, segment 1 jumped ~700 m east to lon 14.010, three
        // minutes later. The straight-line jump between segments must NOT be counted.
        val pts = listOf(
            pt(0, 0, 50.0, 14.0000),
            pt(1, 0, 50.0, 14.0001),
            pt(2, 0, 50.0, 14.0002),
            pt(182, 1, 50.0, 14.0100),
            pt(183, 1, 50.0, 14.0101),
        )
        val segments = Segment.segmentsOf(pts)

        val withinSegments =
            Geo.haversine(50.0, 14.0000, 50.0, 14.0001) +
                Geo.haversine(50.0, 14.0001, 50.0, 14.0002) +
                Geo.haversine(50.0, 14.0100, 50.0, 14.0101)
        val jump = Geo.haversine(50.0, 14.0002, 50.0, 14.0100) // ~700 m, must be excluded

        val distance = Statistics.distance(segments)
        assertEquals(withinSegments, distance, 1e-6)
        assertTrue(
            distance < jump / 10,
            "distance $distance must be nowhere near the ${jump}m cross-segment jump",
        )
    }

    @Test
    fun `elapsed time spans the whole activity including the gap`() {
        val pts = listOf(
            pt(0, 0, 50.0, 14.0000),
            pt(1, 0, 50.0, 14.0001),
            pt(182, 1, 50.0, 14.0100),
            pt(183, 1, 50.0, 14.0101),
        )
        // First fix at t=0, last at t=183 -> elapsed is the full 183 s, gap included.
        assertEquals(183.0, Statistics.elapsedTime(Segment.segmentsOf(pts)).inWholeSeconds.toDouble(), 0.0)
    }

    @Test
    fun `moving time excludes the cross-segment gap`() {
        // Two segments of steady ~7 m/s motion, separated by a 180 s recording gap.
        val pts = listOf(
            pt(0, 0, 50.0, 14.0000),
            pt(1, 0, 50.0, 14.0001),
            pt(2, 0, 50.0, 14.0002),
            pt(182, 1, 50.0, 14.0100),
            pt(183, 1, 50.0, 14.0101),
        )
        val moving = Statistics.movingTime(Segment.segmentsOf(pts), ActivityType.CYCLING)
        // 2 moving steps in seg 0 + 1 in seg 1 = 3 s. The 180 s gap is NOT counted.
        assertEquals(3, moving.inWholeSeconds)
    }

    @Test
    fun `moving time excludes a stationary stretch within a segment`() {
        // Point 1->2 does not move (same coords): below threshold, not moving time.
        val pts = listOf(
            pt(0, 0, 50.0, 14.0000),
            pt(1, 0, 50.0, 14.0001), // moved ~7 m in 1 s -> moving
            pt(6, 0, 50.0, 14.0001), // stayed put for 5 s -> not moving
            pt(7, 0, 50.0, 14.0002), // moved again -> moving
        )
        val moving = Statistics.movingTime(Segment.segmentsOf(pts), ActivityType.CYCLING)
        assertEquals(2, moving.inWholeSeconds) // 1 s + 1 s; the 5 s pause excluded
    }

    @Test
    fun `single point activity has zero distance and time`() {
        val stats = Statistics.compute(
            Segment.segmentsOf(listOf(pt(0, 0, 50.0, 14.0))),
            ActivityType.WALKING,
        )
        assertEquals(0.0, stats.distance, 0.0)
        assertEquals(0L, stats.elapsedTime.inWholeSeconds)
        assertEquals(0.0, stats.averageSpeed, 0.0)
        assertEquals(1, stats.pointCount)
    }
}
