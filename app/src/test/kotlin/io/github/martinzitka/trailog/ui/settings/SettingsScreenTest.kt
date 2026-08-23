package io.github.martinzitka.trailog.ui.settings

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.martinzitka.trailog.ui.format.Formatter
import io.github.martinzitka.trailog.ui.format.LocalFormatter
import io.github.martinzitka.trailog.ui.format.UnitSystem
import io.github.martinzitka.trailog.ui.theme.TrailogTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI test for the Settings screen. Robolectric drives Compose as a JVM test, so this runs
 * on `./gradlew check` with no emulator.
 *
 * The screen-specific criterion for M1.5 screen 5 is that every setting shown actually does
 * something, so the important test here is not that the controls render — it is
 * [choosing imperial immediately re-renders every figure in the app], which proves the unit
 * preference reaches the display edge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsScreenTest {

    @get:Rule val composeRule = createComposeRule()

    private class FakeSettings(initial: AppPreferences = AppPreferences()) : AppSettings {
        private val state = MutableStateFlow(initial)
        override val preferences: StateFlow<AppPreferences> = state.asStateFlow()
        override fun setUnitSystem(units: UnitSystem) = state.update { it.copy(unitSystem = units) }
        override fun setThemeMode(mode: ThemeMode) = state.update { it.copy(themeMode = mode) }
        override fun setDynamicColour(enabled: Boolean) =
            state.update { it.copy(dynamicColour = enabled) }

        override fun setKeepScreenOnWhileRecording(enabled: Boolean) =
            state.update { it.copy(keepScreenOnWhileRecording = enabled) }
    }

    private fun viewModel(
        settings: AppSettings = FakeSettings(),
        dynamicColourSupported: Boolean = true,
    ) = SettingsViewModel(settings, dynamicColourSupported)

    // ---- the controls ----

    @Test fun `every setting is rendered with its current value`() {
        composeRule.setContent { TrailogTheme { SettingsScreen(viewModel()) } }

        composeRule.onNodeWithText("Units").assertIsDisplayed()
        composeRule.onNodeWithText("Metric").assertIsSelected()
        composeRule.onNodeWithText("Imperial").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("Appearance").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("System").performScrollTo().assertIsSelected()
        composeRule.onNodeWithText("Material You colours").performScrollTo().assertIsOn()

        composeRule.onNodeWithText("Keep screen on while recording").performScrollTo().assertIsOff()
    }

    @Test fun `the units explanation says recordings are unaffected`() {
        composeRule.setContent { TrailogTheme { SettingsScreen(viewModel()) } }
        composeRule.onNodeWithText("always stored in metric", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `picking a theme selects it and deselects the others`() {
        val settings = FakeSettings()
        composeRule.setContent { TrailogTheme { SettingsScreen(viewModel(settings)) } }

        composeRule.onNodeWithText("Dark").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Dark").assertIsSelected()
        composeRule.onNodeWithText("System").assertIsNotSelected()
    }

    @Test fun `toggling keep-screen-on flips the switch and is stored`() {
        val settings = FakeSettings()
        composeRule.setContent { TrailogTheme { SettingsScreen(viewModel(settings)) } }

        composeRule.onNodeWithText("Keep screen on while recording").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Keep screen on while recording").assertIsOn()
        assertTrue(settings.preferences.value.keepScreenOnWhileRecording)
    }

    /**
     * A control that cannot change anything is a bug, not a placeholder — so below Android 12,
     * where Material You does not exist, the row must be absent rather than present and inert.
     */
    @Test fun `Material You is absent where the platform cannot honour it`() {
        composeRule.setContent {
            TrailogTheme { SettingsScreen(viewModel(dynamicColourSupported = false)) }
        }
        composeRule.onNodeWithText("Material You colours").assertDoesNotExist()
        // The rest of Appearance is still there.
        composeRule.onNodeWithText("Appearance").performScrollTo().assertIsDisplayed()
    }

    // ---- the setting actually does something ----

    @Test fun `choosing imperial immediately re-renders every figure in the app`() {
        val settings = FakeSettings()
        composeRule.setContent {
            // Mirrors the real root: the formatter is derived from the stored preference, so a
            // figure rendered anywhere in the tree re-renders when the preference changes.
            val prefs by settings.preferences.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalFormatter provides Formatter(prefs.unitSystem)) {
                TrailogTheme {
                    val format = LocalFormatter.current
                    Text(format.distance(12_345.0))
                    SettingsScreen(viewModel(settings))
                }
            }
        }

        composeRule.onNodeWithText("12.35 km").assertIsDisplayed()

        composeRule.onNodeWithText("Imperial").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("7.67 mi").assertIsDisplayed()
        composeRule.onNodeWithText("12.35 km").assertDoesNotExist()
    }

    // ---- navigation ----

    @Test fun `navigating into Settings and back again works through a real NavHost`() {
        val vm = viewModel()
        lateinit var navController: NavHostController
        composeRule.setContent {
            navController = rememberNavController()
            TrailogTheme {
                NavHost(navController = navController, startDestination = "record") {
                    composable("record") {
                        Button(onClick = { navController.navigate("settings") }) {
                            Text("To settings")
                        }
                    }
                    composable("settings") { SettingsScreen(vm) }
                }
            }
        }

        composeRule.onNodeWithText("To settings").performClick()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()

        // Settings is a bottom-bar tab, so leaving it is a back press, not an in-screen control.
        composeRule.runOnUiThread { navController.popBackStack() }
        composeRule.onNodeWithText("To settings").assertIsDisplayed()
        composeRule.onNodeWithText("Units").assertDoesNotExist()
    }
}
