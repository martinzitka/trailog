package io.github.martinzitka.trailog.ui.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Invariants of the bundled map style, asserted against the committed asset.
 *
 * `infra/tiles/fetch-style-assets.sh` checks most of these too, deliberately. That script only
 * runs when someone re-fetches glyphs and sprites; this runs on every push and pull request, and
 * the failures it catches — a third-party host in the style, an icon that is not in the sprite —
 * are otherwise invisible until the map is on a hillside with no labels.
 *
 * The style is vendored: ours, forked from OSM Bright. Nothing regenerates it, so nothing else
 * would notice a bad hand edit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BundledStyleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun asset(path: String): String =
        context.assets.open(path).use { it.readBytes().decodeToString() }

    private val style: JSONObject by lazy { JSONObject(asset("map/style.json")) }

    private val layers: List<JSONObject> by lazy {
        style.getJSONArray("layers").let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }
        }
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    /** Every source-layer the OpenMapTiles schema defines — all our archive can contain. */
    private val knownSourceLayers = setOf(
        "aerodrome_label", "aeroway", "boundary", "building", "housenumber", "landcover",
        "landuse", "mountain_peak", "park", "place", "poi", "transportation",
        "transportation_name", "water", "water_name", "waterway",
    )

    // --- Privacy ---------------------------------------------------------------------------

    @Test fun `the style contacts no third-party host`() {
        // CLAUDE.md's strongest rule. Upstream OSM Bright points glyphs and sources at
        // api.maptiler.com and sprite at openmaptiles.github.io; ours must reach neither, and a
        // re-fork that forgets to rewrite one of them is exactly the mistake this catches.
        val urls = mutableListOf<String>()
        urls += style.getString("glyphs")
        urls += style.getString("sprite")

        val sources = style.getJSONObject("sources")
        for (name in sources.keys()) {
            val source = sources.getJSONObject(name)
            source.optJSONArray("tiles")?.let { urls += it.strings() }
            source.optString("url", "").takeIf { it.isNotEmpty() }?.let { urls += it }
        }

        val external = urls.filter { it.startsWith("http") }
        assertEquals("external URLs in the bundled style", emptyList<String>(), external)
    }

    @Test fun `fonts and sprites are bundled in the APK`() {
        assertEquals("asset://map/fonts/{fontstack}/{range}.pbf", style.getString("glyphs"))
        assertEquals("asset://map/sprite", style.getString("sprite"))
    }

    @Test fun `the vector source carries OSM attribution`() {
        // ODbL makes this a licence condition. It lives on the source so MapLibre's own control
        // renders it and no screen can forget to (ADR 0015).
        val source = style.getJSONObject("sources").getJSONObject("openmaptiles")

        assertTrue(source.getString("attribution").contains("OpenStreetMap"))
    }

    // --- The tile URL template -------------------------------------------------------------

    @Test fun `the tile URL is a placeholder, not a baked path`() {
        // The archive lives in app storage or on the user's own server, so the path is only
        // known at runtime. MapTiles substitutes it at load time.
        val tiles = style.getJSONObject("sources")
            .getJSONObject("openmaptiles")
            .getJSONArray("tiles")
            .strings()

        assertEquals(listOf("__TRAILOG_TILE_URL__"), tiles)
    }

    // --- Layer groups the app addresses by name ---------------------------------------------

    @Test fun `declared layer groups all exist`() {
        // The app reads these from the style rather than hardcoding ids, so this is what stops
        // the asset and the Kotlin drifting apart.
        val ids = layers.map { it.getString("id") }.toSet()
        val metadata = style.getJSONObject("metadata")

        for (key in listOf("trailog:trailLayers", "trailog:unitLabelledLayers")) {
            val declared = metadata.getJSONArray(key).strings()

            assertTrue("$key is empty", declared.isNotEmpty())
            assertEquals("$key names layers that do not exist", emptyList<String>(),
                declared.filterNot { it in ids })
        }
    }

    @Test fun `layer ids are unique`() {
        val ids = layers.map { it.getString("id") }

        assertEquals(ids.size, ids.toSet().size)
    }

    // --- The style asks the archive only for data Planetiler produced ------------------------

    @Test fun `every source-layer exists in the OpenMapTiles schema`() {
        // A layer naming something else renders as nothing at all, silently.
        val used = layers.mapNotNull { it.optString("source-layer", "").takeIf(String::isNotEmpty) }

        assertEquals(emptyList<String>(), used.distinct().filterNot { it in knownSourceLayers })
    }

    @Test fun `every literal icon is in the sprite sheet`() {
        // A missing icon draws nothing and reports nothing. Only literal names can be checked;
        // "{class}_11" is resolved per feature at render time.
        val sprite = JSONObject(asset("map/sprite.json"))
        val available = sprite.keys().asSequence().toSet()

        val named = layers.mapNotNull { it.optJSONObject("layout")?.optString("icon-image") }
            .filter { it.isNotEmpty() && !it.contains("{") }

        assertEquals(emptyList<String>(), named.distinct().filterNot { it in available })
    }

    @Test fun `every referenced fontstack has bundled glyphs`() {
        // A fontstack with no glyph ranges behind it renders labels as nothing.
        //
        // Opening a range rather than listing the directory: the range MapLibre asks for first
        // is the one that must be there, and it also proves the file is readable, which a
        // directory listing does not.
        val referenced = layers
            .flatMap { it.optJSONObject("layout")?.optJSONArray("text-font")?.strings().orEmpty() }
            .distinct()

        assertTrue("no fontstacks referenced at all", referenced.isNotEmpty())
        val missing = referenced.filterNot { stack ->
            runCatching { asset("map/fonts/$stack/0-255.pbf") }.isSuccess
        }
        assertEquals("fontstacks with no bundled glyphs", emptyList<String>(), missing)
    }

    // --- What the fork is for ----------------------------------------------------------------

    @Test fun `tracks are not drawn as residential streets`() {
        // The defect the fork exists to fix: upstream Bright filters class in (minor, service,
        // track) into one layer pair, so a forest track renders as a white street with a casing.
        // Any line layer testing both again has undone that — including the bridge pair, which
        // is easy to miss and leaves a track changing identity every time it crosses a stream.
        //
        // Symbol layers are exempt. highway-name-minor labelling both is correct: a forest road
        // with a name should carry it, and a label has no width or colour to confuse.
        val conflated = layers.filter { layer ->
            val filter = layer.optJSONArray("filter")?.toString().orEmpty()
            layer.getString("type") == "line" &&
                filter.contains("\"minor\"") && filter.contains("\"track\"")
        }

        assertEquals("layers conflating tracks with minor roads", emptyList<String>(),
            conflated.map { it.getString("id") })
    }

    @Test fun `trails and tracks are styled from the zoom their data starts at`() {
        // planetiler-openmaptiles puts CLASS_PATH and CLASS_TRACK at z14 — z13 only with
        // --transportation_z13_paths, which build-tiles.sh does not pass. A width stop below 14
        // is styling data the archive does not contain at that zoom.
        val trailLayers = layers.filter { it.getString("id").startsWith("trailog-path-") ||
            it.getString("id").startsWith("trailog-track") }

        assertTrue("no trail layers found", trailLayers.isNotEmpty())
        for (layer in trailLayers) {
            val stops = layer.getJSONObject("paint").optJSONObject("line-width")
                ?.optJSONArray("stops") ?: continue
            val firstZoom = stops.getJSONArray(0).getDouble(0)

            assertTrue(
                "${layer.getString("id")} has a width stop at z$firstZoom, below the z14 the data starts at",
                firstZoom >= 14.0,
            )
        }
    }
}
