package io.github.martinzitka.trailog.ui.detail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.martinzitka.trailog.core.model.Activity
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.data.ActivityEntity
import io.github.martinzitka.trailog.data.ActivityWithStats
import io.github.martinzitka.trailog.ui.theme.TrailogTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Compose UI test for the fullscreen map and its linked charts.
 *
 * What it can and cannot cover is worth stating. Robolectric has no PMTiles archive and could not
 * load MapLibre's native library anyway, so every one of these runs against the polyline fallback
 * renderer (see `RouteMap`). That covers the linking — cursor state, the chart's gestures, the
 * readout, the toggles — because none of it depends on which renderer drew. It does **not** cover
 * MapLibre's own pan, zoom and cursor symbol, which can only be judged on a real device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityMapScreenTest {

    @get:Rule val composeRule = createComposeRule()

    @Test fun `the map and the chart are both shown and described for a screen reader`() {
        composeRule.setContent { TrailogTheme { ActivityMapScreen(viewModel(), onBack = {}) } }

        composeRule.onNodeWithContentDescription("Map of the recorded route").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(CHART_CD).assertIsDisplayed()
    }

    @Test fun `with nothing selected the screen invites a selection rather than showing blanks`() {
        composeRule.setContent { TrailogTheme { ActivityMapScreen(viewModel(), onBack = {}) } }

        composeRule.onNodeWithText("Touch the chart or the map to inspect a point of the ride.")
            .assertIsDisplayed()
    }

    @Test fun `touching the chart selects a point of the ride`() {
        composeRule.setContent { TrailogTheme { ActivityMapScreen(viewModel(), onBack = {}) } }

        // The left edge of the plot is the start of the ride, which is the one position that does
        // not depend on how wide the chart happened to be measured.
        composeRule.onNodeWithContentDescription(CHART_CD).performTouchInput { click(centerLeft) }
        composeRule.waitForIdle()

        // The readout replaces the invitation, and says where and when the cursor is.
        composeRule.onNodeWithText("Distance").assertIsDisplayed()
        composeRule.onNodeWithText("Elapsed time").assertIsDisplayed()
        composeRule.onNodeWithText("0 m").assertIsDisplayed()
        composeRule.onNodeWithText("0:00").assertIsDisplayed()
    }

    @Test fun `touching a second place moves the cursor rather than adding one`() {
        composeRule.setContent { TrailogTheme { ActivityMapScreen(viewModel(), onBack = {}) } }

        composeRule.onNodeWithContentDescription(CHART_CD).performTouchInput { click(centerLeft) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("0:00").assertIsDisplayed()

        composeRule.onNodeWithContentDescription(CHART_CD).performTouchInput { click(center) }
        composeRule.waitForIdle()

        // Halfway along a 350-fix ride at 1 Hz is somewhere around the third minute; what matters
        // is that the readout followed the finger rather than staying at the start.
        composeRule.onNodeWithText("0:00").assertDoesNotExist()
        composeRule.onNodeWithText("Elapsed time").assertIsDisplayed()
    }

    @Test fun `either series can be hidden and shown again`() {
        composeRule.setContent { TrailogTheme { ActivityMapScreen(viewModel(), onBack = {}) } }

        composeRule.onNodeWithText("Speed").assertIsOn()
        composeRule.onNodeWithText("Speed").performClick()
        composeRule.onNodeWithText("Speed").assertIsOff()

        composeRule.onNodeWithText("Elevation").assertIsOn()
        composeRule.onNodeWithText("Elevation").performClick()
        composeRule.onNodeWithText("Elevation").assertIsOff()

        composeRule.onNodeWithText("Speed").performClick()
        composeRule.onNodeWithText("Speed").assertIsOn()
    }

    @Test fun `a series with nothing to plot cannot be switched on`() {
        val vm = viewModel(points = ride(altitudeAt = { null }))
        composeRule.setContent { TrailogTheme { ActivityMapScreen(vm, onBack = {}) } }

        // An activity recorded without altitude has no elevation curve. Offering a toggle that
        // does nothing would be worse than showing it disabled.
        composeRule.onNodeWithText("Elevation").assertIsNotEnabled()
        composeRule.onNodeWithText("Speed").assertIsOn()
    }

    @Test fun `a deleted activity leaves the screen`() {
        val rows = MutableStateFlow<ActivityWithStats?>(row())
        var wentBack = false
        val vm = viewModel(rows = rows)
        composeRule.setContent {
            TrailogTheme { ActivityMapScreen(vm, onBack = { wentBack = true }) }
        }

        rows.value = null
        composeRule.waitForIdle()

        assertTrue("the map of an activity that no longer exists must not stay open", wentBack)
    }

    // ---- navigation ----------------------------------------------------------------------------

    @Test fun `the detail map opens fullscreen and comes back through a real NavHost`() {
        // Built once, outside the composition: viewModel() here would hand each recomposition a
        // fresh state holder.
        val detailVm = viewModel()
        val mapVm = viewModel()
        composeRule.setContent {
            val navController = rememberNavController()
            TrailogTheme {
                NavHost(navController = navController, startDestination = "activity/$ACTIVITY_ID") {
                    composable(
                        route = "activity/{activityId}",
                        arguments = listOf(navArgument("activityId") { type = NavType.StringType }),
                    ) {
                        ActivityDetailScreen(
                            viewModel = detailVm,
                            onBack = {},
                            onOpenMap = { navController.navigate("activity/$ACTIVITY_ID/map") },
                        )
                    }
                    composable(
                        route = "activity/{activityId}/map",
                        arguments = listOf(navArgument("activityId") { type = NavType.StringType }),
                    ) {
                        ActivityMapScreen(
                            viewModel = mapVm,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }

        // Tapping the preview on the detail screen is what opens the fullscreen map.
        composeRule.onNodeWithContentDescription("Map of the recorded route").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(CHART_CD).assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        // The detail screen's own content is back: the summary the fullscreen map does not show.
        composeRule.onNodeWithText("Summary").assertIsDisplayed()
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun viewModel(
        rows: MutableStateFlow<ActivityWithStats?> = MutableStateFlow(row()),
        points: List<RawPoint> = ride(),
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
        saveMetadata = { _, _, _, _ -> },
        deleteActivity = { true },
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
        // No cached statistics row: the fullscreen map reads the profiles, which are always
        // computed from raw points, so the cache is beside the point here.
        stats = null,
    )

    private companion object {
        const val ACTIVITY_ID = "019fe178-0000-7000-8000-000000000001"
        const val START_TIME = 1_700_000_000_000L
        const val CHART_CD =
            "Elevation and speed against distance. Touch or drag to select a point on the route."
    }
}
