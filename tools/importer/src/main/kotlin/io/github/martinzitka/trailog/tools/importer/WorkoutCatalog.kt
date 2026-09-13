package io.github.martinzitka.trailog.tools.importer

import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One workout as the list endpoint described it, in SI.
 *
 * **The units here were measured, not assumed.** The API documents nothing, and the scraper
 * deliberately left every figure as raw text rather than guess (see `WorkoutIndex` in
 * `:tools:sports-tracker-export`). They were settled by calibrating against the downloaded tracks
 * across a whole exported history:
 *
 * - `totalDistance` is **metres** — its ratio to the haversine distance over the real track points
 *   has a median of 0.9986.
 * - `startTime` and `stopTime` are **epoch milliseconds**, so elapsed time is their difference.
 * - `totalTime` is **seconds of moving time**, not elapsed. Its ratio to `stopTime - startTime`
 *   has a median of 0.82 and a **maximum of exactly 1.0000** across every workout measured, which
 *   is the signature of a moving-time figure and could not be true of anything else.
 *
 * That last one matters: Trailog keeps moving and elapsed time as separate figures for the same
 * reason, so the source's numbers land on the right ones rather than being quietly conflated.
 *
 * @property title the user's own title for the workout. Sports Tracker puts it in `description`;
 *   `workoutName` holds an auto-generated date, which is not a name and is discarded.
 */
data class WorkoutSummary(
    val key: String,
    val title: String?,
    val activityId: String?,
    val startTime: Instant?,
    val stopTime: Instant?,
    val distance: Double?,
    val movingTime: Duration?,
    val manuallyAdded: Boolean,
) {
    /** Wall-clock duration from start to stop, when both are known. */
    val elapsedTime: Duration?
        get() = if (startTime != null && stopTime != null) stopTime - startTime else null
}

/**
 * Reads the saved workout-list response.
 *
 * Deliberately duplicates the scraper's tolerant tree-walking rather than depending on it: that
 * module is throwaway and is deleted once the migration is done, and a durable tool must not have a
 * hole punched in it when that happens. Like the scraper, it finds the largest array of objects
 * anywhere in the tree rather than binding to an envelope shape nobody promised us.
 *
 * `workouts.json` is the **only** record for the workouts whose track never existed or did not
 * survive export — the hand-entered ones, and the ones damaged upstream — which is why the importer
 * reads it rather than working from the GPX folder alone.
 */
object WorkoutCatalog {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val KEY_FIELDS = listOf("workoutKey", "key", "id", "_id", "workoutId")
    private val TITLE_FIELDS = listOf("description")
    private val ACTIVITY_FIELDS = listOf("activityId", "activity", "activityType")
    private val START_FIELDS = listOf("startTime", "start_time", "created", "startTimeMillis")
    private val STOP_FIELDS = listOf("stopTime", "stop_time", "endTime")
    private val DISTANCE_FIELDS = listOf("totalDistance", "distance")
    private val MOVING_FIELDS = listOf("totalTime", "movingTime", "duration")
    private val MANUAL_FIELDS = listOf("isManuallyAdded", "manuallyAdded")

    fun read(file: File): Map<String, WorkoutSummary> = parse(file.readText())

    fun parse(text: String): Map<String, WorkoutSummary> {
        val root = json.parseToJsonElement(text)
        val entries = largestObjectArray(root) ?: return emptyMap()
        return entries.mapNotNull { it as? JsonObject }
            .mapNotNull { obj -> summaryOf(obj) }
            .associateBy { it.key }
    }

    private fun summaryOf(obj: JsonObject): WorkoutSummary? {
        val key = obj.firstString(KEY_FIELDS)?.takeIf { it.isNotBlank() } ?: return null
        return WorkoutSummary(
            key = key,
            title = obj.firstString(TITLE_FIELDS)?.takeIf { it.isNotBlank() },
            activityId = obj.firstString(ACTIVITY_FIELDS)?.takeIf { it.isNotBlank() },
            startTime = obj.firstLong(START_FIELDS)?.let { Instant.fromEpochMilliseconds(it) },
            stopTime = obj.firstLong(STOP_FIELDS)?.let { Instant.fromEpochMilliseconds(it) },
            distance = obj.firstDouble(DISTANCE_FIELDS)?.takeIf { it > 0.0 },
            movingTime = obj.firstDouble(MOVING_FIELDS)?.takeIf { it > 0.0 }?.seconds,
            manuallyAdded = obj.firstBoolean(MANUAL_FIELDS) ?: false,
        )
    }

    /** The biggest array of objects anywhere in the tree — the workout list, whatever wraps it. */
    private fun largestObjectArray(root: JsonElement): JsonArray? {
        var best: JsonArray? = null
        fun walk(node: JsonElement) {
            when (node) {
                is JsonArray -> {
                    if (node.firstOrNull() is JsonObject && node.size > (best?.size ?: 0)) best = node
                    node.forEach { walk(it) }
                }
                is JsonObject -> node.values.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(root)
        return best
    }

    private fun JsonObject.primitive(names: List<String>): JsonPrimitive? =
        names.firstNotNullOfOrNull { this[it] as? JsonPrimitive }?.takeIf { it.content != "null" }

    private fun JsonObject.firstString(names: List<String>): String? =
        primitive(names)?.content

    private fun JsonObject.firstLong(names: List<String>): Long? =
        primitive(names)?.let { it.longOrNull ?: it.content.toDoubleOrNull()?.toLong() }

    private fun JsonObject.firstDouble(names: List<String>): Double? =
        primitive(names)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }

    private fun JsonObject.firstBoolean(names: List<String>): Boolean? =
        primitive(names)?.let { it.booleanOrNull ?: it.content.toBooleanStrictOrNull() }
}
