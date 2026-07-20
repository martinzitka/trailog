package io.github.martinzitka.trailog.core.model

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SegmentTest {

    private fun pt(sec: Long, seg: Int, lat: Double = 50.0, lon: Double = 14.0) =
        RawPoint(
            latitude = lat,
            longitude = lon,
            altitude = null,
            accuracy = null,
            time = Instant.fromEpochSeconds(sec),
            segmentIndex = seg,
        )

    @Test
    fun `groups points by segment index in ascending order`() {
        val segments = Segment.segmentsOf(
            listOf(pt(0, 1), pt(1, 1), pt(2, 0), pt(3, 0)),
        )
        assertEquals(listOf(0, 1), segments.map { it.index })
        assertEquals(2, segments[0].points.size)
        assertEquals(2, segments[1].points.size)
    }

    @Test
    fun `sorts out-of-order points within a segment by time`() {
        val segments = Segment.segmentsOf(listOf(pt(3, 0), pt(1, 0), pt(2, 0)))
        assertEquals(
            listOf(1L, 2L, 3L),
            segments.single().points.map { it.time.epochSeconds },
        )
    }

    @Test
    fun `de-duplicates points sharing a timestamp within a segment`() {
        val segments = Segment.segmentsOf(listOf(pt(1, 0), pt(1, 0), pt(2, 0)))
        assertEquals(2, segments.single().points.size)
    }

    @Test
    fun `identical timestamps in different segments are kept`() {
        val segments = Segment.segmentsOf(listOf(pt(1, 0), pt(1, 1)))
        assertEquals(2, segments.size)
    }
}
