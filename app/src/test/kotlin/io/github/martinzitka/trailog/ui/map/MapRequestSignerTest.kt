package io.github.martinzitka.trailog.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MapRequestSigner].
 *
 * These carry unusual weight. Trailog ships exactly one map source and it needs no credential
 * (ADR 0015), so the [Credential] branches are never executed by the running app — these tests
 * are the *only* thing standing between the abstraction and a credential path that has never
 * worked. They therefore drive it directly with synthetic keyed sources rather than going
 * through [BuiltInMapSources].
 *
 * The host-restriction cases are the important ones: a signer that leaks the user's API key to
 * an unrelated host referenced by a style is a credential disclosure, not a rendering bug.
 */
class MapRequestSignerTest {

    private val provider = setOf("tiles.example.com")

    private fun queryParamSigner(key: String? = "SECRET") =
        MapRequestSigner(Credential.QueryParam("apikey"), key, provider)

    private fun headerSigner(key: String? = "SECRET") =
        MapRequestSigner(Credential.Header("X-Api-Key"), key, provider)

    // --- Credential.None: the shipping configuration -------------------------------------

    @Test
    fun `unkeyed source leaves urls untouched`() {
        val signer = MapRequestSigner(Credential.None, key = null, allowedHosts = provider)
        val url = "https://tiles.example.com/style.json?lang=en"

        assertEquals(url, signer.signUrl(url))
        assertTrue(signer.headersFor(url).isEmpty())
    }

    // --- Query parameter credentials ------------------------------------------------------

    @Test
    fun `appends key when the url has no query string`() {
        assertEquals(
            "https://tiles.example.com/style.json?apikey=SECRET",
            queryParamSigner().signUrl("https://tiles.example.com/style.json"),
        )
    }

    @Test
    fun `preserves an existing query string`() {
        assertEquals(
            "https://tiles.example.com/style.json?lang=en&apikey=SECRET",
            queryParamSigner().signUrl("https://tiles.example.com/style.json?lang=en"),
        )
    }

    @Test
    fun `replaces rather than duplicates an existing key parameter`() {
        // A style that already carries a placeholder key must end up with exactly one, or the
        // provider sees an ambiguous request and behaviour depends on their parser.
        assertEquals(
            "https://tiles.example.com/style.json?lang=en&apikey=SECRET",
            queryParamSigner().signUrl("https://tiles.example.com/style.json?apikey=STALE&lang=en"),
        )
    }

    @Test
    fun `preserves a fragment`() {
        assertEquals(
            "https://tiles.example.com/style.json?apikey=SECRET#layer",
            queryParamSigner().signUrl("https://tiles.example.com/style.json#layer"),
        )
    }

    @Test
    fun `url-encodes a key containing reserved characters`() {
        val signer = MapRequestSigner(Credential.QueryParam("apikey"), "a b&c=d", provider)

        assertEquals(
            "https://tiles.example.com/t.json?apikey=a+b%26c%3Dd",
            signer.signUrl("https://tiles.example.com/t.json"),
        )
    }

    @Test
    fun `signs tile and glyph urls, not merely the style url`() {
        // The whole reason this class exists: a style references sprites, glyphs and tiles, and
        // every one of those requests needs the credential or the style loads and then 401s.
        val signer = queryParamSigner()

        for (path in listOf("/style.json", "/tiles/12/34/56.pbf", "/fonts/Noto/0-255.pbf", "/sprite@2x.png")) {
            val signed = signer.signUrl("https://tiles.example.com$path")
            assertTrue("$path was not signed: $signed", signed.contains("apikey=SECRET"))
        }
    }

    // --- Header credentials ----------------------------------------------------------------

    @Test
    fun `header credential travels in headers and not in the url`() {
        val url = "https://tiles.example.com/style.json"
        val signer = headerSigner()

        assertEquals(url, signer.signUrl(url))
        assertEquals(mapOf("X-Api-Key" to "SECRET"), signer.headersFor(url))
    }

    // --- Host restriction: credential disclosure ---------------------------------------------

    @Test
    fun `does not sign a host the source does not own`() {
        // A style may legitimately reference another host. Signing it would hand the user's key
        // to a third party.
        val url = "https://unrelated-cdn.example.net/sprite.png"

        assertEquals(url, queryParamSigner().signUrl(url))
        assertTrue(headerSigner().headersFor(url).isEmpty())
    }

    @Test
    fun `host matching ignores case`() {
        val signed = queryParamSigner().signUrl("https://TILES.EXAMPLE.COM/style.json")

        assertTrue(signed, signed.contains("apikey=SECRET"))
    }

    @Test
    fun `never signs non-http urls`() {
        // Bundled assets and local archives have no host and must never carry a credential.
        for (url in listOf("file:///data/tiles.pmtiles", "pmtiles:///sdcard/czechia.pmtiles", "asset://map/style.json")) {
            assertEquals(url, queryParamSigner().signUrl(url))
            assertTrue(headerSigner().headersFor(url).isEmpty())
        }
    }

    // --- Missing keys -------------------------------------------------------------------------

    @Test
    fun `a null or blank key is never appended`() {
        // Sending `apikey=` is worse than sending nothing: it reads as a deliberate empty
        // credential and providers return confusing errors for it.
        val url = "https://tiles.example.com/style.json"

        assertEquals(url, queryParamSigner(key = null).signUrl(url))
        assertEquals(url, queryParamSigner(key = "").signUrl(url))
        assertEquals(url, queryParamSigner(key = "   ").signUrl(url))
        assertTrue(headerSigner(key = null).headersFor(url).isEmpty())
    }
}
