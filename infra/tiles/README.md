# Map tiles

Trailog's default map source is a **self-hosted PMTiles archive built from OpenStreetMap**.
No third-party tile server is contacted, ever — see CLAUDE.md, "No third-party egress".

`build-tiles.sh` is the artifact. The tiles themselves are not committed: they are hundreds
of megabytes, and they are reproducible from this script.

## What it produces

A PMTiles vector tile archive covering the **Czech Republic plus a ~25 km buffer** into
Germany, Austria, Poland and Slovakia.

The buffer is not decoration. Geofabrik extracts are hard-clipped at national borders, so a
ride near the border would otherwise run off the edge of the map into blank space.

Tiles use the **OpenMapTiles schema** at zoom 0–14. MapLibre overzooms past z14 on the
client, so the map still zooms to ~20 without storing those tiles.

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

Every stage is idempotent — downloads and clipped extracts are skipped if already present,
so an interrupted run resumes cheaply. Only the final Planetiler stage always re-runs.

Overrides:

```bash
TRAILOG_TILES_WORK=/path/to/scratch   # default ~/trailog-tiles
TRAILOG_TILES_HEAP=16g                # default 12g
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

> **Glyphs must be local.** Off-the-shelf MapLibre styles point `glyphs` and `sprite` at a
> CDN. CLAUDE.md forbids externally hosted fonts outright, so a stock style JSON is a
> privacy violation as shipped. Any style adopted here has its `glyphs`, `sprite` and
> `sources` URLs rewritten to local ones.

## Serving tiles over the network

PMTiles is designed around HTTP range requests, so the *same single file* on ordinary
static hosting serves tiles with no tile server process. Point the app at your own Ktor
instance — or any static host you control — and it fetches ranges on demand.

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
