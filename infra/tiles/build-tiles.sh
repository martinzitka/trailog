#!/usr/bin/env bash
#
# Build the default Trailog map source: a PMTiles vector tile archive covering the Czech
# Republic plus a ~25 km buffer into neighbouring countries.
#
# Read infra/tiles/README.md before running. Intended to run inside WSL (Ubuntu) rather
# than Git Bash: it needs osmium-tool, and Planetiler is heavily I/O bound, so a native
# ext4 working directory is dramatically faster than an NTFS bind mount.
#
# The output is NOT committed — it is hundreds of MB. This script is the artifact; the
# tiles are reproducible from it.

set -euo pipefail

# --- Configuration -----------------------------------------------------------------

# Czech Republic's bounding box (12.09,48.55,18.86,51.06) expanded by ~25 km on all sides.
# Geofabrik extracts are hard-clipped at national borders, so without this a ride near the
# German, Austrian, Polish or Slovak border runs off the edge of the map into blank space.
#
# A bounding box is cruder than buffering the actual border polygon, but needs no GIS
# tooling and the overshoot is useful: it pulls in Dresden, Wroclaw, Bratislava and the
# eastern edge of Vienna. Switch to a buffered .poly if the output ever gets too large.
BBOX="11.7,48.3,19.25,51.35"

# Geofabrik paths, relative to https://download.geofabrik.de/.
#
# Sub-regional extracts are used where the neighbour is large: Sachsen and Bayern together
# cover the entire CZ-DE border, and three voivodeships cover the CZ-PL border. Downloading
# all of Germany (~4 GB) to clip a border strip out of it would be wasteful. Austria and
# Slovakia are small enough to take whole.
#
# German states use their GERMAN names — sachsen and bayern, not saxony and bavaria. Getting
# this wrong does not 404: Geofabrik redirects an unknown path to its index page, which
# returns 200, so curl happily writes an HTML file named .osm.pbf. See validate_pbf below.
REGIONS=(
  europe/czech-republic
  europe/germany/sachsen
  europe/germany/bayern
  europe/austria
  europe/slovakia
  europe/poland/dolnoslaskie
  europe/poland/opolskie
  europe/poland/slaskie
)

# Geofabrik snapshot date (YYMMDD), pinned rather than using `-latest`.
#
# This is a correctness requirement, not just reproducibility. Overlapping extracts share the
# objects along every border, and `osmium merge` only collapses them when type, ID *and
# version* all match. Mixing snapshot dates yields two versions of the same node, which
# Planetiler rejects outright with "Nodes must be sorted ascending by ID, N came after N" —
# after the merge, ~20 minutes into the build.
#
# Geofabrik keeps dated files for roughly three months. When this one expires, bump it to any
# date where every region in REGIONS is published; the preflight below checks.
SNAPSHOT="${TRAILOG_TILES_SNAPSHOT:-260822}"

# Vector tiles stop at z14 — the OpenMapTiles convention. MapLibre overzooms past it on the
# client, so zoom still works to ~20 without storing those tiles.
MAXZOOM=14

OUTPUT_NAME="czechia-buffered"

# Working directory. Keep this on the WSL filesystem, not under /mnt/c.
WORK_DIR="${TRAILOG_TILES_WORK:-$HOME/trailog-tiles}"
DOWNLOAD_DIR="$WORK_DIR/download"
CLIPPED_DIR="$WORK_DIR/clipped"
DIST_DIR="$WORK_DIR/dist"

PLANETILER_JAR="$WORK_DIR/planetiler.jar"
PLANETILER_URL="https://github.com/onthegomap/planetiler/releases/latest/download/planetiler.jar"

# Planetiler heap. It spills to disk beyond this, so more is faster up to a point, but
# leave the OS room for page cache — that is what actually makes the build fast.
JAVA_HEAP="${TRAILOG_TILES_HEAP:-12g}"

# --- Preflight ---------------------------------------------------------------------

require() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "ERROR: '$1' not found. $2" >&2
    exit 1
  }
}

require curl "Install with: sudo apt install curl"
require osmium "Install with: sudo apt install osmium-tool"
require java "Install with: sudo apt install openjdk-21-jre-headless (Planetiler needs 21+)"

java_major="$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
if [ "$java_major" -lt 21 ]; then
  echo "ERROR: Planetiler requires Java 21+, found $java_major." >&2
  echo "       sudo apt install openjdk-21-jre-headless" >&2
  exit 1
fi

mkdir -p "$DOWNLOAD_DIR" "$CLIPPED_DIR" "$DIST_DIR"

# Confirm a download is actually an OSM PBF before accepting it.
#
# `curl --fail` is not sufficient protection here. Geofabrik answers an unknown region path
# with a 302 to its index page, and that page returns 200 — so curl reports success and
# writes ~9 KB of HTML to a file named .osm.pbf. The failure then surfaces much later as a
# confusing osmium parse error, long after the download that caused it.
#
# `osmium fileinfo` only reads the header, so this is fast even on a 1 GB file.
#
# -F pbf is required, not optional. Osmium infers format from the file *suffix*, and this is
# called on `.osm.pbf.partial` during download — an unrecognised suffix, which osmium reports
# as "Format: unknown" and fails on regardless of the content being a perfectly good PBF.
# Forcing the format makes the check depend on the bytes rather than the name. Verified to
# still reject an HTML error page.
validate_pbf() {
  local file="$1" region="$2"
  if ! osmium fileinfo -F pbf "$file" >/dev/null 2>&1; then
    rm -f "$file"
    echo "ERROR: $region did not return an OSM PBF — check the Geofabrik path." >&2
    echo "       German states use German names (sachsen, bayern), not English ones." >&2
    exit 1
  fi
}

# Confirm a file matches Geofabrik's published md5 for the pinned snapshot.
#
# This is what actually enforces "every extract is from the same day". A file left over from
# an earlier run at a different snapshot is indistinguishable by name or size, and the
# resulting version conflict does not surface until deep into the Planetiler run.
matches_snapshot() {
  local file="$1" region="$2"
  local expected
  expected="$(curl --fail --silent --location \
    "https://download.geofabrik.de/${region}-${SNAPSHOT}.osm.pbf.md5" | awk '{print $1}')" || return 1
  [ -n "$expected" ] || return 1
  [ "$(md5sum "$file" | awk '{print $1}')" = "$expected" ]
}

# WSL's ext4 lives in a dynamically growing VHDX on the Windows C: drive. df inside WSL
# reports the virtual disk's size, which is NOT the real ceiling — the real limit is free
# space on C:. Warn on the value we can see; the Windows figure is the one that bites.
avail_gb="$(df -BG --output=avail "$WORK_DIR" | tail -1 | tr -dc '0-9')"
if [ "$avail_gb" -lt 60 ]; then
  echo "WARNING: only ${avail_gb}G visible at $WORK_DIR. This build wants ~50G of headroom." >&2
  echo "         Remember WSL's disk is a VHDX on C: — check free space on C: too." >&2
fi

# --- 1. Download -------------------------------------------------------------------

echo "==> Downloading Geofabrik extracts"
for region in "${REGIONS[@]}"; do
  name="$(basename "$region")"
  dest="$DOWNLOAD_DIR/$name.osm.pbf"
  if [ -f "$dest" ]; then
    # Validate on the resume path too, not just after a fresh download. An interrupted or
    # previously-broken run can leave a file that looks plausible by name and size.
    validate_pbf "$dest" "$region"
    if matches_snapshot "$dest" "$region"; then
      echo "    $name — already at snapshot $SNAPSHOT, skipping"
      continue
    fi
    echo "    $name — different snapshot, re-downloading"
    rm -f "$dest"
  fi
  echo "    $name"
  curl --fail --location --progress-bar \
    "https://download.geofabrik.de/${region}-${SNAPSHOT}.osm.pbf" \
    --output "$dest.partial"
  validate_pbf "$dest.partial" "$region"
  if ! matches_snapshot "$dest.partial" "$region"; then
    rm -f "$dest.partial"
    echo "ERROR: $name failed its md5 check — corrupted download, or snapshot $SNAPSHOT" >&2
    echo "       was withdrawn mid-build. Re-run; if it persists, bump SNAPSHOT." >&2
    exit 1
  fi
  mv "$dest.partial" "$dest"

  # Anything derived from the previous mix of extracts is now stale.
  rm -f "$CLIPPED_DIR/$name.osm.pbf" "$WORK_DIR/$OUTPUT_NAME.osm.pbf"
done

# --- 2. Clip each extract to the buffered bbox ---------------------------------------

# Clipping before merging rather than after keeps the merge cheap: the neighbours
# contribute only their border strip, so the merge input is a fraction of the download.
echo "==> Clipping to $BBOX"
CLIPPED_FILES=()
for region in "${REGIONS[@]}"; do
  name="$(basename "$region")"
  src="$DOWNLOAD_DIR/$name.osm.pbf"
  dest="$CLIPPED_DIR/$name.osm.pbf"
  if [ ! -f "$dest" ]; then
    echo "    $name"
    osmium extract --bbox "$BBOX" --set-bounds --overwrite "$src" -o "$dest"
  else
    echo "    $name — already clipped, skipping"
  fi
  CLIPPED_FILES+=("$dest")
done

# --- 3. Merge ------------------------------------------------------------------------

MERGED="$WORK_DIR/$OUTPUT_NAME.osm.pbf"
if [ -f "$MERGED" ]; then
  echo "==> Merged extract already present, skipping"
else
  echo "==> Merging into $MERGED"
  # osmium merge collapses objects appearing in more than one extract, which they will along
  # every border — but only when type, ID *and version* all match. That is why SNAPSHOT is
  # pinned above: extracts from different days disagree on version and both copies survive.
  # Inputs must be sorted; Geofabrik extracts already are.
  osmium merge --overwrite "${CLIPPED_FILES[@]}" -o "$MERGED"
fi

# --- 4. Build tiles ------------------------------------------------------------------

if [ ! -f "$PLANETILER_JAR" ]; then
  echo "==> Fetching Planetiler"
  curl --fail --location --progress-bar "$PLANETILER_URL" --output "$PLANETILER_JAR"
fi

OUTPUT="$DIST_DIR/$OUTPUT_NAME.pmtiles"
echo "==> Building $OUTPUT (this is the slow part)"
# --download fetches Planetiler's *auxiliary* inputs: lake centerlines, water polygons and
# Natural Earth. The OpenMapTiles profile requires all three regardless of the OSM data, and
# they are not in a Geofabrik extract. It does not re-download the OSM data — that is pinned
# by --osm-path to the merged file built above. Roughly 1 GB, cached across runs.
# Run from the work directory: Planetiler resolves its own data/ folder (auxiliary
# sources, temp node maps - well over a gigabyte) relative to the *working* directory,
# so invoking this from a repo checkout drops all of that inside the repo.
cd "$WORK_DIR"

java "-Xmx$JAVA_HEAP" -jar "$PLANETILER_JAR" \
  --download \
  --osm-path="$MERGED" \
  --output="$OUTPUT" \
  --bounds="$BBOX" \
  --maxzoom="$MAXZOOM" \
  --force

echo
echo "==> Done"
ls -lh "$OUTPUT"
echo
echo "Copy to the phone with:"
echo "  adb push \"$OUTPUT\" /sdcard/Android/data/io.github.martinzitka.trailog.debug/files/tiles/"
