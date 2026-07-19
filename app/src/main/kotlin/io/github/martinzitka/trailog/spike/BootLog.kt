package io.github.martinzitka.trailog.spike

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Dead-simple persistent breadcrumb for the reboot-recovery test. Appends timestamped
 * lines to a file in the app's private storage so we can tell, after a reboot, exactly how
 * far the boot path got — did the receiver fire, was background location held, did the
 * foreground-service start succeed or throw. Read it with:
 *   adb exec-out run-as <pkg> cat files/boot.log
 *
 * Throwaway M0 diagnostics.
 */
object BootLog {
    private const val TAG = "TrailogBoot"

    fun append(context: Context, line: String) {
        try {
            File(context.filesDir, "boot.log")
                .appendText("${System.currentTimeMillis()} $line\n")
        } catch (_: Throwable) {
            // Diagnostics must never crash the thing they diagnose.
        }
        Log.i(TAG, line)
    }
}
