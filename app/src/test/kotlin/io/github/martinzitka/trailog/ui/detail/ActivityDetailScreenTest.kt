package io.github.martinzitka.trailog.ui.detail

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.martinzitka.trailog.core.model.Activity
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.data.ActivityEntity
import io.github.martinzitka.trailog.data.ActivityStatsEntity
import io.github.martinzitka.trailog.data.ActivityWithStats
import io.github.martinzitka.trailog.ui.theme.TrailogTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Compose UI test for the Activity detail screen. Robolectric drives Compose as a JVM test, so
 * this runs on `./gradlew check` and CI with no emulator.
 *
 * It covers the screen-specific criteria the plan calls out — the summary figures, the splits
 * table, editable metadata that persists, and delete requiring confirmation — plus navigation into
 * the screen and back out of it through a real `NavHost`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityDetailScreenTest {

    @get:Rule val composeRule = createComposeRule()

    @Test fun `the summary shows every figure the plan requires`() {
        val vm = viewModel()
        composeRule.setContent { TrailogTheme { ActivityDetailScreen(vm, onBack = {}, onOpenMap = {}) } }

        composeRule.onNodeWithText("Distance").assertIsDisplayed()
        composeRule.onNodeWithText("Elapsed time").assertIsDisplayed()
        composeRule.onNodeWithText("Moving time").assertIsDisplayed()
        composeRule.onNodeWithText("Elevation gain").assertIsDisplayed()
        composeRule.onNodeWithText("Elevation loss").assertIsDisplayed()
        composeRule.onNodeWithText("Average speed").assertIsDisplayed()
        composeRule.onNodeWithText("Max speed").assertIsDisplayed()
        // The cached figures, formatted at the display edge.
        composeRule.onNodeWithText("12.35 km").assertIsDisplayed()
        composeRule.onNodeWithText("1:02:03").assertIsDisplayed()
    }

    @Test fun `the splits table renders per-kilometre rows`() {
        val vm = viewModel()
        composeRule.setContent { TrailogTheme { ActivityDetailScreen(vm, onBack = {}, onOpenMap = {}) } }

        // The page scrolls; the splits table sits below the map, summary and charts.
        composeRule.onNodeWithText("Splits").performScrollTo().assertIsDisplayed()
        // "Gain", not "Speed": the latter is also the speed chart's title.
        composeRule.onNodeWithText("Gain").performScrollTo().assertIsDisplayed()
        // ~2.5 km of ride is two full kilometres and a remainder, numbered from one.
        composeRule.onNodeWithText("3").performScrollTo().assertIsDisplayed()
    }

    @Test fun `the splits interval can be changed and the table follows`() {
        val vm = viewModel()
        composeRule.setContent { TrailogTheme { ActivityDetailScreen(vm, onBack = {}, onOpenMap = {}) } }

        // ~2.5 km lapped at 1 km is three rows; the third is the remainder.
        composeRule.onNodeWithText("3").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithContentDescription("2 km").performScrollTo().performClick()
        composeRule.waitForIdle()

        // Two 2 km rows now, so there is no third.
        composeRule.onNodeWithText("2").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("3").assertDoesNotExist()
    }

    @Test fun `every offered interval is reachable, in the reader's own units`() {
        val vm = viewModel()
        composeRule.setContent { TrailogTheme { ActivityDetailScreen(vm, onBack = {}, onOpenMap = {}) } }

        for (label in listOf("1 km", "2 km", "5 km", "10 km")) {
            composeRule.onNodeWithContentDescription(label).performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun `an interval longer than the ride still says something`() {
        val vm = viewModel()
        composeRule.setContent { TrailogTheme { ActivityDetailScreen(vm, onBack = {}, onOpenMap = {}) } }

        composeRule.onNodeWithContentDescription("10 km").performScrollTo().performClick()
        composeRule.waitForIdle()

        // One partial split rather than an empty table — and the chips are still there to go back.
        composeRule.onNodeWithText("1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("1 km").performScrollTo().assertIsDisplayed()
    }

    @Test fun `both charts are rendered and described for a screen reader`() {
        val vm = viewModel()
        composeRule.setContent { TrailogTheme { ActivityDetailScreen(vm, onBack = {}, onOpenMap = {}) } }

        // The map is at the top, so it is checked before anything scrolls the page down.
        composeRule.onNodeWithContentDescription("Map of the recorded route").assertIsDisplayed()
        composeRule.onNodeWithText("Elevation").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Elevation profile against distance")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Speed profile against distance")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun `an activity with no altitude says so instead of drawing an empty box`() {
        val vm = viewModel(points = ride(altitudeAt = { null }))
        composeRule.setContent { TrailogTheme { ActivityDetailScreen(vm, onBack = {}, onOpenMap = {}) } }

        composeRule.onNodeWithText("No altitude was recorded for this activity.")
            .performScrollTo().assertIsDisplayed()
    }

    // ---- delete ------------------------------------------------------------------------------

    @Test fun `delete requires confirmation`() {
        var deleted = false
        val vm = viewModel(onDelete = { deleted = true; true })
        composeRule.setContent { TrailogTheme { ActivityDetailScreen(vm, onBack = {}, onOpenMap = {}) } }

        composeRule.onNodeWithContentDescription("Delete activity").performClick()

        composeRule.onNodeWithText("Delete this activity?").assertIsDisplayed()
        assertFalse("tapping the delete icon must not delete anything by itself", deleted)

        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitForIdle()
        assertTrue("confirming must delete", deleted)
    }

    @Test fun `dismissing the delete confirmation keeps the activity`() {
        var deleted = false
        val vm = viewModel(onDelete = { deleted = true; true })
        composeRule.setContent { TrailogTheme { ActivityDetailScreen(vm, onBack = {}, onOpenMap = {}) } }

        composeRule.onNodeWithContentDescription("Delete activity").performClick()
        composeRule.onNodeWithText("Keep").performClick()
        composeRule.waitForIdle()

        assertFalse(deleted)
        composeRule.onNodeWithText("Splits").performScrollTo().assertIsDisplayed()
    }

    @Test fun `a deleted activity leaves the screen`() {
        val rows = MutableStateFlow<ActivityWithStats?>(row())
        var wentBack = false
        val vm = viewModel(rows = rows, onDelete = { rows.value = null; true })
        composeRule.setContent {
            TrailogTheme { ActivityDetailScreen(vm, onBack = { wentBack = true }, onOpenMap = {}) }
        }

        composeRule.onNodeWithContentDescription("Delete activity").performClick()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitForIdle()

        assertTrue("once the activity is gone the screen must navigate back", wentBack)
    }

    // ---- editing -----------------------------------------------------------------------------

    @Test fun `name, notes and type are editable and the edit is saved`() {
        val saved = mutableListOf<Triple<String, String?, ActivityType>>()
        val vm = viewModel(onSave = { name, notes, type -> saved += Triple(name, notes, type) })
        composeRule.setContent { TrailogTheme { ActivityDetailScreen(vm, onBack = {}, onOpenMap = {}) } }

        composeRule.onNodeWithContentDescription("Edit activity").performClick()
        composeRule.onNodeWithText("Edit activity").assertIsDisplayed()

        // Addressed by its label: the top bar shows the same name as the field's contents.
        composeRule.onNodeWithText("Name").performTextClearance()
        composeRule.onNodeWithText("Name").performTextInput("Evening loop")
        composeRule.onNodeWithText("Notes").performTextInput("Muddy after the rain")
        composeRule.onNodeWithText("Hiking").performClick()
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitForIdle()

        assertEquals(
            listOf(Triple("Evening loop", "Muddy after the rain", ActivityType.HIKING)),
            saved,
        )
    }

    @Test fun `cancelling an edit saves nothing`() {
        val saved = mutableListOf<Triple<String, String?, ActivityType>>()
        val vm = viewModel(onSave = { name, notes, type -> saved += Triple(name, notes, type) })
        composeRule.setContent { TrailogTheme { ActivityDetailScreen(vm, onBack = {}, onOpenMap = {}) } }

        composeRule.onNodeWithContentDescription("Edit activity").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()

        assertTrue(saved.isEmpty())
    }

    @Test fun `tapping the map asks for the fullscreen one`() {
        var opened = false
        val vm = viewModel()
        composeRule.setContent {
            TrailogTheme { ActivityDetailScreen(vm, onBack = {}, onOpenMap = { opened = true }) }
        }

        composeRule.onNodeWithContentDescription("Map of the recorded route").performClick()
        composeRule.waitForIdle()

        assertTrue("the embedded map is a preview; tapping it opens the real one", opened)
    }

    // ---- navigation --------------------------------------------------------------------------

    @Test fun `navigating into detail and back again works through a real NavHost`() {
        val vm = viewModel()
        composeRule.setContent {
            val navController = rememberNavController()
            TrailogTheme {
                NavHost(navController = navController, startDestination = "history") {
                    composable("history") {
                        Button(onClick = { navController.navigate("activity/$ACTIVITY_ID") }) {
                            Text("Open activity")
                        }
                    }
                    composable(
                        route = "activity/{activityId}",
                        arguments = listOf(navArgument("activityId") { type = NavType.StringType }),
                    ) {
                        ActivityDetailScreen(
                            vm,
                            onBack = { navController.popBackStack() },
                            onOpenMap = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Open activity").performClick()
        composeRule.onNodeWithText("Splits").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("Open activity").assertIsDisplayed()
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun viewModel(
        rows: MutableStateFlow<ActivityWithStats?> = MutableStateFlow(row()),
        points: List<RawPoint> = ride(),
        onSave: (String, String?, ActivityType) -> Unit = { _, _, _ -> },
        onDelete: () -> Boolean = { true },
    ) = ActivityDetailViewModel(
        activityId = ACTIVITY_ID,
        row = rows,
        loadActivity = { id ->
            Activity(
                id = UUID.fromString(id),
                type = ActivityType.CYCLING,
                name = rows.value?.activity?.name.orEmpty(),
                points = points,
            )
        },
        saveMetadata = { _, name, notes, type -> onSave(name, notes, type) },
        deleteActivity = { onDelete() },
        // Robolectric drives Compose on the main thread; keeping the derivation there means the
        // screen has its state before the first assertion, with no scheduler to advance.
        computeDispatcher = Dispatchers.Main,
    )

    /** ~2.5 km of eastward line at 50°N, one fix per second, climbing steadily. */
    private fun ride(
        count: Int = 350,
        altitudeAt: (Int) -> Double? = { 300.0 + it * 0.4 },
    ): List<RawPoint> = (0 until count).map { i ->
        RawPoint(
            latitude = 50.0,
            longitude = 14.0 + i * 0.0001,
            altitude = altitudeAt(i),
            accuracy = 5.0,
            time = Instant.fromEpochSeconds(i.toLong()),
            segmentIndex = 0,
        )
    }

    private fun row() = ActivityWithStats(
        activity = ActivityEntity(
            id = ACTIVITY_ID,
            type = ActivityType.CYCLING.name,
            name = "Morning ride",
            notes = null,
            startTime = START_TIME,
            createdAt = START_TIME,
            updatedAt = START_TIME,
        ),
        stats = ActivityStatsEntity(
            activityId = ACTIVITY_ID,
            distance = 12_345.0,
            elapsedTime = 3_723_000L,
            movingTime = 3_600_000L,
            averageSpeed = 3.4,
            maxSpeed = 9.1,
            elevationGain = 251.0,
            elevationLoss = 248.0,
            segmentCount = 1,
            pointCount = 350,
            computedAt = START_TIME,
        ),
    )

    private companion object {
        const val ACTIVITY_ID = "019fe178-0000-7000-8000-000000000001"
        const val START_TIME = 1_700_000_000_000L
    }
}
