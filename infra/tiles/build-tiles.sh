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
# Sub-regional extracts are used where the neighbour is large: Saxony and Bavaria together
# cover the entire CZ-DE border, and three voivodeships cover the CZ-PL border. Downloading
# all of Germany (~4 GB) to clip a border strip out of it would be wasteful. Austria and
# Slovakia are small enough to take whole.
REGIONS=(
  europe/czech-republic
  europe/germany/saxony
  europe/germany/bavaria
  europe/austria
  europe/slovakia
  europe/poland/dolnoslaskie
  europe/poland/opolskie
  europe/poland/slaskie
)

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
    echo "    $name — already downloaded, skipping"
    continue
  fi
  echo "    $name"
  # --fail so an HTTP error does not leave a valid-looking HTML file named .osm.pbf.
  curl --fail --location --progress-bar \
    "https://download.geofabrik.de/${region}-latest.osm.pbf" \
    --output "$dest.partial"
  mv "$dest.partial" "$dest"
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
echo "==> Merging into $MERGED"
# osmium merge deduplicates objects that appear in more than one extract, which they will
# along every border. Inputs must be sorted; Geofabrik extracts already are.
osmium merge --overwrite "${CLIPPED_FILES[@]}" -o "$MERGED"

# --- 4. Build tiles ------------------------------------------------------------------

if [ ! -f "$PLANETILER_JAR" ]; then
  echo "==> Fetching Planetiler"
  curl --fail --location --progress-bar "$PLANETILER_URL" --output "$PLANETILER_JAR"
fi

OUTPUT="$DIST_DIR/$OUTPUT_NAME.pmtiles"
echo "==> Building $OUTPUT (this is the slow part)"
java "-Xmx$JAVA_HEAP" -jar "$PLANETILER_JAR" \
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
