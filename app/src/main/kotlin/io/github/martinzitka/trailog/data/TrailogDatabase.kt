package io.github.martinzitka.trailog.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The app's Room database. Holds raw points (written on arrival) and the single recording
 * session. `PRAGMA synchronous = FULL` is set on open: at 1 Hz the write volume is negligible
 * and durability is the whole point (CLAUDE.md), so recently committed fixes must survive power
 * loss rather than sit in the WAL.
 *
 * Schema export is off for M1.3; M1.4 turns it on, commits the schema and adds migration tests
 * along with the `activities` and `activity_stats` tables.
 */
@Database(
    entities = [RawPointEntity::class, RecordingSessionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class TrailogDatabase : RoomDatabase() {

    abstract fun rawPointDao(): RawPointDao
    abstract fun recordingSessionDao(): RecordingSessionDao

    companion object {
        @Volatile
        private var instance: TrailogDatabase? = null

        fun get(context: Context): TrailogDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): TrailogDatabase =
            Room.databaseBuilder(context, TrailogDatabase::class.java, "trailog.db")
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // Durability beats throughput at 1 Hz (CLAUDE.md).
                        db.query("PRAGMA synchronous = FULL").close()
                    }
                })
                .build()
    }
}
