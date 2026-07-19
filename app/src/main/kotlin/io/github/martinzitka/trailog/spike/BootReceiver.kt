package io.github.martinzitka.trailog.spike

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Reboot recovery probe for M0. `location` is not among the FGS types a BOOT_COMPLETED
 * receiver is forbidden to launch, but Android 14+ verifies the app holds the permission
 * for the service type at creation — and ACCESS_FINE_LOCATION is while-in-use only. So
 * starting a location FGS from boot requires ACCESS_BACKGROUND_LOCATION.
 *
 * This exists to VERIFY that behaviour on the developer's device (an explicit M0 acceptance
 * criterion), not to implement the real recovery policy — that is M1.3.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        val hasBackground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        // Only attempt if we actually hold background location; otherwise a start would
        // throw SecurityException on API 34+. The debug screen reports whether we got here.
        if (hasBackground) {
            RecordingService.start(context)
        }
    }
}
