package io.github.martinzitka.trailog.ui.record

import android.Manifest
import android.content.Context
import android.os.PowerManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.recording.RecordingEngine
import io.github.martinzitka.trailog.core.recording.RecordingSession
import io.github.martinzitka.trailog.ui.theme.TrailogTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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

    private fun viewModel() = RecordViewModel(
        engine = FakeEngine(),
        loadActivity = { null },
        settings = FakeSettings(ActivityType.CYCLING),
        now = { Instant.fromEpochMilliseconds(0) },
        ticker = emptyFlow(),
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

    private class FakeEngine : RecordingEngine {
        override val session: StateFlow<RecordingSession?> = MutableStateFlow(null)
        override fun start(type: ActivityType) = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun requestStop() = Unit
        override fun finalize() = Unit
        override fun recoverResume() = Unit
        override fun record(point: RawPoint) = Unit
        override fun recoverInterruptedSession() = null
    }

    private class FakeSettings(private var type: ActivityType) : RecordSettings {
        override fun lastActivityType(): ActivityType = type
        override fun setLastActivityType(type: ActivityType) { this.type = type }
    }
}
