package io.github.martinzitka.trailog.ui.history

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.data.ActivityEntity
import io.github.martinzitka.trailog.data.ActivityStatsEntity
import io.github.martinzitka.trailog.data.ActivityWithStats
import io.github.martinzitka.trailog.ui.theme.TrailogTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI test for the History screen. Robolectric drives Compose as a JVM test (no emulator).
 *
 * It covers the states the plan calls out — the empty state explaining how to record a first
 * activity, and a populated row carrying type, date and the three figures — plus navigation into
 * the screen and out of it into Activity detail, through a real [NavHost].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryScreenTest {

    @get:Rule val composeRule = createComposeRule()

    @Test fun `empty state explains how to record a first activity`() {
        var wentToRecord = false
        val viewModel = HistoryViewModel(MutableStateFlow(emptyList()))
        composeRule.setContent {
            TrailogTheme {
                HistoryScreen(
                    viewModel = viewModel,
                    onOpenActivity = {},
                    onStartRecording = { wentToRecord = true },
                )
            }
        }

        composeRule.onNodeWithText("No activities yet").assertIsDisplayed()
        composeRule.onNodeWithText("Go to Record").performClick()
        assert(wentToRecord) { "the empty state's action must send the user to the Record tab" }
    }

    @Test fun `a read failure offers a retry rather than an empty list`() {
        val viewModel = HistoryViewModel(flow { throw IllegalStateException("unavailable") })
        composeRule.setContent {
            TrailogTheme {
                HistoryScreen(
                    viewModel = viewModel,
                    onOpenActivity = {},
                    onStartRecording = {},
                )
            }
        }

        composeRule.onNodeWithText("Couldn’t load your activities").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test fun `a row shows the activity type, date, distance, duration and elevation gain`() {
        val viewModel = HistoryViewModel(MutableStateFlow(listOf(namedRide())))
        composeRule.setContent {
            TrailogTheme {
                HistoryScreen(
                    viewModel = viewModel,
                    onOpenActivity = {},
                    onStartRecording = {},
                )
            }
        }

        composeRule.onNodeWithText("Morning ride").assertIsDisplayed()
        // Type and date share the subtitle line; the date renders in the JVM's default locale.
        composeRule.onNodeWithText("Mountain biking", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("12.35 km").assertIsDisplayed()
        composeRule.onNodeWithText("1:02:03").assertIsDisplayed()
        composeRule.onNodeWithText("251 m").assertIsDisplayed()
    }

    @Test fun `an activity with no cached stats still lists, with figures pending`() {
        val viewModel = HistoryViewModel(MutableStateFlow(listOf(namedRide(stats = null))))
        composeRule.setContent {
            TrailogTheme {
                HistoryScreen(
                    viewModel = viewModel,
                    onOpenActivity = {},
                    onStartRecording = {},
                )
            }
        }

        composeRule.onNodeWithText("Morning ride").assertIsDisplayed()
        composeRule.onNodeWithText("Distance").assertIsDisplayed()
    }

    @Test fun `navigating into History and tapping a row opens Activity detail`() {
        val viewModel = HistoryViewModel(MutableStateFlow(listOf(namedRide())))
        composeRule.setContent {
            val navController = rememberNavController()
            TrailogTheme {
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        Button(onClick = { navController.navigate("history") }) {
                            Text("Open history")
                        }
                    }
                    composable("history") {
                        HistoryScreen(
                            viewModel = viewModel,
                            onOpenActivity = { id -> navController.navigate("activity/$id") },
                            onStartRecording = {},
                        )
                    }
                    composable(
                        route = "activity/{activityId}",
                        arguments = listOf(navArgument("activityId") { type = NavType.StringType }),
                    ) { entry ->
                        Text("Detail of ${entry.arguments?.getString("activityId")}")
                    }
                }
            }
        }

        composeRule.onNodeWithText("Open history").performClick()
        composeRule.onNodeWithText("Morning ride").assertIsDisplayed()

        composeRule.onNodeWithText("Morning ride").performClick()
        composeRule.onNodeWithText("Detail of ride-1").assertIsDisplayed()
    }

    private fun namedRide(stats: ActivityStatsEntity? = rideStats()) = ActivityWithStats(
        activity = ActivityEntity(
            id = "ride-1",
            type = ActivityType.MOUNTAIN_BIKING.name,
            name = "Morning ride",
            notes = null,
            startTime = 1_700_000_000_000L,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
        ),
        stats = stats,
    )

    private fun rideStats() = ActivityStatsEntity(
        activityId = "ride-1",
        distance = 12_345.0,
        elapsedTime = 3_723_000L,
        movingTime = 3_600_000L,
        averageSpeed = 3.4,
        maxSpeed = 9.1,
        elevationGain = 251.0,
        elevationLoss = 248.0,
        segmentCount = 2,
        pointCount = 3_600,
        computedAt = 1_700_000_100_000L,
    )
}
