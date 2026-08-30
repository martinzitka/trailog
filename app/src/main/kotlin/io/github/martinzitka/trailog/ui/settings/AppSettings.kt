package io.github.martinzitka.trailog.ui.settings

import android.content.Context
import io.github.martinzitka.trailog.ui.format.UnitSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Which colour scheme to use, independent of what the system is doing. */
enum class ThemeMode {
    /** Follow the device's light/dark setting. The default. */
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * Every user preference in the app, in one immutable value.
 *
 * Each field has exactly one reader, named in its doc. A field nothing reads is a bug, not a
 * placeholder (IMPLEMENTATION_PLAN.md, M1.5 screen 5) — so this class stays small, and anything
 * added to it arrives together with the code that acts on it.
 *
 * The defaults here are the app's behaviour before the user ever opens Settings, so they must
 * match what the app did previously: metric, system theme, dynamic colour on, screen not held on,
 * trails drawn.
 */
data class AppPreferences(
    /** Read by `Formatter` via `LocalFormatter` — the single display edge. */
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    /** Read by `TrailogTheme`'s `darkTheme`. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Read by `TrailogTheme`'s `dynamicColor`. Only meaningful on Android 12+. */
    val dynamicColour: Boolean = true,
    /** Read by the Record screen's wake flag while recording (ADR 0011). */
    val keepScreenOnWhileRecording: Boolean = false,
    /** Read by `RouteMap` through `LocalShowTrails`, which hides the style's trail layer group. */
    val showTrails: Boolean = true,
    /**
     * File name of the region pack the maps render, read by `MapArchiveStore.active`.
     *
     * Null means "no choice made", which is the state after a fresh install and after an archive
     * is pushed with adb — the store then falls back to the largest installed archive. It is a
     * name rather than a path because the directory is the app's own and a stored absolute path
     * would not survive the app being moved to an SD card.
     */
    val mapArchive: String? = null,
)

/**
 * Storage for [AppPreferences]. Behind an interface so the ViewModel can be tested against a
 * trivial in-memory fake and never touches SharedPreferences, matching `RecordSettings`.
 *
 * Exposed as a [StateFlow] because two of these settings apply app-wide and must take effect the
 * moment they are changed — the theme repaints and the formatter swaps while Settings is still on
 * screen, without a restart.
 */
interface AppSettings {
    val preferences: StateFlow<AppPreferences>

    fun setUnitSystem(units: UnitSystem)
    fun setThemeMode(mode: ThemeMode)
    fun setDynamicColour(enabled: Boolean)
    fun setKeepScreenOnWhileRecording(enabled: Boolean)
    fun setShowTrails(enabled: Boolean)

    /** Pass null to forget the choice and let the store fall back to the largest archive. */
    fun setMapArchive(name: String?)
}

/**
 * SharedPreferences-backed [AppSettings], as a process-wide singleton because the theme is read at
 * the app root while Settings writes it from a ViewModel — both must see the same instance.
 *
 * Nothing stored here is a secret (CLAUDE.md reserves the Keystore for tokens), and none of it is
 * recording state, which lives in the database. Plain SharedPreferences is the right tool.
 *
 * Unknown or corrupt stored values fall back to the default rather than throwing: a preferences
 * file edited by hand or left behind by an older build must never stop the app from starting.
 */
class PrefsAppSettings private constructor(context: Context) : AppSettings {

    private val prefs = context.applicationContext
        .getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    private val state = MutableStateFlow(read())

    override val preferences: StateFlow<AppPreferences> = state.asStateFlow()

    private fun read() = AppPreferences(
        unitSystem = prefs.getString(KEY_UNITS, null).toEnum(UnitSystem.METRIC),
        themeMode = prefs.getString(KEY_THEME, null).toEnum(ThemeMode.SYSTEM),
        dynamicColour = prefs.getBoolean(KEY_DYNAMIC_COLOUR, true),
        keepScreenOnWhileRecording = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false),
        showTrails = prefs.getBoolean(KEY_SHOW_TRAILS, true),
        mapArchive = prefs.getString(KEY_MAP_ARCHIVE, null),
    )

    override fun setUnitSystem(units: UnitSystem) {
        prefs.edit().putString(KEY_UNITS, units.name).apply()
        state.update { it.copy(unitSystem = units) }
    }

    override fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        state.update { it.copy(themeMode = mode) }
    }

    override fun setDynamicColour(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOUR, enabled).apply()
        state.update { it.copy(dynamicColour = enabled) }
    }

    override fun setKeepScreenOnWhileRecording(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
        state.update { it.copy(keepScreenOnWhileRecording = enabled) }
    }

    override fun setShowTrails(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_TRAILS, enabled).apply()
        state.update { it.copy(showTrails = enabled) }
    }

    override fun setMapArchive(name: String?) {
        prefs.edit().putString(KEY_MAP_ARCHIVE, name).apply()
        state.update { it.copy(mapArchive = name) }
    }

    companion object {
        private const val KEY_UNITS = "unit_system"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_DYNAMIC_COLOUR = "dynamic_colour"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on_while_recording"
        private const val KEY_SHOW_TRAILS = "show_trails"
        private const val KEY_MAP_ARCHIVE = "map_archive"

        @Volatile private var instance: PrefsAppSettings? = null

        fun get(context: Context): PrefsAppSettings =
            instance ?: synchronized(this) {
                instance ?: PrefsAppSettings(context).also { instance = it }
            }
    }
}

/** A stored enum name, or [fallback] when it is absent or no longer a member of the enum. */
private inline fun <reified T : Enum<T>> String?.toEnum(fallback: T): T =
    this?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
