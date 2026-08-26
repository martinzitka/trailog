# 17. Contours ship as their own archive

Date: 2026-08-25

## Status

Accepted

## Context

The backlog asks for hillshading and contour lines in the self-hosted style. The narrower want
that prompted this is elevation isolines from a certain zoom level, which is the useful half
and — unlike hillshading — needs nothing but vector tiles.

Nothing in the OpenMapTiles schema carries elevation. Planetiler's profile produces none, so
contours are new data: a DEM, `gdal_contour`, tippecanoe, a PMTiles archive.

That much was never in question. What had to be settled first is whether the result is one
artifact or two, because the answer constrains the app, the build, and a tile service that has
not been written yet.

**A merged archive is genuinely possible**, and it is worth recording that it was considered
rather than assumed away. A vector tile already carries every layer in one blob — ours hold
around sixteen — so `contour` would simply be another one. Two routes reach it:

- `tile-join` from felt/tippecanoe reads and writes PMTiles and merges by tile coordinate.
- A fork of `planetiler-openmaptiles` adding a class in the `addons` package, registered in
  `ExtraLayers`, reading contours from a GeoPackage. Planetiler already reads GeoPackage,
  Shapefile, GeoJSON and GeoParquet alongside OSM.

ADR 0015 states one active archive at a time as a consequence, which on a first reading looks
like it forbids this outright. It does not, and the distinction matters enough to write down.

## Decision

### Contours ship as a second PMTiles archive

Ordered by weight, because the reason that first suggested it turns out to be the weakest:

**Terrain is static; OSM is not.** The basemap is refreshed every few months. Contours, short
of a new DEM, are correct forever. Artifacts with different lifecycles should not be welded
into one file — merged, every OSM refresh drags the terrain along with it.

**Terrain becomes optional per device.** A merged archive forces it on everyone. Two archives
let a phone short on storage take the basemap alone and still have a working map. "Install
once, never again, skip it if you like" is a state a single file cannot express.

**The build pipelines decouple.** Merged, `build-tiles.sh` cannot refresh OSM without the
contour artifacts to hand — it would either keep gigabytes of DEM derivatives around
indefinitely or regenerate them every run. That build already wants ~50 GB of scratch and
several hours; a GDAL prerequisite on top of it costs more in practice than the download does.

**No tile-size interaction.** `tile-join` does not reprocess geometry, so it has no recourse
when a merged tile exceeds the 500 KB limit: it omits the tile. Not the contours — the tile. A
dense z14 tile in the mountains could lose its basemap silently. `-pk` lifts the limit but then
ships oversized tiles to a phone renderer. Separate archives remove the question.

Bandwidth — not re-downloading terrain on every basemap refresh — is real but modest, and is
the least of these. It is listed last deliberately, so that a future reader weighing a merge
does not think refuting it settles anything.

### This refines ADR 0015; it does not supersede it

0015's "one active region at a time" is about two *basemaps*. Its reasoning is that a style
layer binds to exactly one source, so two basemap archives mean duplicating the entire layer
set per source. That reasoning is untouched and still holds.

A contour source is additive, not a second basemap: one new source and three new layers, with
nothing duplicated. One basemap at a time remains the rule.

### Archives are identified by their content, not their filename

`MapTiles.findArchive` currently picks the largest `.pmtiles` file. With two archives that is
wrong, and a filename convention is no better — archives arrive by `adb push` during
development and from a region download later, and neither guarantees a name.

The file identifies itself. The PMTiles v3 header carries min zoom at byte 100 and max zoom at
101, so a contour archive built from z11 is already distinguishable from a z0 basemap on bytes
we parse today. The authoritative answer is the JSON metadata — offset at bytes 16–23, length
at 24–31, compressed per the byte at 97 — which for MVT archives must carry `vector_layers`
under TileJSON 3.0. An archive's role is therefore read from the layers it declares.

This is more code than comparing file sizes. That is the point: the alternative fails by
loading terrain as a basemap and rendering a map of nothing but contour lines.

### A missing terrain archive is an ordinary state, not an error

The contour source and its layers are stripped at style load time when no terrain archive is
installed. A fresh install is that state by definition, exactly as it is for the basemap under
0015, and the map must render without complaint.

### The style keeps its template shape

A second placeholder joins `__TRAILOG_TILE_URL__`. 0015's fail-loudly check on the basemap
placeholder is unchanged — a style asset without it is still a build mistake. The terrain
placeholder is conditional by design, and its absence from a loaded style is not an error.

### The contour schema follows `maptiler-terrain-gl-style`

Source layer `contour`, with `height` in metres and `nth_line` marking index lines. Adopted
because it is an existing published contract with a working reference style, so the three
layers — line, index line, label — can be lifted rather than invented, and any future style
written against that schema works against our archive.

`height` is metres, per CLAUDE.md's SI rule.

### Contour density is controlled at generation, not in the style

Per-feature `tippecanoe: {minzoom}`: index lines only at z11–12, full interval from z13.
Filtering by zoom in the style changes what is drawn but not what is stored, and tile size is
the constraint that matters.

### Constraints this places on the tile service

Recorded now because the service is backlog and unwritten, and these are cheap to honour in a
design and expensive to retrofit.

- **A region is a set of artifacts, not a URL** — each with a role, a size, a checksum, and
  **its own version**. A per-region version would force a terrain re-download on every OSM
  refresh, reintroducing precisely the cost this ADR exists to avoid.
- **Style catalogue entries declare the artifacts they require.** Once terrain is optional, a
  style carrying contour layers is unusable against a basemap-only install, and a picker that
  offers it renders half a map.
- **`PartialContent` is mandatory and `Compression` must not touch `.pmtiles`.** PMTiles is
  entirely HTTP range requests; without the former, streaming does not work at all. Tile data
  is already compressed internally, so gzipping a range response is both wrong and wasteful.
- **Streaming means two concurrent range streams.** Since the server is multi-tenant, those
  requests carry a token, which is the trigger for attaching `MapRequestSigner` to MapLibre's
  HTTP stack — the piece 0015 deliberately left unwired. Both archives sit on the user's own
  host, so one allowlist entry covers them and 0015's design needs no change.
- **A shared instance stores terrain once** across however many OSM vintages its users are on.
  Merged archives would mean a full copy of the terrain per vintage.
- **Contour tiles are not a DEM and cannot be sampled for elevation.** Elevation at a point —
  plausible later, given that absolute altitude wants anchoring and not every phone has a
  barometer — needs the raster DEM, a third artifact. The contour pipeline produces it as an
  intermediate, so keeping it is nearly free, but nothing may assume the contours can serve it.

### What this does not decide

The DEM source, and therefore its attribution terms. Also untouched: hillshading, which needs a
`raster-dem` source rather than vector tiles, and Czech KČT waymarks, which are blocked in the
tiles rather than in the style and need their own decision.

## Consequences

- **Two PMTiles sources in one style is unverified on MapLibre Native Android.** There is no
  particular reason to expect trouble, but the whole pipeline is built on the assumption, so it
  is checked on a device with a stub contour archive before any of it is written. Map rendering
  is only ever verifiable on a device (0015, CLAUDE.md).
- The `tile-join` merge prototype is no longer needed. Its only purpose was measuring whether
  merged z13–14 tiles stay under the size limit, and that question is now moot.
- `findArchive` gains a gunzip and a JSON parse where it had a file-size comparison.
- Development gains a second `adb push`, and `infra/tiles/README.md` gains a second artifact to
  explain. Two files is a marginally worse story to tell a user than one; the lifecycle
  argument is judged to outweigh it.
- A new build script, with GDAL and tippecanoe as prerequisites alongside the existing WSL
  requirement for osmium and Planetiler.
- Whichever DEM is chosen carries an attribution obligation of its own. Like ODbL for OSM it is
  a licence condition rather than a courtesy, so it is carried in the contour source's
  `attribution` where a screen cannot forget it — the same mechanism 0015 uses.
- The reference style labels contours as `"{height} m"` in the style JSON. That is evaluated at
  render time, so it does not violate the "no stored formatted strings" rule, but it does
  hardcode metric in an asset rather than in the formatter. When the imperial preference
  arrives it will need a runtime layout-property change on that one layer. Noted so it is found
  then rather than discovered.
