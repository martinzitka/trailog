package io.github.martinzitka.trailog.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.github.martinzitka.trailog.spike.data.SpikeDatabase
import io.github.martinzitka.trailog.spike.data.RawFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * M0 recording spike service. Requests GPS fixes at 1 Hz via the platform LocationManager
 * (no Google Play Services — see docs/adr/0002), samples the barometer if present, and
 * writes every fix to Room the instant it arrives.
 *
 * START_STICKY + stopWithTask=false + a location foreground service is the sanctioned
 * durability mechanism (CLAUDE.md). Throwaway code; the real engine is M1.3.
 */
class RecordingService : Service(), LocationListener, SensorEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationManager: LocationManager
    private var sensorManager: SensorManager? = null
    private var barometer: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var sessionId: Long = 0
    private var latestPressure: Double? = null
    private var latestSatellites: Int? = null

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
            }
            latestSatellites = used
            RecordingState.update { it.copy(satellites = used) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager?
        barometer = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "trailog:m0-recording").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L) // safety cap: 4h
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        // A new service start always begins a new session => a new segment on recovery.
        scope.launch {
            val dao = SpikeDatabase.get(applicationContext).rawFixDao()
            sessionId = (dao.maxSessionId() ?: 0) + 1
            RecordingState.update {
                it.copy(
                    serviceRunning = true,
                    sessionId = sessionId,
                    hasBarometer = barometer != null,
                    fixesThisProcess = 0,
                )
            }
            startSensors()
        }
        // START_STICKY: the system restarts the service after killing it for memory.
        return START_STICKY
    }

    private fun startSensors() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L, // 1 Hz
                0f,
                this,
            )
            locationManager.registerGnssStatusCallback(gnssCallback, null)
        } catch (e: SecurityException) {
            // Permission was revoked out from under us. Stop cleanly rather than crash.
            stopSelf()
            return
        }
        barometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onLocationChanged(location: Location) {
        val now = System.currentTimeMillis()
        val fix = RawFix(
            fixTime = location.time,
            recordedAt = now,
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else null,
            accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            speed = if (location.hasSpeed()) location.speed.toDouble() else null,
            bearing = if (location.hasBearing()) location.bearing.toDouble() else null,
            satellites = latestSatellites,
            pressure = latestPressure,
            sessionId = sessionId,
        )
        // Persist immediately; never buffer in memory (CLAUDE.md: lose nothing).
        scope.launch {
            SpikeDatabase.get(applicationContext).rawFixDao().insert(fix)
        }
        RecordingState.update {
            it.copy(
                lastFixTime = location.time,
                lastAccuracy = fix.accuracy,
                provider = location.provider ?: "-",
                fixesThisProcess = it.fixesThisProcess + 1,
            )
        }
        updateNotification(fix)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_PRESSURE) {
            // Sensor reports hectopascals; store Pascals (SI).
            val pascals = event.values[0].toDouble() * 100.0
            latestPressure = pascals
            RecordingState.update { it.copy(pressure = pascals) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Deprecated("Deprecated in Java")
    override fun onProviderDisabled(provider: String) = Unit

    override fun onDestroy() {
        try {
            locationManager.removeUpdates(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                locationManager.unregisterGnssStatusCallback(gnssCallback)
            }
        } catch (_: Exception) {
        }
        sensorManager?.unregisterListener(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        RecordingState.update { it.copy(serviceRunning = false) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- notification ---

    private fun startForegroundCompat() {
        ensureChannel()
        val notification = buildNotification("Starting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(fix: RawFix) {
        val text = "Fixes: ${RecordingState.state.value.fixesThisProcess} · " +
            "acc ${fix.accuracy?.let { "%.0fm".format(it) } ?: "-"}"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Trailog M0 recording")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Recording",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Active GPS recording" },
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "m0_recording"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
