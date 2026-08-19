package io.github.martinzitka.trailog.ui.sensors

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.martinzitka.trailog.core.recording.RecordingSession
import io.github.martinzitka.trailog.core.recording.RecordingState
import io.github.martinzitka.trailog.recording.AndroidRecordingEngine
import io.github.martinzitka.trailog.ui.record.RecordEnvironment
import io.github.martinzitka.trailog.ui.record.secondTicker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * The Sensors screen's state holder. It folds four live inputs — the [SensorProbe]'s snapshots, the
 * permission/battery [RecordEnvironment] pushed in by the screen, the durable recording session,
 * and a one-second ticker — into the flat list of diagnostic rows the screen renders.
 *
 * The ticker matters as much as the probe: fix age grows while nothing arrives, and a screen that
 * only updated when a fix landed would show a reassuring "2s" forever after the signal died.
 *
 * Like [io.github.martinzitka.trailog.ui.record.RecordViewModel] it holds no Android types — the
 * probe is an interface and permissions arrive as booleans — so [reduce], where all the threshold
 * logic lives, is a pure function tested without a device.
 *
 * The probe is only collected while the screen is subscribed, so leaving the tab releases GPS.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SensorsViewModel(
    probe: SensorProbe,
    session: Flow<RecordingSession?>,
    private val serviceRunning: () -> Boolean,
    private val now: () -> Instant = { Clock.System.now() },
    ticker: Flow<Unit> = secondTicker(),
) : ViewModel() {

    private val environment = MutableStateFlow(RecordEnvironment())

    // Restart the probe when location permission changes: a probe started while location was
    // denied registered no location listener and would never recover on its own.
    private val snapshots: Flow<SensorSnapshot> = environment
        .map { it.fineLocationGranted }
        .distinctUntilChanged()
        .flatMapLatest { probe.readings() }
        .onStart { emit(SensorSnapshot()) }

    val uiState: StateFlow<SensorsUiState> =
        combine(snapshots, environment, session, ticker.onStart { emit(Unit) }) { snap, env, sess, _ ->
            reduce(snap, env, sess?.state, serviceRunning(), now())
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = reduce(
                SensorSnapshot(), RecordEnvironment(), null, serviceRunning(), now(),
            ),
        )

    /** Push the latest permission / battery-optimisation state, read by the screen from the OS. */
    fun updateEnvironment(env: RecordEnvironment) {
        environment.value = env
    }

    // ---- derivation -----------------------------------------------------------------------

    /**
     * Pure mapping from the live inputs to the rows on screen, thresholds included. A missing value
     * is [DiagnosticValue.Waiting] when it could still arrive and [DiagnosticValue.Unavailable]
     * when the hardware cannot produce it — the difference is the whole point of the barometer row.
     */
    internal fun reduce(
        snapshot: SensorSnapshot,
        env: RecordEnvironment,
        recordingState: RecordingState?,
        serviceAlive: Boolean,
        now: Instant,
    ): SensorsUiState {
        val fixAge = snapshot.lastFixAtMillis
            ?.let { (now.toEpochMilliseconds() - it) / 1000 }
            ?.coerceAtLeast(0)

        val gps = listOf(
            DiagnosticRow(
                id = DiagnosticId.PROVIDER,
                value = snapshot.provider?.let { DiagnosticValue.Text(it) } ?: DiagnosticValue.Waiting,
            ),
            DiagnosticRow(
                id = DiagnosticId.ACCURACY,
                value = snapshot.accuracy?.let { DiagnosticValue.Length(it) } ?: DiagnosticValue.Waiting,
                status = snapshot.accuracy?.let { accuracyStatus(it) } ?: DiagnosticStatus.NEUTRAL,
            ),
            DiagnosticRow(
                id = DiagnosticId.SATELLITES,
                value = snapshot.satellitesUsed
                    ?.let { DiagnosticValue.Ratio(it, snapshot.satellitesVisible) }
                    ?: DiagnosticValue.Waiting,
                status = snapshot.satellitesUsed?.let { satelliteStatus(it) } ?: DiagnosticStatus.NEUTRAL,
            ),
            DiagnosticRow(
                id = DiagnosticId.FIX_AGE,
                value = fixAge?.let { DiagnosticValue.Age(it) } ?: DiagnosticValue.Waiting,
                status = fixAge?.let { fixAgeStatus(it) } ?: DiagnosticStatus.NEUTRAL,
            ),
        )

        val sensors = listOf(
            DiagnosticRow(
                id = DiagnosticId.BAROMETER,
                value = when {
                    !snapshot.hasBarometer -> DiagnosticValue.Unavailable
                    snapshot.pressure == null -> DiagnosticValue.Waiting
                    else -> DiagnosticValue.Pressure(snapshot.pressure)
                },
            ),
        )

        // A session that believes it is recording while the service is dead is the failure this
        // screen exists to catch, so that pairing — and only that pairing — is a warning.
        val sessionActive = recordingState == RecordingState.RECORDING
        val recording = listOf(
            DiagnosticRow(
                id = DiagnosticId.SESSION_STATE,
                value = DiagnosticValue.Session(recordingState),
            ),
            DiagnosticRow(
                id = DiagnosticId.SERVICE_RUNNING,
                value = DiagnosticValue.Running(serviceAlive),
                status = when {
                    sessionActive && !serviceAlive -> DiagnosticStatus.WARN
                    serviceAlive -> DiagnosticStatus.GOOD
                    else -> DiagnosticStatus.NEUTRAL
                },
            ),
            grantRow(DiagnosticId.PERMISSION_FINE, env.fineLocationGranted),
            grantRow(DiagnosticId.PERMISSION_BACKGROUND, env.backgroundLocationGranted),
            grantRow(DiagnosticId.PERMISSION_NOTIFICATIONS, env.notificationsGranted),
            grantRow(DiagnosticId.BATTERY_OPTIMISATION, env.batteryOptimisationExempt),
        )

        return SensorsUiState(
            groups = listOf(
                DiagnosticGroup(DiagnosticGroupId.GPS, gps),
                DiagnosticGroup(DiagnosticGroupId.SENSORS, sensors),
                DiagnosticGroup(DiagnosticGroupId.RECORDING, recording),
            ),
            locationPermissionMissing = !env.fineLocationGranted,
        )
    }

    private fun grantRow(id: DiagnosticId, granted: Boolean) = DiagnosticRow(
        id = id,
        value = DiagnosticValue.Granted(granted),
        status = if (granted) DiagnosticStatus.GOOD else DiagnosticStatus.WARN,
    )

    /**
     * Assembles a [SensorsViewModel] wired to the real probe and recording engine. A
     * [ViewModelProvider.Factory] so the screen gets a lifecycle-scoped instance.
     */
    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val engine = AndroidRecordingEngine.get(appContext)
            return SensorsViewModel(
                probe = AndroidSensorProbe(appContext),
                session = engine.session,
                serviceRunning = { engine.serviceRunning },
            ) as T
        }
    }

    internal companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Reported accuracy at or below this is a healthy open-sky fix, in metres. */
        const val ACCURACY_GOOD = 20.0

        /** Above this the fix is too vague to trust for distance, in metres. */
        const val ACCURACY_WARN = 50.0

        /** Fixes this fresh mean the receiver is keeping up, in seconds. */
        const val FIX_AGE_GOOD = 10L

        /** Nothing for this long means the signal is gone, in seconds. */
        const val FIX_AGE_WARN = 60L

        /** Fewer satellites than this in the fix and the position is fragile. */
        const val SATELLITES_GOOD = 6

        fun accuracyStatus(meters: Double): DiagnosticStatus = when {
            meters <= ACCURACY_GOOD -> DiagnosticStatus.GOOD
            meters <= ACCURACY_WARN -> DiagnosticStatus.NEUTRAL
            else -> DiagnosticStatus.WARN
        }

        fun fixAgeStatus(seconds: Long): DiagnosticStatus = when {
            seconds <= FIX_AGE_GOOD -> DiagnosticStatus.GOOD
            seconds <= FIX_AGE_WARN -> DiagnosticStatus.NEUTRAL
            else -> DiagnosticStatus.WARN
        }

        fun satelliteStatus(used: Int): DiagnosticStatus = when {
            used >= SATELLITES_GOOD -> DiagnosticStatus.GOOD
            used > 0 -> DiagnosticStatus.NEUTRAL
            else -> DiagnosticStatus.WARN
        }
    }
}
