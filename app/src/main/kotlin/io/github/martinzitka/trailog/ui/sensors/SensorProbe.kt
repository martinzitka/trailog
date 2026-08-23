package io.github.martinzitka.trailog.ui.sensors

import kotlinx.coroutines.flow.Flow

/**
 * The source of live [SensorSnapshot]s for the Sensors screen. An interface so [SensorsViewModel]
 * stays free of Android types and can be unit-tested against a scripted flow.
 *
 * Contract: [readings] emits an initial snapshot immediately on collection (even if it carries
 * nothing but the barometer's presence), then a fresh one whenever anything changes. Collecting
 * acquires the sensors; cancelling releases them — so the probe only runs while the screen is on
 * screen.
 */
fun interface SensorProbe {
    fun readings(): Flow<SensorSnapshot>
}
