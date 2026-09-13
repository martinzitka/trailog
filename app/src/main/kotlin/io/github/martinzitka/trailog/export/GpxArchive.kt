package io.github.martinzitka.trailog.export

import io.github.martinzitka.trailog.core.gpx.Gpx
import io.github.martinzitka.trailog.core.gpx.GpxTrack
import io.github.martinzitka.trailog.core.model.Activity
import io.github.martinzitka.trailog.data.ActivityRef
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the whole activity history into one zip of GPX files — the "all activities" half of M1.7
 * export.
 *
 * **One ride is in memory at a time.** The worklist is [ActivityRef]s, and each ride's raw points
 * are loaded, serialised and streamed into the archive before the next one is opened. A history of
 * several hundred rides is therefore bounded by the largest single ride rather than by their sum,
 * which is the difference between this working on a phone and failing on one.
 *
 * **Serialisation goes through `:core`'s [Gpx]** — the single GPX writer in the project — so each
 * file carries its segments as separate `<trkseg>` elements and a recording gap is never welded
 * into a straight line (CLAUDE.md). The output is exactly what the per-activity export on Activity
 * detail produces, multiplied; there is no second code path that could drift from it.
 *
 * Nothing here touches Android or the network. An export is a query, a string and a stream the
 * user chose, so it works offline and without an account (CLAUDE.md, data portability). No
 * coordinates are logged — nothing is logged at all.
 */
object GpxArchive {

    /**
     * Stream every activity in [refs] into [sink] as a zip of GPX files, and return how many were
     * written.
     *
     * @param load reads one activity with its raw points; null when the row has vanished since
     *   [refs] was taken, which is skipped rather than treated as a failure — a ride deleted
     *   mid-export is deleted on purpose.
     * @param name builds an entry's file name from a slug and the ride's start time. Passed in
     *   because file naming lives at the display edge with the rest of the formatting, not here.
     * @param onProgress called after each activity is dealt with, with the number processed so
     *   far — including skipped ones, so it counts down the worklist rather than the output.
     *
     * [sink] is closed on the way out, whether or not the write succeeded.
     */
    suspend fun write(
        sink: OutputStream,
        refs: List<ActivityRef>,
        load: suspend (String) -> Activity?,
        name: (slug: String, startTime: Long) -> String,
        onProgress: (done: Int) -> Unit = {},
    ): Int {
        var written = 0
        // Names are taken by the ride's own name and start time, so two rides recorded in the same
        // minute with the same name would otherwise silently overwrite each other in the archive.
        val taken = mutableSetOf<String>()

        ZipOutputStream(BufferedOutputStream(sink)).use { zip ->
            refs.forEachIndexed { index, ref ->
                currentCoroutineContext().ensureActive()
                val activity = load(ref.id)
                if (activity != null) {
                    val gpx = Gpx.write(
                        GpxTrack(
                            name = activity.name.ifBlank { null },
                            // The domain Activity carries no notes — the column exists in the
                            // database and Activity detail edits it, but loadActivity does not
                            // read it, so the exporter has nothing to write here. Notes are
                            // therefore absent from exported GPX; see the note in the plan.
                            description = null,
                            type = activity.type.name.lowercase(),
                            points = activity.points,
                            samples = activity.samples,
                        ),
                    )
                    val entry = ZipEntry(
                        unique(taken, name(activity.name.ifBlank { activity.type.name }, ref.startTime)),
                    )
                    // The file's timestamp is the ride's, not the export's: an unzipped folder
                    // then sorts by when the rides happened.
                    entry.time = ref.startTime
                    zip.putNextEntry(entry)
                    zip.write(gpx.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    written++
                }
                onProgress(index + 1)
            }
        }
        return written
    }

    /** [candidate] if it is free, otherwise the same name with `-2`, `-3`… before the extension. */
    private fun unique(taken: MutableSet<String>, candidate: String): String {
        if (taken.add(candidate)) return candidate
        val dot = candidate.lastIndexOf('.')
        val stem = if (dot > 0) candidate.substring(0, dot) else candidate
        val suffix = if (dot > 0) candidate.substring(dot) else ""
        var n = 2
        while (!taken.add("$stem-$n$suffix")) n++
        return "$stem-$n$suffix"
    }
}
