package io.github.martinzitka.trailog.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityStatsDao {

    /** Write (or overwrite) an activity's cached stats. Called by the recompute path only. */
    @Upsert
    suspend fun upsert(stats: ActivityStatsEntity)

    @Query("SELECT * FROM activity_stats WHERE activityId = :activityId")
    suspend fun byId(activityId: String): ActivityStatsEntity?

    @Query("SELECT * FROM activity_stats WHERE activityId = :activityId")
    fun byIdFlow(activityId: String): Flow<ActivityStatsEntity?>
}
