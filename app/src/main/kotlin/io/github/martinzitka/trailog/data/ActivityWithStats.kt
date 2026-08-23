package io.github.martinzitka.trailog.data

import androidx.room.Embedded
import androidx.room.Relation

/**
 * An activity's metadata together with its cached statistics — one History row's worth of data.
 *
 * [stats] is null when the cache has not been built for that activity yet (a freshly imported or
 * just-migrated recording, or one whose cache row was dropped). That is a normal, displayable
 * state, not an error: the figures are a recomputable view over the raw points and the recompute
 * path fills them in later, so the row renders without them rather than hiding the activity.
 */
data class ActivityWithStats(
    @Embedded val activity: ActivityEntity,
    @Relation(parentColumn = "id", entityColumn = "activityId")
    val stats: ActivityStatsEntity?,
)
