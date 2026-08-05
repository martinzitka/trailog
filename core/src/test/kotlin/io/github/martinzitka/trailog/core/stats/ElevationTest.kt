package io.github.martinzitka.trailog.core.stats

import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.model.Segment
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ElevationTest {

    /** A point at a given time/segment carrying an altitude (metres, ellipsoidal). */
    private fun pt(sec: Long, seg: Int, altitude: Double?) =
        RawPoint(
            latitude = 50.0,
            longitude = 14.0,
            altitude = altitude,
            accuracy = null,
            time = Instant.fromEpochSeconds(sec),
            segmentIndex = seg,
        )

    private fun segmentsOf(altitudes: List<Double?>, seg: Int = 0): List<Segment> =
        Segment.segmentsOf(altitudes.mapIndexed { i, a -> pt(i.toLong(), seg, a) })

    @Test
    fun `flat but noisy altitude accumulates no gain`() {
        // Sub-threshold GPS wander: alternating +/-2 m around 100 m. This is exactly the
        // noise that a naive positive-delta sum turns into phantom climb; here it is nothing.
        val altitudes = List(40) { if (it % 2 == 0) 100.0 else 102.0 }
        val change = Elevation.change(segmentsOf(altitudes))
        assertEquals(0.0, change.gain, 0.0)
        assertEquals(0.0, change.loss, 0.0)
    }

    @Test
    fun `steady climb is measured close to the true rise`() {
        // 100 points climbing 1 m each: ~99 m of real climb, no descent. Pinned to a fixed
        // threshold so this tests algorithm mechanics, not the production default (which is
        // tuned to real GPS noise and validated separately by the real-fixture test).
        val altitudes = List(100) { 100.0 + it }
        val change = Elevation.change(segmentsOf(altitudes), ElevationParams(threshold = 3.0))
        // Accumulation books all but a sub-threshold remainder, and edge smoothing trims the
        // ends slightly, so expect just under the raw span of 99 m — never above it.
        assertTrue(change.gain in 90.0..99.0, "gain ${change.gain} not in 90..99")
        assertEquals(0.0, change.loss, 0.0)
    }

    @Test
    fun `a round trip back to the start counts symmetric gain and loss`() {
        val up = (100..160 step 2).map { it.toDouble() } // 100 -> 160
        val down = (158 downTo 100 step 2).map { it.toDouble() } // 158 -> 100
        // Fixed threshold: this checks up/down symmetry mechanics, not the production default.
        val change = Elevation.change(segmentsOf(up + down), ElevationParams(threshold = 3.0))
        // Edge smoothing trims both ends and rounds the peak, so expect a bit under the raw
        // 60 m span each way — but never above it, and never zero.
        assertTrue(change.gain in 48.0..60.0, "gain ${change.gain}")
        assertTrue(change.loss in 48.0..60.0, "loss ${change.loss}")
        assertTrue(kotlin.math.abs(change.gain - change.loss) < 1.0, "should be symmetric")
    }

    @Test
    fun `a big altitude jump between segments is never counted`() {
        // Segment 0 flat at 100 m; segment 1 flat at 500 m — as if pocketed in a car that
        // drove 400 m up a hill during a signal blackout. The jump must NOT become climb.
        val seg0 = List(10) { pt(it.toLong(), 0, 100.0) }
        val seg1 = List(10) { pt((100 + it).toLong(), 1, 500.0) }
        val change = Elevation.change(Segment.segmentsOf(seg0 + seg1))
        assertEquals(0.0, change.gain, 0.0)
        assertEquals(0.0, change.loss, 0.0)
    }

    @Test
    fun `points with no altitude are skipped, real climb still measured`() {
        // GPS occasionally reports no altitude; those fixes contribute nothing but must not
        // break the series or bridge across the missing values.
        val altitudes = listOf(100.0, null, 110.0, null, 120.0, 130.0, null, 140.0)
        val change = Elevation.change(segmentsOf(altitudes))
        assertTrue(change.gain > 0.0, "gain should be positive over a real climb")
        assertEquals(0.0, change.loss, 0.0)
    }

    @Test
    fun `a segment with fewer than two altitudes contributes nothing`() {
        val change = Elevation.change(segmentsOf(listOf(null, 100.0, null)))
        assertEquals(0.0, change.gain, 0.0)
        assertEquals(0.0, change.loss, 0.0)
    }

    @Test
    fun `a large threshold suppresses a modest climb entirely`() {
        val altitudes = List(20) { 100.0 + it } // ~19 m climb
        val change = Elevation.change(segmentsOf(altitudes), ElevationParams(threshold = 50.0))
        assertEquals(0.0, change.gain, 0.0)
    }

    @Test
    fun `compute populates elevation gain and loss`() {
        val altitudes = List(60) { 100.0 + it }
        val stats = Statistics.compute(
            segmentsOf(altitudes),
            io.github.martinzitka.trailog.core.model.ActivityType.HIKING,
        )
        assertTrue(stats.elevationGain > 0.0)
        assertEquals(0.0, stats.elevationLoss, 0.0)
    }
}
