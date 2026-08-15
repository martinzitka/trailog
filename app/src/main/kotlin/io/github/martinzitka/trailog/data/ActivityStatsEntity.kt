package io.github.martinzitka.trailog.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * The cached derived statistics of an activity — a recomputable view over its raw points, never
 * a source of truth. It exists so History and detail screens do not re-run the statistics
 * pipeline on every read; it is rebuilt from raw points by the recompute path
 * ([io.github.martinzitka.trailog.data.ActivityRepository.recompute]) whenever the raw points
 * or the algorithms change. Deleting the whole table loses nothing recoverable.
 *
 * One row per activity, keyed by [activityId] (the same UUIDv7 string as
 * [ActivityEntity.id]). The foreign key cascades on delete, so removing an activity drops its
 * cached stats automatically.
 *
 * Mirrors [io.github.martinzitka.trailog.core.stats.ActivityStats] field for field. Durations
 * are stored as epoch-style millis (Long) and rebuilt into `Duration` by the mapper; distances
 * and speeds are SI (metres, metres per second) with no unit suffixes.
 *
 * @property computedAt epoch millis this cache row was last recomputed — lets staleness be
 *   detected after an algorithm change.
 */
@Entity(
    tableName = "activity_stats",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ActivityStatsEntity(
    @PrimaryKey val activityId: String,
    val distance: Double,
    val elapsedTime: Long,
    val movingTime: Long,
    val averageSpeed: Double,
    val maxSpeed: Double,
    val elevationGain: Double,
    val elevationLoss: Double,
    val segmentCount: Int,
    val pointCount: Int,
    val computedAt: Long,
)
