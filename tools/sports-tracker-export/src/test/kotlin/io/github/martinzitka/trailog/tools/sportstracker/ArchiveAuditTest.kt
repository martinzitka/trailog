package io.github.martinzitka.trailog.tools.sportstracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The audit exists because a real export passed every check the downloader made and was still
 * ruined: Sports Tracker returned syntactically perfect GPX holding one track point for a 49 km
 * ride. These tests pin the distinction the downloader could not make — valid format versus
 * present data.
 */
class ArchiveAuditTest {

    @Test
    fun `a dense track is fine`() {
        // ~10 m per point, which is what a real export looks like.
        assertEquals(AuditVerdict.OK, ArchiveAudit.classify(false, 10_000.0, 1_000))
    }

    @Test
    fun `a 49km ride delivered as a single point is caught`() {
        // A ride that exported as valid GPX carrying a single point.
        assertEquals(AuditVerdict.TOO_FEW_POINTS, ArchiveAudit.classify(false, 49_250.0, 1))
    }

    @Test
    fun `a 41km ride delivered as two points is caught`() {
        assertEquals(AuditVerdict.TOO_FEW_POINTS, ArchiveAudit.classify(false, 41_500.0, 2))
    }

    @Test
    fun `an empty track is expected for a hand-entered workout`() {
        // A manually entered swim: no track, and none was ever claimed. Not a failure — even
        // though it reports a real distance, which is exactly why distance cannot be the test.
        assertEquals(AuditVerdict.EMPTY_AS_EXPECTED, ArchiveAudit.classify(true, 2_000.0, 0))
        assertEquals(AuditVerdict.EMPTY_AS_EXPECTED, ArchiveAudit.classify(true, null, 0))
        assertEquals(AuditVerdict.EMPTY_AS_EXPECTED, ArchiveAudit.classify(true, 2_000.0, null))
    }

    @Test
    fun `a recorded workout with distance but no track is data loss, not an expected empty`() {
        // The pair this whole rule exists to separate. Both report real distance with no track;
        // only the hand-entered one is fine. The other is a recorded activity whose geometry was
        // lost upstream, which no distance-based rule could distinguish.
        assertEquals(AuditVerdict.EMPTY_AS_EXPECTED, ArchiveAudit.classify(true, 2_000.0, 0))
        assertEquals(
            AuditVerdict.EMPTY_BUT_TRACK_EXPECTED,
            ArchiveAudit.classify(false, 25_700.0, 0),
        )
    }

    @Test
    fun `an empty track is a problem when the workout covered real distance`() {
        assertEquals(
            AuditVerdict.EMPTY_BUT_TRACK_EXPECTED,
            ArchiveAudit.classify(false, 10_000.0, 0),
        )
    }

    @Test
    fun `an absent file is reported as missing rather than empty`() {
        assertEquals(AuditVerdict.MISSING, ArchiveAudit.classify(false, 10_000.0, null))
        assertEquals(AuditVerdict.MISSING, ArchiveAudit.classify(false, null, null))
    }

    @Test
    fun `a very short workout is not size-checked`() {
        // Too little distance to reason about; refusing to judge beats a false accusation.
        assertEquals(AuditVerdict.OK, ArchiveAudit.classify(false, 150.0, 1))
    }

    @Test
    fun `the threshold leaves generous headroom for a sparse but real track`() {
        // One point per 500 m is two orders of magnitude below a real export's density, so a
        // genuinely coarse recording still passes.
        val distance = 10_000.0
        val justEnough = (distance / ArchiveAudit.METRES_PER_EXPECTED_POINT).toInt()
        assertEquals(AuditVerdict.OK, ArchiveAudit.classify(false, distance, justEnough))
        assertEquals(AuditVerdict.TOO_FEW_POINTS, ArchiveAudit.classify(false, distance, justEnough - 1))
    }

    @Test
    fun `only real problems are flagged as such`() {
        assertFalse(WorkoutAudit("k", 0.0, 0, AuditVerdict.EMPTY_AS_EXPECTED).isProblem)
        assertFalse(WorkoutAudit("k", 1.0, 9, AuditVerdict.OK).isProblem)
        assertTrue(WorkoutAudit("k", 1.0, 1, AuditVerdict.TOO_FEW_POINTS).isProblem)
        assertTrue(WorkoutAudit("k", 1.0, null, AuditVerdict.MISSING).isProblem)
        assertTrue(WorkoutAudit("k", 1.0, 0, AuditVerdict.EMPTY_BUT_TRACK_EXPECTED).isProblem)
    }

    @Test
    fun `the report names every problem and stays quiet about healthy workouts`() {
        val audits = listOf(
            WorkoutAudit("good", 10_000.0, 1_000, AuditVerdict.OK),
            WorkoutAudit("swim", 0.0, 0, AuditVerdict.EMPTY_AS_EXPECTED),
            WorkoutAudit("broken", 49_250.0, 1, AuditVerdict.TOO_FEW_POINTS),
        )
        val report = ArchiveAudit.report(audits)
        assertTrue(report.contains("broken"), "the damaged workout must be named")
        assertTrue(report.contains("49.25 km"), "the report should show what was expected")
        assertFalse(report.contains("  good  "), "healthy workouts are counted, not listed")
    }

    @Test
    fun `a clean archive says so`() {
        val report = ArchiveAudit.report(listOf(WorkoutAudit("k", 10_000.0, 900, AuditVerdict.OK)))
        assertTrue(report.contains("No problems"))
    }

    @Test
    fun `track points are counted from bytes without decoding`() {
        val gpx = """<gpx><trk><trkseg><trkpt lat="1" lon="2"/><trkpt lat="3" lon="4"></trkpt>
            </trkseg></trk></gpx>""".toByteArray()
        assertEquals(2, PayloadSniffer.countTrackPoints(gpx))
    }

    @Test
    fun `a longer tag name is not counted as a track point`() {
        assertEquals(0, PayloadSniffer.countTrackPoints("<trkptx/>".toByteArray()))
        assertEquals(0, PayloadSniffer.countTrackPoints("<trkseg/>".toByteArray()))
    }

    @Test
    fun `counting survives multi-byte characters in the document`() {
        // Titles may be non-ASCII; a byte scan must not miscount around multi-byte characters.
        val gpx = """<gpx><desc>Přehrada - Žďár</desc><trkpt lat="1" lon="2"/></gpx>"""
        assertEquals(1, PayloadSniffer.countTrackPoints(gpx.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `an empty document counts zero without failing`() {
        assertEquals(0, PayloadSniffer.countTrackPoints(ByteArray(0)))
        assertEquals(0, PayloadSniffer.countTrackPoints("<trkpt".toByteArray()))
    }
}
