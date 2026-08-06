package io.github.martinzitka.trailog.core.recording

/**
 * The state of a recording session. A session is a row in the database (CLAUDE.md: session
 * state lives in the database, never in memory or SharedPreferences), so any process — the
 * app after a cold start, a boot receiver — can read the current state and work out what was
 * happening.
 *
 * - [IDLE]: nothing is being recorded. No live session exists.
 * - [RECORDING]: fixes are arriving and being persisted into the current segment.
 * - [PAUSED]: the user paused. No fixes are recorded; resuming opens a **new** segment so no
 *   statistic ever interpolates across the pause gap.
 * - [STOPPING]: the user asked to stop and confirmed; the session is being finalised into an
 *   activity. A transient state.
 * - [RECOVERING]: a cold start (or a boot) found an interrupted session and the recovery
 *   policy is being applied — resume into a new segment, prompt the user, or finalise.
 */
enum class RecordingState {
    IDLE,
    RECORDING,
    PAUSED,
    STOPPING,
    RECOVERING,
}

/**
 * The commands that drive the [RecordingState] machine. Payloads (activity type, timestamps)
 * are the engine's concern; the state machine only decides which state changes are legal.
 */
enum class RecordingCommand {
    /** Begin a fresh recording. */
    START,

    /** Pause an active recording. */
    PAUSE,

    /** Resume a paused recording (into a new segment). */
    RESUME,

    /** The user confirmed they want to stop. */
    REQUEST_STOP,

    /** Finish finalising, or finalise an interrupted session without resuming. */
    FINALIZE,

    /** A cold start or boot found a session that was left recording or paused. */
    DETECT_INTERRUPTED,

    /** Resume an interrupted session (into a new segment). */
    RECOVER_RESUME,
}

/** Thrown when a [RecordingCommand] is not legal in the current [RecordingState]. */
class IllegalRecordingTransition(
    val from: RecordingState,
    val command: RecordingCommand,
) : IllegalStateException("Cannot apply $command while $from")

/**
 * The recording session state machine. Pure and total: every legal (state, command) pair maps
 * to exactly one next state, and every illegal pair is rejected. Both branches are tested.
 *
 * Legal transitions:
 * ```
 * IDLE       + START              -> RECORDING
 * IDLE       + DETECT_INTERRUPTED -> RECOVERING
 * RECORDING  + PAUSE              -> PAUSED
 * RECORDING  + REQUEST_STOP       -> STOPPING
 * PAUSED     + RESUME             -> RECORDING
 * PAUSED     + REQUEST_STOP       -> STOPPING
 * STOPPING   + FINALIZE           -> IDLE
 * RECOVERING + RECOVER_RESUME     -> RECORDING
 * RECOVERING + FINALIZE           -> IDLE
 * ```
 */
object RecordingStateMachine {

    /** The next state for [command] applied in [from], or null if the transition is illegal. */
    fun nextOrNull(from: RecordingState, command: RecordingCommand): RecordingState? =
        when (from) {
            RecordingState.IDLE -> when (command) {
                RecordingCommand.START -> RecordingState.RECORDING
                RecordingCommand.DETECT_INTERRUPTED -> RecordingState.RECOVERING
                else -> null
            }
            RecordingState.RECORDING -> when (command) {
                RecordingCommand.PAUSE -> RecordingState.PAUSED
                RecordingCommand.REQUEST_STOP -> RecordingState.STOPPING
                else -> null
            }
            RecordingState.PAUSED -> when (command) {
                RecordingCommand.RESUME -> RecordingState.RECORDING
                RecordingCommand.REQUEST_STOP -> RecordingState.STOPPING
                else -> null
            }
            RecordingState.STOPPING -> when (command) {
                RecordingCommand.FINALIZE -> RecordingState.IDLE
                else -> null
            }
            RecordingState.RECOVERING -> when (command) {
                RecordingCommand.RECOVER_RESUME -> RecordingState.RECORDING
                RecordingCommand.FINALIZE -> RecordingState.IDLE
                else -> null
            }
        }

    /** True if [command] is legal in [from]. */
    fun canApply(from: RecordingState, command: RecordingCommand): Boolean =
        nextOrNull(from, command) != null

    /**
     * The next state for [command] applied in [from].
     * @throws IllegalRecordingTransition if the transition is not legal.
     */
    fun next(from: RecordingState, command: RecordingCommand): RecordingState =
        nextOrNull(from, command) ?: throw IllegalRecordingTransition(from, command)
}
