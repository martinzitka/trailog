package io.github.martinzitka.trailog.ui.history

import io.github.martinzitka.trailog.core.model.ActivityType

/**
 * One row of the History list. Everything is SI and unformatted — metres, seconds, epoch millis —
 * because formatting happens only at the render edge (CLAUDE.md).
 *
 * The three figures are nullable: an activity whose statistics cache has not been built yet is
 * still a real recording and still listed, with the figures shown as pending rather than as zero.
 * Zero would be a lie about the ride; the raw points are there and the recompute path will fill
 * the cache in.
 *
 * @property id the activity's UUIDv7 string, used as the stable list key and the detail route arg.
 * @property name the user's title; blank until they set one, in which case the type label is used.
 * @property startTime epoch millis of the activity's start — the sort key, most recent first.
 * @property distance metres, or null if not computed yet.
 * @property elapsedSeconds whole seconds of elapsed (not moving) time, or null if not computed.
 * @property elevationGain metres climbed, or null if not computed yet.
 */
data class HistoryItem(
    val id: String,
    val type: ActivityType,
    val name: String,
    val startTime: Long,
    val distance: Double?,
    val elapsedSeconds: Long?,
    val elevationGain: Double?,
)

/**
 * The complete state of the History screen. A blank list is never a valid rendering (CLAUDE.md's
 * definition of done): the empty case is explicit and explains how to record a first activity, and
 * a read failure is its own state with a way to retry rather than an empty list pretending there
 * is nothing to show.
 */
sealed interface HistoryUiState {

    /** The first read has not produced a list yet. */
    data object Loading : HistoryUiState

    /** No activities recorded yet — the screen explains how to record one. */
    data object Empty : HistoryUiState

    /** Activities to show, already sorted most recent first. */
    data class Populated(val items: List<HistoryItem>) : HistoryUiState

    /** The list could not be read. The recordings themselves are unaffected. */
    data object Error : HistoryUiState
}
