package io.github.martinzitka.trailog.ui.settings

import io.github.martinzitka.trailog.ui.format.UnitSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A trivial [AppSettings]: the same contract, backed by a value instead of a file.
 *
 * Shared rather than nested in one test because three things now read preferences — the Settings
 * screen, its ViewModel, and `MapArchiveStore`, which stores the chosen region pack here — and a
 * third copy of the same eight overrides would be the point at which they start drifting apart.
 */
internal class FakeAppSettings(initial: AppPreferences = AppPreferences()) : AppSettings {

    private val state = MutableStateFlow(initial)

    override val preferences: StateFlow<AppPreferences> = state.asStateFlow()

    override fun setUnitSystem(units: UnitSystem) = state.update { it.copy(unitSystem = units) }

    override fun setThemeMode(mode: ThemeMode) = state.update { it.copy(themeMode = mode) }

    override fun setDynamicColour(enabled: Boolean) = state.update { it.copy(dynamicColour = enabled) }

    override fun setKeepScreenOnWhileRecording(enabled: Boolean) =
        state.update { it.copy(keepScreenOnWhileRecording = enabled) }

    override fun setShowTrails(enabled: Boolean) = state.update { it.copy(showTrails = enabled) }

    override fun setMapArchive(name: String?) = state.update { it.copy(mapArchive = name) }
}
