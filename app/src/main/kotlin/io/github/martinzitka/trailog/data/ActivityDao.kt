package io.github.martinzitka.trailog.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {

    /** Create or update an activity's metadata. Used at record start and on metadata edits. */
    @Upsert
    suspend fun upsert(activity: ActivityEntity)

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun byId(id: String): ActivityEntity?

    /**
     * One activity with its cached statistics, observed — the Activity detail screen's backing
     * flow. Emits null once the activity is deleted, which is how the screen learns to leave.
     */
    @Transaction
    @Query("SELECT * FROM activities WHERE id = :id")
    fun withStatsByIdFlow(id: String): Flow<ActivityWithStats?>

    /**
     * Update only the user-editable metadata (CLAUDE.md's sync model: name, notes and type are
     * the mutable fields, last-write-wins on `updatedAt`). Deliberately not an upsert of the whole
     * row — `startTime` and `createdAt` describe when the recording happened and are not editable.
     */
    @Query(
        "UPDATE activities SET name = :name, notes = :notes, type = :type, updatedAt = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun updateMetadata(
        id: String,
        name: String,
        notes: String?,
        type: String,
        updatedAt: Long,
    )

    /** Every activity id — drives the recompute-all sweep. */
    @Query("SELECT id FROM activities")
    suspend fun allIds(): List<String>

    /**
     * Every activity as an id and a start time, most recent first — the bulk export worklist.
     *
     * Deliberately not [allByStartTimeDesc]: the export names each file after the ride it holds
     * and then loads that ride's points one at a time, so it needs the order and the timestamp up
     * front but must never hold every activity in memory at once.
     */
    @Query("SELECT id, startTime FROM activities ORDER BY startTime DESC")
    suspend fun allRefsByStartTimeDesc(): List<ActivityRef>

    /**
     * Ids of activities that have no cached statistics row yet — the backfill worklist. This is
     * non-empty after the 1 → 2 migration, which created `activities` rows for recordings made
     * before that table existed and left their statistics to be computed later.
     */
    @Query(
        "SELECT a.id FROM activities a " +
            "LEFT JOIN activity_stats s ON s.activityId = a.id " +
            "WHERE s.activityId IS NULL",
    )
    suspend fun idsWithoutStats(): List<String>

    /** All activities, most recent first — the History list. */
    @Query("SELECT * FROM activities ORDER BY startTime DESC")
    fun allByStartTimeDesc(): Flow<List<ActivityEntity>>

    /**
     * All activities with their cached statistics, most recent first — the History list as it is
     * actually rendered. Room resolves the relation in a second query inside the transaction, so
     * this is two queries for the whole list rather than one per row.
     */
    @Transaction
    @Query("SELECT * FROM activities ORDER BY startTime DESC")
    fun allWithStatsByStartTimeDesc(): Flow<List<ActivityWithStats>>

    @Query("DELETE FROM activities WHERE id = :id")
    suspend fun delete(id: String)
}
