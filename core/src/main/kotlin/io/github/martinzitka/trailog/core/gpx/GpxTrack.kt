package io.github.martinzitka.trailog.core.gpx

import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.model.Segment

/**
 * One `<trk>` from a GPX file: its optional name and type, and its points with a
 * [RawPoint.segmentIndex] carrying the ordinal of the `<trkseg>` each point came from.
 *
 * This is the faithful boundary type between GPX and the domain — it holds exactly what the
 * file expresses, no more. Mapping a track to an [io.github.martinzitka.trailog.core.model.Activity]
 * (minting a UUID, resolving [type] to an [io.github.martinzitka.trailog.core.model.ActivityType])
 * is the caller's job, because GPX's free-text `<type>` does not map cleanly onto our enum and
 * inventing that mapping here would bury a lossy guess inside the parser.
 *
 * @property name the track's `<name>`, or null if absent.
 * @property type the track's `<type>` as free text (e.g. "cycling"), or null if absent.
 * @property points every `<trkpt>` in file order, each tagged with its segment ordinal.
 */
data class GpxTrack(
    val name: String?,
    val type: String?,
    val points: List<RawPoint>,
) {
    /**
     * The track's points read as ordered, de-duplicated [Segment]s — the same view the rest of
     * the statistics code consumes, so an imported reference route can be measured immediately.
     */
    fun segments(): List<Segment> = Segment.segmentsOf(points)
}
