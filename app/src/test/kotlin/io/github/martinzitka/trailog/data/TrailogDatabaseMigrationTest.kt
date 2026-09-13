package io.github.martinzitka.trailog.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration tests. The developer's device carries real, irreplaceable recordings, so every upgrade
 * must be non-destructive: each raw point written under an older schema has to survive, and nothing
 * already recorded may be lost or rewritten on the way up.
 *
 * Rather than Room's instrumentation-only `MigrationTestHelper`, these build the old database by
 * hand from the schema of the day and open it through Room with the real migrations under
 * Robolectric — so they run on CI without an emulator, and Room's own post-migration schema
 * validation still fires (an incorrect migration throws on open).
 *
 * The 1 → 2 case also covers 1 → 3, since Room applies the chain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrailogDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"

    @Before
    fun clean() {
        context.deleteDatabase(dbName)
    }

    @After
    fun cleanup() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrate1To2_preservesRawPoints_andBackfillsActivities() = runTest {
        createVersion1Database()

        val db = Room.databaseBuilder(context, TrailogDatabase::class.java, dbName)
            .addMigrations(TrailogDatabase.MIGRATION_1_2, TrailogDatabase.MIGRATION_2_3)
            .build()
        try {
            // Raw points are sacred: every fix recorded under v1 survives the upgrade untouched.
            assertEquals(3, db.rawPointDao().countFor(RIDE))
            assertEquals(1, db.rawPointDao().countFor(STUB))

            // Each recording is backfilled into the new activities table, earliest fix as start.
            val ride = db.activityDao().byId(RIDE)
            assertNotNull(ride)
            assertEquals("CYCLING", ride!!.type)
            assertEquals(1_000L, ride.startTime)
            assertEquals("", ride.name)

            val stub = db.activityDao().byId(STUB)
            assertNotNull(stub)
            assertEquals(5_000L, stub!!.startTime)

            assertEquals(2, db.activityDao().allIds().size)

            // The recompute path works end to end against the migrated data.
            val repository = ActivityRepository(db) { 999L }
            assertTrue(repository.recompute(RIDE))
            val stats = db.activityStatsDao().byId(RIDE)
            assertNotNull(stats)
            assertEquals(2, stats!!.segmentCount)
            assertEquals(3, stats.pointCount)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate2To3_addsAnEmptySensorTable_andLeavesEverythingElseAlone() = runTest {
        createVersion2Database()

        val db = Room.databaseBuilder(context, TrailogDatabase::class.java, dbName)
            .addMigrations(TrailogDatabase.MIGRATION_1_2, TrailogDatabase.MIGRATION_2_3)
            .build()
        try {
            // Purely additive: the fixes and the activity recorded under v2 are untouched.
            assertEquals(2, db.rawPointDao().countFor(RIDE))
            assertNotNull(db.activityDao().byId(RIDE))
            assertEquals("CYCLING", db.activityDao().byId(RIDE)!!.type)

            // The new table exists and starts empty — no activity recorded before v3 carries a
            // sensor reading anywhere, so there is nothing to backfill it from.
            assertEquals(0, db.sensorSampleDao().countFor(RIDE))

            // And it is writable straight away, against the activity that was already there.
            db.sensorSampleDao().insert(
                SensorSampleEntity(activityId = RIDE, time = 1_500, type = "HEART_RATE", value = 142.0),
            )
            assertEquals(1, db.sensorSampleDao().countFor(RIDE))
        } finally {
            db.close()
        }
    }

    /** Builds the exact M1.3 (version 1) schema and seeds two recorded activities. */
    private fun createVersion1Database() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(RAW_POINTS_V1)
                        db.execSQL(RAW_POINTS_INDEX_V1)
                        db.execSQL(RECORDING_SESSIONS_V1)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase.use { db ->
            // RIDE: two segments (a gap), three fixes.
            db.execSQL(insertPoint(RIDE, 0, 1_000))
            db.execSQL(insertPoint(RIDE, 0, 2_000))
            db.execSQL(insertPoint(RIDE, 1, 3_000))
            // STUB: a single later fix — its start time is the earliest (only) fix.
            db.execSQL(insertPoint(STUB, 0, 5_000))
        }
        helper.close()
    }

    /** Builds the version-2 schema — the M1.4 shape, with `activities` and `activity_stats`. */
    private fun createVersion2Database() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(RAW_POINTS_V1)
                        db.execSQL(RAW_POINTS_INDEX_V1)
                        db.execSQL(RECORDING_SESSIONS_V1)
                        db.execSQL(ACTIVITIES_V2)
                        db.execSQL(ACTIVITY_STATS_V2)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO activities (id, type, name, notes, startTime, createdAt, updatedAt) " +
                    "VALUES ('$RIDE', 'CYCLING', 'Morning ride', NULL, 1000, 1000, 1000)",
            )
            db.execSQL(insertPoint(RIDE, 0, 1_000))
            db.execSQL(insertPoint(RIDE, 0, 2_000))
        }
        helper.close()
    }

    private fun insertPoint(activityId: String, segment: Int, time: Long): String =
        "INSERT INTO raw_points " +
            "(activityId, segmentIndex, time, recordedAt, latitude, longitude, altitude, accuracy, speed, bearing, pressure) " +
            "VALUES ('$activityId', $segment, $time, $time, 50.0, 14.0, 200.0, 5.0, 3.0, 90.0, NULL)"

    private companion object {
        // Activity ids are UUIDv7 strings, as the recording engine mints them.
        const val RIDE = "019fdd0d-0000-7000-8000-000000000001"
        const val STUB = "019fdd0d-0000-7000-8000-000000000002"

        // The version-1 tables exactly as Room generated them under M1.3, unchanged in v2.
        const val RAW_POINTS_V1 =
            "CREATE TABLE IF NOT EXISTS `raw_points` " +
                "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `activityId` TEXT NOT NULL, " +
                "`segmentIndex` INTEGER NOT NULL, `time` INTEGER NOT NULL, `recordedAt` INTEGER NOT NULL, " +
                "`latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `altitude` REAL, `accuracy` REAL, " +
                "`speed` REAL, `bearing` REAL, `pressure` REAL)"
        const val RAW_POINTS_INDEX_V1 =
            "CREATE INDEX IF NOT EXISTS `index_raw_points_activityId_time` " +
                "ON `raw_points` (`activityId`, `time`)"
        const val RECORDING_SESSIONS_V1 =
            "CREATE TABLE IF NOT EXISTS `recording_sessions` " +
                "(`activityId` TEXT NOT NULL, `type` TEXT NOT NULL, `state` TEXT NOT NULL, " +
                "`startTime` INTEGER NOT NULL, `currentSegmentIndex` INTEGER NOT NULL, " +
                "`heartbeat` INTEGER, PRIMARY KEY(`activityId`))"

        // The version-2 tables exactly as the 1 → 2 migration and Room generated them.
        const val ACTIVITIES_V2 =
            "CREATE TABLE IF NOT EXISTS `activities` " +
                "(`id` TEXT NOT NULL, `type` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`notes` TEXT, `startTime` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        const val ACTIVITY_STATS_V2 =
            "CREATE TABLE IF NOT EXISTS `activity_stats` " +
                "(`activityId` TEXT NOT NULL, `distance` REAL NOT NULL, " +
                "`elapsedTime` INTEGER NOT NULL, `movingTime` INTEGER NOT NULL, " +
                "`averageSpeed` REAL NOT NULL, `maxSpeed` REAL NOT NULL, " +
                "`elevationGain` REAL NOT NULL, `elevationLoss` REAL NOT NULL, " +
                "`segmentCount` INTEGER NOT NULL, `pointCount` INTEGER NOT NULL, " +
                "`computedAt` INTEGER NOT NULL, PRIMARY KEY(`activityId`), " +
                "FOREIGN KEY(`activityId`) REFERENCES `activities`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
    }
}
