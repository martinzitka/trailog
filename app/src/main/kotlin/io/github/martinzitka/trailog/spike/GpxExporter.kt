package io.github.martinzitka.trailog.spike

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.martinzitka.trailog.spike.data.RawFix
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Dumps all recorded fixes to a GPX file in shared storage so it can be opened in a
 * third-party tool. M0 acceptance requires the exported track to look right elsewhere.
 *
 * Each recording session becomes a separate <trkseg>, so gaps show as segment breaks
 * rather than straight lines — the segment-awareness the real code must preserve.
 */
object GpxExporter {

    private fun iso(millis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(millis))
    }

    fun buildGpx(fixes: List<RawFix>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Trailog-M0\" ")
        sb.append("xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk>\n    <name>Trailog M0 export</name>\n")

        // Group consecutive fixes by session id -> one <trkseg> each.
        var currentSession: Long? = null
        var segOpen = false
        for (fix in fixes.sortedWith(compareBy({ it.sessionId }, { it.fixTime }))) {
            if (fix.sessionId != currentSession) {
                if (segOpen) sb.append("    </trkseg>\n")
                sb.append("    <trkseg>\n")
                segOpen = true
                currentSession = fix.sessionId
            }
            sb.append("      <trkpt lat=\"${fix.latitude}\" lon=\"${fix.longitude}\">\n")
            fix.altitude?.let { sb.append("        <ele>$it</ele>\n") }
            sb.append("        <time>${iso(fix.fixTime)}</time>\n")
            sb.append("      </trkpt>\n")
        }
        if (segOpen) sb.append("    </trkseg>\n")
        sb.append("  </trk>\n</gpx>\n")
        return sb.toString()
    }

    /** Writes the GPX to the public Downloads folder. Returns a human-readable location. */
    fun export(context: Context, fixes: List<RawFix>): String {
        val gpx = buildGpx(fixes)
        val name = "trailog-m0-${System.currentTimeMillis()}.gpx"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "export failed: no MediaStore uri"
            resolver.openOutputStream(uri)?.use { it.write(gpx.toByteArray()) }
            "Downloads/$name"
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS,
            )
            val file = File(dir, name)
            file.writeText(gpx)
            file.absolutePath
        }
    }
}
