package io.github.martinzitka.trailog.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.martinzitka.trailog.core.geo.Geo
import io.github.martinzitka.trailog.core.model.ActivityType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the M1.4 recompute path — rebuilding the [ActivityStatsEntity] cache from raw points
 * through the single `:core` statistics implementation — against a real SQLite database driven by
 * Robolectric, so it runs on `./gradlew check` / CI without an emulator.
 *
 * The segment-awareness assertion is the important one: two segments far apart must contribute
 * only their in-segment distance, never a straight line bridging the gap (CLAUDE.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityRepositoryTest {

    private lateinit var db: TrailogDatabase
    private lateinit var repository: ActivityRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TrailogDatabase::class.java,
        ).build()
        repository = ActivityRepository(db) { COMPUTED_AT }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun recompute_isSegmentAware_andNeverBridgesTheGap() = runTest {
        // Segment 0: two fixes ~100 m apart. Segment 1: two more ~100 m apart, but 1° of
        // longitude (~71 km at 50°N) away from segment 0 — the classic reboot/blackout gap.
        db.activityDao().upsert(activity(ID_SEG))
        insert(ID_SEG, segment = 0, time = 1_000, lat = 50.0000, lon = 14.0000)
        insert(ID_SEG, segment = 0, time = 2_000, lat = 50.0000, lon = 14.0014)
        insert(ID_SEG, segment = 1, time = 3_000, lat = 50.0000, lon = 15.0000)
        insert(ID_SEG, segment = 1, time = 4_000, lat = 50.0000, lon = 15.0014)

        assertTrue(repository.recompute(ID_SEG))

        val stats = db.activityStatsDao().byId(ID_SEG)
        assertNotNull(stats)
        val expectedWithinSegments =
            Geo.haversine(50.0, 14.0000, 50.0, 14.0014) +
                Geo.haversine(50.0, 15.0000, 50.0, 15.0014)
        val gapChord = Geo.haversine(50.0, 14.0014, 50.0, 15.0000)
        assertTrue("the ~71 km gap must be large enough to be unmissable", gapChord > 50_000)
        assertEquals(expectedWithinSegments, stats!!.distance, 1e-6)
        assertEquals(2, stats.segmentCount)
        assertEquals(4, stats.pointCount)
        assertEquals(COMPUTED_AT, stats.computedAt)
    }

    @Test
    fun recompute_returnsFalse_forUnknownActivity() = runTest {
        assertFalse(repository.recompute("nope"))
        assertNull(db.activityStatsDao().byId("nope"))
    }

    @Test
    fun recompute_overwrites_soFixedAlgorithmsRebuildTheCache() = runTest {
        db.activityDao().upsert(activity(ID_A))
        insert(ID_A, segment = 0, time = 1_000, lat = 50.0, lon = 14.0)
        insert(ID_A, segment = 0, time = 2_000, lat = 50.0, lon = 14.0014)
        repository.recompute(ID_A)
        val first = db.activityStatsDao().byId(ID_A)!!

        // A later fix extends the track; recomputing must reflect the new raw points.
        insert(ID_A, segment = 0, time = 3_000, lat = 50.0, lon = 14.0028)
        repository.recompute(ID_A)
        val second = db.activityStatsDao().byId(ID_A)!!

        assertTrue(second.distance > first.distance)
        assertEquals(3, second.pointCount)
    }

    @Test
    fun recomputeAll_rebuildsEveryActivity() = runTest {
        db.activityDao().upsert(activity(ID_A))
        db.activityDao().upsert(activity(ID_B))
        insert(ID_A, segment = 0, time = 1_000, lat = 50.0, lon = 14.0)
        insert(ID_A, segment = 0, time = 2_000, lat = 50.0, lon = 14.0014)
        insert(ID_B, segment = 0, time = 1_000, lat = 49.0, lon = 16.0)
        insert(ID_B, segment = 0, time = 2_000, lat = 49.0, lon = 16.0014)

        assertEquals(2, repository.recomputeAll())
        assertNotNull(db.activityStatsDao().byId(ID_A))
        assertNotNull(db.activityStatsDao().byId(ID_B))
    }

    @Test
    fun recomputeMissing_fillsTheGaps_andLeavesCachedActivitiesAlone() = runTest {
        db.activityDao().upsert(activity(ID_A))
        db.activityDao().upsert(activity(ID_B))
        insert(ID_A, segment = 0, time = 1_000, lat = 50.0, lon = 14.0)
        insert(ID_A, segment = 0, time = 2_000, lat = 50.0, lon = 14.0014)
        insert(ID_B, segment = 0, time = 1_000, lat = 49.0, lon = 16.0)
        insert(ID_B, segment = 0, time = 2_000, lat = 49.0, lon = 16.0014)

        // A is already cached; B stands in for a migration-backfilled activity with no stats.
        repository.recompute(ID_A)
        val cachedA = db.activityStatsDao().byId(ID_A)!!
        // A grows a third fix. A recompute of A would notice; the backfill must not touch it.
        insert(ID_A, segment = 0, time = 3_000, lat = 50.0, lon = 14.0028)

        assertEquals(1, repository.recomputeMissing())

        assertNotNull(db.activityStatsDao().byId(ID_B))
        assertEquals(cachedA.distance, db.activityStatsDao().byId(ID_A)!!.distance, 1e-9)
        assertEquals(2, db.activityStatsDao().byId(ID_A)!!.pointCount)
    }

    @Test
    fun recomputeMissing_doesNothing_whenEveryActivityIsCached() = runTest {
        db.activityDao().upsert(activity(ID_A))
        insert(ID_A, segment = 0, time = 1_000, lat = 50.0, lon = 14.0)
        repository.recompute(ID_A)

        assertEquals(0, repository.recomputeMissing())
    }

    // ---- metadata editing ------------------------------------------------------------------

    @Test
    fun updateMetadata_persistsTheEditableFields_andLeavesTheRecordingTimesAlone() = runTest {
        db.activityDao().upsert(activity(ID_A))
        insert(ID_A, segment = 0, time = 1_000, lat = 50.0, lon = 14.0)

        repository.updateMetadata(ID_A, "Evening loop", "Muddy after the rain", ActivityType.HIKING)

        val row = db.activityDao().byId(ID_A)!!
        assertEquals("Evening loop", row.name)
        assertEquals("Muddy after the rain", row.notes)
        assertEquals(ActivityType.HIKING.name, row.type)
        assertEquals("the recording's start time is not editable metadata", 1_000L, row.startTime)
        assertEquals(1_000L, row.createdAt)
        assertEquals(COMPUTED_AT, row.updatedAt)
    }

    @Test
    fun updateMetadata_blankNotesAreStoredAsNull_notAsAnEmptyString() = runTest {
        db.activityDao().upsert(activity(ID_A))

        repository.updateMetadata(ID_A, "Ride", "   ", ActivityType.CYCLING)

        assertNull(db.activityDao().byId(ID_A)!!.notes)
    }

    @Test
    fun updateMetadata_recomputesStats_becauseTypeChangesTheMovingThreshold() = runTest {
        db.activityDao().upsert(activity(ID_A))
        // ~7.2 m per 20 s hop is ~0.36 m/s: above the walking threshold (0.3), below the
        // cycling one (0.8). The same fixes are therefore "moving" as a walk and "stopped"
        // as a ride, which is exactly what a type edit has to re-derive.
        insert(ID_A, segment = 0, time = 0, lat = 50.0, lon = 14.0000)
        insert(ID_A, segment = 0, time = 20_000, lat = 50.0, lon = 14.0001)
        insert(ID_A, segment = 0, time = 40_000, lat = 50.0, lon = 14.0002)
        repository.updateMetadata(ID_A, "", null, ActivityType.CYCLING)
        val asCycling = db.activityStatsDao().byId(ID_A)!!.movingTime

        repository.updateMetadata(ID_A, "", null, ActivityType.WALKING)
        val asWalking = db.activityStatsDao().byId(ID_A)!!.movingTime

        assertTrue(
            "moving time must be recomputed against the new type's threshold " +
                "(cycling $asCycling, walking $asWalking)",
            asWalking > asCycling,
        )
    }

    // ---- delete ----------------------------------------------------------------------------

    @Test
    fun delete_removesTheActivity_itsStats_andItsRawPoints() = runTest {
        db.activityDao().upsert(activity(ID_A))
        insert(ID_A, segment = 0, time = 1_000, lat = 50.0, lon = 14.0)
        insert(ID_A, segment = 0, time = 2_000, lat = 50.0, lon = 14.0014)
        repository.recompute(ID_A)

        assertTrue(repository.delete(ID_A))

        assertNull(db.activityDao().byId(ID_A))
        assertNull(db.activityStatsDao().byId(ID_A))
        assertEquals(
            "a deleted activity must leave no coordinates behind",
            0,
            db.rawPointDao().countFor(ID_A),
        )
    }

    @Test
    fun delete_leavesOtherActivitiesPointsUntouched() = runTest {
        db.activityDao().upsert(activity(ID_A))
        db.activityDao().upsert(activity(ID_B))
        insert(ID_A, segment = 0, time = 1_000, lat = 50.0, lon = 14.0)
        insert(ID_B, segment = 0, time = 1_000, lat = 49.0, lon = 16.0)
        insert(ID_B, segment = 0, time = 2_000, lat = 49.0, lon = 16.0014)

        repository.delete(ID_A)

        assertNotNull(db.activityDao().byId(ID_B))
        assertEquals(2, db.rawPointDao().countFor(ID_B))
    }

    @Test
    fun delete_returnsFalse_forUnknownActivity() = runTest {
        assertFalse(repository.delete("nope"))
    }

    private fun activity(id: String) = ActivityEntity(
        id = id,
        type = "CYCLING",
        name = "",
        notes = null,
        startTime = 1_000,
        createdAt = 1_000,
        updatedAt = 1_000,
    )

    private suspend fun insert(
        activityId: String,
        segment: Int,
        time: Long,
        lat: Double,
        lon: Double,
    ) = db.rawPointDao().insert(
        RawPointEntity(
            activityId = activityId,
            segmentIndex = segment,
            time = time,
            recordedAt = time,
            latitude = lat,
            longitude = lon,
            altitude = null,
            accuracy = 5.0,
            speed = null,
            bearing = null,
            pressure = null,
        ),
    )

    private companion object {
        const val COMPUTED_AT = 1_700_000_000_000L
        // Real activity ids are UUIDv7 strings; the recompute path parses them via UUID.fromString.
        const val ID_SEG = "019fe178-0000-7000-8000-000000000001"
        const val ID_A = "019fe178-0000-7000-8000-000000000002"
        const val ID_B = "019fe178-0000-7000-8000-000000000003"
    }
}
