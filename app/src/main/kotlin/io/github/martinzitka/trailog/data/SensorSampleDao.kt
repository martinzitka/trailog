package io.github.martinzitka.trailog.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SensorSampleDao {

    /** Persist one reading. Called on arrival, like a fix — never buffered for a whole session. */
    @Insert
    suspend fun insert(sample: SensorSampleEntity)

    /**
     * Persist a batch in one statement. The import path's shape: a whole workout's readings are
     * already in hand when the activity is written, and inserting them one at a time would mean one
     * transaction per reading for no benefit. The recording path uses [insert] instead, because
     * durability there means each reading hitting the disk as it arrives.
     */
    @Insert
    suspend fun insertAll(samples: List<SensorSampleEntity>)

    /** All readings of one activity in time order — the read path for charts and export. */
    @Query("SELECT * FROM sensor_samples WHERE activityId = :activityId ORDER BY time ASC")
    suspend fun samplesFor(activityId: String): List<SensorSampleEntity>

    @Query("SELECT COUNT(*) FROM sensor_samples WHERE activityId = :activityId")
    suspend fun countFor(activityId: String): Int

    /**
     * Drop every reading of one activity. Not needed for the ordinary delete — the foreign key
     * cascades from `activities` — but the `raw_points` delete is explicit for activities that
     * predate that table, and this exists so a caller that must clear samples without touching the
     * activity row has one honest way to do it.
     */
    @Query("DELETE FROM sensor_samples WHERE activityId = :activityId")
    suspend fun deleteFor(activityId: String)
}
