package io.github.martinzitka.trailog.recording

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.martinzitka.trailog.core.gpx.Gpx
import io.github.martinzitka.trailog.core.gpx.GpxTrack
import io.github.martinzitka.trailog.data.RawPointEntity
import io.github.martinzitka.trailog.data.toDomain
import java.io.File

/**
 * Writes recorded points to a GPX file in Downloads so the track can be opened in a third-party
 * tool. Serialisation goes through `:core`'s [Gpx] — the single GPX writer in the project — so
 * segment boundaries become separate `<trkseg>` elements and no gap is welded into a straight
 * line. This is a test-harness affordance for M1.3; the real export UI is M1.7.
 */
object GpxExport {

    fun export(context: Context, timestamp: Long, points: List<RawPointEntity>): String {
        val track = GpxTrack(
            name = "Trailog activity",
            type = null,
            points = points.map { it.toDomain() },
        )
        val gpx = Gpx.write(track)
        val name = "trailog-$timestamp.gpx"

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
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(dir, name)
            file.writeText(gpx)
            file.absolutePath
        }
    }
}
