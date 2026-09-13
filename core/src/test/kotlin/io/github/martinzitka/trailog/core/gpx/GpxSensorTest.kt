package io.github.martinzitka.trailog.core.gpx

import io.github.martinzitka.trailog.core.model.RawPoint
import io.github.martinzitka.trailog.core.model.SensorSample
import io.github.martinzitka.trailog.core.model.SensorStream
import io.github.martinzitka.trailog.core.model.SensorType
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GPX's sensor half: reading the per-point `gpxtpx:` elements a foreign file carries, and writing
 * Trailog's own timestamped stream alongside a portable projection of it.
 *
 * Kept apart from [GpxTest] because the two halves answer different questions — that one is about
 * geometry and segments, this one about a stream that shares the track's clock and nothing else.
 */
class GpxSensorTest {

    private fun pt(sec: Long, seg: Int, lat: Double, lon: Double) = RawPoint(
        latitude = lat,
        longitude = lon,
        altitude = null,
        accuracy = null,
        time = Instant.fromEpochSeconds(sec),
        segmentIndex = seg,
    )

    private fun sample(sec: Long, type: SensorType, value: Double) =
        SensorSample(Instant.fromEpochSeconds(sec), type, value)

    private fun hr(sec: Long, value: Double) = sample(sec, SensorType.HEART_RATE, value)

    private fun GpxTrack.valuesOf(type: SensorType) =
        SensorStream.ofType(samples, type).map { it.value }

    // ---- reading a foreign file ---------------------------------------------------------------

    @Test
    fun `heart rate on a foreign file's track points becomes a sample stream`() {
        // The shape Sports Tracker and Garmin both write: readings hang off the trkpt, so each
        // one takes that fix's timestamp. Nine of the exported workouts look exactly like this.
        val xml = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"
                 xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
              <trk><trkseg>
                <trkpt lat="50.0" lon="14.0"><time>2026-08-03T18:00:00Z</time>
                  <extensions><gpxtpx:TrackPointExtension>
                    <gpxtpx:hr>142</gpxtpx:hr>
                    <gpxtpx:cad>85</gpxtpx:cad>
                    <gpxtpx:atemp>21.5</gpxtpx:atemp>
                  </gpxtpx:TrackPointExtension></extensions>
                </trkpt>
                <trkpt lat="50.001" lon="14.001"><time>2026-08-03T18:00:01Z</time>
                  <extensions><gpxtpx:TrackPointExtension>
                    <gpxtpx:hr>145</gpxtpx:hr>
                  </gpxtpx:TrackPointExtension></extensions>
                </trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val track = Gpx.read(xml).single()
        assertEquals(2, track.points.size)
        assertEquals(listOf(142.0, 145.0), track.valuesOf(SensorType.HEART_RATE))
        assertEquals(listOf(85.0), track.valuesOf(SensorType.CADENCE))
        assertEquals(listOf(21.5), track.valuesOf(SensorType.TEMPERATURE))
        assertEquals(
            Instant.parse("2026-08-03T18:00:00Z"),
            SensorStream.ofType(track.samples, SensorType.HEART_RATE).first().time,
        )
    }

    @Test
    fun `an unprefixed or differently prefixed sensor element reads the same`() {
        val xml = """
            <gpx xmlns:ns3="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
              <trk><trkseg>
              <trkpt lat="50.0" lon="14.0"><time>2026-08-03T18:00:00Z</time>
                <extensions><ns3:TrackPointExtension>
                  <ns3:hr>142</ns3:hr>
                </ns3:TrackPointExtension></extensions>
              </trkpt>
              <trkpt lat="50.0" lon="14.0"><time>2026-08-03T18:00:01Z</time>
                <extensions><hr>145</hr><power>220</power></extensions>
              </trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()

        val track = Gpx.read(xml).single()
        assertEquals(listOf(142.0, 145.0), track.valuesOf(SensorType.HEART_RATE))
        assertEquals(listOf(220.0), track.valuesOf(SensorType.POWER))
    }

    @Test
    fun `a sensor element outside a track point is not a reading`() {
        // `hr` is a short, generic name. Only inside a trkpt does it mean a heart rate.
        val xml = """
            <gpx><trk><desc>hr</desc><trkseg>
              <trkpt lat="50.0" lon="14.0"><time>2026-08-03T18:00:00Z</time></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()
        assertTrue(Gpx.read(xml).single().samples.isEmpty())
    }

    // ---- writing ------------------------------------------------------------------------------

    @Test
    fun `a track with no sensor data declares no sensor namespace and writes no stream`() {
        val xml = Gpx.write(GpxTrack("N", null, "cycling", listOf(pt(0, 0, 50.0, 14.0))))
        assertFalse(xml.contains("gpxtpx"), "no sensor data means no Garmin namespace")
        assertFalse(xml.contains("gpxpx"), "and no power namespace either")
        assertFalse(xml.contains("trailog:samples"))
        assertTrue(Gpx.read(xml).single().samples.isEmpty())
    }

    @Test
    fun `samples round-trip exactly, including ones taken while the GPS was silent`() {
        // This is why samples are a stream and not fields on a fix: the strap keeps emitting
        // during a blackout. The reading at :30 sits in the gap between the two segments with no
        // trkpt anywhere near it, and survives only because Trailog writes its own stream.
        val original = GpxTrack(
            name = "Ride",
            description = null,
            type = "cycling",
            points = listOf(
                pt(0, 0, 50.0, 14.0),
                pt(1, 0, 50.001, 14.001),
                pt(600, 1, 50.01, 14.01),
            ),
            samples = listOf(hr(0, 142.0), hr(1, 145.5), hr(30, 138.0), hr(600, 150.0)),
        )

        val reparsed = Gpx.read(Gpx.write(original)).single()
        assertEquals(original.samples, reparsed.samples)
        assertEquals(original.points, reparsed.points)
    }

    @Test
    fun `the trailog stream wins over the per-point projection instead of doubling it`() {
        // A Trailog file carries both copies. Reading them as a union would report every heart
        // rate twice — once exact, once shifted onto the nearest fix.
        val original = GpxTrack(
            name = null,
            description = null,
            type = null,
            points = listOf(pt(0, 0, 50.0, 14.0), pt(1, 0, 50.001, 14.001)),
            samples = listOf(hr(0, 142.0), hr(1, 145.0)),
        )
        val xml = Gpx.write(original)

        assertTrue(xml.contains("<gpxtpx:hr>142</gpxtpx:hr>"), "the portable projection is written")
        assertTrue(xml.contains("trailog:samples"), "the exact stream is written")
        assertEquals(original.samples, Gpx.read(xml).single().samples)
    }

    @Test
    fun `a foreign stream for one sensor and a trailog stream for another both survive`() {
        // Per-type resolution, not all-or-nothing: a hand-merged file loses neither half.
        val xml = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"
                 xmlns:trailog="urn:trailog:gpx:v1">
              <trk>
                <extensions>
                  <trailog:samples type="HEART_RATE">
                    <trailog:s t="2026-08-03T18:00:00.400Z" v="142"/>
                  </trailog:samples>
                </extensions>
                <trkseg>
                  <trkpt lat="50.0" lon="14.0"><time>2026-08-03T18:00:00Z</time>
                    <extensions><hr>999</hr><cad>85</cad></extensions>
                  </trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val track = Gpx.read(xml).single()
        // The exact stream replaces the projection for heart rate, keeping the sub-second time.
        assertEquals(listOf(142.0), track.valuesOf(SensorType.HEART_RATE))
        assertEquals(
            Instant.parse("2026-08-03T18:00:00.400Z"),
            SensorStream.ofType(track.samples, SensorType.HEART_RATE).single().time,
        )
        // Cadence was never in the stream, so the per-point value stands.
        assertEquals(listOf(85.0), track.valuesOf(SensorType.CADENCE))
    }

    @Test
    fun `integer-typed Garmin elements are written whole`() {
        // gpxtpx:hr and gpxtpx:cad are integer-typed in Garmin's schema, where "142.0" is a value
        // strict readers reject. Temperature is a double there and keeps its fraction.
        val xml = Gpx.write(
            GpxTrack(
                name = null,
                description = null,
                type = null,
                points = listOf(pt(0, 0, 50.0, 14.0)),
                samples = listOf(
                    hr(0, 142.0),
                    sample(0, SensorType.CADENCE, 85.0),
                    sample(0, SensorType.TEMPERATURE, 21.5),
                    sample(0, SensorType.POWER, 220.0),
                ),
            ),
        )

        assertTrue(xml.contains("<gpxtpx:hr>142</gpxtpx:hr>"), xml)
        assertTrue(xml.contains("<gpxtpx:cad>85</gpxtpx:cad>"), xml)
        assertTrue(xml.contains("<gpxtpx:atemp>21.5</gpxtpx:atemp>"), xml)
        assertTrue(xml.contains("<gpxpx:PowerInWatts>220</gpxpx:PowerInWatts>"), xml)
        assertTrue(xml.contains("xmlns:gpxpx="), "power needs its own namespace declared")
    }

    @Test
    fun `a fix with no reading near it gets no sensor extension`() {
        // The projection is deliberately bounded. A fix ten minutes from the only reading must
        // not be labelled with it.
        val xml = Gpx.write(
            GpxTrack(
                name = null,
                description = null,
                type = null,
                points = listOf(pt(0, 0, 50.0, 14.0), pt(600, 0, 50.01, 14.01)),
                samples = listOf(hr(0, 142.0)),
            ),
        )
        assertEquals(1, xml.split("<gpxtpx:hr>").size - 1, "exactly one fix carries the reading")
    }

    @Test
    fun `the trk extensions block precedes the first trkseg`() {
        // GPX 1.1 fixes the order of a trk's children: ... type, extensions, trkseg.
        val xml = Gpx.write(
            GpxTrack("N", "D", "cycling", listOf(pt(0, 0, 50.0, 14.0)), listOf(hr(0, 142.0))),
        )
        assertTrue(xml.indexOf("<type>") < xml.indexOf("<extensions>"))
        assertTrue(xml.indexOf("trailog:samples") < xml.indexOf("<trkseg>"))
    }

    // ---- tolerance of what other versions write -----------------------------------------------

    @Test
    fun `a sensor type this build does not know is skipped, not rejected`() {
        // Forward compatibility: a file written by a later Trailog must still open here, losing
        // only the sensor this build has no constant for.
        val xml = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"
                 xmlns:trailog="urn:trailog:gpx:v1">
              <trk>
                <extensions>
                  <trailog:samples type="MUSCLE_OXYGEN">
                    <trailog:s t="2026-08-03T18:00:00Z" v="62.5"/>
                  </trailog:samples>
                  <trailog:samples type="HEART_RATE">
                    <trailog:s t="2026-08-03T18:00:00Z" v="142"/>
                  </trailog:samples>
                </extensions>
                <trkseg>
                  <trkpt lat="50.0" lon="14.0"><time>2026-08-03T18:00:00Z</time></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        assertEquals(listOf(142.0), Gpx.read(xml).single().samples.map { it.value })
    }

    @Test
    fun `a malformed sample element is a parse error`() {
        val xml = """
            <gpx xmlns:trailog="urn:trailog:gpx:v1"><trk><extensions>
              <trailog:samples type="HEART_RATE">
                <trailog:s t="2026-08-03T18:00:00Z" v="fast"/>
              </trailog:samples>
            </extensions></trk></gpx>
        """.trimIndent()
        assertFailsWith<GpxParseException> { Gpx.read(xml) }
    }

    @Test
    fun `a sample missing its timestamp is a parse error`() {
        val xml = """
            <gpx xmlns:trailog="urn:trailog:gpx:v1"><trk><extensions>
              <trailog:samples type="HEART_RATE"><trailog:s v="142"/></trailog:samples>
            </extensions></trk></gpx>
        """.trimIndent()
        assertFailsWith<GpxParseException> { Gpx.read(xml) }
    }

    @Test
    fun `samples of one track do not leak into the next`() {
        val xml = Gpx.write(
            listOf(
                GpxTrack(null, null, null, listOf(pt(0, 0, 50.0, 14.0)), listOf(hr(0, 142.0))),
                GpxTrack(null, null, null, listOf(pt(10, 0, 51.0, 15.0))),
            ),
        )

        val tracks = Gpx.read(xml)
        assertEquals(listOf(142.0), tracks[0].samples.map { it.value })
        assertTrue(tracks[1].samples.isEmpty())
    }
}
