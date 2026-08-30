package io.github.martinzitka.trailog.ui.map

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.martinzitka.trailog.ui.settings.FakeAppSettings
import io.github.martinzitka.trailog.ui.theme.TrailogTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Compose UI test for the Map data screen. Robolectric drives Compose as a JVM test, so this runs
 * on `./gradlew check` with no emulator.
 *
 * The file picker itself cannot be exercised here — it is a system activity — so what is covered is
 * everything around it: the empty state that has to explain where a region pack comes from, the
 * populated list, the confirmation before a delete, and navigation in and out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapDataScreenTest {

    @get:Rule val composeRule = createComposeRule()

    @get:Rule val temp = TemporaryFolder()

    @Test fun `the empty state says where a region pack comes from`() {
        composeRule.setContent { TrailogTheme { MapDataScreen(viewModel(), onBack = {}) } }

        // A blank screen is never a valid state: with no pack installed this has to explain that
        // recording still works and how to get a map underneath it.
        composeRule.onNodeWithText("No map data on this device").assertIsDisplayed()
        composeRule.onNodeWithText("build-tiles.sh", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Import a region pack…").performScrollTo().assertIsDisplayed()
    }

    @Test fun `an installed pack is listed with its size and the ground it covers`() {
        writeArchive("czechia.pmtiles", minLon = 11.9, minLat = 48.1, maxLon = 19.0, maxLat = 51.4)

        composeRule.setContent { TrailogTheme { MapDataScreen(viewModel(), onBack = {}) } }

        composeRule.onNodeWithText("czechia.pmtiles").assertIsDisplayed()
        // The question this screen answers is "does this pack reach where I am going".
        composeRule.onNodeWithText("48.1°N", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `a pack whose header will not parse still lists, saying its area is unknown`() {
        File(temp.root, "broken.pmtiles").writeBytes(ByteArray(200))

        composeRule.setContent { TrailogTheme { MapDataScreen(viewModel(), onBack = {}) } }

        composeRule.onNodeWithText("broken.pmtiles").assertIsDisplayed()
        composeRule.onNodeWithText("Area unknown", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `removing a pack asks first, and keeping it changes nothing`() {
        writeArchive("czechia.pmtiles")
        composeRule.setContent { TrailogTheme { MapDataScreen(viewModel(), onBack = {}) } }

        composeRule.onNodeWithContentDescription("Remove czechia.pmtiles").performClick()
        composeRule.onNodeWithText("Remove this region pack?").assertIsDisplayed()

        composeRule.onNodeWithText("Keep").performClick()
        composeRule.waitForIdle()

        assertTrue(File(temp.root, "czechia.pmtiles").exists())
        composeRule.onNodeWithText("czechia.pmtiles").assertIsDisplayed()
    }

    @Test fun `confirming the removal deletes the file`() {
        writeArchive("czechia.pmtiles")
        composeRule.setContent { TrailogTheme { MapDataScreen(viewModel(), onBack = {}) } }

        composeRule.onNodeWithContentDescription("Remove czechia.pmtiles").performClick()
        composeRule.onNodeWithText("Remove").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { !File(temp.root, "czechia.pmtiles").exists() }

        assertFalse(File(temp.root, "czechia.pmtiles").exists())
    }

    @Test fun `opens from settings and the back arrow returns`() {
        lateinit var navController: NavHostController
        composeRule.setContent {
            navController = rememberNavController()
            TrailogTheme {
                NavHost(navController = navController, startDestination = "settings") {
                    composable("settings") {
                        Button(onClick = { navController.navigate("map-data") }) {
                            Text("To map data")
                        }
                    }
                    composable("map-data") {
                        MapDataScreen(viewModel(), onBack = { navController.popBackStack() })
                    }
                }
            }
        }

        composeRule.onNodeWithText("To map data").performClick()
        composeRule.onNodeWithText("Map data").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Back to settings").performClick()
        composeRule.waitForIdle()

        assertEquals("settings", navController.currentDestination?.route)
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun viewModel() = MapDataViewModel(
        MapArchiveStore(
            tilesDir = temp.root,
            settings = FakeAppSettings(),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            ioDispatcher = Dispatchers.Unconfined,
        ),
    )

    /** A file with a valid PMTiles v3 header declaring [minLon]…[maxLat]. */
    private fun writeArchive(
        name: String,
        minLon: Double = 12.0,
        minLat: Double = 48.0,
        maxLon: Double = 19.0,
        maxLat: Double = 51.0,
    ): File {
        val header = ByteArray(127)
        "PMTiles".toByteArray(Charsets.US_ASCII).copyInto(header)
        header[7] = 3
        fun put(offset: Int, degrees: Double) {
            val v = Math.round(degrees * 1e7).toInt()
            for (i in 0..3) header[offset + i] = ((v shr (8 * i)) and 0xFF).toByte()
        }
        put(102, minLon)
        put(106, minLat)
        put(110, maxLon)
        put(114, maxLat)
        return File(temp.root, name).apply { writeBytes(header) }
    }
}
