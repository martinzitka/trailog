package io.github.martinzitka.trailog.tools.importer

import io.github.martinzitka.trailog.core.gpx.Gpx
import io.github.martinzitka.trailog.core.gpx.GpxDocument
import io.github.martinzitka.trailog.core.gpx.GpxTrack
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.RawPoint
import kotlinx.datetime.Instant
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

/** One workout turned into the track Trailog will store, plus what had to be decided to get there. */
data class CanonicalWorkout(
    val key: String,
    val track: GpxTrack,
    val type: ActivityType,
    val typeSource: TypeSource,
    /** The Sports Tracker `activityId` this came from, so a fallback can be reported by id. */
    val sourceActivityId: String?,
    /**
     * When the activity happened. Taken from the catalogue rather than the track, because the
     * track-less workouts have no fix to read it from and would otherwise import undated.
     */
    val startTime: Instant?,
    val pointCount: Int,
    val sampleCount: Int,
    val trackless: Boolean,
)

/**
 * Turns one exported Sports Tracker workout into a Trailog-canonical [GpxTrack].
 *
 * Everything producer-specific lives here, and nowhere else. `:core` parses both `<name>` and
 * `<desc>` faithfully and applies no correction, because a quirk fix buried in the shared parser
 * would silently corrupt files from every other tool. This module knows it is looking at Sports
 * Tracker output, so this is where knowing it is safe.
 */
object Canonical {

    /**
     * Namespace for the deterministic activity ids below. A fixed random UUID, generated once and
     * never regenerated — changing it would make every future import duplicate the whole history.
     */
    private val NAMESPACE: UUID = UUID.fromString("6f1a5c2e-9b74-4d38-9a21-0c5e7f3b8d64")

    /**
     * The activity id for a workout, derived from its Sports Tracker key rather than minted fresh.
     *
     * This is what makes the import idempotent. Trailog's own recordings use UUIDv7 so an
     * activity can be created offline with a time-ordered id, but an *imported* activity already has
     * a stable identity upstream, and deriving from it means a second run recognises every ride it
     * already wrote instead of duplicating the whole history. Re-running after fixing one type mapping
     * has to be cheap, or the mapping files cannot be iterated on.
     *
     * UUIDv5 (SHA-1, name-based) as RFC 4122 defines it.
     */
    fun activityIdFor(workoutKey: String): UUID {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(
            ByteBuffer.allocate(16)
                .putLong(NAMESPACE.mostSignificantBits)
                .putLong(NAMESPACE.leastSignificantBits)
                .array(),
        )
        val hash = digest.digest(workoutKey.toByteArray(Charsets.UTF_8))
        hash[6] = ((hash[6].toInt() and 0x0F) or 0x50).toByte()  // version 5
        hash[8] = ((hash[8].toInt() and 0x3F) or 0x80).toByte()  // RFC 4122 variant
        val buffer = ByteBuffer.wrap(hash, 0, 16)
        return UUID(buffer.long, buffer.long)
    }

    /**
     * The workout's title.
     *
     * **Sports Tracker inverts the GPX convention.** It writes the user's chosen title into
     * `<metadata><desc>` and an auto-generated timestamp into `<name>` (and into `<trk><name>`), and
     * the workout JSON agrees: `description` holds the title, `workoutName` holds the date. So the
     * date-shaped name is **discarded** rather than stored — storing a formatted timestamp as a name
     * is exactly what ADR 0014 forbids, and History already derives a title from type and date when
     * the name is blank.
     *
     * The JSON is preferred because it is unambiguous; the GPX metadata is the fallback for a
     * workout missing from the catalogue. The GPX fallback is **gated on the creator string**, so a
     * file from any other tool keeps the ordinary meaning of its elements.
     */
    fun titleOf(summary: WorkoutSummary?, document: GpxDocument?): String? {
        summary?.title?.takeIf { it.isNotBlank() }?.let { return it }
        if (document != null && isSportsTracker(document.creator)) {
            document.description?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun isSportsTracker(creator: String?): Boolean =
        creator != null && creator.contains("sports", ignoreCase = true) &&
            creator.contains("tracker", ignoreCase = true)

    /**
     * The note written onto a workout whose track did not survive — the hand-entered ones, and the
     * ones damaged upstream.
     *
     * Trailog recomputes every statistic from raw points, so an activity with no points computes as
     * zero. The source's own figures cannot go into the statistics cache: that cache is a
     * *recomputable view* over raw data by definition, and a third-party number Trailog can never
     * verify or reproduce does not belong in it. The note is the honest place — plainly attributed
     * to Sports Tracker, so nobody later mistakes it for something Trailog measured.
     *
     * The figures are quoted in the units Sports Tracker itself displayed them in. That is a
     * deliberate exception to "no formatted string is ever stored": this is a quotation of what
     * another system claimed, not a Trailog figure being rendered, and there is nothing to
     * recompute it from if a unit preference later changes.
     */
    fun summaryNote(summary: WorkoutSummary?, reason: String): String? {
        if (summary == null) return null
        val parts = buildList {
            summary.distance?.let { add("%.2f km".format(Locale.ROOT, it / 1000.0)) }
            summary.movingTime?.let { add("${(it.inWholeSeconds / 60.0).roundToLong()} min moving") }
            summary.elapsedTime?.let { add("${(it.inWholeSeconds / 60.0).roundToLong()} min elapsed") }
        }
        if (parts.isEmpty()) return "Imported from Sports Tracker. $reason"
        return "Imported from Sports Tracker, which reported ${parts.joinToString(", ")}. $reason"
    }

    /**
     * Build the canonical track for one workout.
     *
     * @param gpx the exported document, or null when the workout has no file at all (the server
     *   refused two of them outright). A file that parses to no points is the same case as no file:
     *   both mean there is no geometry, and both keep their summary in a note.
     */
    fun workout(
        key: String,
        gpx: String?,
        summary: WorkoutSummary?,
        mapping: TypeMapping,
    ): CanonicalWorkout {
        val document = gpx?.let { Gpx.readDocument(it) }
        val points = flattenSegments(document?.tracks.orEmpty())
        val samples = document?.tracks?.flatMap { it.samples }.orEmpty()
        val resolved = mapping.resolve(key, summary?.activityId)

        // One track point is not a usable track: four real 41–49 km rides exported with a single
        // point. That governs whether the summary note is written — it does **not** discard the
        // point. Raw points are never filtered on ingest (CLAUDE.md), and a lone fix is still a
        // true record of where the ride started.
        val trackless = points.size < 2

        return CanonicalWorkout(
            key = key,
            track = GpxTrack(
                name = titleOf(summary, document),
                description = if (trackless) {
                    summaryNote(summary, reasonFor(summary, gpx, points.size))
                } else {
                    null
                },
                type = resolved.type.name.lowercase(),
                points = points,
                samples = samples,
                activityId = activityIdFor(key),
            ),
            type = resolved.type,
            typeSource = resolved.source,
            sourceActivityId = summary?.activityId,
            startTime = summary?.startTime ?: points.minOfOrNull { it.time },
            pointCount = points.size,
            sampleCount = samples.size,
            trackless = trackless,
        )
    }

    /**
     * Every `<trk>` in a file as one activity's points, with segment indices renumbered so two
     * tracks cannot collide on segment 0 and be welded into one continuous line.
     *
     * Sports Tracker writes one `<trk>` per file, so this is defensive rather than load-bearing —
     * but the cost of being wrong is exactly the failure CLAUDE.md warns about, a gap silently
     * becoming a straight line that adds kilometres to the ride.
     */
    private fun flattenSegments(tracks: List<GpxTrack>): List<RawPoint> {
        if (tracks.size <= 1) return tracks.firstOrNull()?.points.orEmpty()
        val out = ArrayList<RawPoint>()
        var next = 0
        for (track in tracks) {
            val indices = track.points.map { it.segmentIndex }.distinct().sorted()
            val remap = indices.withIndex().associate { (offset, old) -> old to next + offset }
            track.points.mapTo(out) { it.copy(segmentIndex = remap.getValue(it.segmentIndex)) }
            next += indices.size
        }
        return out
    }

    /**
     * Why a workout has no track. The distinction is the whole point of reading `isManuallyAdded`:
     * a typed-in swim reporting a real distance with no geometry is *expected*, and a recorded ride
     * reporting the same thing is *data loss*. Distance cannot tell them apart, so the note says
     * which one this is rather than leaving the user to guess years from now.
     */
    private fun reasonFor(summary: WorkoutSummary?, gpx: String?, points: Int): String = when {
        summary?.manuallyAdded == true -> "Entered by hand, so it never had a track."
        gpx == null -> "Sports Tracker refused to export this workout, so its track is lost."
        points == 0 -> "The export contained no track points, so its track is lost."
        else -> "The export contained only $points track point(s), so its track is lost."
    }
}
