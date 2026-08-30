package io.github.martinzitka.trailog.ui.settings

import app.cash.turbine.test
import io.github.martinzitka.trailog.ui.format.UnitSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SettingsViewModel]. An in-memory [AppSettings] stands in for SharedPreferences,
 * so this needs no device and no Robolectric.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** A trivial fake: the same contract, backed by a value instead of a file. */
    private class FakeSettings(initial: AppPreferences = AppPreferences()) : AppSettings {
        private val state = MutableStateFlow(initial)
        override val preferences: StateFlow<AppPreferences> = state.asStateFlow()
        override fun setUnitSystem(units: UnitSystem) = state.update { it.copy(unitSystem = units) }
        override fun setThemeMode(mode: ThemeMode) = state.update { it.copy(themeMode = mode) }
        override fun setDynamicColour(enabled: Boolean) =
            state.update { it.copy(dynamicColour = enabled) }

        override fun setKeepScreenOnWhileRecording(enabled: Boolean) =
            state.update { it.copy(keepScreenOnWhileRecording = enabled) }

        override fun setShowTrails(enabled: Boolean) =
            state.update { it.copy(showTrails = enabled) }
    }

    private fun viewModel(
        settings: AppSettings = FakeSettings(),
        dynamicColourSupported: Boolean = true,
    ) = SettingsViewModel(settings, dynamicColourSupported)

    // ---- defaults ----

    @Test fun `the defaults are what the app did before Settings existed`() {
        val state = viewModel().uiState.value
        assertEquals(UnitSystem.METRIC, state.unitSystem)
        assertEquals(ThemeMode.SYSTEM, state.themeMode)
        assertTrue("Material You was on by default before the toggle existed", state.dynamicColour)
        assertFalse(
            "ADR 0011: recording must not hold the screen on unless asked",
            state.keepScreenOnWhileRecording,
        )
        assertTrue(
            "the style draws trails; the toggle exists to hide them, not to reveal them",
            state.showTrails,
        )
    }

    @Test fun `the initial state is available synchronously, so the screen never renders blank`() {
        val settings = FakeSettings(AppPreferences(unitSystem = UnitSystem.IMPERIAL))
        assertEquals(UnitSystem.IMPERIAL, viewModel(settings).uiState.value.unitSystem)
    }

    // ---- writes reach storage and come back out ----

    @Test fun `choosing imperial is stored and reflected`() = runTest(dispatcher) {
        val settings = FakeSettings()
        val vm = viewModel(settings)
        vm.uiState.test {
            assertEquals(UnitSystem.METRIC, awaitItem().unitSystem)
            vm.setUnitSystem(UnitSystem.IMPERIAL)
            assertEquals(UnitSystem.IMPERIAL, awaitItem().unitSystem)
        }
        assertEquals(
            "the write must reach storage, not just the ViewModel's own copy",
            UnitSystem.IMPERIAL,
            settings.preferences.value.unitSystem,
        )
    }

    @Test fun `each setting is written independently of the others`() = runTest(dispatcher) {
        val settings = FakeSettings()
        val vm = viewModel(settings)

        vm.setThemeMode(ThemeMode.DARK)
        vm.setDynamicColour(false)
        vm.setKeepScreenOnWhileRecording(true)
        vm.setUnitSystem(UnitSystem.IMPERIAL)
        vm.setShowTrails(false)

        assertEquals(
            AppPreferences(
                unitSystem = UnitSystem.IMPERIAL,
                themeMode = ThemeMode.DARK,
                dynamicColour = false,
                keepScreenOnWhileRecording = true,
                showTrails = false,
            ),
            settings.preferences.value,
        )
    }

    @Test fun `hiding trails is stored and reflected, and reversible`() = runTest(dispatcher) {
        val settings = FakeSettings()
        val vm = viewModel(settings)
        vm.uiState.test {
            assertTrue(awaitItem().showTrails)
            vm.setShowTrails(false)
            assertFalse(awaitItem().showTrails)
            vm.setShowTrails(true)
            assertTrue(awaitItem().showTrails)
        }
        assertTrue(settings.preferences.value.showTrails)
    }

    @Test fun `every theme mode round-trips`() {
        val settings = FakeSettings()
        val vm = viewModel(settings)
        ThemeMode.entries.forEach { mode ->
            vm.setThemeMode(mode)
            assertEquals(mode, settings.preferences.value.themeMode)
        }
    }

    @Test fun `switching back to metric is not a one-way door`() {
        val settings = FakeSettings()
        val vm = viewModel(settings)
        vm.setUnitSystem(UnitSystem.IMPERIAL)
        vm.setUnitSystem(UnitSystem.METRIC)
        assertEquals(UnitSystem.METRIC, settings.preferences.value.unitSystem)
    }

    // ---- device capability ----

    @Test fun `Material You is reported unsupported below Android 12, so the row can be hidden`() {
        assertFalse(viewModel(dynamicColourSupported = false).uiState.value.dynamicColourSupported)
        assertTrue(viewModel(dynamicColourSupported = true).uiState.value.dynamicColourSupported)
    }

    @Test fun `an unsupported device still reports the stored preference untouched`() {
        // The stored value is not rewritten just because this device cannot honour it — moving the
        // same profile to a newer phone must keep the user's choice.
        val settings = FakeSettings(AppPreferences(dynamicColour = true))
        val state = viewModel(settings, dynamicColourSupported = false).uiState.value
        assertTrue(state.dynamicColour)
        assertFalse(state.dynamicColourSupported)
    }
}
