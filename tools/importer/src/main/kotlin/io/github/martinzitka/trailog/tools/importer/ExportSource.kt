package io.github.martinzitka.trailog.tools.importer

import java.io.File

/**
 * The scraper's output directory, read as a stream of canonical workouts.
 *
 * **The worklist is the catalogue, not the `gpx/` folder.** A workout the server refuses to export
 * has no file at all, and walking the directory would silently drop it — the run would look clean
 * while a real ride went missing. `workouts.json` knows about every workout, so it drives the
 * iteration and a missing file becomes a *reported* track-less activity rather than an absence
 * nobody notices.
 *
 * Files are read one at a time, in catalogue order, and never all held at once.
 */
class ExportSource(
    private val gpxDir: File,
    private val catalogue: Map<String, WorkoutSummary>,
    private val mapping: TypeMapping,
) {

    fun describe(): String =
        "${catalogue.size} workouts from ${gpxDir.parentFile?.path ?: gpxDir.path}, " +
            "${mapping.mappedIds.size} mapped activity ids"

    /**
     * Every workout in the catalogue, in start-time order so the archive reads chronologically.
     * Lazy: one GPX document is parsed and released at a time.
     */
    fun workouts(): Sequence<CanonicalWorkout> =
        catalogue.values
            .sortedBy { it.startTime?.toEpochMilliseconds() ?: Long.MAX_VALUE }
            .asSequence()
            .map { summary ->
                val file = File(gpxDir, "${summary.key}.gpx")
                Canonical.workout(
                    key = summary.key,
                    gpx = if (file.isFile) file.readText() else null,
                    summary = summary,
                    mapping = mapping,
                )
            }

    companion object {
        fun open(exportDir: File): ExportSource {
            require(exportDir.isDirectory) { "not a directory: ${exportDir.path}" }
            val catalogueFile = File(exportDir, "workouts.json")
            require(catalogueFile.isFile) {
                "missing workouts.json in ${exportDir.path} — it is the only record for the " +
                    "workouts whose track never existed or did not survive export"
            }
            val gpxDir = File(exportDir, "gpx")
            require(gpxDir.isDirectory) { "missing gpx/ in ${exportDir.path}" }

            val catalogue = WorkoutCatalog.read(catalogueFile)
            require(catalogue.isNotEmpty()) { "workouts.json parsed to no workouts" }
            return ExportSource(gpxDir, catalogue, TypeMapping.read(exportDir))
        }
    }
}
