package io.github.martinzitka.trailog.ui.sensors

import io.github.martinzitka.trailog.core.recording.RecordingState

/**
 * One observation of everything the diagnostics screen can watch, as delivered by a [SensorProbe].
 * Deliberately free of any coordinate: a diagnostics readout is exactly the kind of thing that ends
 * up in a screenshot attached to a bug report, and CLAUDE.md keeps positions out of that path.
 * Accuracy, satellite counts and pressure say everything useful about signal health without saying
 * where the phone is.
 *
 * All SI — metres, Pascals. Every field is nullable because "not observed yet" is a real state the
 * screen must render rather than hide.
 *
 * @property provider the platform location provider that produced the last fix, e.g. `gps`.
 * @property accuracy the last fix's reported horizontal accuracy, in metres.
 * @property satellitesUsed satellites contributing to the fix.
 * @property satellitesVisible satellites the receiver can see at all.
 * @property lastFixAtMillis wall-clock time of the last fix, for deriving its age.
 * @property hasBarometer whether this device has a pressure sensor at all — many phones do not.
 * @property pressure the last barometer reading, in Pascals.
 */
data class SensorSnapshot(
    val provider: String? = null,
    val accuracy: Double? = null,
    val satellitesUsed: Int? = null,
    val satellitesVisible: Int? = null,
    val lastFixAtMillis: Long? = null,
    val hasBarometer: Boolean = false,
    val pressure: Double? = null,
)

/**
 * The groups the rows are shown under. Adding a sensor means adding a [DiagnosticId] to an existing
 * group, not adding a group.
 */
enum class DiagnosticGroupId { GPS, SENSORS, RECORDING }

/**
 * One readout. The screen owns the label and the rendering for each id; the ViewModel owns the
 * value. Adding a new sensor later is: one entry here, one branch in the screen's label and value
 * mappings, one string resource — never a change to the screen's structure (M1.5 acceptance).
 */
enum class DiagnosticId {
    PROVIDER,
    ACCURACY,
    SATELLITES,
    FIX_AGE,
    BAROMETER,
    SESSION_STATE,
    SERVICE_RUNNING,
    PERMISSION_FINE,
    PERMISSION_BACKGROUND,
    PERMISSION_NOTIFICATIONS,
    BATTERY_OPTIMISATION,
}

/**
 * A row's value, kept as typed SI data rather than a string. Formatting happens at the display
 * edge through `Format` (CLAUDE.md: no formatting logic in a ViewModel), and localised prose comes
 * from string resources in the screen.
 */
sealed interface DiagnosticValue {

    /** The source is working but has produced nothing yet — waiting for a first fix, say. */
    data object Waiting : DiagnosticValue

    /** This device has no such sensor. Distinct from [Waiting]: no value is ever coming. */
    data object Unavailable : DiagnosticValue

    /** A raw platform identifier shown verbatim, such as a location provider name. */
    data class Text(val value: String) : DiagnosticValue

    /** A length in metres — reported GPS accuracy. */
    data class Length(val meters: Double) : DiagnosticValue

    /** An age in whole seconds — how stale the last fix is. */
    data class Age(val seconds: Long) : DiagnosticValue

    /** A pressure in Pascals. */
    data class Pressure(val pascals: Double) : DiagnosticValue

    /** A count out of an optional total — satellites used out of satellites visible. */
    data class Ratio(val value: Int, val outOf: Int?) : DiagnosticValue

    /** A permission or exemption the user has or has not given. */
    data class Granted(val granted: Boolean) : DiagnosticValue

    /** Whether the recording foreground service is alive in this process. */
    data class Running(val running: Boolean) : DiagnosticValue

    /** The durable recording session's state, or null when there is no session. */
    data class Session(val state: RecordingState?) : DiagnosticValue
}

/**
 * Whether a value is healthy, worth a warning, or simply informational. Derived in the ViewModel so
 * the thresholds are unit-tested rather than buried in a Composable.
 */
enum class DiagnosticStatus { GOOD, WARN, NEUTRAL }

data class DiagnosticRow(
    val id: DiagnosticId,
    val value: DiagnosticValue,
    val status: DiagnosticStatus = DiagnosticStatus.NEUTRAL,
)

data class DiagnosticGroup(
    val id: DiagnosticGroupId,
    val rows: List<DiagnosticRow>,
)

/**
 * The whole Sensors screen as data. There is no empty or error case: a diagnostics screen always
 * has something to say, and "not granted" or "waiting for a fix" is itself the diagnosis.
 *
 * @property groups the readouts, in display order.
 * @property locationPermissionMissing true when precise location is not granted, so the GPS rows
 *   cannot fill in. The screen surfaces this as an actionable card rather than leaving the reader
 *   to wonder why every GPS row says "waiting".
 */
data class SensorsUiState(
    val groups: List<DiagnosticGroup>,
    val locationPermissionMissing: Boolean,
)
