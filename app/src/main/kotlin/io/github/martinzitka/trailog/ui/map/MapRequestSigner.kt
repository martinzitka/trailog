package io.github.martinzitka.trailog.ui.map

import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * Applies a [MapSource]'s [Credential] to the requests MapLibre makes on its behalf.
 *
 * **Why this exists before any keyed source does.** The obvious way to model a keyed provider
 * is `styleUrl + "?key=$key"`, and it fails immediately: a MapLibre style JSON references
 * sprite sheets, glyph ranges and tile endpoints, and every one of those requests needs the
 * credential too. Signing only the style URL yields a style that loads and then 401s on
 * everything it points at. Retrofitting the fix means unpicking the whole style-loading path,
 * so the logic is written and tested now (ADR 0015).
 *
 * **Not yet attached to MapLibre's HTTP stack.** The only source Trailog ships needs no
 * credential, so there is nothing to sign, and hooking in an interceptor that could only ever
 * be an identity function would be speculative. Connecting this to MapLibre's OkHttp client is
 * a prerequisite for shipping any keyed source; these tests exist so that is a connection
 * rather than a design.
 *
 * **Host restriction is a security property, not a nicety.** A style may legitimately
 * reference a host other than the provider's. Signing every outbound request without checking
 * would hand the user's API key to whatever third party a style happens to name. The
 * credential is therefore applied only to [allowedHosts].
 *
 * Deliberately free of MapLibre and Android types so it can be unit tested directly against
 * synthetic keyed sources — the only way to exercise the [Credential] branches while a single
 * unkeyed source ships.
 *
 * @param key the user-supplied secret, or null when the source needs none. Never logged.
 */
class MapRequestSigner(
    private val credential: Credential,
    private val key: String?,
    allowedHosts: Set<String> = emptySet(),
) {

    private val allowedHosts: Set<String> =
        allowedHosts.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }

    /** True when there is a non-blank credential to apply. */
    private val hasKey: Boolean get() = !key.isNullOrBlank()

    /**
     * The URL MapLibre should actually request. Returns [url] unchanged when the source needs
     * no credential, when the credential travels in a header, or when the host is not one this
     * source is allowed to authenticate against.
     */
    fun signUrl(url: String): String {
        val param = credential as? Credential.QueryParam ?: return url
        if (!hasKey || !isAllowed(url)) return url
        return url.withQueryParam(param.name, key!!)
    }

    /**
     * Headers to add to a request for [url]. Empty unless the source authenticates by header
     * and the host is allowed.
     */
    fun headersFor(url: String): Map<String, String> {
        val header = credential as? Credential.Header ?: return emptyMap()
        if (!hasKey || !isAllowed(url)) return emptyMap()
        return mapOf(header.name to key!!)
    }

    /**
     * Whether [url] belongs to this source. Non-http(s) URLs — bundled assets, `file://`,
     * `pmtiles://` — never carry a credential and never match.
     */
    private fun isAllowed(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        return host.lowercase(Locale.ROOT) in allowedHosts
    }
}

/**
 * Sets [name] to [value] in the URL's query string, replacing any existing occurrence rather
 * than appending a duplicate, and preserving any fragment.
 *
 * Hand-rolled rather than using `android.net.Uri` so the signer stays a plain JVM class that
 * tests can exercise without Robolectric.
 */
internal fun String.withQueryParam(name: String, value: String): String {
    val fragmentAt = indexOf('#')
    val fragment = if (fragmentAt >= 0) substring(fragmentAt) else ""
    val withoutFragment = if (fragmentAt >= 0) substring(0, fragmentAt) else this

    val queryAt = withoutFragment.indexOf('?')
    val path = if (queryAt >= 0) withoutFragment.substring(0, queryAt) else withoutFragment
    val existing = if (queryAt >= 0) withoutFragment.substring(queryAt + 1) else ""

    val encodedName = name.urlEncoded()
    val kept = existing
        .split('&')
        .filter { it.isNotEmpty() && it.substringBefore('=') != encodedName }

    val query = (kept + "$encodedName=${value.urlEncoded()}").joinToString("&")
    return "$path?$query$fragment"
}

private fun String.urlEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8.name())
