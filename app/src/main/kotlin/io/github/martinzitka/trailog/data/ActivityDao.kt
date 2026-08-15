package io.github.martinzitka.trailog.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {

    /** Create or update an activity's metadata. Used at record start and on metadata edits. */
    @Upsert
    suspend fun upsert(activity: ActivityEntity)

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun byId(id: String): ActivityEntity?

    /** Every activity id — drives the recompute-all sweep. */
    @Query("SELECT id FROM activities")
    suspend fun allIds(): List<String>

    /** All activities, most recent first — the History list. */
    @Query("SELECT * FROM activities ORDER BY startTime DESC")
    fun allByStartTimeDesc(): Flow<List<ActivityEntity>>

    @Query("DELETE FROM activities WHERE id = :id")
    suspend fun delete(id: String)
}
