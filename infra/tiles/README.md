# Map tiles

Trailog's default map source is a **self-hosted PMTiles archive built from OpenStreetMap**.
No third-party tile server is contacted, ever — see CLAUDE.md, "No third-party egress".

There are two scripts, with very different outputs:

| Script | Produces | Size | Committed? |
|---|---|---|---|
| `build-tiles.sh` | `czechia-buffered.pmtiles` | hundreds of MB | **No** — reproducible from the script |
| `fetch-style-assets.sh` | glyphs and sprites in `app/src/main/assets/map/` | 2.3 MB | **Yes** — small, and the app needs them at all times |

The style itself, `app/src/main/assets/map/style.json`, is neither: it is **vendored** — forked
from OSM Bright and maintained by hand. See "The style is vendored" below before editing it.

## What `build-tiles.sh` produces

A PMTiles vector tile archive covering the **Czech Republic plus a ~25 km buffer** into
Germany, Austria, Poland and Slovakia.

The buffer is not decoration. Geofabrik extracts are hard-clipped at national borders, so a
ride near the border would otherwise run off the edge of the map into blank space.

Tiles use the **OpenMapTiles schema** at zoom 0–14. MapLibre overzooms past z14 on the
client, so the map still zooms to ~20 without storing those tiles.

### The snapshot date is pinned

`SNAPSHOT` in the script fixes which Geofabrik daily build every region is taken from, rather
than using `-latest`. This is a **correctness** requirement, not merely reproducibility.

Overlapping extracts share the objects along every border, and `osmium merge` only collapses
duplicates when type, ID *and version* all match. Mixing snapshot dates leaves two versions of
the same node in the merged file, and Planetiler rejects that outright:

```
java.lang.IllegalArgumentException: Nodes must be sorted ascending by ID, 251449575 came after 251449575
```

— roughly twenty minutes into the build, long after the download that caused it.

Every download is checked against Geofabrik's published md5 for the pinned snapshot, so a
leftover file from a different day is detected and re-fetched rather than silently poisoning
the merge. Geofabrik keeps dated files for about three months; when the pin expires, bump it
to any date where all regions are published.

## Prerequisites

Run this in **WSL (Ubuntu)**, not Git Bash or PowerShell:

- `osmium-tool` has no good native Windows build.
- Planetiler is heavily I/O bound. A native ext4 working directory is dramatically faster
  than an NTFS bind mount through `/mnt/c`.

```bash
sudo apt update
sudo apt install osmium-tool openjdk-21-jre-headless curl
```

Planetiler requires **Java 21+**. Note this is deliberately *not* the JDK the app builds
with — Trailog is pinned to JDK 17 by ADR 0003 because AGP 8.7 requires it. The two are
unrelated: `jvmToolchain(17)` fixes the app's bytecode target regardless of what else is
installed.

### Disk space

The build wants roughly **50 GB of headroom**, mostly Planetiler's temporary node maps.

Watch out for a trap: WSL's ext4 lives in a dynamically growing VHDX on the Windows `C:`
drive. `df` inside WSL reports the *virtual* disk size, which can be far larger than what
is actually available. **The real ceiling is free space on `C:`.**

## Running it

```bash
./infra/tiles/build-tiles.sh
```

Every stage is idempotent — downloads, clipped extracts and the merge are skipped if already
present, so an interrupted run resumes cheaply. Only the final Planetiler stage always
re-runs. Re-downloading an extract invalidates whatever was derived from it.

Overrides:

```bash
TRAILOG_TILES_WORK=/path/to/scratch   # default ~/trailog-tiles
TRAILOG_TILES_HEAP=16g                # default 12g
TRAILOG_TILES_SNAPSHOT=260822         # Geofabrik snapshot date, YYMMDD
```

### Stages

1. **Download** — Geofabrik extracts. Sub-regional where the neighbour is large: Saxony and
   Bavaria cover the whole CZ–DE border, three voivodeships cover CZ–PL. Pulling all of
   Germany (~4 GB) to clip a border strip out of it would be wasteful.
2. **Clip** — each extract to the buffered bounding box. Clipping *before* the merge keeps
   the merge cheap; neighbours contribute only their border strip.
3. **Merge** — `osmium merge`, which deduplicates objects appearing in more than one
   extract (they will, along every border).
4. **Build** — Planetiler produces PMTiles directly. No MBTiles conversion step.

## Getting tiles onto the phone

The archive is **not bundled in the APK** — it is far too large, and Play caps the base APK
at 150 MB. It lives in app storage.

```bash
adb push dist/czechia-buffered.pmtiles \
  /sdcard/Android/data/io.github.martinzitka.trailog.debug/files/tiles/
```

Later, M1.6's offline region packs will download it from the user's own server instead.

What *is* bundled in the APK is the small stuff: the style JSON, glyph (font) ranges and
sprite sheet — a few MB total. That means the app always has a valid style even with no
region downloaded, which is what makes the "map tiles unavailable offline" state render a
route over a blank background rather than an error.

## Style, glyphs and sprites — `fetch-style-assets.sh`

```bash
./infra/tiles/fetch-style-assets.sh
```

Runs anywhere with bash, curl and Python — no WSL needed. Takes seconds. Its output is
committed, so you only re-run it to update the glyphs or sprites.

### The style is vendored

`app/src/main/assets/map/style.json` is **ours** — "Trailog Outdoor", forked from OSM Bright.
It is a committed artifact, not a generated one. Nothing regenerates it, and the script no
longer downloads it: doing so would silently destroy the fork.

This is a change from how it used to work, and it changes the update story. To re-fork against
a newer upstream Bright:

```bash
./infra/tiles/fetch-style-assets.sh --upstream   # writes work/osm-bright-upstream.json
```

then diff and port by hand. The fork is design, not a mechanical rewrite, so there is no script
that can redo it.

### What the fork changes

Bright is a general-purpose basemap. These are the edits that make it an outdoor one, all of
them reading data our archive already contains:

| Change | Why |
|---|---|
| `track` split out of `highway-minor` | A forest track rendered as a white residential street. Now tan, with its own casing — including on bridges, which is easy to miss and makes a track change identity at every stream |
| Unpaved tracks dashed | `surface`, which Planetiler collapses to exactly `paved`/`unpaved` |
| Paths split by `subclass` | Cycleway, footway and path were one undifferentiated dash |
| `mtb_scale` underlay | Technical trails read as thicker without losing the dash that says what they are |
| `mountain_peak` added | Bright omits the layer entirely; the sprite already had `mountain_11` |
| `park` added | Protected areas and nature reserves, likewise omitted |
| Woodland opacity 0.1 → 0.16 | Near-invisible against the `#f8f4f0` background |

Trails are distinguished by **dash pattern rather than by hue**. The route line is
`MaterialTheme.colorScheme.primary`, which under dynamic colour can be any hue at all, so a
basemap encoding meaning in saturated colour would collide with it unpredictably.

**Paths and tracks only exist from z14.** `planetiler-openmaptiles` puts `CLASS_PATH` and
`CLASS_TRACK` at zoom 14 — 13 only with `--transportation_z13_paths`, which `build-tiles.sh`
does not pass. Styling them lower renders nothing. Worth adding to the next rebuild if trails
at z13 turn out to matter.

Two known gaps, neither worth a rebuild on its own:

- A track inside a **tunnel** still renders as a service road (`tunnel-service-track`). Rare
  enough to leave; Bright's brunnel layers are structured separately and splitting them out
  costs two more layers for something you may never ride through.
- `trailog-peak` labels elevation as `"{height} m"`, hardcoding metric in an asset rather than
  going through `Formatter`. It is evaluated at render time so nothing formatted is stored, but
  the imperial preference will need a runtime layout-property change. Layers with this problem
  are listed in the style's `metadata` under `trailog:unitLabelledLayers`, and ADR 0017 records
  the same gap for contour labels.

### The script validates rather than generates

> **A stock OpenMapTiles style is a privacy violation as shipped.** Upstream OSM Bright points
> `glyphs` and `sources` at `api.maptiler.com` (with an API key placeholder) and `sprite` at
> `openmaptiles.github.io` — three third-party hosts contacted at render time, one of them on
> every label draw. CLAUDE.md forbids externally hosted fonts outright.
>
> Ours rewrites all three to `asset://` URLs, and the script **asserts that no `http` URL
> survives anywhere in the style**, failing if one does. Fetching from those hosts at *build*
> time is fine; the rule is about what the app does at *run* time.

It also checks that every `source-layer` is one the OpenMapTiles schema actually produces, that
every literal icon is in the sprite sheet, that layer ids are unique, and that the layer groups
declared in `metadata` all exist. A layer naming data the archive lacks, or an icon the sprite
lacks, renders as nothing and reports nothing — the failure would otherwise be found on a
hillside.

`BundledStyleTest` asserts the same invariants from `:app`. The overlap is deliberate: this
script only runs when someone re-fetches assets, whereas the test runs on every push and pull
request, and it is the one that gates CI.

The vector source's tile URL is deliberately left as a `__TRAILOG_TILE_URL__` placeholder.
The archive lives either in app storage or on the user's own server, and the path is only
known at runtime, so it is substituted when the style is loaded.

Glyphs cover seven Unicode ranges per fontstack — Latin, Latin Extended-A/B, combining
diacriticals, Greek, Cyrillic, Latin Extended Additional and General Punctuation — which is
enough for Czech, German, Polish and Slovak place names. Bundling all 256 ranges would cost
~60 MB per fontstack for scripts this map will never label. A missing range renders as blank
boxes rather than failing, so widen `RANGES` if labels come up empty somewhere.

## Serving tiles over the network

PMTiles is designed around HTTP range requests, so the *same single file* on ordinary
static hosting serves tiles with no tile server process. Point the app at your own Ktor
instance — or any static host you control — and it fetches ranges on demand.

MapLibre Native has read PMTiles archives directly since Android **11.7.0** (we are on
11.11.0), so neither mode needs a custom protocol handler. The tile source URL carries it:

| Mode | Tile source URL |
|---|---|
| On-device archive | `pmtiles://file:///…/files/tiles/czechia-buffered.pmtiles` |
| Streamed from your server | `pmtiles://https://your-host/czechia-buffered.pmtiles` |

One artifact, two modes: stream from your own server when online, or download it whole for
offline use. Zero third-party egress either way.

## Adding other countries

One build per Geofabrik extract. Adding Austria properly means another run with a different
region list and bbox.

Two things to know before you do:

**Multiple regions at once is awkward.** A MapLibre style layer binds to exactly one
source, so two PMTiles archives means duplicating the entire layer set per source. The
intended model is **one active region at a time**, switchable in the UI — you are in one
country on any given ride. If you genuinely need seamless cross-border coverage, widen the
bbox and region list here and rebuild as a single archive, which is exactly what the Czech
buffer already does on a small scale.

**Europe-wide is feasible but changes what the artifact is for.** Input is ~30 GB, output
runs to tens of GB, and it wants far more RAM and scratch disk than a country build. That
size never goes on a phone, so an Europe archive only makes sense as a *server-hosted
streaming* source. Offline packs stay regional regardless.

The middle option is usually the right one: widen the region list to the countries you
actually ride in, and keep it small enough to sit on the device.

## Keeping it current

OSM data goes stale; Geofabrik rebuilds daily. Re-running the script from scratch is the
update mechanism — delete `~/trailog-tiles/download` to force fresh extracts. Realistically
worth doing every few months.
