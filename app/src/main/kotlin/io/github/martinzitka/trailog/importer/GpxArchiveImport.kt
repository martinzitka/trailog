package io.github.martinzitka.trailog.importer

import io.github.martinzitka.trailog.core.gpx.Gpx
import io.github.martinzitka.trailog.core.gpx.GpxDocument
import io.github.martinzitka.trailog.core.gpx.GpxTrack
import io.github.martinzitka.trailog.core.id.Uuid7
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.RawPoint
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Reads a zip of GPX files into the database — the mirror of
 * [io.github.martinzitka.trailog.export.GpxArchive], and deliberately the same format.
 *
 * That symmetry is the feature. The archive the bulk export produces is exactly what this reads, so
 * **restoring a backup and migrating a history from another app are one operation**, with one code
 * path to build and get right. CLAUDE.md's deployment guidance says a backup that has never been
 * restored is a guess; this is what makes the export a real backup.
 *
 * **One activity is in memory at a time.** An archive can hold years of riding and hundreds of
 * megabytes of GPX; parsing it whole would fail on a phone. Each entry is read, written and released
 * before the next is opened.
 *
 * **Idempotent by activity id.** A file carrying `<trailog:activityId>` is the activity it names, so
 * a second run over the same archive skips what it already holds rather than duplicating it. Nothing
 * already stored is ever modified or overwritten — an import only ever adds. That matters because
 * the alternative, updating in place, would silently discard edits the user made after the first
 * run.
 *
 * Parsing goes through `:core`'s [Gpx], the single GPX reader in the project, so an imported track
 * is segmented exactly as a recorded one is and a gap never becomes a straight line.
 *
 * Nothing here touches the network and nothing is logged — an import is a file the user chose.
 */
object GpxArchiveImport {

    /**
     * Read every entry in [source] and write the activities it does not already hold.
     *
     * @param exists true when an activity with this id is already stored. Checked before the write
     *   so a re-run costs one query per entry rather than a failed insert.
     * @param write persists one activity with its points and samples. Expected to be atomic; see
     *   [io.github.martinzitka.trailog.data.ActivityRepository.importActivity].
     * @param onProgress called after each entry, with the number dealt with so far.
     *
     * **Progress is a count, not a fraction.** A zip read as a stream cannot say how many entries it
     * holds without decompressing to the end first, and doing that to draw a percentage would double
     * the work on a very large archive. The screen shows a running count instead of a bar that lies.
     *
     * [source] is closed on the way out.
     */
    suspend fun read(
        source: InputStream,
        exists: suspend (UUID) -> Boolean,
        write: suspend (ImportedActivity) -> Unit,
        onProgress: (done: Int) -> Unit = {},
    ): ImportReport {
        var report = ImportReport()

        ZipInputStream(BufferedInputStream(source)).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !entry.name.endsWith(".gpx", ignoreCase = true)) {
                    zip.closeEntry()
                    continue
                }

                // One unreadable entry must not cost the rest of the archive. A file that is not
                // GPX, or is truncated, is counted and stepped over.
                val activity = runCatching {
                    activityOf(Gpx.readDocument(zip.readBytes().toString(Charsets.UTF_8)))
                }.getOrNull()

                report = when {
                    activity == null -> report.copy(failed = report.failed + 1)
                    exists(activity.id) -> report.copy(skipped = report.skipped + 1)
                    else -> {
                        write(activity)
                        report.copy(imported = report.imported + 1)
                    }
                }
                zip.closeEntry()
                onProgress(report.total)
            }
        }
        return report
    }

    /**
     * One parsed document as an activity to store, or null when the file says nothing usable.
     *
     * A document with no `<trk>` at all is rejected: there is no activity in it. A track with no
     * *points* is kept, because that is a real case — a workout entered by hand, or one whose
     * geometry was lost before Trailog ever saw it — provided the file says when it happened.
     */
    internal fun activityOf(document: GpxDocument): ImportedActivity? {
        val track = document.tracks.firstOrNull() ?: return null
        val points = flatten(document.tracks)
        val start = points.minOfOrNull { it.time } ?: document.time ?: return null

        return ImportedActivity(
            // A file with no identity of its own gets one now, minted from its own start time so
            // the id stays time-ordered like every other activity's (UUIDv7).
            id = track.activityId ?: Uuid7.generate(start.toEpochMilliseconds()),
            type = typeOf(track.type),
            name = track.name?.trim().orEmpty(),
            notes = track.description?.trim()?.takeIf { it.isNotEmpty() },
            startTime = start,
            points = points,
            samples = document.tracks.flatMap { it.samples },
        )
    }

    /**
     * Every `<trk>` in the file as one activity's points, with segment indices renumbered so two
     * tracks cannot collide on segment 0 and be silently welded into one continuous line.
     *
     * Trailog writes one `<trk>` per file, so this only matters for an archive from somewhere else —
     * but the cost of getting it wrong is the failure CLAUDE.md names: a gap becoming a straight
     * line that adds distance the ride never covered.
     */
    private fun flatten(tracks: List<GpxTrack>): List<RawPoint> {
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
     * GPX's free-text `<type>` resolved to an [ActivityType], falling back to
     * [ActivityType.OTHER].
     *
     * Deliberately tolerant and deliberately *not* in `:core`: GPX's type is free text that every
     * producer writes differently, so this is a guess, and a guess does not belong in the shared
     * parser. Falling back to OTHER rather than refusing the file is the right trade — the type is
     * editable metadata the user can correct in one tap, while a rejected file is a lost ride.
     */
    internal fun typeOf(raw: String?): ActivityType {
        val text = raw?.trim()?.lowercase()?.replace(' ', '_')?.replace('-', '_')
        if (text.isNullOrEmpty()) return ActivityType.OTHER
        return ActivityType.entries.firstOrNull { it.name.lowercase() == text } ?: ActivityType.OTHER
    }
}
