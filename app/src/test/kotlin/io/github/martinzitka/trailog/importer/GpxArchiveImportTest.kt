package io.github.martinzitka.trailog.importer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.martinzitka.trailog.core.gpx.Gpx
import io.github.martinzitka.trailog.core.gpx.GpxTrack
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.model.SensorSample
import io.github.martinzitka.trailog.core.model.SensorType
import io.github.martinzitka.trailog.data.ActivityRepository
import io.github.martinzitka.trailog.data.TrailogDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The bulk import, against a real SQLite database under Robolectric.
 *
 * The load-bearing assertions are **idempotence** — a second run over the same archive must not
 * duplicate a history — and that an imported ride's statistics are Trailog's own, recomputed from
 * the points, rather than anything the file claimed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GpxArchiveImportTest {

    private lateinit var db: TrailogDatabase
    private lateinit var repository: ActivityRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TrailogDatabase::class.java,
        ).build()
        repository = ActivityRepository(db) { CLOCK }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun import(bytes: ByteArray): ImportReport =
        GpxArchiveImport.read(
            source = ByteArrayInputStream(bytes),
            exists = { repository.exists(it.toString()) },
            write = {
                repository.importActivity(
                    id = it.id.toString(),
                    type = it.type,
                    name = it.name,
                    notes = it.notes,
                    startTime = it.startTime.toEpochMilliseconds(),
                    points = it.points,
                    incoming = it.samples,
                )
            },
        )

    // ---- the round trip ------------------------------------------------------------------------

    @Test
    fun anArchiveRoundTrips_andTheRideKeepsItsIdentity() = runTest {
        val id = UUID.fromString("019fdd0d-0000-7000-8000-0000000000a1")
        val report = import(archiveOf(track(id, name = "A ride", points = ride())))

        assertEquals(1, report.imported)
        assertEquals(0, report.skipped)
        assertEquals(0, report.failed)

        val loaded = repository.loadActivity(id.toString())!!
        assertEquals("A ride", loaded.name)
        assertEquals(ActivityType.CYCLING, loaded.type)
        assertEquals(4, loaded.points.size)
    }

    @Test
    fun importingTheSameArchiveTwice_addsNothingTheSecondTime() = runTest {
        // The whole point of carrying an id in the file. Re-running after fixing a type mapping has
        // to be safe, or a history gets imported twice and has to be cleaned up by hand.
        val archive = archiveOf(track(UUID.randomUUID(), points = ride()))

        assertEquals(1, import(archive).imported)
        val second = import(archive)

        assertEquals(0, second.imported)
        assertEquals(1, second.skipped)
        assertEquals(1, db.activityDao().allIds().size)
    }

    @Test
    fun aReimport_doesNotOverwriteEditsMadeSinceTheFirstOne() = runTest {
        // Additive only: the name, notes and type a user corrects after importing are exactly what
        // a naive "update in place" would silently throw away.
        val id = UUID.fromString("019fdd0d-0000-7000-8000-0000000000a2")
        val archive = archiveOf(track(id, name = "Original", points = ride()))
        import(archive)
        repository.updateMetadata(id.toString(), "Renamed by hand", "my note", ActivityType.HIKING)

        import(archive)

        val row = db.activityDao().byId(id.toString())!!
        assertEquals("Renamed by hand", row.name)
        assertEquals("my note", row.notes)
        assertEquals("HIKING", row.type)
    }

    // ---- what gets computed --------------------------------------------------------------------

    @Test
    fun statisticsAreRecomputedFromThePoints_notTakenFromTheFile() = runTest {
        val id = UUID.randomUUID()
        import(archiveOf(track(id, points = ride())))

        val stats = db.activityStatsDao().byId(id.toString())!!
        assertEquals(4, stats.pointCount)
        assertTrue("distance is measured, not claimed", stats.distance > 0.0)
        assertEquals(CLOCK, stats.computedAt)
    }

    @Test
    fun segmentsSurviveTheArchive_andTheGapIsNeverBridged() = runTest {
        // Two segments far apart. A straight line between them would add distance the ride never
        // covered, which is the failure this whole codebase is careful about.
        val id = UUID.randomUUID()
        val far = ride(segment = 1, startSecond = 3_600, lon = 15.0)
        import(archiveOf(track(id, points = ride() + far)))

        val stats = db.activityStatsDao().byId(id.toString())!!
        assertEquals(2, stats.segmentCount)
        assertTrue("a bridged gap would be tens of km", stats.distance < 10_000.0)
    }

    @Test
    fun sensorSamplesSurviveTheArchive() = runTest {
        val id = UUID.randomUUID()
        val samples = listOf(
            SensorSample(Instant.fromEpochSeconds(0), SensorType.HEART_RATE, 142.0),
            SensorSample(Instant.fromEpochSeconds(1), SensorType.HEART_RATE, 145.0),
        )
        import(archiveOf(track(id, points = ride(), samples = samples)))

        assertEquals(2, repository.sampleCount(id.toString()))
        assertEquals(samples, repository.loadActivity(id.toString())!!.samples)
    }

    // ---- the awkward files ----------------------------------------------------------------------

    @Test
    fun aTrackWithNoPointsIsImported_ifTheFileSaysWhenItHappened() = runTest {
        // Hand-entered workouts, and rides whose geometry was lost upstream. Summary-only beats
        // losing them, and the note is the only record of what the source claimed.
        val id = UUID.randomUUID()
        val when_ = Instant.parse("2020-09-13T12:26:40Z")
        val gpx = Gpx.write(
            GpxTrack("Typed in", "Source reported 1.90 km", "swimming", emptyList(), activityId = id),
            time = when_,
        )

        assertEquals(1, import(zipOf("a.gpx" to gpx)).imported)

        val row = db.activityDao().byId(id.toString())!!
        assertEquals(when_.toEpochMilliseconds(), row.startTime)
        assertEquals("SWIMMING", row.type)
        assertEquals("Source reported 1.90 km", row.notes)
    }

    @Test
    fun aFileWithNeitherPointsNorADate_isCountedAsFailedRatherThanStored() = runTest {
        val gpx = Gpx.write(GpxTrack("Nothing", null, "cycling", emptyList()))
        val report = import(zipOf("a.gpx" to gpx))

        assertEquals(0, report.imported)
        assertEquals(1, report.failed)
    }

    @Test
    fun oneUnreadableEntry_doesNotCostTheRest() = runTest {
        // An archive can hold a whole history. Aborting on the first bad file would be the worst
        // possible behaviour.
        val good = track(UUID.randomUUID(), points = ride())
        val report = import(
            zipOf(
                "broken.gpx" to "<gpx><trk><trkseg>",
                "good.gpx" to Gpx.write(good, time = Instant.fromEpochSeconds(0)),
            ),
        )

        assertEquals(1, report.imported)
        assertEquals(1, report.failed)
        assertEquals(2, report.total)
    }

    @Test
    fun nonGpxEntriesAreIgnoredEntirely() = runTest {
        val report = import(zipOf("readme.txt" to "not a track", "notes/" to ""))
        assertEquals("a non-GPX entry is not a failure, it is not an activity", 0, report.total)
    }

    @Test
    fun aFileWithNoIdOfItsOwn_stillImports_withATimeOrderedId() = runTest {
        // A plain GPX from another tool. It gets a fresh UUIDv7 minted from its own start time.
        val gpx = Gpx.write(GpxTrack("Foreign", null, "running", ride()))
        assertEquals(1, import(zipOf("a.gpx" to gpx)).imported)

        val id = db.activityDao().allIds().single()
        assertEquals(7, UUID.fromString(id).version())
    }

    @Test
    fun anUnknownTypeBecomesOther_ratherThanRejectingTheRide() = runTest {
        assertEquals(ActivityType.OTHER, GpxArchiveImport.typeOf("kitesurfing"))
        assertEquals(ActivityType.OTHER, GpxArchiveImport.typeOf(null))
        assertEquals(ActivityType.MOUNTAIN_BIKING, GpxArchiveImport.typeOf("mountain biking"))
        assertEquals(ActivityType.CROSS_COUNTRY_SKIING, GpxArchiveImport.typeOf("CROSS-COUNTRY-SKIING"))
    }

    @Test
    fun progressIsReportedForEveryEntry() = runTest {
        val seen = mutableListOf<Int>()
        GpxArchiveImport.read(
            source = ByteArrayInputStream(
                zipOf(
                    "a.gpx" to Gpx.write(track(UUID.randomUUID(), points = ride())),
                    "b.gpx" to Gpx.write(track(UUID.randomUUID(), points = ride())),
                ),
            ),
            exists = { false },
            write = {},
            onProgress = { seen += it },
        )
        assertEquals(listOf(1, 2), seen)
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private fun ride(segment: Int = 0, startSecond: Long = 0, lon: Double = 14.0) =
        List(4) { i ->
            RawPoint(
                latitude = 50.0,
                longitude = lon + i * 0.0005,
                altitude = 200.0,
                accuracy = 5.0,
                time = Instant.fromEpochSeconds(startSecond + i),
                segmentIndex = segment,
            )
        }

    private fun track(
        id: UUID,
        name: String = "Ride",
        points: List<RawPoint>,
        samples: List<SensorSample> = emptyList(),
    ) = GpxTrack(name, null, "cycling", points, samples, activityId = id)

    private fun archiveOf(track: GpxTrack) = zipOf("ride.gpx" to Gpx.write(track))

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, body) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private companion object {
        const val CLOCK = 1_700_000_000_000L
    }
}
