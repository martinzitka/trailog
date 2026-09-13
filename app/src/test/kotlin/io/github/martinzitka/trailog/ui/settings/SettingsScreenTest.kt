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
import io.github.martinzitka.trailog.core.model.RawPoint
import kotlinx.datetime.Instant
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

    private fun viewModel(
        settings: AppSettings = FakeAppSettings(),
        dynamicColourSupported: Boolean = true,
    ) = SettingsViewModel(settings, dynamicColourSupported)

    /**
     * The export half of the screen. Defaults to an empty history, which is what most of these
     * tests want: the Data group renders and nothing runs.
     */
    /**
     * The import half. Defaults to a source that yields nothing, which is what most of these tests
     * want: the row renders and nothing runs.
     */
    private fun importViewModel() = ImportViewModel(exists = { false }, write = {})

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
        composeRule.setContent { TrailogTheme { SettingsScreen(viewModel(), exportViewModel(), importViewModel(), onOpenMapData = {}) } }

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
        composeRule.setContent { TrailogTheme { SettingsScreen(viewModel(), exportViewModel(), importViewModel(), onOpenMapData = {}) } }
        composeRule.onNodeWithText("zoom 14", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `the units explanation says recordings are unaffected`() {
        composeRule.setContent { TrailogTheme { SettingsScreen(viewModel(), exportViewModel(), importViewModel(), onOpenMapData = {}) } }
        composeRule.onNodeWithText("always stored in metric", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `picking a theme selects it and deselects the others`() {
        val settings = FakeAppSettings()
        composeRule.setContent { TrailogTheme { SettingsScreen(viewModel(settings), exportViewModel(), importViewModel(), onOpenMapData = {}) } }

        composeRule.onNodeWithText("Dark").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Dark").assertIsSelected()
        composeRule.onNodeWithText("System").assertIsNotSelected()
    }

    @Test fun `toggling keep-screen-on flips the switch and is stored`() {
        val settings = FakeAppSettings()
        composeRule.setContent { TrailogTheme { SettingsScreen(viewModel(settings), exportViewModel(), importViewModel(), onOpenMapData = {}) } }

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
            TrailogTheme { SettingsScreen(viewModel(dynamicColourSupported = false), exportViewModel(), importViewModel(), onOpenMapData = {}) }
        }
        composeRule.onNodeWithText("Material You colours").assertDoesNotExist()
        // The rest of Appearance is still there.
        composeRule.onNodeWithText("Appearance").performScrollTo().assertIsDisplayed()
    }

    @Test fun `the map group offers the region packs screen`() {
        var opened = false
        composeRule.setContent {
            TrailogTheme {
                SettingsScreen(viewModel(), exportViewModel(), importViewModel(), onOpenMapData = { opened = true })
            }
        }

        composeRule.onNodeWithText("Map data").performScrollTo().performClick()

        assertTrue(opened)
    }

    // ---- the data group ----

    @Test fun `the data group offers an export of the whole history`() {
        composeRule.setContent { TrailogTheme { SettingsScreen(viewModel(), exportViewModel(), importViewModel(), onOpenMapData = {}) } }

        composeRule.onNodeWithText("Data").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Export all activities").performScrollTo().assertIsDisplayed()
        // The promise that makes this export trustworthy: gaps are not welded into straight lines.
        composeRule.onNodeWithText("separate track segments", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `a finished export says what it wrote`() {
        val vm = exportViewModel(refs = listOf(ActivityRef(id = "a", startTime = 1_000L)))
        composeRule.setContent { TrailogTheme { SettingsScreen(viewModel(), vm, importViewModel(), onOpenMapData = {}) } }

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
        val settings = FakeAppSettings()
        composeRule.setContent {
            // Mirrors the real root: the formatter is derived from the stored preference, so a
            // figure rendered anywhere in the tree re-renders when the preference changes.
            val prefs by settings.preferences.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalFormatter provides Formatter(prefs.unitSystem)) {
                TrailogTheme {
                    val format = LocalFormatter.current
                    Text(format.distance(12_345.0))
                    SettingsScreen(viewModel(settings), exportViewModel(), importViewModel(), onOpenMapData = {})
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
        val settings = FakeAppSettings()
        composeRule.setContent {
            // Mirrors the real root, where trail visibility is provided once for the whole tree
            // and every RouteMap reads it ambiently. Standing in for a map here because MapLibre
            // never renders under Robolectric — what is being tested is that the preference
            // reaches a reader, not what the renderer then does with it.
            val prefs by settings.preferences.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalShowTrails provides prefs.showTrails) {
                TrailogTheme {
                    Text(if (LocalShowTrails.current) "trails drawn" else "trails hidden")
                    SettingsScreen(viewModel(settings), exportViewModel(), importViewModel(), onOpenMapData = {})
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
                    composable("settings") { SettingsScreen(vm, exportViewModel(), importViewModel(), onOpenMapData = {}) }
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

    // ---- import -------------------------------------------------------------------------------

    @Test fun `the Data group offers an import alongside the export`() {
        composeRule.setContent {
            TrailogTheme { SettingsScreen(viewModel(), exportViewModel(), importViewModel(), onOpenMapData = {}) }
        }

        composeRule.onNodeWithText("Import activities").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Export all activities").performScrollTo().assertIsDisplayed()
    }

    @Test fun `the import note promises nothing is overwritten`() {
        // The reassurance that matters: re-running an import is safe, and edits made after the
        // first run survive it.
        composeRule.setContent {
            TrailogTheme { SettingsScreen(viewModel(), exportViewModel(), importViewModel(), onOpenMapData = {}) }
        }

        composeRule.onNodeWithText("Nothing is overwritten or deleted", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `a finished import says what it added and what was already there`() {
        val vm = ImportViewModel(exists = { true }, write = {})
        composeRule.setContent {
            TrailogTheme { SettingsScreen(viewModel(), exportViewModel(), vm, onOpenMapData = {}) }
        }

        vm.importArchive { importArchiveOf("a", "b") }
        // Waiting on a semantics node rather than the flow: fetching nodes pumps the looper, so
        // this actually lets the import finish. A plain field read would not.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("already here", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // "Already here" is the expected outcome of a second run, so it has to be on screen —
        // reporting only "Added 0" would read as a failure.
        composeRule.onNodeWithText("were already here", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `an unreadable archive says so, and says nothing was changed`() {
        val vm = ImportViewModel(exists = { false }, write = {})
        composeRule.setContent {
            TrailogTheme { SettingsScreen(viewModel(), exportViewModel(), vm, onOpenMapData = {}) }
        }

        vm.importArchive { null }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("read that archive", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Couldn’t read that archive", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    /** A minimal valid archive, so the screen tests exercise the real reader rather than a stub. */
    private fun importArchiveOf(vararg names: String): java.io.InputStream {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            names.forEachIndexed { index, name ->
                zip.putNextEntry(java.util.zip.ZipEntry("$name.gpx"))
                zip.write(
                    io.github.martinzitka.trailog.core.gpx.Gpx.write(
                        io.github.martinzitka.trailog.core.gpx.GpxTrack(
                            name = "Ride",
                            description = null,
                            type = "cycling",
                            points = listOf(
                                RawPoint(
                                    latitude = 50.0,
                                    longitude = 14.0,
                                    altitude = null,
                                    accuracy = null,
                                    time = Instant.fromEpochSeconds(index.toLong()),
                                ),
                                RawPoint(
                                    latitude = 50.001,
                                    longitude = 14.001,
                                    altitude = null,
                                    accuracy = null,
                                    time = Instant.fromEpochSeconds(index + 1L),
                                ),
                            ),
                            activityId = UUID.nameUUIDFromBytes(name.toByteArray()),
                        ),
                    ).toByteArray(Charsets.UTF_8),
                )
                zip.closeEntry()
            }
        }
        return java.io.ByteArrayInputStream(out.toByteArray())
    }
}
