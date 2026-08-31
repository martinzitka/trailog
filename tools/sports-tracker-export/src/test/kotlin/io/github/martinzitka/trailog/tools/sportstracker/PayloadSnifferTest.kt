package io.github.martinzitka.trailog.tools.sportstracker

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sniffer is the whole safety mechanism of this tool: the endpoints answer an expired session
 * with HTTP 200 and a login page, so "did the export work" can only be decided from the bytes.
 * These tests pin the distinction that matters — a body that merely arrived versus a body that is
 * the format we asked for.
 */
class PayloadSnifferTest {

    /** A minimal but structurally valid FIT header: size 12, `.FIT` at bytes 8..11. */
    private fun fitBytes(dataSize: Int = 0, headerSize: Int = 12): ByteArray = byteArrayOf(
        headerSize.toByte(), 0x10, 0x00, 0x00,
        (dataSize and 0xFF).toByte(),
        ((dataSize shr 8) and 0xFF).toByte(),
        ((dataSize shr 16) and 0xFF).toByte(),
        ((dataSize shr 24) and 0xFF).toByte(),
        0x2E, 0x46, 0x49, 0x54,
    )

    @Test
    fun `a valid FIT header is recognised`() {
        assertEquals(PayloadFormat.FIT, PayloadSniffer.sniff(fitBytes()))
        assertTrue(PayloadSniffer.isFit(fitBytes()))
    }

    @Test
    fun `the 14-byte FIT header form is also valid`() {
        assertTrue(PayloadSniffer.isFit(fitBytes(headerSize = 14)))
    }

    @Test
    fun `a signature under an implausible header size is not FIT`() {
        // Guards against a coincidental `.FIT` inside some other format's payload.
        assertFalse(PayloadSniffer.isFit(fitBytes(headerSize = 99)))
    }

    @Test
    fun `bytes too short to hold a header are not FIT`() {
        assertFalse(PayloadSniffer.isFit(byteArrayOf(12, 0x10, 0x00)))
    }

    @Test
    fun `FIT data size is read little-endian from the header`() {
        assertEquals(1000L, PayloadSniffer.fitDataSize(fitBytes(dataSize = 1000)))
    }

    @Test
    fun `FIT data size is null for anything that is not FIT`() {
        assertNull(PayloadSniffer.fitDataSize("<gpx/>".toByteArray()))
    }

    @Test
    fun `a GPX document is recognised through its XML declaration`() {
        val gpx = """<?xml version="1.0"?><gpx version="1.1"><trk/></gpx>"""
        assertEquals(PayloadFormat.GPX, PayloadSniffer.sniff(gpx.toByteArray()))
    }

    @Test
    fun `a byte order mark does not hide a GPX document`() {
        val gpx = "﻿<?xml version=\"1.0\"?><gpx><trk/></gpx>"
        assertEquals(PayloadFormat.GPX, PayloadSniffer.sniff(gpx.toByteArray()))
    }

    @Test
    fun `leading whitespace does not hide a GPX document`() {
        assertEquals(PayloadFormat.GPX, PayloadSniffer.sniff("\n\n  <gpx/>".toByteArray()))
    }

    @Test
    fun `XML that is not GPX is reported as such rather than as a success`() {
        val body = """<?xml version="1.0"?><error>expired</error>"""
        assertEquals(PayloadFormat.XML_OTHER, PayloadSniffer.sniff(body.toByteArray()))
    }

    @Test
    fun `a login page returned with a 200 is recognised as HTML`() {
        // The exact failure this tool exists to survive.
        val page = "<!DOCTYPE html>\n<html><body>Sign in</body></html>"
        assertEquals(PayloadFormat.HTML, PayloadSniffer.sniff(page.toByteArray()))
    }

    @Test
    fun `a JSON error body is recognised`() {
        assertEquals(PayloadFormat.JSON, PayloadSniffer.sniff("""{"error":"nope"}""".toByteArray()))
        assertEquals(PayloadFormat.JSON, PayloadSniffer.sniff("[]".toByteArray()))
    }

    @Test
    fun `an empty body is distinguished from an unknown one`() {
        assertEquals(PayloadFormat.EMPTY, PayloadSniffer.sniff(ByteArray(0)))
        assertEquals(PayloadFormat.UNKNOWN, PayloadSniffer.sniff("not markup".toByteArray()))
    }

    @Test
    fun `matches requires the requested format, not merely a recognisable one`() {
        val gpx = "<gpx/>".toByteArray()
        assertTrue(PayloadSniffer.matches(gpx, ExportFormat.GPX))
        assertFalse(PayloadSniffer.matches(gpx, ExportFormat.FIT))
        assertTrue(PayloadSniffer.matches(fitBytes(), ExportFormat.FIT))
        assertFalse(PayloadSniffer.matches(fitBytes(), ExportFormat.GPX))
    }

    @Test
    fun `an HTML body is never usable as an export`() {
        val page = "<html>login</html>".toByteArray()
        assertFalse(PayloadSniffer.matches(page, ExportFormat.GPX))
        assertFalse(PayloadSniffer.matches(page, ExportFormat.FIT))
    }

    @Test
    fun `the token is redacted out of any URI shown to a human`() {
        val uri = URI.create("https://api.sports-tracker.com/apiserver/v1/workouts?token=s3cret&limit=5")
        val redacted = SportsTrackerApi.redact(uri)
        assertFalse(redacted.contains("s3cret"), "token leaked into $redacted")
        assertTrue(redacted.contains("token=REDACTED"))
        assertTrue(redacted.contains("limit=5"), "redaction ate the rest of the query")
    }

    @Test
    fun `redaction survives the token being the final query parameter`() {
        val uri = URI.create("https://api.sports-tracker.com/apiserver/v1/workout/exportFit/abc?token=s3cret")
        assertFalse(SportsTrackerApi.redact(uri).contains("s3cret"))
    }
}
