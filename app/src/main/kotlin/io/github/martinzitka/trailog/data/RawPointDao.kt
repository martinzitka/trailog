package io.github.martinzitka.trailog.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RawPointDao {

    /** Persist one fix. Called on arrival for every location update — never batched. */
    @Insert
    suspend fun insert(point: RawPointEntity)

    @Query("SELECT COUNT(*) FROM raw_points")
    fun countFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM raw_points WHERE activityId = :activityId")
    suspend fun countFor(activityId: String): Int

    /** The timestamp of the most recent fix of an activity — the recovery gap is measured from it. */
    @Query("SELECT MAX(time) FROM raw_points WHERE activityId = :activityId")
    suspend fun maxTime(activityId: String): Long?

    /** The highest segment index seen for an activity, or null if it has no points yet. */
    @Query("SELECT MAX(segmentIndex) FROM raw_points WHERE activityId = :activityId")
    suspend fun maxSegmentIndex(activityId: String): Int?

    /** All points of one activity in time order — for export. */
    @Query("SELECT * FROM raw_points WHERE activityId = :activityId ORDER BY time ASC")
    suspend fun pointsFor(activityId: String): List<RawPointEntity>

    /** The activity id of the most recently recorded fix, or null if nothing was ever recorded. */
    @Query("SELECT activityId FROM raw_points ORDER BY time DESC LIMIT 1")
    suspend fun latestActivityId(): String?

    /** The most recent fix across everything — drives the live "last fix" diagnostic. */
    @Query("SELECT * FROM raw_points ORDER BY time DESC LIMIT 1")
    fun latestFlow(): Flow<RawPointEntity?>
}
