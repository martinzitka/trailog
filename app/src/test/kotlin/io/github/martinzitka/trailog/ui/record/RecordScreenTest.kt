package io.github.martinzitka.trailog.ui.record

import android.Manifest
import android.content.Context
import android.os.PowerManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.recording.RecordingEngine
import io.github.martinzitka.trailog.core.recording.RecordingSession
import io.github.martinzitka.trailog.core.recording.RecordingState
import io.github.martinzitka.trailog.ui.theme.TrailogTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Compose UI test for the Record screen's permission-driven states. Robolectric drives Compose as
 * a JVM test (no emulator). It verifies the two idle cases the plan calls out: recording is
 * blocked and clearly explained when location is missing, and becomes available with the Start
 * control once permissions are granted.
 *
 * The ViewModel is built with fakes and an empty ticker so no live-stats loop runs during the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordScreenTest {

    @get:Rule val composeRule = createComposeRule()

    /** Every metadata write the screen caused, in order. */
    private val saved = mutableListOf<Triple<String, String, String?>>()

    private fun viewModel(engine: RecordingEngine = FakeEngine()) = RecordViewModel(
        engine = engine,
        loadActivity = { null },
        saveMetadata = { id, name, notes, _ -> saved += Triple(id, name, notes) },
        settings = FakeSettings(ActivityType.CYCLING),
        now = { Instant.fromEpochMilliseconds(0) },
        ticker = emptyFlow(),
    )

    private fun recordingSession() = RecordingSession(
        activityId = UUID.fromString("018f0000-0000-7000-8000-000000000001"),
        type = ActivityType.CYCLING,
        state = RecordingState.RECORDING,
        startTime = Instant.fromEpochMilliseconds(0),
    )

    @Test fun `permissions missing state blocks recording and explains why`() {
        composeRule.setContent {
            TrailogTheme { RecordScreen(viewModel = viewModel()) }
        }
        composeRule.onNodeWithText("Location permission needed").assertIsDisplayed()
        composeRule.onNodeWithText("Grant location & notifications").assertIsDisplayed()
    }

    @Test fun `ready state offers the start control once permissions are granted`() {
        grantAllPermissions()
        composeRule.setContent {
            TrailogTheme { RecordScreen(viewModel = viewModel()) }
        }
        composeRule.onNodeWithText("Start recording").assertIsDisplayed()
        composeRule.onNodeWithText("Cycling").assertIsDisplayed()
    }

    @Test fun `finishing a recording offers to name it, and skipping keeps the ride`() {
        grantAllPermissions()
        val engine = FakeEngine(recordingSession())
        val vm = viewModel(engine)
        composeRule.setContent {
            TrailogTheme { RecordScreen(viewModel = vm) }
        }

        composeRule.onNodeWithText("Stop").performScrollTo().performClick()
        composeRule.onNodeWithText("Stop & save").performClick()

        composeRule.onNodeWithText("Name this ride?").assertIsDisplayed()
        composeRule.onNodeWithText("Skip").performClick()

        composeRule.onNodeWithText("Name this ride?").assertDoesNotExist()
        // The ride was finalised before the prompt appeared, and skipping wrote no metadata.
        assertEquals(1, engine.finalizeCalls)
        assertTrue(saved.isEmpty())
    }

    @Test fun `naming a finished ride writes the name and notes`() {
        grantAllPermissions()
        val vm = viewModel(FakeEngine(recordingSession()))
        composeRule.setContent {
            TrailogTheme { RecordScreen(viewModel = vm) }
        }

        composeRule.onNodeWithText("Stop").performScrollTo().performClick()
        composeRule.onNodeWithText("Stop & save").performClick()
        composeRule.onNodeWithText("Name").performTextInput("Evening loop")
        composeRule.onNodeWithText("Notes").performTextInput("Headwind the whole way back")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Name this ride?").assertDoesNotExist()
        assertEquals(
            listOf(
                Triple(
                    "018f0000-0000-7000-8000-000000000001",
                    "Evening loop",
                    "Headwind the whole way back",
                ),
            ),
            saved,
        )
    }

    private fun grantAllPermissions() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(pm).setIgnoringBatteryOptimizations(app.packageName, true)
    }

    private class FakeEngine(initial: RecordingSession? = null) : RecordingEngine {
        private val _session = MutableStateFlow(initial)
        override val session: StateFlow<RecordingSession?> = _session
        var finalizeCalls = 0
            private set

        override fun start(type: ActivityType) = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun requestStop() {
            _session.value = _session.value?.copy(state = RecordingState.STOPPING)
        }

        override fun finalize() {
            finalizeCalls++
            _session.value = null
        }

        override fun recoverResume() = Unit
        override fun record(point: RawPoint) = Unit
        override fun recoverInterruptedSession() = null
    }

    private class FakeSettings(private var type: ActivityType) : RecordSettings {
        override fun lastActivityType(): ActivityType = type
        override fun setLastActivityType(type: ActivityType) { this.type = type }
    }
}
