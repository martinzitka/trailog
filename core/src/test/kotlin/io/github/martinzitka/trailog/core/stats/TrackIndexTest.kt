package io.github.martinzitka.trailog.core.stats

import io.github.martinzitka.trailog.core.geo.Geo
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.model.Segment
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackIndexTest {

    /**
     * A straight eastward line at 50°N, one fix per second, ~7.2 m apart — the same shape the
     * profile tests use, so distances along it are directly comparable.
     */
    private fun line(
        count: Int,
        segment: Int = 0,
        startSecond: Long = 0,
        startLon: Double = 14.0,
        startLat: Double = 50.0,
        altitudeAt: (Int) -> Double? = { null },
    ): List<RawPoint> = (0 until count).map { i ->
        RawPoint(
            latitude = startLat,
            longitude = startLon + i * 0.0001,
            altitude = altitudeAt(i),
            accuracy = null,
            time = Instant.fromEpochSeconds(startSecond + i),
            segmentIndex = segment,
        )
    }

    // ---- building --------------------------------------------------------------------------

    @Test
    fun `an empty track answers nothing rather than throwing`() {
        val index = TrackIndex.of(emptyList())

        assertTrue(index.isEmpty)
        assertEquals(0.0, index.totalDistance)
        assertNull(index.atDistance(100.0))
        assertNull(index.nearestTo(50.0, 14.0))
    }

    @Test
    fun `the total distance agrees with the distance statistic`() {
        val segments = Segment.segmentsOf(line(100))

        assertEquals(
            Statistics.distance(segments),
            TrackIndex.of(segments).totalDistance,
            1e-6,
            "the index and the summary figure must measure the same ride",
        )
    }

    @Test
    fun `every fix is indexed, across every segment`() {
        val points = line(10) + line(10, segment = 1, startSecond = 600, startLon = 14.05)

        assertEquals(20, TrackIndex.of(Segment.segmentsOf(points)).size)
    }

    // ---- lookup by distance ----------------------------------------------------------------

    @Test
    fun `a distance resolves to the fix nearest it`() {
        val segments = Segment.segmentsOf(line(100, altitudeAt = { 300.0 + it }))
        val index = TrackIndex.of(segments)

        // ~7.15 m per hop at this latitude, so ~50 m in is the seventh fix.
        val position = assertNotNull(index.atDistance(50.0))

        assertEquals(7L, position.elapsedSeconds, "the seventh fix is seven seconds in")
        assertEquals(307.0, position.altitude!!, 1e-9)
        assertTrue(
            kotlin.math.abs(position.distance - 50.0) < 4.0,
            "the nearest fix must be within half a hop of the requested distance",
        )
    }

    @Test
    fun `a distance beyond the ride clamps to its ends`() {
        val index = TrackIndex.of(Segment.segmentsOf(line(50)))

        val start = assertNotNull(index.atDistance(-100.0))
        val end = assertNotNull(index.atDistance(index.totalDistance * 10))

        assertEquals(0.0, start.distance, 1e-9)
        assertEquals(index.totalDistance, end.distance, 1e-9)
    }

    @Test
    fun `elapsed time is measured from the first fix, by fix timestamps`() {
        // The ride starts at an arbitrary wall-clock instant; elapsed is relative to it.
        val index = TrackIndex.of(Segment.segmentsOf(line(30, startSecond = 1_700_000_000)))

        assertEquals(0L, index.atDistance(0.0)!!.elapsedSeconds)
        assertEquals(29L, index.atDistance(index.totalDistance)!!.elapsedSeconds)
    }

    // ---- segment awareness -----------------------------------------------------------------

    @Test
    fun `the distance axis does not advance across a recording gap`() {
        // Segment 1 restarts ~3.5 km east, ten minutes later. That jump is not distance.
        val points = line(10) + line(10, segment = 1, startSecond = 600, startLon = 14.05)
        val segments = Segment.segmentsOf(points)
        val index = TrackIndex.of(segments)

        assertEquals(
            Statistics.distance(segments),
            index.totalDistance,
            1e-6,
            "the gap covered no ground and must not lengthen the axis",
        )
    }

    @Test
    fun `elapsed time does advance across a recording gap`() {
        // Nothing was recorded for ten minutes, but ten minutes passed. Distance and time part
        // company here, and the cursor must report both honestly.
        val points = line(10) + line(10, segment = 1, startSecond = 600, startLon = 14.05)
        val index = TrackIndex.of(Segment.segmentsOf(points))

        val last = assertNotNull(index.atDistance(index.totalDistance))
        assertEquals(1, last.segmentIndex)
        assertEquals(609L, last.elapsedSeconds)
    }

    @Test
    fun `a distance shared by both sides of a gap resolves to the earlier segment`() {
        val points = line(10) + line(10, segment = 1, startSecond = 600, startLon = 14.05)
        val index = TrackIndex.of(Segment.segmentsOf(points))

        // The end of segment 0 and the start of segment 1 sit at the same distance.
        val endOfFirst = Statistics.distance(Segment.segmentsOf(line(10)))

        val position = assertNotNull(index.atDistance(endOfFirst))
        assertEquals(
            0,
            position.segmentIndex,
            "an ambiguous distance must resolve deterministically, to the side that ends there",
        )
    }

    // ---- lookup by coordinate --------------------------------------------------------------

    @Test
    fun `a coordinate resolves to the nearest fix`() {
        val segments = Segment.segmentsOf(line(100))
        val index = TrackIndex.of(segments)

        // 20 m north of the fortieth fix: off the line, but nearest to that one.
        val target = segments[0].points[40]
        val position = assertNotNull(index.nearestTo(target.latitude + 0.00018, target.longitude))

        assertEquals(target.longitude, position.longitude, 1e-9)
        assertEquals(40L, position.elapsedSeconds)
    }

    @Test
    fun `a coordinate near a self-crossing picks the pass it is nearest to`() {
        // An out-and-back on slightly different lines: the two passes are ~11 m apart. A tap just
        // north of the outward leg must land on the outward leg, not on the return.
        val out = line(50, startLat = 50.0)
        val back = line(50, segment = 0, startSecond = 100, startLat = 50.0001)
            .reversed()
            .mapIndexed { i, point -> point.copy(time = Instant.fromEpochSeconds(100L + i)) }
        val index = TrackIndex.of(Segment.segmentsOf(out + back))

        val position = assertNotNull(index.nearestTo(50.00002, 14.0025))

        assertTrue(
            position.elapsedSeconds < 100,
            "the outward pass is nearer, so the outward pass is the answer",
        )
    }

    @Test
    fun `the reported position is a real recorded fix, never an interpolation`() {
        val segments = Segment.segmentsOf(line(60, altitudeAt = { 300.0 + it }))
        val index = TrackIndex.of(segments)
        val recorded = segments.flatMap { it.points }

        val position = assertNotNull(index.atDistance(123.4))

        assertTrue(
            recorded.any {
                Geo.haversine(
                    it.latitude,
                    it.longitude,
                    position.latitude,
                    position.longitude,
                ) < 1e-6
            },
            "a cursor must sit on a fix that was actually recorded",
        )
    }
}
