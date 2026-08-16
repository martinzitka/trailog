package io.github.martinzitka.trailog.data

import io.github.martinzitka.trailog.core.model.Activity
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.stats.Statistics
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * The read/write surface over activities and their derived statistics, and the home of the
 * **recompute path** M1.4 requires: rebuilding the [ActivityStatsEntity] cache from raw points
 * via the single `:core` [Statistics] implementation.
 *
 * This path is why raw points are immutable and sacred (CLAUDE.md): the cache is disposable,
 * and every displayed number is a recomputable view over the raw fixes. When a statistics
 * algorithm improves or a bug is fixed, [recomputeAll] rebuilds every activity's numbers from
 * the fixes that were preserved exactly as the OS delivered them.
 *
 * No coordinates are ever logged here (nothing is logged here); statistics carry none anyway.
 */
class ActivityRepository(
    private val activities: ActivityDao,
    private val points: RawPointDao,
    private val stats: ActivityStatsDao,
    private val now: () -> Long,
) {
    constructor(db: TrailogDatabase, now: () -> Long = { System.currentTimeMillis() }) : this(
        activities = db.activityDao(),
        points = db.rawPointDao(),
        stats = db.activityStatsDao(),
        now = now,
    )

    /** All activities, most recent first. */
    fun activitiesByRecency(): Flow<List<ActivityEntity>> = activities.allByStartTimeDesc()

    /**
     * All activities with their cached statistics, most recent first — the History screen's
     * backing flow. Rows whose cache has not been built yet come through with null stats and are
     * still listed; the figures are derived data, never a reason to hide a recording.
     */
    fun activitiesWithStatsByRecency(): Flow<List<ActivityWithStats>> =
        activities.allWithStatsByStartTimeDesc()

    /** An activity's cached stats, observed. Null until the first recompute. */
    fun statsFlow(activityId: String): Flow<ActivityStatsEntity?> = stats.byIdFlow(activityId)

    /**
     * Load an activity as a platform-free domain [Activity] — its metadata plus every raw point
     * in time order. Returns null when no such activity exists.
     *
     * This is the single place raw points are read into the domain, so the Record screen's live
     * stats, the detail screen, and [recompute] all segment and compute through the one `:core`
     * [Statistics] path — a figure can never be computed two different ways (CLAUDE.md).
     */
    suspend fun loadActivity(activityId: String): Activity? {
        val activity = activities.byId(activityId) ?: return null
        return Activity(
            id = UUID.fromString(activity.id),
            type = ActivityType.valueOf(activity.type),
            name = activity.name,
            points = points.pointsFor(activityId).map { it.toDomain() },
        )
    }

    /**
     * Recompute one activity's derived statistics from its raw points and overwrite the cache
     * row. Returns false (and touches nothing) when no such activity exists.
     *
     * The raw points are read in time order and grouped into segments by [Statistics], so the
     * result is fully segment-aware — nothing is interpolated across a gap between segments.
     */
    suspend fun recompute(activityId: String): Boolean {
        val domain = loadActivity(activityId) ?: return false
        stats.upsert(Statistics.compute(domain).toEntity(activityId, now()))
        return true
    }

    /**
     * Recompute the activities that have **no** cached statistics yet, and return how many were
     * rebuilt. Activities whose cache already exists are left alone.
     *
     * This is the backfill for recordings whose metadata row predates the statistics cache — the
     * ones the 1 → 2 migration created from raw points. Without it their figures would stay
     * pending forever, because [recompute] otherwise only runs when an activity is finalised.
     *
     * Cheap when there is nothing to do: one query returning no rows. It is *not* the path for an
     * algorithm change — that is [recomputeAll], which rebuilds caches that already exist.
     */
    suspend fun recomputeMissing(): Int {
        val ids = activities.idsWithoutStats()
        for (id in ids) recompute(id)
        return ids.size
    }

    /**
     * Recompute every activity's statistics from raw points. This is the "recompute all derived
     * data from raw points" path: run it after an algorithm change to rebuild the whole cache.
     * Returns the number of activities recomputed.
     */
    suspend fun recomputeAll(): Int {
        val ids = activities.allIds()
        for (id in ids) recompute(id)
        return ids.size
    }
}
