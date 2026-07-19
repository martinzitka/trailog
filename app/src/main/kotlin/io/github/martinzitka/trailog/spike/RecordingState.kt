package io.github.martinzitka.trailog.spike

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide live diagnostics for the M0 debug screen. The service and the Activity run
 * in the same process, so a simple observable singleton is enough here — persistent state
 * lives in Room. This is throwaway M0 scaffolding.
 */
object RecordingState {

    data class Snapshot(
        val serviceRunning: Boolean = false,
        val sessionId: Long = 0,
        /** epoch millis of the most recent fix seen this process, or 0. */
        val lastFixTime: Long = 0,
        val lastAccuracy: Double? = null,
        val satellites: Int? = null,
        val hasBarometer: Boolean = false,
        val pressure: Double? = null,
        /** Fixes ingested since this process started (not total in DB). */
        val fixesThisProcess: Int = 0,
        val provider: String = "-",
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun update(transform: (Snapshot) -> Snapshot) {
        _state.value = transform(_state.value)
    }
}
