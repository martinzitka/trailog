package io.github.martinzitka.trailog.core.recording

import io.github.martinzitka.trailog.core.model.ActivityType
import kotlinx.datetime.Instant
import java.util.UUID

/**
 * A recording session: the durable record of an in-progress (or just-interrupted) recording.
 * CLAUDE.md requires this to live in the database, never in memory or SharedPreferences, so
 * that any process can read it and reconstruct what was happening. This is the platform-free
 * domain view of that row; the Android layer maps it to and from Room.
 *
 * One session records into exactly one [activityId]. Fixes are written with a
 * [currentSegmentIndex]; a pause/resume or a recovery opens a new segment (increments the
 * index) so that no statistic ever interpolates across the gap.
 *
 * @property activityId the client-generated UUIDv7 of the activity being recorded. Fixed for
 *   the session's life; the server never mints it.
 * @property type the activity type; affects pause thresholds and some statistics.
 * @property state the current [RecordingState].
 * @property startTime when recording began (from the clock, not a fix — a session may start
 *   before the first fix arrives).
 * @property currentSegmentIndex the segment index new fixes are written with.
 * @property lastFixTime the timestamp of the most recently recorded fix, or null if none yet.
 *   This is what the recovery policy measures the gap from.
 * @property heartbeat the last time the recording service reported itself alive. Lets a later
 *   recovery distinguish a service that was dead from one that was merely getting no signal.
 */
data class RecordingSession(
    val activityId: UUID,
    val type: ActivityType,
    val state: RecordingState,
    val startTime: Instant,
    val currentSegmentIndex: Int = 0,
    val lastFixTime: Instant? = null,
    val heartbeat: Instant? = null,
) {
    /** Applies [command] to [state], returning the updated session. */
    fun apply(command: RecordingCommand): RecordingSession =
        copy(state = RecordingStateMachine.next(state, command))

    /**
     * Opens a new segment: increments [currentSegmentIndex]. Called whenever recording resumes
     * after a gap — a manual resume or a recovery — so subsequent fixes land in a fresh segment
     * and statistics never draw a line across the gap.
     */
    fun startNewSegment(): RecordingSession =
        copy(currentSegmentIndex = currentSegmentIndex + 1)

    /** Records that a fix at [time] was just persisted. */
    fun withFixAt(time: Instant): RecordingSession =
        copy(lastFixTime = time)

    /** Records a liveness heartbeat at [time]. */
    fun withHeartbeatAt(time: Instant): RecordingSession =
        copy(heartbeat = time)

    /** True if this session was left mid-recording and needs the recovery policy applied. */
    fun isInterrupted(): Boolean =
        state == RecordingState.RECORDING || state == RecordingState.PAUSED

    companion object {
        /**
         * Starts a fresh session: [RecordingState.RECORDING], segment 0, no fixes yet, with the
         * initial heartbeat at [now].
         */
        fun start(activityId: UUID, type: ActivityType, now: Instant): RecordingSession =
            RecordingSession(
                activityId = activityId,
                type = type,
                state = RecordingState.RECORDING,
                startTime = now,
                currentSegmentIndex = 0,
                lastFixTime = null,
                heartbeat = now,
            )
    }
}
