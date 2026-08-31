package io.github.martinzitka.trailog.core.gpx

import io.github.martinzitka.trailog.core.model.RawPoint
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpxTest {

    private fun pt(
        sec: Long,
        seg: Int,
        lat: Double,
        lon: Double,
        alt: Double? = null,
        accuracy: Double? = null,
        speed: Double? = null,
        bearing: Double? = null,
        pressure: Double? = null,
    ) = RawPoint(
        latitude = lat,
        longitude = lon,
        altitude = alt,
        accuracy = accuracy,
        time = Instant.fromEpochSeconds(sec),
        speed = speed,
        bearing = bearing,
        pressure = pressure,
        segmentIndex = seg,
    )

    @Test
    fun `reads a plain single-segment gpx from another app`() {
        val xml = """
            <?xml version="1.0"?>
            <gpx version="1.1" creator="SomeApp" xmlns="http://www.topografix.com/GPX/1/1">
              <trk>
                <name>Morning ride</name>
                <type>cycling</type>
                <trkseg>
                  <trkpt lat="50.0000" lon="14.0000"><ele>200.0</ele><time>2026-08-03T18:23:12Z</time></trkpt>
                  <trkpt lat="50.0001" lon="14.0001"><ele>201.5</ele><time>2026-08-03T18:23:13Z</time></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val tracks = Gpx.read(xml)
        assertEquals(1, tracks.size)
        val t = tracks.single()
        assertEquals("Morning ride", t.name)
        assertEquals("cycling", t.type)
        assertEquals(2, t.points.size)
        assertEquals(50.0001, t.points[1].latitude, 1e-9)
        assertEquals(201.5, t.points[1].altitude)
        assertEquals(Instant.parse("2026-08-03T18:23:12Z"), t.points[0].time)
        assertEquals(0, t.points[0].segmentIndex)
        // A foreign file carries no Trailog extension fields.
        assertNull(t.points[0].accuracy)
    }

    @Test
    fun `multiple trkseg elements become distinct segment indices`() {
        val xml = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk>
                <trkseg>
                  <trkpt lat="50.0" lon="14.0"><time>2026-08-03T18:00:00Z</time></trkpt>
                  <trkpt lat="50.0" lon="14.0001"><time>2026-08-03T18:00:01Z</time></trkpt>
                </trkseg>
                <trkseg>
                  <trkpt lat="50.01" lon="14.01"><time>2026-08-03T18:05:00Z</time></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val pts = Gpx.read(xml).single().points
        assertEquals(listOf(0, 0, 1), pts.map { it.segmentIndex })
    }

    @Test
    fun `time is required per trkpt`() {
        val xml = """
            <gpx xmlns="http://www.topografix.com/GPX/1/1">
              <trk><trkseg>
                <trkpt lat="50.0" lon="14.0"><ele>200</ele></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()
        assertFailsWith<GpxParseException> { Gpx.read(xml) }
    }

    @Test
    fun `malformed xml throws GpxParseException`() {
        assertFailsWith<GpxParseException> { Gpx.read("<gpx><trk><trkseg>") }
    }

    @Test
    fun `written gpx is valid and preserves segment structure`() {
        val track = GpxTrack(
            name = "Test",
            description = null,
            type = "cycling",
            points = listOf(
                pt(0, 0, 50.0, 14.0, alt = 200.0),
                pt(1, 0, 50.0, 14.0001, alt = 201.0),
                pt(300, 1, 50.01, 14.01, alt = 210.0),
            ),
        )
        val xml = Gpx.write(track)
        // Two segments in, two <trkseg> out.
        assertEquals(2, Regex("<trkseg>").findAll(xml).count())
        assertTrue(xml.contains("http://www.topografix.com/GPX/1/1"))
        assertTrue(xml.contains("creator=\"Trailog\""))
        // Re-reading yields the same structure.
        assertEquals(listOf(0, 0, 1), Gpx.read(xml).single().points.map { it.segmentIndex })
    }

    @Test
    fun `round-trips a phone activity without losing non-core fields`() {
        val original = GpxTrack(
            name = "Durable ride",
            description = "Notes that must survive",
            type = "mountain_biking",
            points = listOf(
                pt(0, 0, 50.0, 14.0, alt = 200.0, accuracy = 1.5, speed = 3.2, bearing = 90.0, pressure = 101325.0),
                pt(1, 0, 50.0001, 14.0001, alt = 201.5, accuracy = 1.7, speed = 3.4, bearing = 91.0, pressure = 101320.0),
                pt(180, 1, 50.005, 14.005, alt = 205.0, accuracy = 2.0, speed = 0.0, bearing = null, pressure = 101300.0),
            ),
        )

        val reparsed = Gpx.read(Gpx.write(original)).single()

        assertEquals(original.name, reparsed.name)
        assertEquals(original.description, reparsed.description)
        assertEquals(original.type, reparsed.type)
        assertEquals(original.points, reparsed.points)
    }

    @Test
    fun `round-trips tiny values without scientific notation`() {
        // A near-zero speed must not serialize as 1.0E-4 (some parsers reject it).
        val track = GpxTrack(null, null, null, listOf(pt(0, 0, 50.0, 14.0, speed = 0.0001)))
        val xml = Gpx.write(track)
        assertTrue(xml.contains("0.0001"), "expected plain decimal, got:\n$xml")
        assertTrue(!xml.contains("E-", ignoreCase = true), "must not use scientific notation")
        assertEquals(0.0001, Gpx.read(xml).single().points[0].speed)
    }

    @Test
    fun `ignores foreign extensions like heart rate`() {
        val xml = """
            <gpx xmlns="http://www.topografix.com/GPX/1/1"
                 xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
              <trk><trkseg>
                <trkpt lat="50.0" lon="14.0">
                  <ele>200</ele>
                  <time>2026-08-03T18:00:00Z</time>
                  <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>142</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>
                </trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val p = Gpx.read(xml).single().points.single()
        assertEquals(200.0, p.altitude)
        assertNull(p.pressure)
    }

    @Test
    fun `reads timestamps with a timezone offset`() {
        val xml = """
            <gpx xmlns="http://www.topografix.com/GPX/1/1">
              <trk><trkseg>
                <trkpt lat="50.0" lon="14.0"><time>2026-08-03T20:00:00+02:00</time></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()
        // 20:00 at +02:00 is 18:00 UTC.
        assertEquals(Instant.parse("2026-08-03T18:00:00Z"), Gpx.read(xml).single().points[0].time)
    }

    // ---- document-level metadata ----------------------------------------------------------

    /**
     * The document shape some exporters emit: a file-level name and desc, with an author block
     * that nests its own <name> immediately after. Reading this wrong is not hypothetical — a
     * naive handler lets the author's name overwrite the file's, so every such file would appear
     * to be named after the account holder. Contents here are placeholders.
     */
    private val sportsTrackerShape = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx xmlns="http://www.topografix.com/GPX/1/1" creator="Sports Tracker" version="1.1">
          <metadata>
            <name>8/29/26 11:35</name>
            <desc>Ridge loop</desc>
            <author><name>Example User</name></author>
          </metadata>
          <trk><name>8/29/26 11:35</name><trkseg>
            <trkpt lat="50.0" lon="14.0"><ele>300</ele><time>2026-08-29T09:35:00Z</time></trkpt>
          </trkseg></trk>
        </gpx>
    """.trimIndent()

    @Test
    fun `reads document metadata and the creator attribute`() {
        val doc = Gpx.readDocument(sportsTrackerShape)
        assertEquals("Sports Tracker", doc.creator)
        assertEquals("8/29/26 11:35", doc.name)
        assertEquals("Ridge loop", doc.description)
        assertEquals(1, doc.tracks.size)
    }

    @Test
    fun `the author name does not overwrite the document name`() {
        // The whole reason inAuthor exists.
        assertEquals("8/29/26 11:35", Gpx.readDocument(sportsTrackerShape).name)
    }

    @Test
    fun `document metadata does not leak into the track`() {
        // <metadata><desc> belongs to the file, not the trk. A reader that merged them would
        // make the importer's Sports-Tracker-specific correction impossible to express.
        val track = Gpx.readDocument(sportsTrackerShape).tracks.single()
        assertEquals("8/29/26 11:35", track.name)
        assertNull(track.description)
    }

    @Test
    fun `read returns the same tracks as readDocument`() {
        assertEquals(Gpx.readDocument(sportsTrackerShape).tracks, Gpx.read(sportsTrackerShape))
    }

    @Test
    fun `reads a track-level desc`() {
        val xml = """
            <gpx xmlns="http://www.topografix.com/GPX/1/1">
              <trk>
                <name>Named</name><desc>Described</desc><type>cycling</type>
                <trkseg><trkpt lat="50.0" lon="14.0"><time>2026-08-03T18:00:00Z</time></trkpt></trkseg>
              </trk>
            </gpx>
        """.trimIndent()
        val track = Gpx.read(xml).single()
        assertEquals("Named", track.name)
        assertEquals("Described", track.description)
        assertEquals("cycling", track.type)
    }

    @Test
    fun `absent metadata reads as null rather than empty string`() {
        val xml = """
            <gpx xmlns="http://www.topografix.com/GPX/1/1">
              <trk><trkseg>
                <trkpt lat="50.0" lon="14.0"><time>2026-08-03T18:00:00Z</time></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()
        val doc = Gpx.readDocument(xml)
        assertNull(doc.creator)
        assertNull(doc.name)
        assertNull(doc.description)
        assertNull(doc.tracks.single().description)
    }

    @Test
    fun `a written desc is placed where GPX 1_1 requires it`() {
        // GPX 1.1 fixes child order: name, cmt, desc, ... type. A desc after type is invalid.
        val xml = Gpx.write(
            GpxTrack("N", "D", "cycling", listOf(pt(0, 0, 50.0, 14.0))),
        )
        assertTrue(xml.indexOf("<name>") < xml.indexOf("<desc>"), "desc must follow name")
        assertTrue(xml.indexOf("<desc>") < xml.indexOf("<type>"), "desc must precede type")
    }

    @Test
    fun `a desc containing markup characters round-trips escaped`() {
        val nasty = "R&D <ride> \"quoted\""
        val reparsed = Gpx.read(
            Gpx.write(GpxTrack(null, nasty, null, listOf(pt(0, 0, 50.0, 14.0)))),
        ).single()
        assertEquals(nasty, reparsed.description)
    }
}
