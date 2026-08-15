package io.github.martinzitka.trailog.ui.record

import android.content.Context
import io.github.martinzitka.trailog.core.model.ActivityType

/**
 * The sliver of UI preference the Record screen needs: the last-used activity type, so a cold
 * start defaults to it (M1.5: "the activity type defaults to the last one used"). Behind an
 * interface so the ViewModel can be tested with a trivial fake and never touches SharedPreferences.
 *
 * This is a UI convenience, not recording session state — session state lives in the database
 * (CLAUDE.md), and nothing here is a secret, so plain SharedPreferences is appropriate.
 */
interface RecordSettings {
    fun lastActivityType(): ActivityType
    fun setLastActivityType(type: ActivityType)
}

/** SharedPreferences-backed [RecordSettings]. Defaults to cycling on first ever launch. */
class PrefsRecordSettings(context: Context) : RecordSettings {
    private val prefs = context.applicationContext
        .getSharedPreferences("record_prefs", Context.MODE_PRIVATE)

    override fun lastActivityType(): ActivityType =
        prefs.getString(KEY_LAST_TYPE, null)
            ?.let { runCatching { ActivityType.valueOf(it) }.getOrNull() }
            ?: ActivityType.CYCLING

    override fun setLastActivityType(type: ActivityType) {
        prefs.edit().putString(KEY_LAST_TYPE, type.name).apply()
    }

    private companion object {
        const val KEY_LAST_TYPE = "last_activity_type"
    }
}
