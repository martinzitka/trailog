package io.github.martinzitka.trailog.ui.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Android side of the Sensors screen: a read-only tap on the same hardware the recorder uses.
 *
 * It registers its own location, GNSS-status and barometer listeners for as long as the screen is
 * collecting, and **never writes a point**. Diagnostics are a live view, not data; the raw-point
 * store is written by the recording service alone (ADR 0013). Running a second location listener
 * alongside a recording is deliberate — the platform multiplexes them onto one GPS session, so the
 * screen reads the same hardware without disturbing the recording or duplicating its writes.
 *
 * If precise location is not granted only the barometer is registered, and the GPS rows stay empty;
 * the screen explains why rather than silently showing nothing.
 *
 * No coordinate ever leaves this class — [SensorSnapshot] carries signal quality only (CLAUDE.md).
 */
class AndroidSensorProbe(context: Context) : SensorProbe {

    private val appContext = context.applicationContext

    override fun readings(): Flow<SensorSnapshot> = callbackFlow {
        val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager?
        val barometer = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
        val locationManager =
            appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager?

        // All callbacks land on the main looper and mutate this atomically; the collector sees a
        // consistent snapshot. Seeded with the one thing known without any callback: whether this
        // device has a barometer at all.
        val snapshots = MutableStateFlow(SensorSnapshot(hasBarometer = barometer != null))
        val relay = launch { snapshots.collect { send(it) } }

        val handler = Handler(Looper.getMainLooper())

        val locationListener = LocationListener { location: Location ->
            snapshots.update {
                it.copy(
                    provider = location.provider,
                    accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                    lastFixAtMillis = location.time,
                )
            }
        }

        val gnssCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var used = 0
                for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
                snapshots.update {
                    it.copy(satellitesUsed = used, satellitesVisible = status.satelliteCount)
                }
            }
        }

        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_PRESSURE) {
                    // The sensor reports hectopascals; everything internal is SI, so store Pascals.
                    snapshots.update { it.copy(pressure = event.values[0].toDouble() * 100.0) }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        // The permission check is not enough on its own: it can be revoked between the check and the
        // call, so the SecurityException is handled explicitly — the same pattern the recording
        // service uses. Failing to register leaves the GPS rows empty, which the screen explains.
        val locationRegistered = if (locationManager != null && appContext.hasFineLocation()) {
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_INTERVAL_MS,
                    0f,
                    locationListener,
                    Looper.getMainLooper(),
                )
                locationManager.registerGnssStatusCallback(gnssCallback, handler)
                true
            } catch (_: SecurityException) {
                false
            }
        } else {
            false
        }

        if (barometer != null) {
            sensorManager.registerListener(
                sensorListener,
                barometer,
                SensorManager.SENSOR_DELAY_UI,
                handler,
            )
        }

        awaitClose {
            if (locationRegistered) {
                runCatching { locationManager?.removeUpdates(locationListener) }
                runCatching { locationManager?.unregisterGnssStatusCallback(gnssCallback) }
            }
            if (barometer != null) sensorManager.unregisterListener(sensorListener)
            relay.cancel()
        }
    }

    private fun Context.hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        /** 1 Hz, matching the recorder — fast enough that "fix age" means something. */
        const val LOCATION_INTERVAL_MS = 1000L
    }
}
