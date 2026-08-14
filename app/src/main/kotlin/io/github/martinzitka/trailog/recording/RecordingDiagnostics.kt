package io.github.martinzitka.trailog.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live, non-persisted sensor diagnostics for the recording UI: signal quality that helps a user
 * judge whether recording is healthy, but which is not part of the sacred raw-point model.
 * Process-wide because the service and UI share one process. The durable truth is the database;
 * this is throwaway live state that resets each process. The real Sensors screen is M1.5.
 */
object RecordingDiagnostics {

    data class Snapshot(
        val provider: String = "-",
        val lastAccuracy: Double? = null,
        val satellitesUsed: Int? = null,
        val hasBarometer: Boolean = false,
        val pressure: Double? = null,
        /** Fixes seen since this process started — not the total in the database. */
        val fixesThisProcess: Int = 0,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun update(transform: (Snapshot) -> Snapshot) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        _state.value = Snapshot(hasBarometer = _state.value.hasBarometer)
    }
}
