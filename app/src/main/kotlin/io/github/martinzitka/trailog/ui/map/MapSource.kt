package io.github.martinzitka.trailog.ui.map

import androidx.annotation.StringRes
import io.github.martinzitka.trailog.R

/**
 * Where a source's MapLibre style comes from.
 *
 * Three variants rather than one, because providers genuinely differ. A model that assumed
 * "a style JSON exists somewhere" could not represent [RasterTemplate] at all, and that is
 * what most legacy and hobby tile servers offer (ADR 0015).
 */
sealed interface StyleSource {

    /** A complete MapLibre style JSON fetched over http(s). Most commercial vector providers. */
    data class Remote(val url: String) : StyleSource

    /**
     * A style JSON in the app's assets. Trailog's default.
     *
     * Bundling it is what lets the map render a route over a blank background when no region
     * archive has been downloaded, rather than failing — the "tiles unavailable offline" state
     * the plan requires on Activity detail.
     */
    data class Bundled(val assetPath: String) : StyleSource

    /**
     * A bare XYZ tile template such as `https://host/{z}/{x}/{y}.png`, which we wrap in a
     * minimal synthesised style.
     */
    data class RasterTemplate(
        val urlTemplate: String,
        val tileSize: Int = 256,
        val maxZoom: Int = 19,
    ) : StyleSource
}

/**
 * How a provider expects a user-supplied credential to be presented.
 *
 * Deliberately not a `requiresApiKey: Boolean`. The parameter name differs per provider —
 * `key`, `apikey`, `access_token` — and some want a header instead, so presence alone is not
 * enough information to make a request.
 */
sealed interface Credential {

    /** No credential. Trailog's self-hosted default, and any open tile server. */
    data object None : Credential

    /** Appended to the query string of every request the style makes. */
    data class QueryParam(val name: String) : Credential

    /** Sent as an HTTP header on every request the style makes. */
    data class Header(val name: String) : Credential
}

/**
 * Attribution a source obliges us to display. Not optional metadata — for most providers it
 * is a licence condition, and for OpenStreetMap data it is required by ODbL.
 *
 * Text is a resource id because CLAUDE.md permits no hardcoded user-facing strings. A
 * user-configured custom source would supply literal text instead; that variant arrives with
 * the settings UI that can create one, not before.
 */
data class Attribution(
    @StringRes val textRes: Int,
    val url: String? = null,
    /** Asset path of a logo the provider mandates alongside the text, if any. */
    val logoAsset: String? = null,
)

/**
 * A map source: everything needed to render tiles and to obey the terms they come under.
 *
 * Exactly one source ships today (see [BuiltInMapSources]) and it is self-hosted, so no map
 * traffic leaves a host the user controls. The abstraction exists ahead of a second source
 * because the parts that are hard to retrofit — credential injection especially — are
 * structural rather than cosmetic. ADR 0015 records the reasoning.
 */
data class MapSource(
    val id: String,
    @StringRes val displayNameRes: Int,
    val style: StyleSource,
    val credential: Credential = Credential.None,
    val attribution: Attribution,
    /**
     * Whether tiles from this source may be stored on disk.
     *
     * Read by the offline region pack code, which must refuse to build a pack from a source
     * that forbids it. Some providers' terms prohibit caching outright. This is a property the
     * code checks, not a note in a README — a README does not constrain whatever is written
     * after it.
     */
    val cacheable: Boolean,
    /** Whether the source needs no network at all: a local archive rather than a remote host. */
    val worksOffline: Boolean,
)

/** The sources compiled into the app. */
object BuiltInMapSources {

    /**
     * Self-hosted OpenStreetMap vector tiles, built by `infra/tiles/build-tiles.sh` and either
     * stored on the device or streamed from the user's own server.
     *
     * The only source Trailog ships. Adding a third-party provider is not a configuration
     * change — it requires the discussion CLAUDE.md mandates and a superseding ADR.
     */
    val selfHosted: MapSource = MapSource(
        id = "self-hosted-osm",
        displayNameRes = R.string.map_source_self_hosted,
        style = StyleSource.Bundled(assetPath = "map/style.json"),
        credential = Credential.None,
        attribution = Attribution(
            textRes = R.string.map_attribution_osm,
            url = "https://www.openstreetmap.org/copyright",
        ),
        cacheable = true,
        worksOffline = true,
    )

    /** Every source the app knows about, in display order. */
    val all: List<MapSource> = listOf(selfHosted)
}
