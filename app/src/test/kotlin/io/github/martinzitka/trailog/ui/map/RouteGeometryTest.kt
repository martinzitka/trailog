package io.github.martinzitka.trailog.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Point

/**
 * Unit tests for [routeGeometry] — the single point where "a recording gap stays a gap" is
 * decided.
 *
 * These exist because the device cannot prove it. A screenshot cannot distinguish a correctly
 * drawn 20 m break from a bridged one, and of six real rides on the test phone the largest gap
 * is 299 m on a 945 m track whose GPS is so noisy that within-segment spikes look exactly like
 * bridging. Rendering one `LineString` per segment is what makes the guarantee structural, so
 * that is what is asserted here (CLAUDE.md: never interpolate across a segment boundary).
 */
class RouteGeometryTest {

    private fun pt(lat: Double, lon: Double) = TracePoint(lat, lon)

    @Test fun `each segment becomes its own line string`() {
        val geometry = routeGeometry(
            listOf(
                listOf(pt(50.0, 14.0), pt(50.1, 14.1)),
                listOf(pt(50.5, 14.5), pt(50.6, 14.6)),
                listOf(pt(51.0, 15.0), pt(51.1, 15.1)),
            ),
        )

        assertEquals(3, geometry.lineStrings().size)
    }

    @Test fun `no line string spans a gap between two segments`() {
        // The failure this guards against: flattening segments into one line, so the end of one
        // and the start of the next are joined by a straight line that never happened — adding
        // kilometres of phantom distance across a signal blackout or a process kill.
        val beforeGap = pt(50.0, 14.0)
        val afterGap = pt(50.9, 14.9)

        val geometry = routeGeometry(
            listOf(
                listOf(beforeGap, pt(50.1, 14.1)),
                listOf(afterGap, pt(51.0, 15.0)),
            ),
        )

        for (line in geometry.lineStrings()) {
            val coords = line.coordinates()
            val joinsAcrossGap = coords.zipWithNext().any { (a, b) ->
                (a.latitude() == 50.1 && b.latitude() == afterGap.latitude) ||
                    (a.latitude() == afterGap.latitude && b.latitude() == 50.1)
            }
            assertTrue("a line string joined the two sides of the gap", !joinsAcrossGap)
        }
    }

    @Test fun `coordinates keep longitude-latitude order`() {
        // GeoJSON is lng,lat while the domain model is lat,lon. Getting this backwards puts a
        // Czech ride in the Indian Ocean, and it is the classic mistake at this boundary.
        val geometry = routeGeometry(listOf(listOf(pt(50.08, 14.44), pt(50.09, 14.45))))

        val first = geometry.lineStrings().single().coordinates().first()
        assertEquals(14.44, first.longitude(), 1e-9)
        assertEquals(50.08, first.latitude(), 1e-9)
    }

    @Test fun `a segment with a single point is dropped rather than joined onward`() {
        // A one-point segment has no line to draw. Keeping it would give MapLibre a degenerate
        // line string; worse, folding its point into a neighbour would bridge the very gap the
        // segment boundary exists to record.
        val geometry = routeGeometry(
            listOf(
                listOf(pt(50.0, 14.0), pt(50.1, 14.1)),
                listOf(pt(50.5, 14.5)),
                listOf(pt(51.0, 15.0), pt(51.1, 15.1)),
            ),
        )

        assertEquals(2, geometry.lineStrings().size)
        assertTrue(geometry.lineStrings().all { it.coordinates().size == 2 })
    }

    @Test fun `an empty track produces empty geometry rather than throwing`() {
        // Reached while recording, before the first fix arrives.
        assertEquals(0, routeGeometry(emptyList()).lineStrings().size)
        assertEquals(0, routeGeometry(listOf(emptyList())).lineStrings().size)
    }

    @Test fun `point order within a segment is preserved`() {
        val segment = (0..4).map { pt(50.0 + it * 0.01, 14.0 + it * 0.01) }

        val coords = routeGeometry(listOf(segment)).lineStrings().single().coordinates()

        assertEquals(segment.map { it.latitude }, coords.map { it.latitude() })
    }

    // ---- the live position marker --------------------------------------------------------------

    @Test fun `the live position marker sits on the newest fix`() {
        // Which fix is "newest" is the whole question on an out-and-back: the return leg is drawn
        // on top of the outbound one, so the line cannot say where the rider is and the marker is
        // the only thing that can.
        val geometry = endMarkerGeometry(
            listOf(
                listOf(pt(50.0, 14.0), pt(50.1, 14.1)),
                listOf(pt(50.5, 14.5), pt(50.6, 14.6)),
            ),
            show = true,
        )

        val point = geometry.features()!!.single().geometry() as Point
        assertEquals(14.6, point.longitude(), 1e-9)
        assertEquals(50.6, point.latitude(), 1e-9)
    }

    @Test fun `a trailing empty segment does not lose the marker`() {
        // A resumed recording opens its new segment before its first fix arrives. The marker must
        // stay on the last fix that exists rather than disappearing for a few seconds.
        val geometry = endMarkerGeometry(
            listOf(listOf(pt(50.0, 14.0), pt(50.1, 14.1)), emptyList()),
            show = true,
        )

        val point = geometry.features()!!.single().geometry() as Point
        assertEquals(50.1, point.latitude(), 1e-9)
    }

    @Test fun `no marker is emitted when there is nothing to mark or nothing is asked for`() {
        // Empty rather than absent: this is what removes the marker when a recording finishes,
        // instead of stranding it at the final fix of a ride that is over.
        assertEquals(0, endMarkerGeometry(emptyList(), show = true).features()!!.size)
        assertEquals(0, endMarkerGeometry(listOf(emptyList()), show = true).features()!!.size)
        assertEquals(
            0,
            endMarkerGeometry(listOf(listOf(pt(50.0, 14.0))), show = false).features()!!.size,
        )
    }
}
