package io.github.martinzitka.trailog.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.cos

/** A single geographic point for the trace. Lat/lon in WGS84 degrees, matching the raw model. */
data class TracePoint(val latitude: Double, val longitude: Double)

/**
 * The map seam. Today this renders a lightweight, self-contained **placeholder**: the route as
 * an auto-fitted polyline over a blank surface, with segment gaps shown as breaks — never as a
 * straight line joining the ends (CLAUDE.md: never interpolate across a segment boundary).
 *
 * M1.6 replaces the body of this composable with MapLibre Native (tiles, pan, follow, re-centre)
 * without any screen having to change — screens depend only on this signature. Interactive
 * follow/pan/re-centre are deliberately deferred to that real map; a placeholder that faked them
 * would be throwaway work.
 *
 * @param segments one polyline per recording segment, each in draw order. Rendering each
 *   segment as its own path is what keeps a recording gap from being bridged.
 * @param contentDescription accessibility label for the whole map surface.
 * @param showEndMarker whether to mark the most recent point (the live position while recording).
 */
@Composable
fun RouteMap(
    segments: List<List<TracePoint>>,
    modifier: Modifier = Modifier,
    contentDescription: String,
    showEndMarker: Boolean = true,
    emptyLabel: String? = null,
) {
    val hasGeometry = segments.any { it.isNotEmpty() }
    val lineColor = MaterialTheme.colorScheme.primary
    val markerColor = MaterialTheme.colorScheme.secondary

    Box(
        modifier = modifier
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (!hasGeometry) {
            if (emptyLabel != null) {
                Text(
                    text = emptyLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
            return@Box
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val projection = Projection.fit(segments, size.width, size.height, PADDING_PX)
            for (segment in segments) {
                if (segment.size < 2) continue
                val path = Path()
                segment.forEachIndexed { i, point ->
                    val (x, y) = projection.project(point)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = lineColor, style = Stroke(width = STROKE_PX))
            }
            if (showEndMarker) {
                segments.lastOrNull { it.isNotEmpty() }?.last()?.let { end ->
                    val (x, y) = projection.project(end)
                    drawCircle(color = markerColor, radius = MARKER_PX, center = Offset(x, y))
                }
            }
        }
    }
}

private const val PADDING_PX = 24f
private const val STROKE_PX = 6f
private const val MARKER_PX = 12f

/**
 * An equirectangular fit of a set of geographic points into a canvas rectangle, preserving
 * aspect ratio. Longitude is scaled by cos(latitude) so the track is not stretched east–west at
 * Czech latitudes. A degenerate extent (a single point, or all points coincident) centres
 * rather than dividing by zero.
 */
private class Projection(
    private val lonScale: Double,
    private val minX: Double,
    private val minY: Double,
    private val scale: Double,
    private val offsetX: Float,
    private val offsetY: Float,
    private val height: Float,
) {
    fun project(p: TracePoint): Pair<Float, Float> {
        val px = (p.longitude * lonScale - minX) * scale
        val py = (p.latitude - minY) * scale
        val x = offsetX + px.toFloat()
        // Screen y grows downward; latitude grows up, so invert within the fitted box.
        val y = height - offsetY - py.toFloat()
        return x to y
    }

    companion object {
        fun fit(
            segments: List<List<TracePoint>>,
            width: Float,
            height: Float,
            padding: Float,
        ): Projection {
            val points = segments.flatten()
            val midLat = points.sumOf { it.latitude } / points.size
            val lonScale = cos(Math.toRadians(midLat)).coerceAtLeast(1e-6)

            val xs = points.map { it.longitude * lonScale }
            val ys = points.map { it.latitude }
            val minX = xs.min()
            val maxX = xs.max()
            val minY = ys.min()
            val maxY = ys.max()

            val spanX = (maxX - minX).takeIf { it > 0 } ?: 1.0
            val spanY = (maxY - minY).takeIf { it > 0 } ?: 1.0
            val usableW = (width - 2 * padding).coerceAtLeast(1f)
            val usableH = (height - 2 * padding).coerceAtLeast(1f)
            val scale = minOf(usableW / spanX, usableH / spanY)

            // Centre the fitted track within the padded box.
            val drawnW = (spanX * scale).toFloat()
            val drawnH = (spanY * scale).toFloat()
            val offsetX = padding + (usableW - drawnW) / 2f
            val offsetY = padding + (usableH - drawnH) / 2f

            return Projection(lonScale, minX, minY, scale, offsetX, offsetY, height)
        }
    }
}
