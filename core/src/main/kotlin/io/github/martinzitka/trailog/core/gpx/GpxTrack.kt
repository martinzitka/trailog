package io.github.martinzitka.trailog.core.gpx

import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.model.Segment
import io.github.martinzitka.trailog.core.model.SensorSample

/**
 * One `<trk>` from a GPX file: its optional name, description and type, and its points with a
 * [RawPoint.segmentIndex] carrying the ordinal of the `<trkseg>` each point came from.
 *
 * This is the faithful boundary type between GPX and the domain — it holds exactly what the
 * file expresses, no more. Mapping a track to an [io.github.martinzitka.trailog.core.model.Activity]
 * (minting a UUID, resolving [type] to an [io.github.martinzitka.trailog.core.model.ActivityType])
 * is the caller's job, because GPX's free-text `<type>` does not map cleanly onto our enum and
 * inventing that mapping here would bury a lossy guess inside the parser.
 *
 * That principle is load-bearing, not decorative. Sports Tracker writes the user's chosen title
 * into `<desc>` and an auto-generated timestamp into `<name>`, inverting what those elements
 * conventionally mean. Correcting for that belongs in the importer, which knows it is looking at a
 * Sports Tracker file; this type simply reports what each element contained.
 *
 * @property name the track's `<name>`, or null if absent.
 * @property description the track's `<desc>`, or null if absent.
 * @property type the track's `<type>` as free text (e.g. "cycling"), or null if absent.
 * @property points every `<trkpt>` in file order, each tagged with its segment ordinal.
 * @property samples the track's sensor readings — heart rate and its kin — as their own
 *   timestamped stream. Read from Trailog's own extension when the file has one, and otherwise
 *   from the per-point `gpxtpx:` elements a foreign file carries, in which case each sample takes
 *   the timestamp of the `<trkpt>` that held it. Empty for a file with no sensor data at all.
 */
data class GpxTrack(
    val name: String?,
    val description: String?,
    val type: String?,
    val points: List<RawPoint>,
    val samples: List<SensorSample> = emptyList(),
) {
    /**
     * The track's points read as ordered, de-duplicated [Segment]s — the same view the rest of
     * the statistics code consumes, so an imported reference route can be measured immediately.
     */
    fun segments(): List<Segment> = Segment.segmentsOf(points)
}

/**
 * A whole GPX document: the `<gpx>` element's `creator` attribute, the file-level `<metadata>`
 * name and description, and every `<trk>` in the file.
 *
 * Exists because file-level metadata has nowhere to live in a [GpxTrack] and is sometimes the only
 * place the interesting data is. Sports Tracker puts the workout title in `<metadata><desc>` and
 * writes no track-level `<desc>` at all, so a reader that looked only at `<trk>` would conclude
 * the file carries no title.
 *
 * [creator] is what makes a producer-specific correction safe to apply: an importer can key its
 * quirk handling on the tool that wrote the file rather than applying it to every GPX it meets.
 *
 * @property creator the `<gpx creator="...">` attribute, or null if absent.
 * @property name `<metadata><name>`, or null if absent. Not the `<author><name>`.
 * @property description `<metadata><desc>`, or null if absent.
 * @property tracks every `<trk>` in file order.
 */
data class GpxDocument(
    val creator: String?,
    val name: String?,
    val description: String?,
    val tracks: List<GpxTrack>,
)
