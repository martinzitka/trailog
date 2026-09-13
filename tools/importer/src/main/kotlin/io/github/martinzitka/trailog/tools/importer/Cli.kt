package io.github.martinzitka.trailog.tools.importer

import java.io.File
import java.io.OutputStream

/**
 * The Sports Tracker migration's second half: turns the scraper's output into a Trailog-canonical
 * archive the app can import.
 *
 * ```
 * build  --export <dir> --out <zip>   write the archive
 * report --export <dir>               same work, no output file — what *would* be written
 * ```
 *
 * `report` exists because the type mapping is hand-written and a run can be thousands of workouts
 * long. It
 * answers "did every id resolve, and how many rides lost their track" before anything is produced,
 * which is far cheaper than reading a zip afterwards.
 *
 * Nothing here reaches the network. The scraper already did that (M2.1); this reads local files and
 * writes one local file. No coordinate is ever printed — the report deals in counts.
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull()
    try {
        when (command) {
            "build" -> build(args)
            "report" -> report(args)
            "--help", "-h", null -> println(USAGE)
            else -> {
                System.err.println("unknown command: $command")
                println(USAGE)
                kotlin.system.exitProcess(2)
            }
        }
    } catch (e: IllegalArgumentException) {
        // Thrown by the mapping reader for a typo in a hand-written file, among others. The
        // message names the file and line; a stack trace would only bury it.
        System.err.println("error: ${e.message}")
        kotlin.system.exitProcess(1)
    }
}

private fun build(args: Array<String>) {
    val exportDir = File(args.flagOrFail("--export"))
    val out = File(args.flagOrFail("--out"))
    val source = ExportSource.open(exportDir)

    println("Reading ${source.describe()}")
    out.parentFile?.mkdirs()

    var lastTick = 0
    val report = out.outputStream().use { sink ->
        ImportArchive.write(sink, source.workouts()) { done ->
            if (done - lastTick >= 100) {
                lastTick = done
                println("  $done…")
            }
        }
    }

    println()
    println("Wrote ${report.written} activities to ${out.path} (${out.length() / 1024} KiB)")
    printReport(report)
}

private fun report(args: Array<String>) {
    val source = ExportSource.open(File(args.flagOrFail("--export")))
    println("Reading ${source.describe()}")
    // Same pipeline, discarded output: a dry run that disagreed with the real one would be worse
    // than no dry run at all.
    val report = ImportArchive.write(OutputStream.nullOutputStream(), source.workouts())
    println()
    println("Would write ${report.written} activities")
    printReport(report)
}

private fun printReport(report: ArchiveReport) {
    println("  track points     ${report.totalPoints}")
    println("  sensor samples   ${report.totalSamples} across ${report.withSamples} activities")
    println("  no usable track  ${report.trackless} (summary kept in the activity's notes)")
    println("  type overrides   ${report.overridden}")
    println()
    println("  By activity type:")
    for ((type, count) in report.byType.entries.sortedByDescending { it.value }) {
        println("    %-22s %d".format(type, count))
    }
    if (report.unmappedActivityIds.isNotEmpty()) {
        println()
        println("  UNMAPPED Sports Tracker ids — these became OTHER:")
        for ((id, count) in report.unmappedActivityIds.entries.sortedByDescending { it.value }) {
            println("    id %-8s %d workout(s)".format(id, count))
        }
        println("  Add them to activity-types.tsv and re-run; ids are stable, so a re-import")
        println("  updates those activities rather than duplicating them.")
    }
}

private fun Array<String>.flagOrFail(name: String): String {
    val index = indexOf(name)
    require(index >= 0 && index + 1 < size) { "missing $name\n\n$USAGE" }
    return this[index + 1]
}

private val USAGE = """
    Trailog import tool — Sports Tracker export -> Trailog archive

      build  --export <dir> --out <archive.zip>
      report --export <dir>

    <dir> is the scraper's output directory, holding:
      gpx/<workoutKey>.gpx    the exported tracks
      workouts.json           the workout list (the only record for track-less workouts)
      activity-types.tsv      this account's activityId -> Trailog type mapping
      type-overrides.tsv      per-workout corrections (optional)

    The archive is a zip of GPX, the same shape the app's own export writes, so importing it
    and restoring a backup are the same operation. Activity ids are derived from the Sports
    Tracker key, so re-running updates rather than duplicates.
""".trimIndent()
