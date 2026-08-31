package io.github.martinzitka.trailog.ui.record

import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.ui.map.TracePoint

/**
 * What the user has granted the app that bears on recording. Kept as plain booleans, read from
 * the platform by the screen and pushed into the ViewModel, so the ViewModel stays free of
 * Android types and is unit-testable.
 *
 * @property fineLocationGranted precise location — recording cannot start without it.
 * @property backgroundLocationGranted "Allow all the time" — required for reboot recovery
 *   (CLAUDE.md / ADR 0004), not for recording itself.
 * @property notificationsGranted POST_NOTIFICATIONS (Android 13+) — needed for the ongoing
 *   recording notification.
 * @property batteryOptimisationExempt whether the app is exempt from battery optimisation; when
 *   not, OEM power management may kill long recordings.
 */
data class RecordEnvironment(
    val fineLocationGranted: Boolean = false,
    val backgroundLocationGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val batteryOptimisationExempt: Boolean = false,
) {
    /** True once recording is possible at all — everything else is a warning, not a blocker. */
    val canRecord: Boolean get() = fineLocationGranted

    /**
     * The non-blocking gaps to surface as warnings while recording is possible: no background
     * location (reboot recovery won't work), no notifications, or no battery-optimisation
     * exemption. This is the "permissions partial" condition — recording works, but degraded.
     */
    val warnings: List<RecordWarning>
        get() = buildList {
            if (canRecord && !backgroundLocationGranted) add(RecordWarning.NO_BACKGROUND_LOCATION)
            if (canRecord && !notificationsGranted) add(RecordWarning.NO_NOTIFICATIONS)
            if (canRecord && !batteryOptimisationExempt) add(RecordWarning.BATTERY_NOT_EXEMPT)
        }
}

/** A non-blocking configuration gap surfaced to the user while recording is otherwise possible. */
enum class RecordWarning { NO_BACKGROUND_LOCATION, NO_NOTIFICATIONS, BATTERY_NOT_EXEMPT }

/**
 * Live derived figures for an in-progress activity, recomputed each tick from the raw points via
 * the single `:core` statistics path. All SI; the display edge formats them.
 */
data class LiveStats(
    val elapsedSeconds: Long,
    val movingSeconds: Long,
    val distance: Double,
    val currentSpeed: Double,
    val elevationGain: Double,
) {
    companion object {
        val EMPTY = LiveStats(0, 0, 0.0, 0.0, 0.0)
    }
}

/**
 * The complete state of the Record screen — the seven cases from the M1.5 plan. "Permissions
 * partial" is not a separate case: it is [Ready] (or a recording state) carrying a non-empty
 * [RecordEnvironment.warnings]. Every case carries the environment so the battery/permission
 * warnings can be shown regardless of what the recorder is doing.
 */
sealed interface RecordUiState {
    val environment: RecordEnvironment

    /** Precise location not granted: recording is impossible until it is. */
    data class PermissionsMissing(
        override val environment: RecordEnvironment,
    ) : RecordUiState

    /** Idle and able to record. [activityType] is the last-used type, pre-selected. */
    data class Ready(
        val activityType: ActivityType,
        /**
         * The activity types to offer as chips, most-recently-used first, with the current
         * selection guaranteed to be among them.
         *
         * A subset rather than every type: there are fifteen, and a chip per type pushed the
         * start button off the bottom of the screen. The rest stay reachable through the picker's
         * overflow.
         */
        val offeredTypes: List<ActivityType>,
        override val environment: RecordEnvironment,
    ) : RecordUiState

    /** Actively recording. */
    data class Recording(
        val activityType: ActivityType,
        val live: LiveStats,
        val segments: List<List<TracePoint>>,
        override val environment: RecordEnvironment,
    ) : RecordUiState

    /** Paused by the user; resuming opens a new segment. */
    data class Paused(
        val activityType: ActivityType,
        val live: LiveStats,
        val segments: List<List<TracePoint>>,
        override val environment: RecordEnvironment,
    ) : RecordUiState

    /** The transient finalising state after the user confirmed Stop. */
    data class Saving(
        override val environment: RecordEnvironment,
    ) : RecordUiState

    /** An interrupted session was found and the recovery policy is prompting the user. */
    data class InterruptedSessionFound(
        val gapSeconds: Long,
        override val environment: RecordEnvironment,
    ) : RecordUiState
}

/**
 * The "name this ride" prompt raised once a recording has been finalised (M1.8). It is deliberately
 * *not* a [RecordUiState] case: the activity is already saved by the time this appears, so the
 * screen underneath is whatever it would otherwise be — usually [RecordUiState.Ready], ready for
 * the next ride. Naming is metadata laid on top of a ride that is already safe.
 *
 * Carries the type so the prompt can write metadata without re-reading the activity;
 * `ActivityRepository.updateMetadata` takes all three fields together and the type must round-trip
 * unchanged.
 *
 * No name is pre-filled. History derives a display title from the type and date when the name is
 * blank, and storing that derived string would put a formatted value in the database (ADR 0014).
 * The type label is offered as placeholder text instead — a hint, never a value.
 */
data class NamingPrompt(
    val activityId: String,
    val activityType: ActivityType,
)
