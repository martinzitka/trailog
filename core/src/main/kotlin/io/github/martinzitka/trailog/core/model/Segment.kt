package io.github.martinzitka.trailog.core.model

/**
 * A contiguous run of fixes with no gap in between. An activity is a *sequence of segments*,
 * not one continuous line (CLAUDE.md): a crash, a process kill, a reboot, or a signal
 * blackout ends one segment and begins the next. This maps directly onto GPX `<trkseg>`.
 *
 * Statistics are computed within a segment and summed across segments; nothing is ever
 * interpolated across the boundary between two segments — not distance, not elevation, not
 * speed, not a chart line. That boundary is where a naive tracker invents kilometres.
 *
 * @property index the segment's ordinal within the activity, as recorded on each raw point.
 * @property points the fixes in this segment, sorted by time and de-duplicated.
 */
data class Segment(
    val index: Int,
    val points: List<RawPoint>,
) {
    companion object {
        /**
         * Groups raw points into segments. Fixes are assumed to arrive out of order and
         * duplicated (CLAUDE.md), so within each segment points are sorted by time and
         * points sharing a timestamp are de-duplicated (first one wins). Segments are
         * returned in ascending index order. Empty segments are omitted.
         */
        fun segmentsOf(points: List<RawPoint>): List<Segment> =
            points
                .groupBy { it.segmentIndex }
                .toSortedMap()
                .map { (index, pts) ->
                    Segment(
                        index = index,
                        points = pts.sortedBy { it.time }.distinctBy { it.time },
                    )
                }
                .filter { it.points.isNotEmpty() }
    }
}
