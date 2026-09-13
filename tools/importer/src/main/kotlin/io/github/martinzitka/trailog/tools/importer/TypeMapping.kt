package io.github.martinzitka.trailog.tools.importer

import io.github.martinzitka.trailog.core.model.ActivityType
import java.io.File

/** How one workout's type was decided. Reported per run so the result can be audited. */
enum class TypeSource {
    /** A per-workout line in `type-overrides.tsv` won. */
    OVERRIDE,

    /** The account's `activityId` was in `activity-types.tsv`. */
    MAPPING,

    /** Neither knew the id, so it landed in [ActivityType.OTHER]. */
    FALLBACK,
}

data class ResolvedType(val type: ActivityType, val source: TypeSource)

/**
 * The account's Sports Tracker `activityId` → [ActivityType] mapping, plus the per-workout
 * corrections that the id mapping alone cannot express.
 *
 * **Never a table in this repo.** Sports Tracker identifies types by a numeric id that appears only
 * in the workout-list JSON, and it offers generic "Other 1…6" slots — so while the common ids are
 * stable across accounts, the rest are whatever a given user assigned them. The same id means
 * different sports to different people, and a table baked in here would be wrong for everyone
 * except its author. Both files are supplied by the user alongside the export they describe and are
 * kept out of version control.
 *
 * **The overrides file is not optional bookkeeping.** One id can hold more than one real activity —
 * a generic slot used for two unrelated sports in different years — so no id-to-type rule is
 * correct on its own. Deriving the mapping is human judgement over the workout list, and a
 * reviewable data file beats guesses buried in code.
 *
 * An unknown id falls back to [ActivityType.OTHER] rather than failing the import; the run reports
 * which ids landed there. A **misspelled type name fails loudly**, with its line number: the files
 * are hand-written and the whole import hangs off them, so a typo must not silently become OTHER.
 */
class TypeMapping(
    private val byActivityId: Map<String, ActivityType>,
    private val byWorkoutKey: Map<String, ActivityType>,
) {

    /** Every activity id the mapping file knows. */
    val mappedIds: Set<String> get() = byActivityId.keys

    fun resolve(workoutKey: String, activityId: String?): ResolvedType {
        byWorkoutKey[workoutKey]?.let { return ResolvedType(it, TypeSource.OVERRIDE) }
        activityId?.let { id -> byActivityId[id]?.let { return ResolvedType(it, TypeSource.MAPPING) } }
        return ResolvedType(ActivityType.OTHER, TypeSource.FALLBACK)
    }

    companion object {

        /**
         * Reads both files from an export directory. A missing `type-overrides.tsv` is fine — an
         * account may need no corrections — but a missing `activity-types.tsv` is fatal, because
         * without it every workout would import as OTHER, which looks like a successful run and
         * is not.
         */
        fun read(exportDir: File): TypeMapping {
            val types = File(exportDir, "activity-types.tsv")
            require(types.isFile) {
                "missing ${types.name} in ${exportDir.path} — without it every workout would " +
                    "import as OTHER, which looks like success and is not"
            }
            val overrides = File(exportDir, "type-overrides.tsv")
            return TypeMapping(
                byActivityId = parse(types.readLines(), types.name, valueColumn = 1),
                byWorkoutKey = if (overrides.isFile) {
                    parse(overrides.readLines(), overrides.name, valueColumn = 1)
                } else {
                    emptyMap()
                },
            )
        }

        /**
         * Tab-separated, `#` comments and blank lines ignored. Column 0 is the key; [valueColumn]
         * holds the Trailog type name. Any further columns (the overrides file's `note`) are read
         * past — they exist for the human, not for this.
         */
        internal fun parse(
            lines: List<String>,
            fileName: String,
            valueColumn: Int,
        ): Map<String, ActivityType> {
            val out = LinkedHashMap<String, ActivityType>()
            lines.forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val cells = line.split('\t').map { it.trim() }
                val lineNumber = index + 1
                require(cells.size > valueColumn) {
                    "$fileName:$lineNumber: expected at least ${valueColumn + 1} tab-separated " +
                        "columns, found ${cells.size}"
                }
                val key = cells[0]
                require(key.isNotEmpty()) { "$fileName:$lineNumber: empty key" }
                val typeName = cells[valueColumn]
                val type = ActivityType.entries.firstOrNull { it.name == typeName }
                    ?: throw IllegalArgumentException(
                        "$fileName:$lineNumber: '$typeName' is not a Trailog ActivityType. " +
                            "Known: ${ActivityType.entries.joinToString(", ") { it.name }}",
                    )
                out[key] = type
            }
            return out
        }
    }
}
