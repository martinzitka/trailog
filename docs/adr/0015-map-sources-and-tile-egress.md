# 15. Map sources and tile egress

Date: 2026-08-23

## Status

Accepted

## Context

M1.6 replaces the placeholder route renderer with MapLibre Native. Until now `RouteMap` has
been a Compose `Canvas` drawing an auto-fitted polyline over a blank surface — a *rendering*
seam with no notion of a tile source, a style, a credential or attribution.

IMPLEMENTATION_PLAN.md describes a map source as "a MapLibre style JSON plus an optional API
key". Designing against real providers showed that one-liner is too thin to survive contact
with any of them.

Two forces pull in opposite directions:

- CLAUDE.md's strongest rule is that no network call may reach a domain the user does not
  control. The default map must therefore be self-hosted, and it is (see
  `infra/tiles/README.md`).
- Mapy.com and similar providers are genuinely wanted eventually, and retrofitting a
  provider model onto an established map implementation is the expensive path.

We resolve this by building the abstraction now and shipping exactly one implementation.

## Decision

### The source model

```kotlin
data class MapSource(
    val id: String,
    val displayName: String,
    val style: StyleSource,
    val credential: Credential = Credential.None,
    val attribution: Attribution,
    val cacheable: Boolean,
    val worksOffline: Boolean,
)
```

Four properties carry their weight even with a single unkeyed source:

**`StyleSource` has three variants, not one.** `Remote` (style JSON over http(s) — most
commercial vector providers), `Bundled` (style JSON in app assets — our default), and
`RasterTemplate` (a bare `{z}/{x}/{y}` URL we synthesise a minimal style around). The third
is the one that gets forgotten; many providers and every hobby tile server offer nothing
else, and a model assuming "a style JSON exists" cannot represent them at all.

**`Credential` models the form, not merely the presence, of a key.** A boolean
`requiresApiKey` would be the natural minimal choice and would be wrong: the query parameter
name differs per provider (`key`, `apikey`, `access_token`) and some want a header.

**`cacheable` is read by code, not by humans.** Some providers' terms prohibit storing
tiles. When M1.6's offline region packs land, the pack builder reads this flag and refuses.
A rule documented only in a README gets violated by whatever is written after the README.

**`worksOffline` distinguishes a local archive from a remote one**, which drives both source
selection when there is no network and the honesty of the UI.

### Credential injection is a request hook, never string concatenation

Modelling a keyed source as `styleUrl + "?key=$k"` fails immediately in practice: a style JSON
references sprites, glyphs and tile endpoints on the same host, and *every one* of those
requests needs the credential too. Signing only the style URL yields a style that loads and then
401s on everything it points at.

Credentials are therefore applied per request by `MapRequestSigner`, which is deliberately free
of MapLibre and Android types and is unit-tested directly against synthetic keyed sources.

**It is not yet attached to MapLibre's HTTP stack.** Today's only source is unkeyed, so there is
nothing to sign, and wiring an interceptor that could only ever be an identity function would be
speculative. Attaching it — via MapLibre's OkHttp client — is a prerequisite for shipping any
keyed source, and the tests exist so that work is a connection rather than a design.

The signer also restricts credentials to an explicit host allowlist. A style may legitimately
reference a host other than the provider's, and signing every outbound request would hand the
user's API key to whatever third party a style happens to name. That is a credential disclosure,
not a rendering bug.

### Only one source ships

The self-hosted PMTiles archive built by `infra/tiles/build-tiles.sh`. No third-party
provider is implemented, and no third-party host is contacted.

Consequently **no `mapSourceId` preference is added to `AppPreferences`**. That class
documents its own rule — a field nothing reads is a bug, not a placeholder — and with one
source there is nothing to choose. The picker and the preference arrive together with the
second source.

### A bundled style is a template, not a finished style

`StyleSource.Bundled` names a style with a `__TRAILOG_TILE_URL__` placeholder where the vector
source's tile URL belongs, substituted when the style is loaded.

It has to work this way. The archive lives either in app storage — a path that varies by device
and is unknown until runtime — or on the user's own server. Neither can be baked into an asset
at build time. `MapTiles.loadStyleJson` fails loudly if the placeholder is missing, because the
alternative is a map with no tiles and no explanation.

Only `Bundled` has a renderer today. `Remote` and `RasterTemplate` are valid values of the model
with no implementation behind them, so `RouteMap` degrades to its polyline renderer rather than
casting and crashing.

### Rendering degrades rather than failing

`RouteMap` chooses between MapLibre and a plain polyline over a blank surface based on whether an
archive is installed. The polyline is not a placeholder or a test seam: it is the state the plan
requires of Activity detail, where a route must still render with no network and no cached tiles.
Because the archive is in app storage rather than the APK, it is also the state of every fresh
install until a region pack is downloaded.

A consequence worth stating plainly: JVM tests only ever exercise the fallback. No archive exists
under Robolectric, and MapLibre's native GL library would not load there in any case. **MapLibre
rendering can only be verified on a real device**, consistent with CLAUDE.md's rule about
emulators and location.

### Bundled styles must not reference external hosts

Off-the-shelf MapLibre styles point `glyphs` and `sprite` at a CDN. CLAUDE.md forbids
externally hosted fonts outright, so a stock style JSON is a privacy violation as shipped.
Any style adopted here has `glyphs`, `sprite` and `sources` rewritten to local URLs, and the
glyph ranges and sprite sheet ship as app assets.

## Third-party tile providers: deferred, not rejected

Recorded so the reasoning is not re-derived later.

A tile request does not leak the user's track directly. It leaks the **viewport**, which is
a close proxy. The severity is not uniform:

- **Viewing a past activity** sends the provider the bounding box of a ride already
  completed.
- **Recording with follow-mode on** sends the provider a live stream of where the user is
  right now, at roughly the map's refresh rate.

The second is materially worse than the first, and a single "use provider X" toggle would
conflate them. Should a third-party source be added, the design must allow a different
source per context, or at minimum be honest in the opt-in copy that enabling it while
recording means live location egress to a third party.

Any such addition requires the explicit discussion CLAUDE.md mandates, plus a superseding
ADR. It is not a configuration change.

## Consequences

- One implementation cannot exercise the credential path, so the `Credential` branches carry
  a real risk of being wrong until a second source exists. Mitigated by keeping the
  injection point live rather than stubbed, and by unit-testing the rewriter directly
  against synthetic keyed sources.
- Offline packs gain a hard constraint (`cacheable`) before there is any source that
  violates it. Deliberate: the enforcement point must predate the provider that needs it.
- The Czech archive plus buffer is one file per region. Multiple simultaneous regions would
  require duplicating style layers per source, so the model is one active region at a time.
- With one source and no picker, `MapSource` risks becoming decorative. `RouteMap` therefore
  reads its style and offline capability through `BuiltInMapSources.selfHosted` rather than
  hardcoding an asset path, so adding a picker is a substitution rather than a rewrite.
- Attribution is carried in the style's source definition, where MapLibre renders it in its own
  attribution control. That is deliberate: a screen cannot forget it, and OSM data is ODbL, so
  attribution is a licence condition rather than a nicety. `MapSource.attribution` remains the
  app-level record for a future source picker.
