package io.github.martinzitka.trailog.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.martinzitka.trailog.core.stats.ProfileSeries

/**
 * One quantity plotted on a shared chart: the series, how to label it, and whether it is shown.
 *
 * Labels arrive pre-formatted. Formatting happens at the display edge in one utility and nowhere
 * else (CLAUDE.md), and a chart is not that utility — it plots the SI values it is given.
 *
 * @property label the series' name, used by its visibility toggle.
 * @property minLabel formatted series minimum, shown at the bottom of its axis.
 * @property maxLabel formatted series maximum, shown at the top of its axis.
 * @property visible whether the curve and its axis are drawn.
 * @property onVisibleChange toggles [visible]. State is hoisted so it survives rotation with the
 *   screen rather than resetting when the chart recomposes.
 */
data class ProfileTrack(
    val series: ProfileSeries,
    val label: String,
    val minLabel: String,
    val maxLabel: String,
    val visible: Boolean,
    val onVisibleChange: (Boolean) -> Unit,
)

/**
 * Two profiles on one set of axes: [left] against the left-hand axis, [right] against the
 * right-hand one, both against cumulative distance.
 *
 * Sharing the x-axis is the point. Elevation and speed are read *against each other* — how much
 * the climb cost, where the descent was quick — and on two stacked charts that comparison is
 * done by eye across a gap, which is exactly where it goes wrong. Each series keeps its own
 * y-scale, because metres and metres per second have no common zero.
 *
 * **Each segment is drawn as its own [Path]**, so a recording gap is a break in the line and can
 * never become a straight run joining the two ends — the chart equivalent of the rule every
 * statistics function obeys (CLAUDE.md).
 *
 * Touching or dragging anywhere on the plot moves the cursor, reported back as a distance in
 * metres. The chart draws the cursor it is given rather than one it remembers, so the same
 * position can be set from the map beside it and the two can never disagree.
 *
 * Rendering cost is bounded by the series, not by the ride: [ProfileSeries] arrives already
 * reduced to a few hundred envelope-preserving samples, so a four-hour recording draws the same
 * number of line segments as a twenty-minute one.
 *
 * @param cursorDistance the selected distance in metres, or null for no selection.
 * @param onCursorChange invoked with the distance under the finger.
 * @param emptyLabel shown instead of a plot when neither series has anything to draw.
 * @param contentDescription spoken description of the plot, which is otherwise a bitmap.
 */
@Composable
fun DualProfileChart(
    left: ProfileTrack,
    right: ProfileTrack,
    cursorDistance: Double?,
    onCursorChange: (Double) -> Unit,
    emptyLabel: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    height: Dp = DEFAULT_HEIGHT,
    leftColor: Color = MaterialTheme.colorScheme.primary,
    rightColor: Color = MaterialTheme.colorScheme.tertiary,
    cursorColor: Color = MaterialTheme.colorScheme.error,
) {
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    // One x-axis for both curves, so a point on one lines up with the same point on the other.
    // The two series are downsampled independently and can end a few metres apart.
    val maxDistance = maxOf(left.series.maxDistance, right.series.maxDistance)
    val nothingToPlot = left.series.isEmpty && right.series.isEmpty

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SeriesToggle(track = left, colour = leftColor)
            SeriesToggle(track = right, colour = rightColor)
        }

        if (nothingToPlot || maxDistance <= 0.0) {
            Box(
                modifier = Modifier.fillMaxWidth().height(height),
                contentAlignment = Alignment.Center,
            ) {
                Text(emptyLabel, style = MaterialTheme.typography.bodySmall, color = labelColor)
            }
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(height),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The axes show their extremes only. Gridlines would crowd a chart this short, and the
            // exact figure under the cursor is printed above it.
            AxisLabels(track = left, colour = leftColor, alignment = Alignment.End, style = labelStyle)

            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics { this.contentDescription = contentDescription }
                    // Tap and drag are separate detectors on purpose: one pointerInput can only
                    // run one of them, and a chart wants both — tap to place the cursor, drag to
                    // sweep it along the ride.
                    .pointerInput(maxDistance, onCursorChange) {
                        detectTapGestures { offset ->
                            onCursorChange(distanceAt(offset.x, size.width, maxDistance))
                        }
                    }
                    .pointerInput(maxDistance, onCursorChange) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                onCursorChange(distanceAt(offset.x, size.width, maxDistance))
                            },
                            onHorizontalDrag = { change, _ ->
                                onCursorChange(
                                    distanceAt(change.position.x, size.width, maxDistance),
                                )
                            },
                        )
                    },
            ) {
                if (left.visible) drawSeries(left.series, maxDistance, leftColor)
                if (right.visible) drawSeries(right.series, maxDistance, rightColor)

                if (cursorDistance != null) {
                    val x = (cursorDistance / maxDistance).toFloat().coerceIn(0f, 1f) * size.width
                    drawLine(
                        color = cursorColor,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = CURSOR_STROKE_PX,
                    )
                    if (left.visible) drawCursorDot(left.series, cursorDistance, x, leftColor)
                    if (right.visible) drawCursorDot(right.series, cursorDistance, x, rightColor)
                }
            }

            AxisLabels(
                track = right,
                colour = rightColor,
                alignment = Alignment.Start,
                style = labelStyle,
            )
        }
    }
}

// ---- pieces -----------------------------------------------------------------------------------

@Composable
private fun SeriesToggle(track: ProfileTrack, colour: Color) {
    // A series with nothing in it cannot be shown, so its toggle says so rather than appearing to
    // do nothing when tapped.
    val enabled = !track.series.isEmpty
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .toggleable(
                value = track.visible && enabled,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = track.onVisibleChange,
            )
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(checked = track.visible && enabled, onCheckedChange = null, enabled = enabled)
        Text(
            text = track.label,
            style = MaterialTheme.typography.labelLarge,
            // The label carries the curve's colour, which is what identifies which axis is whose.
            color = if (enabled) colour else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun AxisLabels(
    track: ProfileTrack,
    colour: Color,
    alignment: Alignment.Horizontal,
    style: TextStyle,
) {
    Column(
        modifier = Modifier.width(AXIS_WIDTH),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = alignment,
    ) {
        // A hidden series keeps its space but not its numbers: the chart must not resize as a
        // toggle is flipped, and stale extremes for an invisible curve would be a lie.
        val visible = track.visible && !track.series.isEmpty
        Text(
            text = if (visible) track.maxLabel else "",
            style = style,
            color = colour,
            maxLines = 1,
        )
        Text(
            text = if (visible) track.minLabel else "",
            style = style,
            color = colour,
            maxLines = 1,
        )
    }
}

private fun DrawScope.drawSeries(series: ProfileSeries, maxDistance: Double, colour: Color) {
    val minValue = series.minValue ?: return
    val maxValue = series.maxValue ?: return
    // A perfectly flat series would divide by zero; draw it as a centred line.
    val valueSpan = (maxValue - minValue).takeIf { it > 0.0 }

    for (segment in series.segments) {
        if (segment.size < 2) continue
        val path = Path()
        segment.forEachIndexed { i, sample ->
            val x = (sample.distance / maxDistance).toFloat() * size.width
            val normalised = if (valueSpan == null) 0.5 else (sample.value - minValue) / valueSpan
            // Screen y grows downward; a higher value belongs nearer the top.
            val y = size.height - (normalised.toFloat() * size.height)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = colour, style = Stroke(width = STROKE_PX))
    }
}

/** Marks where the cursor crosses one curve, at the plotted value nearest the cursor. */
private fun DrawScope.drawCursorDot(
    series: ProfileSeries,
    cursorDistance: Double,
    x: Float,
    colour: Color,
) {
    val value = series.valueAt(cursorDistance) ?: return
    val minValue = series.minValue ?: return
    val maxValue = series.maxValue ?: return
    val valueSpan = (maxValue - minValue).takeIf { it > 0.0 }
    val normalised = if (valueSpan == null) 0.5 else (value - minValue) / valueSpan
    val y = size.height - (normalised.toFloat() * size.height)
    drawCircle(color = colour, radius = CURSOR_DOT_PX, center = Offset(x, y))
}

/** Where along the ride a horizontal touch position falls, clamped to the plotted extent. */
private fun distanceAt(x: Float, width: Int, maxDistance: Double): Double =
    (x / width.coerceAtLeast(1)).toDouble().coerceIn(0.0, 1.0) * maxDistance

private const val STROKE_PX = 3f
private const val CURSOR_STROKE_PX = 2f
private const val CURSOR_DOT_PX = 7f

/** Fixed width for each axis column, so the plot does not jump as labels change length. */
private val AXIS_WIDTH = 56.dp

private val DEFAULT_HEIGHT = 132.dp
