package io.github.martinzitka.trailog.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The app's Room database. Holds raw points (written on arrival), sensor samples (a stream of their
 * own, on the track's clock), the single recording session, activity metadata, and a derived
 * statistics cache. `PRAGMA synchronous = FULL` is set on open:
 * at 1 Hz the write volume is negligible and durability is the whole point (CLAUDE.md), so
 * recently committed fixes must survive power loss rather than sit in the WAL.
 *
 * Schema is exported to `app/schemas/` and committed. Migrations are forward-only and
 * **non-destructive** — the developer's device carries real, irreplaceable recordings, and raw
 * points are immutable and sacred (CLAUDE.md), so a destructive fallback is never configured.
 */
@Database(
    entities = [
        RawPointEntity::class,
        RecordingSessionEntity::class,
        ActivityEntity::class,
        ActivityStatsEntity::class,
        SensorSampleEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class TrailogDatabase : RoomDatabase() {

    abstract fun rawPointDao(): RawPointDao
    abstract fun recordingSessionDao(): RecordingSessionDao
    abstract fun activityDao(): ActivityDao
    abstract fun activityStatsDao(): ActivityStatsDao
    abstract fun sensorSampleDao(): SensorSampleDao

    companion object {
        @Volatile
        private var instance: TrailogDatabase? = null

        /**
         * Migration 1 → 2: introduce the `activities` and `activity_stats` tables. `raw_points`
         * and `recording_sessions` are untouched, so every fix recorded under version 1 survives.
         *
         * The migration also **backfills** an `activities` row for every activity already present
         * in `raw_points` (the field recordings made under M1.3, before this table existed), so
         * they become visible and recomputable rather than orphaned. The activity type is taken
         * from a still-live recording session where one exists, otherwise it defaults to CYCLING
         * — editable metadata the user can correct later, never touching the sacred raw points.
         * `activity_stats` is left empty; the recompute path fills it on demand.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `activities` (" +
                        "`id` TEXT NOT NULL, `type` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`notes` TEXT, `startTime` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `activity_stats` (" +
                        "`activityId` TEXT NOT NULL, `distance` REAL NOT NULL, " +
                        "`elapsedTime` INTEGER NOT NULL, `movingTime` INTEGER NOT NULL, " +
                        "`averageSpeed` REAL NOT NULL, `maxSpeed` REAL NOT NULL, " +
                        "`elevationGain` REAL NOT NULL, `elevationLoss` REAL NOT NULL, " +
                        "`segmentCount` INTEGER NOT NULL, `pointCount` INTEGER NOT NULL, " +
                        "`computedAt` INTEGER NOT NULL, PRIMARY KEY(`activityId`), " +
                        "FOREIGN KEY(`activityId`) REFERENCES `activities`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                // Backfill activities for recordings that predate this table.
                db.execSQL(
                    "INSERT INTO `activities` (id, type, name, notes, startTime, createdAt, updatedAt) " +
                        "SELECT rp.activityId, " +
                        "COALESCE((SELECT s.type FROM recording_sessions s WHERE s.activityId = rp.activityId), 'CYCLING'), " +
                        "'', NULL, MIN(rp.time), MIN(rp.time), MIN(rp.time) " +
                        "FROM raw_points rp GROUP BY rp.activityId",
                )
            }
        }

        /**
         * Migration 2 → 3: introduce the `sensor_samples` table. Purely additive — no existing
         * table is touched, so every raw point and every activity survives untouched, and a device
         * upgrading from version 2 simply gains an empty table.
         *
         * Nothing is backfilled, because there is nothing to backfill from: no activity recorded
         * before this version carries a sensor reading anywhere. Imported activities get theirs
         * from the file they came out of, on the import's first pass.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sensor_samples` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`activityId` TEXT NOT NULL, `time` INTEGER NOT NULL, " +
                        "`type` TEXT NOT NULL, `value` REAL NOT NULL, " +
                        "FOREIGN KEY(`activityId`) REFERENCES `activities`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sensor_samples_activityId_time` " +
                        "ON `sensor_samples` (`activityId`, `time`)",
                )
            }
        }

        fun get(context: Context): TrailogDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): TrailogDatabase =
            Room.databaseBuilder(context, TrailogDatabase::class.java, "trailog.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // Durability beats throughput at 1 Hz (CLAUDE.md).
                        db.query("PRAGMA synchronous = FULL").close()
                    }
                })
                .build()
    }
}
