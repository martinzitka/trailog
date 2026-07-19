package io.github.martinzitka.trailog.spike.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [RawFix::class], version = 1, exportSchema = false)
abstract class SpikeDatabase : RoomDatabase() {
    abstract fun rawFixDao(): RawFixDao

    companion object {
        @Volatile
        private var instance: SpikeDatabase? = null

        fun get(context: Context): SpikeDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): SpikeDatabase =
            Room.databaseBuilder(context, SpikeDatabase::class.java, "trailog-m0.db")
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // Durability beats throughput at 1 Hz (CLAUDE.md): survive power
                        // loss without losing recently committed fixes.
                        db.query("PRAGMA synchronous = FULL").close()
                    }
                })
                .build()
    }
}
