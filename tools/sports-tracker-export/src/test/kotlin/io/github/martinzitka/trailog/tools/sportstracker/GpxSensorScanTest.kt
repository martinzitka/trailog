package io.github.martinzitka.trailog.tools.sportstracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The scan answers the second half of the FIT question: FIT is only worth a decoder if the
 * historical workouts actually carry heart rate or cadence. Getting a false positive here would
 * argue for building a parser nobody needs.
 */
class GpxSensorScanTest {

    @Test
    fun `a plain track reports no sensor data`() {
        // The shape of the real sportstracker_reference.gpx fixture: the Garmin extension
        // namespace is declared on the root element but no extension element is ever used.
        val gpx = """
            <?xml version="1.0"?>
            <gpx xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
              <trk><trkseg>
                <trkpt lat="1" lon="2"><ele>10</ele><time>2020-01-01T00:00:00Z</time></trkpt>
                <trkpt lat="1" lon="2"><ele>11</ele><time>2020-01-01T00:00:01Z</time></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val scan = GpxSensorScan.of(gpx)
        assertEquals(2, scan.trackPoints)
        assertEquals(0, scan.extensionElements)
        assertEquals(0, scan.heartRate)
        assertFalse(scan.hasAnySensorData, "a declared namespace is not sensor data")
    }

    @Test
    fun `heart rate and cadence are counted regardless of namespace prefix`() {
        val gpx = """
            <gpx><trk><trkseg>
              <trkpt lat="1" lon="2"><extensions><gpxtpx:TrackPointExtension>
                <gpxtpx:hr>142</gpxtpx:hr><gpxtpx:cad>85</gpxtpx:cad>
              </gpxtpx:TrackPointExtension></extensions></trkpt>
              <trkpt lat="1" lon="2"><extensions><ns3:TrackPointExtension>
                <ns3:hr>145</ns3:hr><ns3:cad>86</ns3:cad><ns3:atemp>21</ns3:atemp>
              </ns3:TrackPointExtension></extensions></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()

        val scan = GpxSensorScan.of(gpx)
        assertEquals(2, scan.trackPoints)
        assertEquals(2, scan.extensionElements)
        assertEquals(2, scan.heartRate)
        assertEquals(2, scan.cadence)
        assertEquals(1, scan.temperature)
        assertTrue(scan.hasAnySensorData)
    }

    @Test
    fun `an unprefixed sensor element is counted too`() {
        val scan = GpxSensorScan.of("<trkpt><extensions><hr>140</hr></extensions></trkpt>")
        assertEquals(1, scan.heartRate)
    }

    @Test
    fun `a longer tag name that merely starts with a sensor name is not counted`() {
        // `hr` must not also match `hrm`, nor `cad` match `cadence_target`.
        val scan = GpxSensorScan.of("<hrm>x</hrm><cadence_target>90</cadence_target>")
        assertEquals(0, scan.heartRate)
        assertEquals(0, scan.cadence)
    }

    @Test
    fun `self-closing and attributed tags are counted`() {
        val scan = GpxSensorScan.of("""<trkpt lat="1" lon="2"/><trkpt/>""")
        assertEquals(2, scan.trackPoints)
    }

    @Test
    fun `closing tags are not double counted`() {
        val scan = GpxSensorScan.of("<hr>140</hr>")
        assertEquals(1, scan.heartRate)
    }

    @Test
    fun `malformed input is scanned rather than rejected`() {
        // This runs on bodies that may not be valid XML at all; throwing here would lose the
        // answer exactly when something has gone wrong. A truncated element still has a complete
        // opening tag name, and counting it is the intended behaviour of a substring scan — the
        // scan reports what markup is present, it does not validate the document.
        val scan = GpxSensorScan.of("<gpx><trkpt lat=")
        assertEquals(1, scan.trackPoints)
        assertFalse(scan.hasAnySensorData)
    }

    @Test
    fun `a body with no markup at all counts nothing`() {
        val scan = GpxSensorScan.of("Not found")
        assertEquals(0, scan.trackPoints)
        assertFalse(scan.hasAnySensorData)
    }

    @Test
    fun `power is counted`() {
        val scan = GpxSensorScan.of("<power>250</power>")
        assertEquals(1, scan.power)
        assertTrue(scan.hasAnySensorData)
    }
}
