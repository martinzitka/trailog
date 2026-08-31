package io.github.martinzitka.trailog.ui.record

import android.content.Context
import io.github.martinzitka.trailog.core.model.ActivityType

/**
 * The sliver of UI preference the Record screen needs: which activity types were used most
 * recently, so a cold start defaults to the last one (M1.5: "the activity type defaults to the
 * last one used") and the picker can offer the handful actually in use rather than all of them.
 *
 * Behind an interface so the ViewModel can be tested with a trivial fake and never touches
 * SharedPreferences.
 *
 * This is a UI convenience, not recording session state — session state lives in the database
 * (CLAUDE.md), and nothing here is a secret, so plain SharedPreferences is appropriate.
 */
interface RecordSettings {
    /**
     * Every [ActivityType], most-recently-used first.
     *
     * Always a complete permutation rather than a short list of favourites: the picker takes the
     * first few, and a complete ordering means there is never a gap to pad and a type added to the
     * enum later simply arrives at the back instead of being absent.
     */
    fun recentActivityTypes(): List<ActivityType>

    /** Record that [type] was used, moving it to the front of [recentActivityTypes]. */
    fun noteActivityTypeUsed(type: ActivityType)

    /** The type a cold start defaults to — by definition the most recently used one. */
    fun lastActivityType(): ActivityType = recentActivityTypes().first()
}

/**
 * SharedPreferences-backed [RecordSettings].
 *
 * Stored as a comma-separated list of enum names. Names that no longer resolve are dropped and
 * types missing from the stored list are appended in declaration order, so a preferences file
 * written by an older build — one that had never heard of half these types — still yields a
 * complete, usable ordering. On first ever launch that leaves the enum's own order, which starts
 * at cycling.
 */
class PrefsRecordSettings(context: Context) : RecordSettings {
    private val prefs = context.applicationContext
        .getSharedPreferences("record_prefs", Context.MODE_PRIVATE)

    override fun recentActivityTypes(): List<ActivityType> {
        val stored = prefs.getString(KEY_RECENT_TYPES, null)
            ?.split(',')
            ?.mapNotNull { name -> runCatching { ActivityType.valueOf(name.trim()) }.getOrNull() }
            ?.distinct()
            ?: legacyLastType()
        return stored + ActivityType.entries.filterNot { it in stored }
    }

    /**
     * The single type written by builds before the ordering existed, as a one-element list.
     *
     * Without this an upgrade silently resets the default activity type to cycling on a device
     * that has been recording something else for months. The old key is only read, never written,
     * and the first [noteActivityTypeUsed] supersedes it.
     */
    private fun legacyLastType(): List<ActivityType> =
        prefs.getString(KEY_LEGACY_LAST_TYPE, null)
            ?.let { runCatching { ActivityType.valueOf(it) }.getOrNull() }
            ?.let(::listOf)
            .orEmpty()

    override fun noteActivityTypeUsed(type: ActivityType) {
        val updated = listOf(type) + recentActivityTypes().filterNot { it == type }
        prefs.edit().putString(KEY_RECENT_TYPES, updated.joinToString(",") { it.name }).apply()
    }

    private companion object {
        const val KEY_RECENT_TYPES = "recent_activity_types"

        /** Written by builds before the MRU ordering. Read once, for the upgrade path only. */
        const val KEY_LEGACY_LAST_TYPE = "last_activity_type"
    }
}
