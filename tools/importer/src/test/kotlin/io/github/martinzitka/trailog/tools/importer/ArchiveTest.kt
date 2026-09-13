package io.github.martinzitka.trailog.tools.importer

import io.github.martinzitka.trailog.core.gpx.Gpx
import io.github.martinzitka.trailog.core.model.ActivityType
import kotlinx.datetime.Instant
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The catalogue reader and the archive writer. Shapes only — no real ride data in a public repo. */
class ArchiveTest {

    // ---- the workout catalogue -----------------------------------------------------------------

    @Test
    fun `the catalogue is found whatever envelope wraps it`() {
        // Bound to "the biggest array of objects in the tree", not to a shape anyone promised us.
        val json = """
            {"meta":{"page":1},"payload":[
              {"workoutKey":"a","description":"Title A","activityId":2,
               "startTime":1600000000000,"stopTime":1600003600000,
               "totalDistance":25000.0,"totalTime":3000,"isManuallyAdded":false},
              {"workoutKey":"b","description":"Title B","activityId":17,
               "startTime":1600100000000,"stopTime":1600101800000,
               "totalDistance":1900.0,"totalTime":1500,"isManuallyAdded":true}
            ]}
        """.trimIndent()

        val catalogue = WorkoutCatalog.parse(json)
        assertEquals(setOf("a", "b"), catalogue.keys)
        val a = catalogue.getValue("a")
        assertEquals("Title A", a.title)
        assertEquals("2", a.activityId)
        assertEquals(25_000.0, a.distance)
        assertEquals(3_000L, a.movingTime!!.inWholeSeconds)
        assertEquals(3_600L, a.elapsedTime!!.inWholeSeconds)
        assertTrue(catalogue.getValue("b").manuallyAdded)
    }

    @Test
    fun `moving time and elapsed time stay separate`() {
        // totalTime is moving seconds, stop-start is elapsed millis. Conflating them would make
        // every imported ride look like it never stopped.
        val catalogue = WorkoutCatalog.parse(
            """[{"workoutKey":"a","startTime":1600000000000,"stopTime":1600003600000,"totalTime":1800}]""",
        )
        val w = catalogue.getValue("a")
        assertEquals(1_800L, w.movingTime!!.inWholeSeconds)
        assertEquals(3_600L, w.elapsedTime!!.inWholeSeconds)
        assertTrue(w.movingTime!! < w.elapsedTime!!)
    }

    @Test
    fun `a workout with no key is skipped rather than crashing the read`() {
        val catalogue = WorkoutCatalog.parse("""[{"description":"orphan"},{"workoutKey":"a"}]""")
        assertEquals(setOf("a"), catalogue.keys)
    }

    @Test
    fun `zero distance and zero duration read as absent, not as a real figure`() {
        val catalogue = WorkoutCatalog.parse(
            """[{"workoutKey":"a","totalDistance":0,"totalTime":0}]""",
        )
        val w = catalogue.getValue("a")
        assertEquals(null, w.distance)
        assertEquals(null, w.movingTime)
    }

    // ---- naming ---------------------------------------------------------------------------------

    @Test
    fun `entry names mirror the app's own export naming`() {
        assertEquals(
            "trailog-morning-loop-2020-09-13-1226.gpx",
            ImportArchive.entryName(
                "Morning Loop",
                "CYCLING",
                Instant.parse("2020-09-13T12:26:40Z"),
            ),
        )
    }

    @Test
    fun `a nameless workout is named after its type`() {
        assertEquals(
            "trailog-cycling-2020-09-13-1226.gpx",
            ImportArchive.entryName(null, "CYCLING", Instant.parse("2020-09-13T12:26:40Z")),
        )
    }

    @Test
    fun `a name that slugs to nothing still produces a usable file name`() {
        assertEquals("activity", ImportArchive.slug("————"))
    }

    // ---- the archive -----------------------------------------------------------------------------

    @Test
    fun `every workout becomes one entry, and reads back through the one GPX reader`() {
        val out = ByteArrayOutputStream()
        val report = ImportArchive.write(out, sequenceOf(withTrack("a"), trackless("b")))

        assertEquals(2, report.written)
        assertEquals(1, report.trackless)
        assertEquals(2, report.totalPoints)

        val entries = read(out)
        assertEquals(2, entries.size)
        for (gpx in entries.values) {
            // Valid GPX that the project's single reader accepts, with its identity intact.
            assertTrue(Gpx.read(gpx).single().activityId != null)
        }
    }

    @Test
    fun `a track-less entry carries its date in the document metadata`() {
        // Its only record of when it happened — there is no fix to read one from.
        val out = ByteArrayOutputStream()
        ImportArchive.write(out, sequenceOf(trackless("b")))
        val gpx = read(out).values.single()

        assertEquals(START, Gpx.readDocument(gpx).time)
        assertTrue(read(out).keys.single().contains("2020-09-13"), read(out).keys.single())
    }

    @Test
    fun `workouts that would collide on name are kept apart`() {
        val out = ByteArrayOutputStream()
        ImportArchive.write(out, sequenceOf(withTrack("a"), withTrack("b"), withTrack("c")))
        assertEquals(3, read(out).size, "no entry may silently overwrite another")
    }

    @Test
    fun `the report counts unmapped ids by id`() {
        val out = ByteArrayOutputStream()
        val report = ImportArchive.write(
            out,
            sequenceOf(withTrack("a", source = TypeSource.FALLBACK, activityId = "999")),
        )
        assertEquals(mapOf("999" to 1), report.unmappedActivityIds)
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private val START = Instant.parse("2020-09-13T12:26:40Z")

    private fun withTrack(
        key: String,
        source: TypeSource = TypeSource.MAPPING,
        activityId: String? = "2",
    ): CanonicalWorkout {
        val gpx = """
            <gpx version="1.1" creator="Sports Tracker" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><trkseg>
                <trkpt lat="50.0" lon="14.0"><time>2020-09-13T12:26:40Z</time></trkpt>
                <trkpt lat="50.001" lon="14.001"><time>2020-09-13T12:26:41Z</time></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()
        val track = Gpx.read(gpx).single()
        return CanonicalWorkout(
            key = key,
            track = track.copy(name = "Ride", activityId = Canonical.activityIdFor(key)),
            type = ActivityType.CYCLING,
            typeSource = source,
            sourceActivityId = activityId,
            startTime = START,
            pointCount = track.points.size,
            sampleCount = 0,
            trackless = false,
        )
    }

    private fun trackless(key: String) = Canonical.workout(
        key = key,
        gpx = null,
        summary = WorkoutSummary(
            key = key,
            title = "Typed in",
            activityId = "17",
            startTime = START,
            stopTime = null,
            distance = 1_900.0,
            movingTime = null,
            manuallyAdded = true,
        ),
        mapping = TypeMapping(mapOf("17" to ActivityType.SWIMMING), emptyMap()),
    )

    private fun read(out: ByteArrayOutputStream): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
