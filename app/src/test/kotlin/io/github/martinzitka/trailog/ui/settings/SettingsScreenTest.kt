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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.martinzitka.trailog.core.model.Activity
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.data.ActivityRef
import io.github.martinzitka.trailog.ui.format.Formatter
import io.github.martinzitka.trailog.ui.format.LocalFormatter
import io.github.martinzitka.trailog.ui.format.UnitSystem
import io.github.martinzitka.trailog.ui.map.LocalShowTrails
import io.github.martinzitka.trailog.ui.theme.TrailogTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.UUID

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

        override fun setShowTrails(enabled: Boolean) =
            state.update { it.copy(showTrails = enabled) }
    }

    private fun viewModel(
        settings: AppSettings = FakeSettings(),
        dynamicColourSupported: Boolean = true,
    ) = SettingsViewModel(settings, dynamicColourSupported)

    /**
     * The export half of the screen. Defaults to an empty history, which is what most of these
     * tests want: the Data group renders and nothing runs.
     */
    private fun exportViewModel(
        refs: List<ActivityRef> = emptyList(),
    ) = ExportViewModel(
        activityRefs = { refs },
        loadActivity = { id ->
            Activity(
                id = UUID.nameUUIDFromBytes(id.toByteArray()),
                type = ActivityType.CYCLING,
                name = "Ride",
                points = emptyList(),
            )
        },
    )

    // ---- the controls ----

    @Test fun `every setting is rendered with its current value`() {
        composeRule.setContent { TrailogTheme { SettingsScreen(viewModel(), exportViewModel()) } }

        composeRule.onNodeWithText("Units").assertIsDisplayed()
        composeRule.onNodeWithText("Metric").assertIsSelected()
        composeRule.onNodeWithText("Imperial").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("Appearance").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("System").performScrollTo().assertIsSelected()
        composeRule.onNodeWithText("Material You colours").performScrollTo().assertIsOn()

        composeRule.onNodeWithText("Show paths and trails").performScrollTo().assertIsOn()

        composeRule.onNodeWithText("Keep screen on while recording").performScrollTo().assertIsOff()
    }

    @Test fun `the trail toggle warns that it only shows above zoom 14`() {
        // Without this the setting reads as broken: every map opens fitted to a whole route, well
        // below the zoom at which the archive has any path data to draw.
        composeRule.setContent { TrailogTheme { SettingsScreen(viewModel(), exportViewModel()) } }
        composeRule.onNodeWithText("zoom 14", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `the units explanation says recordings are unaffected`() {
        composeRule.setContent { TrailogTheme { SettingsScreen(viewModel(), exportViewModel()) } }
        composeRule.onNodeWithText("always stored in metric", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `picking a theme selects it and deselects the others`() {
        val settings = FakeSettings()
        composeRule.setContent { TrailogTheme { SettingsScreen(viewModel(settings), exportViewModel()) } }

        composeRule.onNodeWithText("Dark").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Dark").assertIsSelected()
        composeRule.onNodeWithText("System").assertIsNotSelected()
    }

    @Test fun `toggling keep-screen-on flips the switch and is stored`() {
        val settings = FakeSettings()
        composeRule.setContent { TrailogTheme { SettingsScreen(viewModel(settings), exportViewModel()) } }

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
            TrailogTheme { SettingsScreen(viewModel(dynamicColourSupported = false), exportViewModel()) }
        }
        composeRule.onNodeWithText("Material You colours").assertDoesNotExist()
        // The rest of Appearance is still there.
        composeRule.onNodeWithText("Appearance").performScrollTo().assertIsDisplayed()
    }

    // ---- the data group ----

    @Test fun `the data group offers an export of the whole history`() {
        composeRule.setContent { TrailogTheme { SettingsScreen(viewModel(), exportViewModel()) } }

        composeRule.onNodeWithText("Data").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Export all activities").performScrollTo().assertIsDisplayed()
        // The promise that makes this export trustworthy: gaps are not welded into straight lines.
        composeRule.onNodeWithText("separate track segments", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `a finished export says what it wrote`() {
        val vm = exportViewModel(refs = listOf(ActivityRef(id = "a", startTime = 1_000L)))
        composeRule.setContent { TrailogTheme { SettingsScreen(viewModel(), vm) } }

        vm.exportAll(
            openSink = { ByteArrayOutputStream() },
            entryName = { slug, startTime -> "$slug-$startTime.gpx" },
        )

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Exported 1 activity.").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Exported 1 activity.").performScrollTo().assertIsDisplayed()
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
                    SettingsScreen(viewModel(settings), exportViewModel())
                }
            }
        }

        composeRule.onNodeWithText("12.35 km").assertIsDisplayed()

        composeRule.onNodeWithText("Imperial").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("7.67 mi").assertIsDisplayed()
        composeRule.onNodeWithText("12.35 km").assertDoesNotExist()
    }

    @Test fun `hiding trails immediately reaches every map in the tree`() {
        val settings = FakeSettings()
        composeRule.setContent {
            // Mirrors the real root, where trail visibility is provided once for the whole tree
            // and every RouteMap reads it ambiently. Standing in for a map here because MapLibre
            // never renders under Robolectric — what is being tested is that the preference
            // reaches a reader, not what the renderer then does with it.
            val prefs by settings.preferences.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalShowTrails provides prefs.showTrails) {
                TrailogTheme {
                    Text(if (LocalShowTrails.current) "trails drawn" else "trails hidden")
                    SettingsScreen(viewModel(settings), exportViewModel())
                }
            }
        }

        composeRule.onNodeWithText("trails drawn").assertIsDisplayed()

        composeRule.onNodeWithText("Show paths and trails").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("trails hidden").assertIsDisplayed()
        assertFalse(
            "the write must reach storage, not just the ambient value",
            settings.preferences.value.showTrails,
        )
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
                    composable("settings") { SettingsScreen(vm, exportViewModel()) }
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
