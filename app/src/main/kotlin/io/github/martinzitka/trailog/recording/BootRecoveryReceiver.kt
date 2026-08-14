package io.github.martinzitka.trailog.recording

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.martinzitka.trailog.core.recording.RecoveryDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Best-effort recovery after a device reboot (ADR 0004: data-loss-free reboot is mandatory and
 * already guaranteed by on-arrival writes; automatic restart is best-effort, never relied upon).
 *
 * Starting a `location` foreground service from here requires `ACCESS_BACKGROUND_LOCATION` on
 * Android 14+ (the system verifies the permission for the service type at creation, and
 * `ACCESS_FINE_LOCATION` is while-in-use only). If it is not granted we do nothing and leave the
 * interrupted session for the app-open recovery path to surface — never mutate state we cannot
 * act on.
 *
 * Only `BOOT_COMPLETED` (post-unlock) is handled, not `LOCKED_BOOT_COMPLETED`: the Room database
 * lives in credential-encrypted storage and is unreadable before first unlock.
 */
class BootRecoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        if (!hasBackgroundLocation(context)) {
            Log.i(TAG, "boot: no background location; leaving recovery to app open")
            return
        }

        val appContext = context.applicationContext
        val engine = AndroidRecordingEngine.get(appContext)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val decision = engine.reconcileServiceRestart()
                Log.i(TAG, "boot recovery decision=$decision")
                if (decision is RecoveryDecision.ResumeIntoNewSegment) {
                    try {
                        RecordingForegroundService.start(appContext)
                    } catch (t: Throwable) {
                        // OEM boot broadcasts are flaky; a refusal degrades to the manual path.
                        Log.w(TAG, "boot: service start refused ${t.javaClass.simpleName}")
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun hasBackgroundLocation(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    companion object {
        private const val TAG = "TrailogRecording"
    }
}
