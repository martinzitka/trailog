package io.github.martinzitka.trailog.ui.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [MapTiles] — archive discovery and style templating.
 *
 * The archive is not in the APK, so "no archive installed" is an ordinary state on a fresh
 * install rather than an error, and it is the state these tests mostly pin down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapTilesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun tilesDir(): File =
        File(context.getExternalFilesDir(null), "tiles").apply { mkdirs() }

    private fun writeArchive(name: String, sizeBytes: Int): File =
        File(tilesDir(), name).apply { writeBytes(ByteArray(sizeBytes)) }

    // --- The tiles directory and the header ------------------------------------------------

    @Test fun `owns the tiles directory, creating it if absent`() {
        // Owning it is what makes the documented adb push work: a directory created by `adb shell
        // mkdir` belongs to the shell user, and the app then cannot list it at all.
        val dir = MapTiles.tilesDir(context)

        assertTrue(dir.isDirectory)
        assertTrue(dir.canRead())
        assertEquals("tiles", dir.name)
    }

    /** A valid v3 header over an ordinary Central European extent. */
    private fun header() = pmtilesHeader(12.0, 48.0, 19.0, 51.0)

    @Test fun `recognises a real pmtiles header`() {
        assertTrue(MapTiles.isArchiveHeader(header()))
    }

    @Test fun `rejects bytes that do not open a pmtiles v3 archive`() {
        // What an import checks before copying a gigabyte of the wrong file.
        assertFalse(MapTiles.isArchiveHeader(ByteArray(MapTiles.HEADER_SIZE)))
        assertFalse(MapTiles.isArchiveHeader(ByteArray(8)))
        assertFalse(
            MapTiles.isArchiveHeader(header().also { it[7] = 2 }),
        )
    }

    // --- Tile URL --------------------------------------------------------------------------

    @Test fun `tile url uses the pmtiles scheme over a file url`() {
        // MapLibre reads pmtiles:// natively since 11.7.0 — no custom protocol handler.
        // Built from a real file rather than a literal POSIX path: these tests run on the JVM,
        // and File("/sdcard/x").absolutePath is "C:\sdcard\x" on a Windows dev machine.
        val archive = writeArchive("czechia-buffered.pmtiles", 16)

        val url = MapTiles.tileUrl(archive)

        assertTrue(url, url.startsWith("pmtiles://file://"))
        assertEquals("pmtiles://file://" + archive.absolutePath, url)
    }

    // --- Coverage -----------------------------------------------------------------------

    /** A minimal but valid PMTiles v3 header carrying the given bounds. */
    private fun pmtilesHeader(
        minLon: Double, minLat: Double, maxLon: Double, maxLat: Double,
        magic: String = "PMTiles", version: Int = 3,
    ): ByteArray {
        val h = ByteArray(127)
        magic.toByteArray(Charsets.US_ASCII).copyInto(h)
        h[7] = version.toByte()
        fun put(offset: Int, degrees: Double) {
            val v = Math.round(degrees * 1e7).toInt()
            for (i in 0..3) h[offset + i] = ((v shr (8 * i)) and 0xFF).toByte()
        }
        put(102, minLon); put(106, minLat); put(110, maxLon); put(114, maxLat)
        return h
    }

    private fun writeHeader(name: String, bytes: ByteArray) =
        File(tilesDir(), name).apply { writeBytes(bytes) }

    @Test fun `reads the coverage bounds a real archive declares`() {
        // The values the committed build script actually produces, verified against the 1.3 GB
        // archive on the test device.
        val archive = writeHeader("cz.pmtiles", pmtilesHeader(11.70, 48.30, 19.25, 51.35))

        val coverage = MapTiles.coverage(archive)!!

        assertEquals(11.70, coverage.minLongitude, 1e-6)
        assertEquals(48.30, coverage.minLatitude, 1e-6)
        assertEquals(19.25, coverage.maxLongitude, 1e-6)
        assertEquals(51.35, coverage.maxLatitude, 1e-6)
    }

    @Test fun `handles bounds west of Greenwich and south of the equator`() {
        // Longitudes and latitudes are signed int32; reading them unsigned would put a Welsh
        // ride in China. Trailog is Czech-only today, which is exactly why this needs a test.
        val archive = writeHeader("neg.pmtiles", pmtilesHeader(-8.65, -34.0, -4.0, -20.0))

        val coverage = MapTiles.coverage(archive)!!

        assertEquals(-8.65, coverage.minLongitude, 1e-6)
        assertEquals(-34.0, coverage.minLatitude, 1e-6)
    }

    @Test fun `rejects a file that is not a pmtiles archive`() {
        // The camera falls back to MapLibre's default rather than trusting garbage as bounds.
        assertNull(MapTiles.coverage(writeHeader("bad.pmtiles", pmtilesHeader(1.0, 2.0, 3.0, 4.0, magic = "NOTPMT."))))
        assertNull(MapTiles.coverage(writeHeader("v2.pmtiles", pmtilesHeader(1.0, 2.0, 3.0, 4.0, version = 2))))
        assertNull(MapTiles.coverage(writeArchive("tiny.pmtiles", 16)))
    }

    @Test fun `rejects degenerate bounds`() {
        // An empty or inverted box would make newLatLngBounds throw or frame nothing.
        assertNull(MapTiles.coverage(writeHeader("empty.pmtiles", pmtilesHeader(14.0, 50.0, 14.0, 50.0))))
        assertNull(MapTiles.coverage(writeHeader("inverted.pmtiles", pmtilesHeader(19.0, 51.0, 11.0, 48.0))))
    }

    // --- Style templating -------------------------------------------------------------------

    @Test fun `substitutes the tile url into the bundled style`() {
        val json = MapTiles.loadStyleJson(context, "pmtiles://file:///t.pmtiles", "map/style.json")

        assertTrue("placeholder survived", !json.contains("__TRAILOG_TILE_URL__"))
        assertTrue("tile url missing", json.contains("pmtiles://file:///t.pmtiles"))
    }

    @Test fun `bundled style reaches no third-party host`() {
        // The upstream OSM Bright style points glyphs and tiles at api.maptiler.com and sprites
        // at openmaptiles.github.io. fetch-style-assets.sh rewrites all three; this asserts the
        // committed asset really is rewritten, because a stale asset would silently reintroduce
        // third-party egress at render time — the one thing CLAUDE.md forbids outright.
        val json = MapTiles.loadStyleJson(context, "pmtiles://file:///t.pmtiles", "map/style.json")

        for (host in listOf("api.maptiler.com", "openmaptiles.github.io", "fonts.openmaptiles.org")) {
            assertTrue("style still references $host", !json.contains(host))
        }
        assertTrue("glyphs are not local", json.contains("asset://map/fonts/"))
        assertTrue("sprite is not local", json.contains("asset://map/sprite"))
    }

    // --- The trail layer group ---------------------------------------------------------------

    @Test fun `reads the trail layer group the bundled style declares`() {
        // The toggle addresses layers by id, and those ids live in the style. This is the half of
        // that contract on the Kotlin side; BundledStyleTest asserts the other half, that every
        // declared id names a layer that exists.
        val json = MapTiles.loadStyleJson(context, "pmtiles://file:///t.pmtiles", "map/style.json")

        val ids = MapTiles.trailLayerIds(json)

        assertTrue("no trail layers declared", ids.isNotEmpty())
        assertTrue("the MTB underlay is not in the group", ids.contains("trailog-path-mtb"))
        assertTrue(
            "forest tracks are roads and must stay visible: $ids",
            ids.none { it.startsWith("trailog-track") },
        )
    }

    @Test fun `a style with no declared group hides nothing rather than failing`() {
        // A hand-edited or older style must not take the map down over a cosmetic setting.
        assertEquals(emptyList<String>(), MapTiles.trailLayerIds("""{"layers":[]}"""))
        assertEquals(emptyList<String>(), MapTiles.trailLayerIds("not json at all"))
    }

    @Test fun `fails loudly when the asset and the fetch script have drifted apart`() {
        // Substituting into a style with no placeholder would yield a map with no tiles and no
        // explanation. sprite.json is a convenient stand-in for any asset lacking the marker.
        val error = runCatching {
            MapTiles.loadStyleJson(context, "pmtiles://file:///t.pmtiles", "map/sprite.json")
        }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
        assertTrue(error!!.message!!.contains("fetch-style-assets.sh"))
    }
}
