package io.github.martinzitka.trailog.core.stats

import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.model.Segment
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.DurationUnit

class SplitsTest {

    private fun pt(
        sec: Long,
        seg: Int,
        lat: Double,
        lon: Double,
        alt: Double? = null,
    ) = RawPoint(
        latitude = lat,
        longitude = lon,
        altitude = alt,
        accuracy = null,
        time = Instant.fromEpochSeconds(sec),
        segmentIndex = seg,
    )

    /** A single straight segment: n points marching east along 50N, one fix per second. */
    private fun straightLine(n: Int, seg: Int = 0, altStep: Double? = null): List<RawPoint> =
        (0 until n).map { i ->
            pt(
                sec = i.toLong(),
                seg = seg,
                lat = 50.0,
                lon = 14.0 + i * 0.0001,
                alt = altStep?.let { 100.0 + i * it },
            )
        }

    @Test
    fun `split distances sum to total and only the last is short`() {
        val segments = Segment.segmentsOf(straightLine(40))
        val total = Statistics.distance(segments)
        val splitDistance = 10.0

        val splits = Statistics.splits(segments, ActivityType.CYCLING, splitDistance)

        // Every split but the last is exactly one split-distance; interpolation at the
        // boundary is what makes them exact even though no fix lands on the boundary.
        splits.dropLast(1).forEach {
            assertEquals(splitDistance, it.distance, 1e-6)
        }
        assertTrue(
            splits.last().distance <= splitDistance + 1e-6,
            "final split ${splits.last().distance} must not exceed the split distance",
        )
        assertEquals(total, splits.sumOf { it.distance }, 1e-6)
        assertEquals(splits.indices.toList(), splits.map { it.index })
    }

    @Test
    fun `average speed is uniform across splits of a constant-speed line`() {
        // ~7.17 m/s the whole way (0.0001 deg lon per second at 50N).
        val segments = Segment.segmentsOf(straightLine(60))
        val splits = Statistics.splits(segments, ActivityType.CYCLING, splitDistance = 20.0)

        val speeds = splits.dropLast(1).map { it.averageSpeed }
        val mean = speeds.average()
        speeds.forEach {
            assertEquals(mean, it, mean * 0.02, "per-split speed should be steady")
        }
        assertTrue(mean > ActivityType.CYCLING.movingSpeedThreshold)
    }

    @Test
    fun `a split spanning a segment gap counts distance and moving time within segments only`() {
        // Two short segments whose combined distance stays inside one 1 km split, separated
        // by a 200 s recording gap and a 700 m jump east. The gap must add nothing.
        val pts = straightLine(3, seg = 0) +
            listOf(
                pt(203, 1, 50.0, 14.0100),
                pt(204, 1, 50.0, 14.0101),
                pt(205, 1, 50.0, 14.0102),
            )
        val segments = Segment.segmentsOf(pts)

        val splits = Statistics.splits(segments, ActivityType.CYCLING, splitDistance = 1000.0)

        assertEquals(1, splits.size, "everything fits in one split")
        val only = splits.single()
        assertEquals(Statistics.distance(segments), only.distance, 1e-6)
        assertEquals(
            Statistics.movingTime(segments, ActivityType.CYCLING).inWholeSeconds,
            only.movingTime.inWholeSeconds,
            "the 200 s dead gap is never moving time",
        )
        // The 700 m cross-segment jump must not appear anywhere.
        assertTrue(only.distance < 100.0, "distance ${only.distance} wrongly includes the jump")
    }

    @Test
    fun `per-split elevation gain and loss sum to the activity total`() {
        // A climb then a descent, steep enough to clear the deadband, spread over enough
        // points to survive smoothing.
        val up = (0 until 40).map { i ->
            pt(i.toLong(), 0, 50.0, 14.0 + i * 0.0001, alt = 100.0 + i * 2.0)
        }
        val down = (40 until 80).map { i ->
            pt(i.toLong(), 0, 50.0, 14.0 + i * 0.0001, alt = 100.0 + (80 - i) * 2.0)
        }
        val segments = Segment.segmentsOf(up + down)

        val total = Elevation.change(segments)
        val splits = Statistics.splits(segments, ActivityType.CYCLING, splitDistance = 30.0)

        assertEquals(total.gain, splits.sumOf { it.elevationGain }, 1e-6)
        assertEquals(total.loss, splits.sumOf { it.elevationLoss }, 1e-6)
        assertTrue(total.gain > 0.0 && total.loss > 0.0, "fixture should have both")
    }

    @Test
    fun `split distance is a free parameter — five hundred metre laps differ from a kilometre`() {
        val segments = Segment.segmentsOf(straightLine(300))
        val km = Statistics.splits(segments, ActivityType.CYCLING, splitDistance = 1000.0)
        val half = Statistics.splits(segments, ActivityType.CYCLING, splitDistance = 500.0)

        assertTrue(half.size >= km.size * 2 - 1, "500 m laps should roughly double the count")
        // Both partitions describe the same track.
        assertEquals(km.sumOf { it.distance }, half.sumOf { it.distance }, 1e-6)
    }

    @Test
    fun `an activity with no distance yields no splits`() {
        val stationary = listOf(pt(0, 0, 50.0, 14.0), pt(1, 0, 50.0, 14.0))
        val splits = Statistics.splits(Segment.segmentsOf(stationary), ActivityType.WALKING)
        assertTrue(splits.isEmpty())
    }

    @Test
    fun `moving time across full splits reconciles with total moving time`() {
        val segments = Segment.segmentsOf(straightLine(50))
        val splits = Statistics.splits(segments, ActivityType.CYCLING, splitDistance = 15.0)

        val summed = splits.sumOf { it.movingTime.toDouble(DurationUnit.SECONDS) }
        val total = Statistics.movingTime(segments, ActivityType.CYCLING)
            .toDouble(DurationUnit.SECONDS)
        assertEquals(total, summed, 1e-6)
    }

    @Test
    fun `non-positive split distance is rejected`() {
        val segments = Segment.segmentsOf(straightLine(3))
        assertFailsWith<IllegalArgumentException> {
            Statistics.splits(segments, ActivityType.CYCLING, splitDistance = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            Statistics.splits(segments, ActivityType.CYCLING, splitDistance = -100.0)
        }
    }
}
