package io.github.martinzitka.trailog.ui.detail

import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.stats.ProfileSeries
import io.github.martinzitka.trailog.core.stats.TrackIndex
import io.github.martinzitka.trailog.ui.map.TracePoint

/**
 * One row of the per-kilometre splits table, ready to render. SI and unformatted, like everything
 * else that leaves a ViewModel — metres, whole seconds, metres per second (CLAUDE.md).
 *
 * @property number the split's 1-based ordinal, as a rider counts kilometres.
 * @property distance the split's length in metres. The last split is usually short of a full
 *   kilometre, and is rendered with its real length rather than being padded or hidden.
 * @property movingSeconds whole seconds moving within the split; a recording gap contributes none.
 * @property averageSpeed metres per second over [movingSeconds].
 * @property elevationGain metres climbed within the split.
 * @property elevationLoss metres descended within the split.
 * @property isPartial true when the split is shorter than a full interval — the leftover at the
 *   end of a ride. Decided where the split distance is defined, so the screen never has to keep
 *   its own copy of that constant to work it out.
 */
data class SplitRow(
    val number: Int,
    val distance: Double,
    val movingSeconds: Long,
    val averageSpeed: Double,
    val elevationGain: Double,
    val elevationLoss: Double,
    val isPartial: Boolean,
)

/**
 * Everything the detail screen shows for a loaded activity.
 *
 * The summary figures are non-null here, unlike a History row: the detail screen recomputes them
 * from the raw points when the cache is missing rather than rendering a screen full of dashes.
 *
 * @property id the activity's UUIDv7 string.
 * @property name the user's title; blank means "not named yet" and the screen falls back to the
 *   type label. A blank name is never stored as a formatted string (CLAUDE.md).
 * @property notes free-text notes, or null.
 * @property segments the route, one polyline per recording segment, so a gap can never be drawn
 *   as a straight line joining the ends.
 * @property elevationProfile altitude against cumulative distance, per segment.
 * @property speedProfile ground speed against cumulative distance, per segment.
 * @property track the same route prepared for lookup in both directions, which is what lets the
 *   fullscreen map and its charts point at the same moment of the ride.
 * @property statsPending true when the cached statistics row was missing and these figures were
 *   computed on the fly — the screen says so, because the recompute path will persist them later.
 */
data class ActivityDetail(
    val id: String,
    val type: ActivityType,
    val name: String,
    val notes: String?,
    val startTime: Long,
    val distance: Double,
    val elapsedSeconds: Long,
    val movingSeconds: Long,
    val averageSpeed: Double,
    val maxSpeed: Double,
    val elevationGain: Double,
    val elevationLoss: Double,
    val segmentCount: Int,
    val pointCount: Int,
    val segments: List<List<TracePoint>>,
    val elevationProfile: ProfileSeries,
    val speedProfile: ProfileSeries,
    val track: TrackIndex,
    val splits: List<SplitRow>,
    val statsPending: Boolean,
)

/**
 * The complete state of the Activity detail screen. No blank screen is ever a valid state
 * (CLAUDE.md's definition of done), so loading and "gone" are explicit cases rather than an
 * absence of content.
 *
 * There is no separate error case. The only read here is the local database, and a failure to
 * read a row that the History list just linked to is indistinguishable from the activity having
 * been deleted — which [Gone] already describes honestly.
 */
sealed interface ActivityDetailUiState {

    /** The activity is being read from the database. */
    data object Loading : ActivityDetailUiState

    /** The activity, its figures, its route and its splits. */
    data class Loaded(val detail: ActivityDetail) : ActivityDetailUiState

    /**
     * No such activity — it was deleted, from this screen or elsewhere. The screen shows this
     * briefly and navigates back rather than sitting on a stale view of something that is gone.
     */
    data object Gone : ActivityDetailUiState
}
