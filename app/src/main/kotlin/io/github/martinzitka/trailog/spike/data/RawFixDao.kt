package io.github.martinzitka.trailog.spike.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RawFixDao {

    @Insert
    suspend fun insert(fix: RawFix)

    @Query("SELECT COUNT(*) FROM raw_fix")
    fun countFlow(): Flow<Int>

    @Query("SELECT * FROM raw_fix ORDER BY fixTime DESC LIMIT 1")
    fun latestFlow(): Flow<RawFix?>

    @Query("SELECT * FROM raw_fix ORDER BY fixTime ASC")
    suspend fun all(): List<RawFix>

    @Query("SELECT MAX(sessionId) FROM raw_fix")
    suspend fun maxSessionId(): Long?

    @Query("DELETE FROM raw_fix")
    suspend fun clear()
}
