package io.github.martinzitka.trailog.tools.sportstracker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One workout as the list endpoint described it — reduced to the only field this tool needs
 * ([key], which addresses the export endpoints) plus a handful of raw values for eyeballing.
 *
 * **Nothing here is unit-normalised.** The API's own units are undocumented; `totalTime` in
 * particular is reported as seconds by some community scripts and milliseconds by others, and
 * guessing wrong would produce an index that looks authoritative and is silently false. The
 * fields keep the `Raw` suffix and the verbatim text so a human reading the index knows they are
 * looking at the server's numbers, not ours. Conversion to SI happens in `:tools:importer`
 * against the actual downloaded track, where it can be checked.
 */
data class WorkoutRef(
    val key: String,
    val startTimeRaw: String?,
    val activityIdRaw: String?,
    val distanceRaw: String?,
    val durationRaw: String?,
    /**
     * Whether the user typed this workout in rather than recording it.
     *
     * The one field that separates "this never had a track" from "this lost its track", which is
     * the difference between an expected empty export and silent data loss. Distance cannot tell
     * them apart: a hand-entered swim has a distance and no track, and so does a ruined ride.
     */
    val manuallyAdded: Boolean = false,
    /** Whether the API holds any track geometry for this workout. */
    val hasTrack: Boolean = false,
)

/**
 * Reads the undocumented workout-list response.
 *
 * Written to survive a response shape nobody has promised us. Rather than binding to
 * `{"payload":[...]}` — the shape community scripts from 2016 describe — it finds the largest
 * array of objects anywhere in the tree and treats that as the workout list. If Sports Tracker
 * renames the envelope, this still works; if they restructure it beyond recognition, the raw
 * response is on disk (the CLI always writes it verbatim) and this file is a few lines to fix.
 */
object WorkoutIndex {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Field names seen in the wild for the workout's own identifier, in order of preference. */
    private val KEY_FIELDS = listOf("workoutKey", "key", "id", "_id", "workoutId")

    private val START_FIELDS = listOf("startTime", "start_time", "created", "startTimeMillis")
    private val ACTIVITY_FIELDS = listOf("activityId", "activity", "activityType", "type")
    private val DISTANCE_FIELDS = listOf("totalDistance", "distance")
    private val DURATION_FIELDS = listOf("totalTime", "duration", "totalDuration", "movingTime")
    private val MANUAL_FIELDS = listOf("isManuallyAdded", "manuallyAdded")
    private val TRACK_FIELDS = listOf("polyline")

    /**
     * Extracts every workout reference from a list response.
     *
     * Objects with no recognisable key are skipped rather than failing the parse: a response that
     * mixes workouts with some other record type should still yield the workouts.
     */
    fun parse(body: String): List<WorkoutRef> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val candidates = largestObjectArray(root) ?: return emptyList()
        return candidates.mapNotNull { it.toWorkoutRef() }
    }

    private fun JsonObject.toWorkoutRef(): WorkoutRef? {
        val key = firstString(KEY_FIELDS) ?: return null
        if (key.isBlank()) return null
        return WorkoutRef(
            key = key,
            startTimeRaw = firstString(START_FIELDS),
            activityIdRaw = firstString(ACTIVITY_FIELDS),
            distanceRaw = firstString(DISTANCE_FIELDS),
            durationRaw = firstString(DURATION_FIELDS),
            manuallyAdded = firstString(MANUAL_FIELDS)?.equals("true", ignoreCase = true) == true,
            hasTrack = !firstString(TRACK_FIELDS).isNullOrBlank(),
        )
    }

    /** The first of [names] present as a scalar, rendered as text. Nested objects are ignored. */
    private fun JsonObject.firstString(names: List<String>): String? =
        names.firstNotNullOfOrNull { name ->
            (this[name] as? JsonPrimitive)?.takeUnless { it.content == "null" }?.content
        }

    /**
     * Depth-first search for the biggest array whose elements are objects.
     *
     * "Biggest" is the heuristic that separates the workout list from incidental arrays such as
     * a pagination or metadata block, which are short.
     */
    private fun largestObjectArray(element: JsonElement): List<JsonObject>? {
        var best: List<JsonObject>? = null

        fun visit(node: JsonElement) {
            when (node) {
                is JsonArray -> {
                    val objects = node.filterIsInstance<JsonObject>()
                    if (objects.size == node.size && objects.isNotEmpty()) {
                        if (best == null || objects.size > best!!.size) best = objects
                    }
                    node.forEach(::visit)
                }
                is JsonObject -> node.values.forEach(::visit)
                else -> Unit
            }
        }

        visit(element)
        return best
    }

    /**
     * Renders the references as a tab-separated index for a human to read and for a later run to
     * drive the fetch from.
     *
     * TSV rather than CSV because the values may contain commas and none of them can contain a
     * tab. Any tab or newline that does appear is replaced rather than quoted — this is a
     * throwaway index, and a mangled description column is preferable to a parser.
     */
    fun toTsv(refs: List<WorkoutRef>): String = buildString {
        appendLine("key\tstartTimeRaw\tactivityIdRaw\tdistanceRaw\tdurationRaw")
        refs.forEach { ref ->
            appendLine(
                listOf(
                    ref.key,
                    ref.startTimeRaw.orEmpty(),
                    ref.activityIdRaw.orEmpty(),
                    ref.distanceRaw.orEmpty(),
                    ref.durationRaw.orEmpty(),
                    ref.manuallyAdded.toString(),
                    ref.hasTrack.toString(),
                ).joinToString("\t") { it.replace(Regex("[\t\r\n]"), " ") },
            )
        }
    }

    /** Reads back an index written by [toTsv], returning just the keys in file order. */
    fun keysFromTsv(tsv: String): List<String> =
        tsv.lineSequence()
            .drop(1)
            .map { it.substringBefore('\t').trim() }
            .filter { it.isNotEmpty() }
            .toList()
}
