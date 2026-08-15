package io.github.martinzitka.trailog.recording

import android.content.Context
import android.util.Log
import io.github.martinzitka.trailog.core.id.Uuid7
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.recording.IllegalRecordingTransition
import io.github.martinzitka.trailog.core.recording.RecordingCommand
import io.github.martinzitka.trailog.core.recording.RecordingEngine
import io.github.martinzitka.trailog.core.recording.RecordingSession
import io.github.martinzitka.trailog.core.recording.RecordingState
import io.github.martinzitka.trailog.core.recording.RecoveryDecision
import io.github.martinzitka.trailog.core.recording.RecoveryPolicy
import io.github.martinzitka.trailog.data.ActivityEntity
import io.github.martinzitka.trailog.data.ActivityRepository
import io.github.martinzitka.trailog.data.TrailogDatabase
import io.github.martinzitka.trailog.data.toDomain
import io.github.martinzitka.trailog.data.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * The Android implementation of [RecordingEngine]. It owns the durable session and raw-point
 * writes; the [RecordingForegroundService] is the platform mechanism that produces fixes and
 * hands each one to [record]. Both live in the same process, so this singleton is the single
 * point of truth in memory, backed by Room on disk.
 *
 * The correctness-critical policy — the five-state machine, "resume always opens a new segment",
 * and the gap-based recovery decision — lives in `:core` and is unit-tested there. This class is
 * the wiring: it maps to Room, starts and stops the service, and drives recovery on startup.
 *
 * No coordinates are ever logged (CLAUDE.md).
 */
class AndroidRecordingEngine private constructor(
    private val appContext: Context,
    db: TrailogDatabase,
) : RecordingEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val points = db.rawPointDao()
    private val sessions = db.recordingSessionDao()
    private val activities = db.activityDao()
    private val repository = ActivityRepository(db)

    private val _session = MutableStateFlow<RecordingSession?>(null)
    override val session: StateFlow<RecordingSession?> = _session.asStateFlow()

    /**
     * True while the foreground service is alive in this process. Lets [reconcileOnAppOpen]
     * tell "recording right now" from "was recording, process died" without a heartbeat guess,
     * since the app is single-process.
     */
    @Volatile
    var serviceRunning: Boolean = false
        private set

    private val recoveryMutex = Mutex()

    @Volatile
    private var startupRecoveryDone = false

    init {
        // Mirror any persisted session into memory on process (re)start.
        scope.launch {
            _session.value = sessions.active()?.toDomain()
        }
    }

    // ---- commands -------------------------------------------------------------------------

    override fun start(type: ActivityType) {
        _session.value?.let { throw IllegalRecordingTransition(it.state, RecordingCommand.START) }
        val now = Clock.System.now()
        val fresh = RecordingSession.start(Uuid7.generate(now.toEpochMilliseconds()), type, now)
        persist(fresh)
        // The activity's metadata row exists from the first moment of recording; its stats
        // cache is filled by the recompute path when the activity is finalised. The name is
        // empty until the user sets one (a display name is derived at the render edge).
        val startMillis = now.toEpochMilliseconds()
        scope.launch {
            activities.upsert(
                ActivityEntity(
                    id = fresh.activityId.toString(),
                    type = type.name,
                    name = "",
                    notes = null,
                    startTime = startMillis,
                    createdAt = startMillis,
                    updatedAt = startMillis,
                ),
            )
        }
        Log.i(TAG, "start: new session, type=$type")
        RecordingForegroundService.start(appContext)
    }

    override fun pause() {
        advance(RecordingCommand.PAUSE)
        Log.i(TAG, "pause")
        // The service stays foreground but idles GPS; capture stops because state != RECORDING.
        RecordingForegroundService.applyState(appContext)
    }

    override fun resume() {
        // A manual resume opens a new segment, exactly like a recovery (ADR 0008).
        advance(RecordingCommand.RESUME) { it.startNewSegment() }
        Log.i(TAG, "resume: new segment")
        RecordingForegroundService.applyState(appContext)
    }

    override fun requestStop() {
        advance(RecordingCommand.REQUEST_STOP)
        Log.i(TAG, "requestStop")
    }

    override fun finalize() {
        val current = _session.value ?: return
        // Validates STOPPING/RECOVERING -> IDLE; throws for anything else.
        current.apply(RecordingCommand.FINALIZE)
        _session.value = null
        val activityId = current.activityId.toString()
        scope.launch {
            sessions.delete(activityId)
            // Fill the stats cache from the raw points now the activity is complete.
            repository.recompute(activityId)
        }
        Log.i(TAG, "finalize: session cleared")
        RecordingForegroundService.stop(appContext)
    }

    override fun recoverResume() {
        advance(RecordingCommand.RECOVER_RESUME) { it.startNewSegment() }
        Log.i(TAG, "recoverResume: new segment")
        RecordingForegroundService.start(appContext)
    }

    override fun record(point: RawPoint) {
        val current = _session.value ?: return
        if (current.state != RecordingState.RECORDING) return
        val stamped = point.copy(segmentIndex = current.currentSegmentIndex)
        _session.value = current.withFixAt(point.time)
        val recordedAt = Clock.System.now().toEpochMilliseconds()
        scope.launch {
            points.insert(stamped.toEntity(current.activityId.toString(), recordedAt))
        }
    }

    // ---- service liveness & heartbeat -----------------------------------------------------

    fun onServiceCreated() {
        serviceRunning = true
    }

    fun onServiceDestroyed() {
        serviceRunning = false
    }

    /** Persist a liveness heartbeat. Called periodically by the service while it is alive. */
    fun heartbeat() {
        val current = _session.value ?: return
        val beat = current.withHeartbeatAt(Clock.System.now())
        _session.value = beat
        scope.launch { sessions.upsert(beat.toEntity()) }
    }

    // ---- recovery -------------------------------------------------------------------------

    override fun recoverInterruptedSession(): RecoveryDecision? =
        runBlocking { reconcileServiceRestart() }

    /**
     * Recovery triggered by the app being opened. If a live recording service is running in this
     * process there is nothing to recover; otherwise applies the recovery policy once per process.
     */
    fun reconcileOnAppOpen() {
        scope.launch {
            if (serviceRunning) {
                _session.value = sessions.active()?.toDomain()
                return@launch
            }
            runStartupRecovery(startServiceOnResume = true)
        }
    }

    /**
     * Recovery triggered by the service (re)starting — a START_STICKY restart or a boot. Always
     * evaluates the policy: a restart after a gap must open a new segment, never append to the
     * old one. Returns the decision so the caller (service/boot receiver) can act on it.
     */
    suspend fun reconcileServiceRestart(): RecoveryDecision? =
        runStartupRecovery(startServiceOnResume = false)

    private suspend fun runStartupRecovery(startServiceOnResume: Boolean): RecoveryDecision? =
        recoveryMutex.withLock {
            if (startupRecoveryDone) return@withLock null
            val entity = sessions.active()
            if (entity == null) {
                _session.value = null
                startupRecoveryDone = true
                return@withLock null
            }
            val persisted = entity.toDomain()
            _session.value = persisted
            if (!persisted.isInterrupted()) {
                // STOPPING/RECOVERING already: leave it for the UI. Not an automatic recovery.
                startupRecoveryDone = true
                return@withLock null
            }

            val now = Clock.System.now()
            val lastFix = points.maxTime(persisted.activityId.toString())
                ?.let { Instant.fromEpochMilliseconds(it) }
                ?: persisted.startTime
            val decision = RecoveryPolicy.decide(lastFix, now)
            Log.i(TAG, "recovery: gap decides $decision")

            // Route through the state machine: interrupted -> RECOVERING -> {RECORDING | IDLE}.
            val recovering = persisted.copy(state = RecordingState.RECOVERING)
            when (decision) {
                RecoveryDecision.ResumeIntoNewSegment -> {
                    persist(recovering.apply(RecordingCommand.RECOVER_RESUME).startNewSegment())
                    if (startServiceOnResume) RecordingForegroundService.start(appContext)
                }
                RecoveryDecision.PromptUser -> {
                    persist(recovering)
                }
                RecoveryDecision.FinalizeAutomatically -> {
                    _session.value = null
                    sessions.delete(persisted.activityId.toString())
                    // The activity still recorded points; fill its stats cache from them.
                    repository.recompute(persisted.activityId.toString())
                }
            }
            startupRecoveryDone = true
            decision
        }

    // ---- helpers --------------------------------------------------------------------------

    /** Applies [command] via the state machine, optionally transforming the result, and persists. */
    private fun advance(
        command: RecordingCommand,
        transform: (RecordingSession) -> RecordingSession = { it },
    ): RecordingSession {
        val current = _session.value
            ?: throw IllegalStateException("no active session for $command")
        val next = transform(current.apply(command))
        persist(next)
        return next
    }

    private fun persist(session: RecordingSession) {
        _session.value = session
        scope.launch { sessions.upsert(session.toEntity()) }
    }

    companion object {
        private const val TAG = "TrailogRecording"

        @Volatile
        private var instance: AndroidRecordingEngine? = null

        fun get(context: Context): AndroidRecordingEngine =
            instance ?: synchronized(this) {
                instance ?: AndroidRecordingEngine(
                    context.applicationContext,
                    TrailogDatabase.get(context),
                ).also { instance = it }
            }
    }
}
