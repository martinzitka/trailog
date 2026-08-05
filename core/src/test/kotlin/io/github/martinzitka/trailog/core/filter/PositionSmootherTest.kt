package io.github.martinzitka.trailog.core.filter

import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.model.Segment
import io.github.martinzitka.trailog.core.stats.Statistics
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PositionSmootherTest {

    private fun pt(sec: Long, seg: Int, lat: Double, lon: Double, alt: Double? = null) =
        RawPoint(
            latitude = lat,
            longitude = lon,
            altitude = alt,
            accuracy = 3.0,
            time = Instant.fromEpochSeconds(sec),
            segmentIndex = seg,
        )

    @Test
    fun `smoothing reduces distance inflated by perpendicular jitter`() {
        // A straight eastward line at ~7 m/s, with every other fix knocked ~11 m north/south.
        // The zig-zag inflates raw distance; smoothing should pull it back toward the true line.
        val clean = (0..40).map { pt(it.toLong(), 0, 50.0, 14.0 + it * 0.0001) }
        val jittered = clean.mapIndexed { i, p ->
            if (i % 2 == 1) p.copy(latitude = 50.0 + 0.0001) else p
        }
        val trueLen = Statistics.distance(Segment.segmentsOf(clean))
        val rawLen = Statistics.distance(Segment.segmentsOf(jittered))
        val smoothLen = Statistics.distance(
            PositionSmoother(window = 5).apply(Segment.segmentsOf(jittered)),
        )
        assertTrue(rawLen > trueLen, "sanity: jitter inflates raw distance ($rawLen vs $trueLen)")
        assertTrue(smoothLen < rawLen, "smoothing should reduce inflation ($smoothLen vs $rawLen)")
        assertTrue(smoothLen >= trueLen * 0.98, "should not collapse below the true line")
    }

    @Test
    fun `window of 1 is an identity`() {
        val segs = Segment.segmentsOf((0..10).map { pt(it.toLong(), 0, 50.0 + it * 0.0001, 14.0) })
        assertEquals(segs, PositionSmoother(window = 1).apply(segs))
    }

    @Test
    fun `smoothing never pulls a point across a segment boundary`() {
        // Segment 0 sits at lat 50.00, segment 1 at lat 50.10. If smoothing bridged the gap,
        // the boundary points would drift toward each other. They must not.
        val seg0 = (0..9).map { pt(it.toLong(), 0, 50.00, 14.0 + it * 0.0001) }
        val seg1 = (0..9).map { pt((100 + it).toLong(), 1, 50.10, 14.02 + it * 0.0001) }
        val out = PositionSmoother(window = 9).apply(Segment.segmentsOf(seg0 + seg1))
        // Tolerance, not exact equality: 50.10 is not representable, so averaging copies lands a
        // few ULPs off. Cross-boundary bridging would instead move a point ~0.05°, not ~1e-12.
        assertTrue(out[0].points.all { kotlin.math.abs(it.latitude - 50.00) < 1e-6 }, "segment 0 drifted")
        assertTrue(out[1].points.all { kotlin.math.abs(it.latitude - 50.10) < 1e-6 }, "segment 1 drifted")
    }

    @Test
    fun `smoothing preserves altitude time accuracy and segment index`() {
        val pts = (0..9).map { pt(it.toLong(), 0, 50.0 + it * 0.0002, 14.0, alt = 100.0 + it) }
        val out = PositionSmoother(window = 5).apply(Segment.segmentsOf(pts)).single().points
        assertEquals(pts.map { it.time }, out.map { it.time })
        assertEquals(pts.map { it.altitude }, out.map { it.altitude })
        assertEquals(pts.map { it.accuracy }, out.map { it.accuracy })
        assertEquals(pts.map { it.segmentIndex }, out.map { it.segmentIndex })
    }

    @Test
    fun `a segment too short to smooth is passed through unchanged`() {
        val segs = Segment.segmentsOf(listOf(pt(0, 0, 50.0, 14.0), pt(1, 0, 50.0, 14.0001)))
        assertEquals(segs, PositionSmoother(window = 5).apply(segs))
    }

    @Test
    fun `window must be positive`() {
        assertFailsWith<IllegalArgumentException> { PositionSmoother(window = 0) }
    }

    @Test
    fun `raw input points are not mutated`() {
        val original = pt(0, 0, 50.0, 14.0)
        val pts = listOf(original, pt(1, 0, 50.001, 14.001), pt(2, 0, 50.002, 14.0))
        PositionSmoother(window = 3).apply(Segment.segmentsOf(pts))
        assertEquals(50.0, original.latitude) // the input RawPoint is untouched
    }
}
