package io.github.martinzitka.trailog.core.recording

import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.RawPoint
import kotlinx.coroutines.flow.StateFlow

/**
 * The recording engine: the platform-free contract for capturing an activity. The Android
 * foreground service is one implementation (CLAUDE.md: "the recording engine sits behind an
 * interface; the Android foreground service is one implementation") — which is what keeps iOS
 * possible later without touching this module.
 *
 * Everything here is expressed in domain types. There are no Android, Ktor or file-I/O types,
 * because `:core` stays platform-free. The engine owns the session lifecycle and the durable
 * write of raw points; callers (a ViewModel) observe [session] and issue commands.
 *
 * Contract:
 * - Raw fixes handed to the engine are persisted on arrival, exactly as delivered, never
 *   buffered in memory for the session (CLAUDE.md: lose nothing).
 * - Session state is persisted durably, not held only in memory, so any process can recover it.
 * - Recovery always opens a **new** segment; it never appends to the previous one.
 * - Commands that are illegal in the current state throw [IllegalRecordingTransition].
 */
interface RecordingEngine {

    /** The current session, or null when [RecordingState.IDLE] (nothing is being recorded). */
    val session: StateFlow<RecordingSession?>

    /**
     * Begins a fresh recording of [type]. Mints a client-side UUIDv7 activity id, persists a
     * new session in [RecordingState.RECORDING], and starts capturing fixes.
     * @throws IllegalRecordingTransition if a session is already active.
     */
    fun start(type: ActivityType)

    /** Pauses capture. No fixes are recorded until [resume]. */
    fun pause()

    /** Resumes a paused recording into a new segment. */
    fun resume()

    /**
     * The user asked to stop; moves to [RecordingState.STOPPING]. Confirmation is the caller's
     * responsibility (CLAUDE.md: an accidental stop loses a ride). Call [finalize] to complete.
     */
    fun requestStop()

    /** Finalises the session into an activity and returns to [RecordingState.IDLE]. */
    fun finalize()

    /**
     * Resumes an interrupted session that the recovery policy left in [RecordingState.RECOVERING]
     * awaiting the user's answer (the "Resume" action on the interrupted-session prompt). Opens a
     * new segment and restarts capture.
     * @throws IllegalRecordingTransition if there is no session awaiting recovery.
     */
    fun recoverResume()

    /**
     * Persists a single fix into the current segment. Called by the platform layer for each
     * location update while [RecordingState.RECORDING]. The point is stored unmodified; the
     * engine stamps it with the current segment index and updates [RecordingSession.lastFixTime].
     */
    fun record(point: RawPoint)

    /**
     * Reconciles persisted state on a cold start or boot: if an interrupted session exists,
     * evaluates the [RecoveryPolicy] against the gap since its last fix and either resumes it
     * into a new segment, moves it to [RecordingState.RECOVERING] to await the user, or
     * finalises it. Returns the decision taken, or null if there was nothing to recover.
     */
    fun recoverInterruptedSession(): RecoveryDecision?
}
