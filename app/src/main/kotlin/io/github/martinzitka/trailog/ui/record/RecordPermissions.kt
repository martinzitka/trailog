package io.github.martinzitka.trailog.ui.record

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * The Android side of the Record screen's permission handling: reads the current permission and
 * battery-optimisation state into a platform-free [RecordEnvironment], and launches the system
 * settings pages the plan requires deep-links to. Kept apart from [RecordViewModel] so the
 * ViewModel stays free of Android types.
 *
 * Version handling: background location is a distinct runtime permission only on Android 10+;
 * below that it rides along with foreground location. POST_NOTIFICATIONS is a runtime permission
 * only on Android 13+; below that notifications are granted by default.
 */
object RecordPermissions {

    fun read(context: Context): RecordEnvironment {
        val fine = context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            fine
        }
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.isGranted(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        return RecordEnvironment(
            fineLocationGranted = fine,
            backgroundLocationGranted = background,
            notificationsGranted = notifications,
            batteryOptimisationExempt = context.isIgnoringBatteryOptimizations(),
        )
    }

    /** The foreground permissions requested together at first: fine location and notifications. */
    fun foregroundPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    /** The background-location permission, or null on versions where it is not requested separately. */
    fun backgroundPermission(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        } else {
            null
        }

    /** Open the app's system settings page — where "Allow all the time" is granted on Android 10+. */
    fun openAppSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    /** Prompt the system dialog to exempt the app from battery optimisation. */
    fun requestBatteryExemption(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    private fun Context.isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun Context.isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }
}
