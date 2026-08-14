package io.github.martinzitka.trailog.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.martinzitka.trailog.R
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.recording.RecordingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * The location foreground service — the sanctioned recording mechanism (CLAUDE.md). It produces
 * fixes at 1 Hz via the platform LocationManager (no Play Services, per ADR 0002) and hands each
 * one to the [AndroidRecordingEngine], which owns the durable write. The service holds no
 * recording state of its own beyond a wake lock and the sensor registrations; the session lives
 * in the database, so a START_STICKY restart or a boot reconstructs everything from there.
 *
 * `stopWithTask="false"` + `START_STICKY` + the location FGS type are declared in the manifest.
 * On a bare restart (null intent) the service asks the engine to reconcile — a gap since the
 * last fix always opens a new segment, never appends to the old one.
 *
 * No coordinates are logged (CLAUDE.md).
 */
class RecordingForegroundService : LifecycleService(), LocationListener, SensorEventListener {

    private lateinit var engine: AndroidRecordingEngine
    private lateinit var locationManager: LocationManager
    private var sensorManager: SensorManager? = null
    private var barometer: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var locationActive = false
    private var latestPressure: Double? = null

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            RecordingDiagnostics.update { it.copy(satellitesUsed = used) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        engine = AndroidRecordingEngine.get(this)
        engine.onServiceCreated()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager?
        barometer = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
        RecordingDiagnostics.update { it.copy(hasBarometer = barometer != null) }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "trailog:recording").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        startHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        try {
            startForegroundCompat()
        } catch (t: Throwable) {
            // A location FGS start from the background can be refused on Android 12+.
            Log.w(TAG, "startForeground refused: ${t.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent == null) {
            // Bare restart by the system after a kill: reconcile before recording so the gap
            // opens a new segment instead of appending to the old one.
            Log.i(TAG, "restarted by system; reconciling")
            lifecycleScope.launch {
                engine.reconcileServiceRestart()
                applyState()
            }
        } else {
            applyState()
        }
        // START_STICKY: the system restarts the service after killing it for memory.
        return START_STICKY
    }

    /** Starts or stops GPS capture to match the engine's current state; stops the service if idle. */
    private fun applyState() {
        when (engine.session.value?.state) {
            RecordingState.RECORDING -> startLocationUpdates()
            RecordingState.PAUSED -> stopLocationUpdates() // stay foreground, idle GPS
            else -> {
                // IDLE / STOPPING / RECOVERING / no session: nothing to capture.
                stopLocationUpdates()
                stopSelf()
            }
        }
        updateNotification()
    }

    private fun startLocationUpdates() {
        if (locationActive) return
        val mainLooper = Looper.getMainLooper()
        val mainHandler = Handler(mainLooper)
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_INTERVAL_MS,
                0f,
                this,
                mainLooper,
            )
            locationManager.registerGnssStatusCallback(gnssCallback, mainHandler)
        } catch (e: SecurityException) {
            Log.w(TAG, "location permission revoked; stopping")
            stopSelf()
            return
        }
        barometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, mainHandler)
        }
        locationActive = true
    }

    private fun stopLocationUpdates() {
        if (!locationActive) return
        try {
            locationManager.removeUpdates(this)
            locationManager.unregisterGnssStatusCallback(gnssCallback)
        } catch (_: Exception) {
        }
        sensorManager?.unregisterListener(this)
        locationActive = false
    }

    override fun onLocationChanged(location: Location) {
        val point = RawPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else null,
            accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            time = Instant.fromEpochMilliseconds(location.time),
            speed = if (location.hasSpeed()) location.speed.toDouble() else null,
            bearing = if (location.hasBearing()) location.bearing.toDouble() else null,
            pressure = latestPressure,
            // segmentIndex is stamped by the engine from the current session.
        )
        engine.record(point)
        RecordingDiagnostics.update {
            it.copy(
                provider = location.provider ?: "-",
                lastAccuracy = point.accuracy,
                fixesThisProcess = it.fixesThisProcess + 1,
            )
        }
        updateNotification()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_PRESSURE) {
            // Sensor reports hectopascals; store Pascals (SI).
            val pascals = event.values[0].toDouble() * 100.0
            latestPressure = pascals
            RecordingDiagnostics.update { it.copy(pressure = pascals) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Deprecated("Deprecated in Java")
    override fun onProviderDisabled(provider: String) = Unit

    private fun startHeartbeat() {
        lifecycleScope.launch {
            while (true) {
                engine.heartbeat()
                updateNotification()
                delay(HEARTBEAT_INTERVAL)
            }
        }
    }

    override fun onDestroy() {
        stopLocationUpdates()
        wakeLock?.let { if (it.isHeld) it.release() }
        engine.onServiceDestroyed()
        super.onDestroy()
    }

    // ---- notification ---------------------------------------------------------------------

    private fun startForegroundCompat() {
        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val session = engine.session.value
        val diag = RecordingDiagnostics.state.value
        val paused = session?.state == RecordingState.PAUSED
        val elapsed = session?.let { formatElapsed(Clock.System.now() - it.startTime) } ?: "—"
        val title = if (paused) {
            getString(R.string.recording_notification_paused)
        } else {
            getString(R.string.recording_notification_active)
        }
        val text = getString(
            R.string.recording_notification_text,
            elapsed,
            diag.fixesThisProcess,
            diag.lastAccuracy?.let { "%.0f".format(it) } ?: "—",
        )
        val tapIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pending = tapIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun formatElapsed(d: kotlin.time.Duration): String {
        val total = d.inWholeSeconds.coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.recording_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.recording_channel_description) },
            )
        }
    }

    companion object {
        private const val TAG = "TrailogRecording"
        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1
        private const val LOCATION_INTERVAL_MS = 1000L // 1 Hz
        private const val WAKE_LOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000 // 12 h safety cap
        private val HEARTBEAT_INTERVAL = 10.seconds

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RecordingForegroundService::class.java))
        }

        /** Ask a running service to re-evaluate the engine state (e.g. after pause/resume). */
        fun applyState(context: Context) = start(context)

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingForegroundService::class.java))
        }
    }
}
