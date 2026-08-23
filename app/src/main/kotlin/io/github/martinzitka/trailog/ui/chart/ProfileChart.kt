package io.github.martinzitka.trailog.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.martinzitka.trailog.core.stats.ProfileSeries

/**
 * An elevation or speed profile: value on the y-axis against cumulative distance on the x-axis.
 *
 * **Each segment is drawn as its own [Path].** A recording gap therefore appears as a break in
 * the line and can never become a straight run joining the two ends — the chart equivalent of
 * the rule every statistics function obeys (CLAUDE.md). Nothing here connects two segments, and
 * nothing here interpolates between them.
 *
 * Rendering cost is bounded by the series, not by the ride: [ProfileSeries] arrives already
 * reduced to a few hundred envelope-preserving samples, so a four-hour recording draws the same
 * number of line segments as a twenty-minute one.
 *
 * Values are plotted in the SI units they arrive in; the axis labels are formatted by the caller,
 * which is the only place conversion may happen (CLAUDE.md).
 *
 * @param series the polylines to draw, one list per recording segment.
 * @param title the chart's heading, e.g. "Elevation".
 * @param minLabel formatted label for the series minimum, shown at the bottom of the axis.
 * @param maxLabel formatted label for the series maximum, shown at the top of the axis.
 * @param emptyLabel shown instead of a plot when the activity carries no values to chart — an
 *   older recording with no altitude, say. A blank box is never a valid state.
 * @param contentDescription spoken description of the whole chart, which is otherwise a bitmap.
 */
@Composable
fun ProfileChart(
    series: ProfileSeries,
    title: String,
    minLabel: String,
    maxLabel: String,
    emptyLabel: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    height: Dp = DEFAULT_HEIGHT,
    lineColor: Color = MaterialTheme.colorScheme.primary,
) {
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall)

        if (series.isEmpty) {
            Box(
                modifier = Modifier.fillMaxWidth().height(height),
                contentAlignment = Alignment.Center,
            ) {
                Text(emptyLabel, style = MaterialTheme.typography.bodySmall, color = labelColor)
            }
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(height).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The y-axis: just its extremes. A full gridline treatment would crowd a phone-width
            // chart, and the exact figures are in the summary above it.
            Column(
                modifier = Modifier.fillMaxWidth(AXIS_WIDTH_FRACTION),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                Text(maxLabel, style = labelStyle, color = labelColor, maxLines = 1)
                Text(minLabel, style = labelStyle, color = labelColor, maxLines = 1)
            }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { this.contentDescription = contentDescription },
            ) {
                val minValue = series.minValue ?: return@Canvas
                val maxValue = series.maxValue ?: return@Canvas
                // A perfectly flat series would divide by zero; draw it as a centred line.
                val valueSpan = (maxValue - minValue).takeIf { it > 0.0 }
                val distanceSpan = series.maxDistance.takeIf { it > 0.0 } ?: return@Canvas

                for (segment in series.segments) {
                    if (segment.size < 2) continue
                    val path = Path()
                    segment.forEachIndexed { i, sample ->
                        val x = (sample.distance / distanceSpan).toFloat() * size.width
                        val normalised = if (valueSpan == null) {
                            0.5
                        } else {
                            (sample.value - minValue) / valueSpan
                        }
                        // Screen y grows downward; a higher value belongs nearer the top.
                        val y = size.height - (normalised.toFloat() * size.height)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color = lineColor, style = Stroke(width = STROKE_PX))
                }
            }
        }
    }
}

private const val STROKE_PX = 3f

/** Share of the chart row given to the y-axis labels. */
private const val AXIS_WIDTH_FRACTION = 0.22f

private val DEFAULT_HEIGHT = 120.dp
