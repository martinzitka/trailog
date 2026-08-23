package io.github.martinzitka.trailog.ui.settings

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.martinzitka.trailog.ui.format.UnitSystem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * What the Settings screen renders.
 *
 * Deliberately a flat record rather than the generic row model the Sensors screen uses: diagnostics
 * rows are all the same shape (a label and a value), settings are not — a unit choice and a switch
 * want genuinely different controls, and pretending otherwise would cost more than it saves at this
 * size. Adding a setting is a field here, a row in the screen, and the code that reads it.
 *
 * @param dynamicColourSupported false below Android 12, where Material You does not exist. The row
 *   is hidden rather than shown-and-inert, because a setting that changes nothing is a bug.
 */
data class SettingsUiState(
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColour: Boolean = true,
    val dynamicColourSupported: Boolean = true,
    val keepScreenOnWhileRecording: Boolean = false,
)

/**
 * Settings. A thin reactive projection of [AppSettings] plus the one piece of device capability the
 * screen needs, with no Android types in the logic — [dynamicColourSupported] arrives as a plain
 * boolean so the whole thing is testable without Robolectric.
 *
 * Writes go straight through to storage. There is no apply/confirm step: every setting here takes
 * effect immediately and is individually reversible, so staging changes would add ceremony without
 * protecting anything.
 */
class SettingsViewModel(
    private val settings: AppSettings,
    private val dynamicColourSupported: Boolean,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = settings.preferences
        .map { it.toUiState() }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            settings.preferences.value.toUiState(),
        )

    private fun AppPreferences.toUiState() = SettingsUiState(
        unitSystem = unitSystem,
        themeMode = themeMode,
        dynamicColour = dynamicColour,
        dynamicColourSupported = dynamicColourSupported,
        keepScreenOnWhileRecording = keepScreenOnWhileRecording,
    )

    fun setUnitSystem(units: UnitSystem) = settings.setUnitSystem(units)

    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)

    fun setDynamicColour(enabled: Boolean) = settings.setDynamicColour(enabled)

    fun setKeepScreenOnWhileRecording(enabled: Boolean) =
        settings.setKeepScreenOnWhileRecording(enabled)

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
            settings = PrefsAppSettings.get(appContext),
            dynamicColourSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        ) as T
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
