package io.github.martinzitka.trailog.tools.importer

import io.github.martinzitka.trailog.core.gpx.Gpx
import io.github.martinzitka.trailog.core.model.ActivityType
import io.github.martinzitka.trailog.core.model.SensorType
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The producer-specific half of the migration. Every fixture here is a *shape* — no real ride
 * title, workout key or coordinate appears in this repo (it is public; see the export's README).
 */
class CanonicalTest {

    private val mapping = TypeMapping(
        byActivityId = mapOf("2" to ActivityType.CYCLING, "4" to ActivityType.SNOWKITING),
        byWorkoutKey = mapOf("corrected" to ActivityType.BOBSLEIGH),
    )

    private fun summary(
        key: String = "w1",
        title: String? = "Placeholder title",
        activityId: String? = "2",
        start: Long? = 1_600_000_000_000,
        stop: Long? = 1_600_003_600_000,
        distance: Double? = 25_000.0,
        movingSeconds: Long? = 3_000,
        manual: Boolean = false,
    ) = WorkoutSummary(
        key = key,
        title = title,
        activityId = activityId,
        startTime = start?.let { Instant.fromEpochMilliseconds(it) },
        stopTime = stop?.let { Instant.fromEpochMilliseconds(it) },
        distance = distance,
        movingTime = movingSeconds?.let { kotlin.time.Duration.parse("${it}s") },
        manuallyAdded = manual,
    )

    private fun gpxOf(vararg times: String, creator: String = "Sports Tracker"): String {
        val points = times.joinToString("\n") {
            """<trkpt lat="50.0" lon="14.0"><ele>200</ele><time>$it</time></trkpt>"""
        }
        return """
            <gpx version="1.1" creator="$creator" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata><name>9/13/26 18:00</name><desc>The real title</desc></metadata>
              <trk><name>9/13/26 18:00</name><trkseg>
                $points
              </trkseg></trk>
            </gpx>
        """.trimIndent()
    }

    // ---- identity -----------------------------------------------------------------------------

    @Test
    fun `the activity id is derived from the workout key and is stable`() {
        // The whole basis of re-running the import: same key, same id, so a second run updates
        // rather than duplicating the whole history.
        val first = Canonical.activityIdFor("abc123")
        assertEquals(first, Canonical.activityIdFor("abc123"))
        assertTrue(first != Canonical.activityIdFor("abc124"))
        assertEquals(5, first.version(), "RFC 4122 name-based UUID")
        assertEquals(2, first.variant())
    }

    @Test
    fun `the id reaches the written file and survives a read`() {
        val workout = Canonical.workout("w1", gpxOf("2026-09-13T18:00:00Z", "2026-09-13T18:00:01Z"), summary(), mapping)
        val reparsed = Gpx.read(Gpx.write(workout.track)).single()
        assertEquals(Canonical.activityIdFor("w1"), reparsed.activityId)
    }

    // ---- the Sports Tracker inversion ----------------------------------------------------------

    @Test
    fun `the title comes from the catalogue, and the date-shaped name is discarded`() {
        val workout = Canonical.workout("w1", gpxOf("2026-09-13T18:00:00Z", "2026-09-13T18:00:01Z"), summary(), mapping)
        assertEquals("Placeholder title", workout.track.name)
    }

    @Test
    fun `without a catalogue title the inverted metadata desc is used`() {
        val workout = Canonical.workout(
            "w1",
            gpxOf("2026-09-13T18:00:00Z", "2026-09-13T18:00:01Z"),
            summary(title = null),
            mapping,
        )
        assertEquals("The real title", workout.track.name)
    }

    @Test
    fun `the inversion is gated on the creator, so a foreign file keeps GPX's meaning`() {
        // A desc is a description everywhere except Sports Tracker. Applying the correction to
        // every file would silently rename rides from other tools.
        val workout = Canonical.workout(
            "w1",
            gpxOf("2026-09-13T18:00:00Z", "2026-09-13T18:00:01Z", creator = "SomeOtherApp"),
            summary(title = null),
            mapping,
        )
        assertNull(workout.track.name)
    }

    // ---- type resolution -----------------------------------------------------------------------

    @Test
    fun `a mapped activity id resolves through the mapping file`() {
        val workout = Canonical.workout("w1", gpxOf("2026-09-13T18:00:00Z", "2026-09-13T18:00:01Z"), summary(), mapping)
        assertEquals(ActivityType.CYCLING, workout.type)
        assertEquals(TypeSource.MAPPING, workout.typeSource)
    }

    @Test
    fun `a per-workout override beats the id mapping`() {
        // One generic id held two unrelated sports across different years, which no id rule can fix.
        val workout = Canonical.workout(
            "corrected",
            gpxOf("2026-09-13T18:00:00Z", "2026-09-13T18:00:01Z"),
            summary(key = "corrected", activityId = "4"),
            mapping,
        )
        assertEquals(ActivityType.BOBSLEIGH, workout.type)
        assertEquals(TypeSource.OVERRIDE, workout.typeSource)
    }

    @Test
    fun `an unknown id falls back to OTHER and is reported, not fatal`() {
        // Failing the whole run over one unmapped id would make the mapping impossible to iterate.
        val workout = Canonical.workout(
            "w1",
            gpxOf("2026-09-13T18:00:00Z", "2026-09-13T18:00:01Z"),
            summary(activityId = "999"),
            mapping,
        )
        assertEquals(ActivityType.OTHER, workout.type)
        assertEquals(TypeSource.FALLBACK, workout.typeSource)
        assertEquals("999", workout.sourceActivityId)
    }

    // ---- track-less workouts -------------------------------------------------------------------

    @Test
    fun `a hand-entered workout keeps its summary in a note, labelled as the source's`() {
        val workout = Canonical.workout("w1", null, summary(manual = true), mapping)

        assertTrue(workout.trackless)
        assertEquals(0, workout.pointCount)
        val note = assertNotNull(workout.track.description)
        assertTrue(note.contains("Sports Tracker"), note)
        assertTrue(note.contains("25.00 km"), note)
        assertTrue(note.contains("never had a track"), note)
    }

    @Test
    fun `a lost track says so, rather than reading as hand-entered`() {
        // isManuallyAdded is the only thing separating "never had a track" from "lost its track",
        // and distance cannot tell them apart. Years later the note is the only record of which.
        val workout = Canonical.workout("w1", null, summary(manual = false), mapping)
        val note = assertNotNull(workout.track.description)
        assertTrue(note.contains("refused to export"), note)
    }

    @Test
    fun `a track-less workout still knows when it happened`() {
        // The bug this exists to stop: with no fix to read a date from, these imported undated.
        val workout = Canonical.workout("w1", null, summary(manual = true), mapping)
        assertEquals(Instant.fromEpochMilliseconds(1_600_000_000_000), workout.startTime)

        val document = Gpx.readDocument(Gpx.write(workout.track, time = workout.startTime))
        assertEquals(workout.startTime, document.time)
    }

    @Test
    fun `a single-point export is treated as track-less but the point is kept`() {
        // Four real 41-49 km rides exported with one point. It is not a usable track — but raw
        // points are never filtered on ingest, and a lone fix is still true.
        val workout = Canonical.workout("w1", gpxOf("2026-09-13T18:00:00Z"), summary(), mapping)
        assertTrue(workout.trackless)
        assertEquals(1, workout.pointCount)
        assertTrue(assertNotNull(workout.track.description).contains("only 1 track point"))
    }

    @Test
    fun `a real track carries no note`() {
        val workout = Canonical.workout("w1", gpxOf("2026-09-13T18:00:00Z", "2026-09-13T18:00:01Z"), summary(), mapping)
        assertNull(workout.track.description)
        assertEquals(2, workout.pointCount)
    }

    // ---- sensors -------------------------------------------------------------------------------

    @Test
    fun `per-point heart rate survives into the canonical track`() {
        // Some workouts in a history carry it, and the importer must write them complete on the
        // first pass — a re-run recognises the activity and would not backfill it.
        val gpx = """
            <gpx version="1.1" creator="Sports Tracker" xmlns="http://www.topografix.com/GPX/1/1"
                 xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
              <trk><trkseg>
                <trkpt lat="50.0" lon="14.0"><time>2026-09-13T18:00:00Z</time>
                  <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>142</gpxtpx:hr>
                  </gpxtpx:TrackPointExtension></extensions></trkpt>
                <trkpt lat="50.001" lon="14.001"><time>2026-09-13T18:00:01Z</time>
                  <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>145</gpxtpx:hr>
                  </gpxtpx:TrackPointExtension></extensions></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val workout = Canonical.workout("w1", gpx, summary(), mapping)
        assertEquals(2, workout.sampleCount)

        val reparsed = Gpx.read(Gpx.write(workout.track)).single()
        assertEquals(
            listOf(142.0, 145.0),
            reparsed.samples.filter { it.type == SensorType.HEART_RATE }.map { it.value },
        )
    }

    // ---- mapping files -------------------------------------------------------------------------

    @Test
    fun `the mapping reader skips comments and blank lines`() {
        val parsed = TypeMapping.parse(
            listOf("# a comment", "", "2\tCYCLING", "10\tMOUNTAIN_BIKING"),
            "activity-types.tsv",
            valueColumn = 1,
        )
        assertEquals(mapOf("2" to ActivityType.CYCLING, "10" to ActivityType.MOUNTAIN_BIKING), parsed)
    }

    @Test
    fun `the overrides reader ignores the trailing note column`() {
        val parsed = TypeMapping.parse(
            listOf("key1\tBOBSLEIGH\tid 4 is otherwise snowkiting"),
            "type-overrides.tsv",
            valueColumn = 1,
        )
        assertEquals(mapOf("key1" to ActivityType.BOBSLEIGH), parsed)
    }

    @Test
    fun `a misspelled type name fails loudly, with its line number`() {
        // These files are hand-written and the whole import hangs off them. A typo silently
        // becoming OTHER would be discovered months later, after everything had been renamed.
        val error = assertFailsWith<IllegalArgumentException> {
            TypeMapping.parse(listOf("# head", "2\tCYCLNIG"), "activity-types.tsv", valueColumn = 1)
        }
        assertTrue(error.message!!.contains("activity-types.tsv:2"), error.message!!)
        assertTrue(error.message!!.contains("CYCLNIG"), error.message!!)
    }

    @Test
    fun `a short line fails rather than being skipped`() {
        assertFailsWith<IllegalArgumentException> {
            TypeMapping.parse(listOf("2"), "activity-types.tsv", valueColumn = 1)
        }
    }
}
