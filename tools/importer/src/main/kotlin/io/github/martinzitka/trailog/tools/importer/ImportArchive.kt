package io.github.martinzitka.trailog.tools.importer

import io.github.martinzitka.trailog.core.gpx.Gpx
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What one run produced, for the report the CLI prints. */
data class ArchiveReport(
    val written: Int,
    val trackless: Int,
    val withSamples: Int,
    val totalPoints: Long,
    val totalSamples: Long,
    val byType: Map<String, Int>,
    val unmappedActivityIds: Map<String, Int>,
    val overridden: Int,
)

/**
 * Writes canonical workouts into a zip of GPX files — deliberately the **same shape the app's own
 * bulk export produces**, so the app needs one importer rather than two and an exported archive is
 * restorable by the same path a migration takes. A backup that cannot be restored is a guess
 * (CLAUDE.md), and this is what makes the Trailog archive a real backup rather than a hope.
 *
 * One workout is in memory at a time. A history runs to hundreds of megabytes of GPX; holding it
 * all would be the difference between this working and failing.
 *
 * Serialisation goes through `:core`'s [Gpx] — the single GPX writer in the project — so segments
 * become separate `<trkseg>` elements and a gap is never welded into a straight line.
 */
object ImportArchive {

    /**
     * Stream [workouts] into [sink] and return what was written. [sink] is closed on the way out.
     *
     * @param onProgress called with the number dealt with so far, for a CLI that would otherwise
     *   look hung for a minute and a half.
     */
    fun write(
        sink: OutputStream,
        workouts: Sequence<CanonicalWorkout>,
        onProgress: (done: Int) -> Unit = {},
    ): ArchiveReport {
        var written = 0
        var trackless = 0
        var withSamples = 0
        var totalPoints = 0L
        var totalSamples = 0L
        var overridden = 0
        val byType = LinkedHashMap<String, Int>()
        val unmapped = LinkedHashMap<String, Int>()
        val taken = mutableSetOf<String>()

        ZipOutputStream(BufferedOutputStream(sink)).use { zip ->
            for (workout in workouts) {
                // The document time is what carries the date for a track with no fixes in it.
                val gpx = Gpx.write(workout.track, time = workout.startTime)
                val entry = ZipEntry(
                    unique(taken, entryName(workout.track.name, workout.type.name, workout.startTime)),
                )
                workout.startTime?.let { entry.time = it.toEpochMilliseconds() }
                zip.putNextEntry(entry)
                zip.write(gpx.toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                written++
                if (workout.trackless) trackless++
                if (workout.sampleCount > 0) withSamples++
                totalPoints += workout.pointCount
                totalSamples += workout.sampleCount
                byType[workout.type.name] = (byType[workout.type.name] ?: 0) + 1
                when (workout.typeSource) {
                    TypeSource.OVERRIDE -> overridden++
                    TypeSource.FALLBACK -> {
                        val id = workout.sourceActivityId ?: "(none)"
                        unmapped[id] = (unmapped[id] ?: 0) + 1
                    }
                    TypeSource.MAPPING -> Unit
                }
                onProgress(written)
            }
        }

        return ArchiveReport(
            written = written,
            trackless = trackless,
            withSamples = withSamples,
            totalPoints = totalPoints,
            totalSamples = totalSamples,
            byType = byType.toSortedMap().toMap(),
            unmappedActivityIds = unmapped.toSortedMap().toMap(),
            overridden = overridden,
        )
    }

    /**
     * `trailog-<slug>-<yyyy-MM-dd>-<HHmm>.gpx`, mirroring the naming the app's export uses so an
     * unzipped folder from either source sorts and reads the same. UTC, because a long history
     * crosses time zones and a stamp that depends on where the import was run is not a stable name.
     */
    internal fun entryName(name: String?, typeName: String, start: Instant?): String {
        val slug = slug(name?.takeIf { it.isNotBlank() } ?: typeName)
        val stamp = start?.toLocalDateTime(TimeZone.UTC)?.let {
            "%04d-%02d-%02d-%02d%02d".format(
                Locale.ROOT, it.year, it.monthNumber, it.dayOfMonth, it.hour, it.minute,
            )
        } ?: "undated"
        return "trailog-$slug-$stamp.gpx"
    }

    /** Lowercase ASCII words joined by hyphens. Non-ASCII is dropped, not transliterated. */
    internal fun slug(raw: String): String {
        val slug = raw.lowercase(Locale.ROOT)
            .map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
            .take(60)
            .trim('-')
        return slug.ifEmpty { "activity" }
    }

    /** [candidate] if free, else the same name with `-2`, `-3`… before the extension. */
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
