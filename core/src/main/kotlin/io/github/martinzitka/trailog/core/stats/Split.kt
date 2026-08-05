package io.github.martinzitka.trailog.core.stats

import kotlin.time.Duration

/**
 * One distance interval of an activity — a "lap" of a fixed distance, the per-kilometre (or
 * per-mile, or whatever the caller chose) breakdown shown as a splits table.
 *
 * The interval length is a *parameter* of the computation, not a display concern: changing it
 * changes these numbers, unlike unit formatting which only changes how a fixed value is
 * rendered. See [Statistics.splits].
 *
 * Splits accumulate distance **within segments only**; a gap between segments adds no distance
 * and is never interpolated across (CLAUDE.md). A split therefore *may* span a segment gap —
 * its [movingTime] is the clean answer for how long that split took, since the dead recording
 * gap was never moving time to begin with.
 *
 * All units are SI. Times are [Duration] and convert to seconds only at the display edge.
 *
 * @property index the split's ordinal, starting at 0.
 * @property distance length of this split in metres. Every split but the last is exactly the
 *   requested split distance; the final split is whatever remainder is left over and is
 *   usually shorter.
 * @property movingTime time spent moving within this split, gaps and pauses excluded. A hop
 *   that straddles a split boundary contributes its time in proportion to the distance on
 *   each side.
 * @property elevationGain ascent booked within this split, in metres, segment-aware. The
 *   per-split gains sum to the activity total.
 * @property elevationLoss descent booked within this split, in metres, segment-aware. The
 *   per-split losses sum to the activity total.
 * @property averageSpeed [distance] divided by [movingTime], in m/s (0 if not moving).
 */
data class Split(
    val index: Int,
    val distance: Double,
    val movingTime: Duration,
    val elevationGain: Double,
    val elevationLoss: Double,
    val averageSpeed: Double,
)
