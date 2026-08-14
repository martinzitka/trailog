package io.github.martinzitka.trailog.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingSessionDao {

    /** Insert or update the session row (state transitions, heartbeats, segment bumps). */
    @Upsert
    suspend fun upsert(session: RecordingSessionEntity)

    /** The single active session, observed. Null when idle. */
    @Query("SELECT * FROM recording_sessions LIMIT 1")
    fun activeFlow(): Flow<RecordingSessionEntity?>

    /** The single active session read once, or null when idle. */
    @Query("SELECT * FROM recording_sessions LIMIT 1")
    suspend fun active(): RecordingSessionEntity?

    @Query("DELETE FROM recording_sessions WHERE activityId = :activityId")
    suspend fun delete(activityId: String)
}
