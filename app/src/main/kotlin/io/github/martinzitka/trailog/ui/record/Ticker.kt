package io.github.martinzitka.trailog.ui.record

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A flow that emits once per second forever, driving the ≥1 Hz live-stats refresh on the Record
 * screen. Injected into [RecordViewModel] so tests can supply a controllable substitute instead
 * of real time.
 */
fun secondTicker(periodMillis: Long = 1_000L): Flow<Unit> = flow {
    while (true) {
        delay(periodMillis)
        emit(Unit)
    }
}
