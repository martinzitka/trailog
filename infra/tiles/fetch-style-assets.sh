#!/usr/bin/env bash
#
# Fetch the map font glyphs and sprites into the app's assets, and validate the vendored style.
#
# Quick (a few MB) and its output IS committed — the assets are small, and bundling them means
# the app always has a valid style even before any region archive has been downloaded. That is
# what lets a route render over a blank background instead of an error when tiles are
# unavailable.
#
# Runs anywhere with bash, curl and python3 — no WSL requirement.
#
# WHAT CHANGED, AND WHY IT MATTERS:
#
# This script used to download OSM Bright and rewrite it on every run. The style is now
# VENDORED — app/src/main/assets/map/style.json is ours, forked from Bright, and re-downloading
# would silently destroy the fork. So the style is no longer generated here; it is *checked*.
#
# The checks are the valuable half and they are all kept. Upstream Bright points `glyphs` and
# `sources` at api.maptiler.com and `sprite` at openmaptiles.github.io, so a stock style is a
# privacy violation under CLAUDE.md as shipped. Ours rewrites those to asset:// URLs, and the
# validation below fails if an http URL ever reappears — whether from a bad merge, a re-fork,
# or a hand edit.
#
# To re-fork against a newer upstream Bright, run with --upstream to drop a copy in work/ for
# diffing, then port the changes by hand. The fork is design, not a mechanical rewrite.
#
# Downloading glyphs and sprites at BUILD time is fine and is what this does. The rule is about
# what the app does at RUN time. Same reasoning as fetching Geofabrik extracts.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ASSETS="$REPO_ROOT/app/src/main/assets/map"
STYLE="$ASSETS/style.json"

UPSTREAM_STYLE_URL="https://raw.githubusercontent.com/openmaptiles/osm-bright-gl-style/master/style.json"
SPRITE_BASE="https://openmaptiles.github.io/osm-bright-gl-style"

# Glyphs come from the openmaptiles/fonts *release zip*, not from a per-range URL.
#
# fonts.openmaptiles.org looks like it serves {fontstack}/{range}.pbf and answers such a
# request with HTTP 200 — but the body is the font server's HTML landing page, not a glyph
# PBF. MapLibre then fails at render time with "unknown pbf field type exception" and every
# label silently disappears. The zip is the only source verified to contain real PBFs.
GLYPH_ZIP_URL="https://github.com/openmaptiles/fonts/releases/download/v2.0/noto-sans.zip"
# Cached between runs; 60 MB. infra/tiles/work/ is gitignored.
GLYPH_ZIP="$REPO_ROOT/infra/tiles/work/noto-sans.zip"

# The fontstacks the style may reference. Cross-checked against the style below, so a style
# edit that introduces a fourth fails loudly rather than rendering empty labels.
FONTSTACKS=("Noto Sans Regular" "Noto Sans Bold" "Noto Sans Italic")

# Glyph ranges to bundle. Not all 256 — that would be ~60 MB per stack for scripts this map
# will never label. These cover Latin, Latin Extended-A/B, combining diacriticals, Greek,
# Cyrillic, Latin Extended Additional and General Punctuation: enough for Czech, German,
# Polish and Slovak place names, which is what the buffered extract contains.
#
# A missing range renders as blank boxes rather than failing, so widen this list if labels
# come up empty somewhere.
RANGES=(0-255 256-511 512-767 768-1023 1024-1279 7680-7935 8192-8447)

# The name of the vector source inside the style. The tile URL is NOT baked in: the archive
# lives in app storage or on the user's own server, and the path is only known at runtime.
# A placeholder sits there instead, substituted when the style is loaded.
SOURCE_NAME="openmaptiles"
TILE_PLACEHOLDER="__TRAILOG_TILE_URL__"

# Every source-layer the OpenMapTiles schema defines. A style referencing anything else is
# asking our archive for data Planetiler never produced, which renders as nothing at all.
KNOWN_SOURCE_LAYERS="aerodrome_label aeroway boundary building housenumber landcover landuse mountain_peak park place poi transportation transportation_name water water_name waterway"

require() {
  command -v "$1" >/dev/null 2>&1 || { echo "ERROR: '$1' not found." >&2; exit 1; }
}
require curl

# Find a real Python. `command -v python3` is not enough on Windows: Git Bash sees the
# Microsoft Store alias stub, which exists on PATH and exits with an install prompt instead of
# running anything. Probing with --version is the only reliable test.
PYTHON=""
for candidate in python3 python py; do
  if "$candidate" --version >/dev/null 2>&1; then PYTHON="$candidate"; break; fi
done
if [ -z "$PYTHON" ]; then
  echo "ERROR: no working Python found (tried python3, python, py)." >&2
  exit 1
fi

# --- 0. Optional: fetch upstream for diffing ------------------------------------------

if [ "${1:-}" = "--upstream" ]; then
  dest="$REPO_ROOT/infra/tiles/work/osm-bright-upstream.json"
  mkdir -p "$(dirname "$dest")"
  echo "==> Fetching upstream OSM Bright for comparison"
  curl --fail --silent --location "$UPSTREAM_STYLE_URL" --output "$dest"
  echo "    written to $dest"
  echo
  echo "    This is NOT installed. Diff it against $STYLE by hand and port what you want."
  echo "    Ours differs deliberately: asset:// glyphs and sprite, an inline vector source"
  echo "    with a tile placeholder, tracks and paths split out, park and mountain_peak added."
  exit 0
fi

mkdir -p "$ASSETS/fonts"

# --- 1. Validate the vendored style ----------------------------------------------------

echo "==> Validating $STYLE"
"$PYTHON" - "$STYLE" "$SOURCE_NAME" "$TILE_PLACEHOLDER" "$KNOWN_SOURCE_LAYERS" <<'PY'
import json, sys

style_path, source_name, placeholder, known_source_layers = sys.argv[1:5]
known = set(known_source_layers.split())

try:
    with open(style_path, encoding="utf-8") as fh:
        style = json.load(fh)
except FileNotFoundError:
    sys.exit(f"ERROR: {style_path} is missing. It is vendored and must be committed.")
except json.JSONDecodeError as exc:
    sys.exit(f"ERROR: {style_path} is not valid JSON: {exc}")

problems = []

# Fonts and sprites must be bundled. asset:// is MapLibre Native's scheme for APK files, and
# CLAUDE.md forbids externally hosted fonts outright.
if style.get("glyphs") != "asset://map/fonts/{fontstack}/{range}.pbf":
    problems.append(f"glyphs must be the bundled asset:// URL, found {style.get('glyphs')!r}")
if style.get("sprite") != "asset://map/sprite":
    problems.append(f"sprite must be the bundled asset:// URL, found {style.get('sprite')!r}")

sources = style.get("sources", {})
if source_name not in sources:
    problems.append(f"expected a '{source_name}' source, found {list(sources)}")
else:
    tiles = sources[source_name].get("tiles", [])
    if tiles != [placeholder]:
        problems.append(f"'{source_name}' tiles must be exactly [{placeholder!r}], found {tiles!r}")
    if not sources[source_name].get("attribution"):
        # OSM data is ODbL. Attribution lives in the source so MapLibre's own control renders
        # it and no screen can forget it.
        problems.append(f"'{source_name}' has no attribution")

# The whole point: no third-party host may be contacted at render time.
for value in (style.get("glyphs"), style.get("sprite")):
    if isinstance(value, str) and value.startswith("http"):
        problems.append(f"external URL in the style: {value}")
for name, src in sources.items():
    for url in src.get("tiles", []) + ([src["url"]] if "url" in src else []):
        if url.startswith("http"):
            problems.append(f"external URL in source {name}: {url}")

layer_ids = [l["id"] for l in style.get("layers", [])]
duplicates = {i for i in layer_ids if layer_ids.count(i) > 1}
if duplicates:
    problems.append(f"duplicate layer ids: {sorted(duplicates)}")

unknown = sorted({l.get("source-layer") for l in style.get("layers", [])
                  if "source-layer" in l} - known)
if unknown:
    problems.append(f"layers reference source-layers our archive does not contain: {unknown}")

# Layer groups the app addresses by name. Declared in the style so the asset and the Kotlin
# cannot drift; MapTiles reads these rather than hardcoding a list.
metadata = style.get("metadata", {})
for key in ("trailog:trailLayers", "trailog:unitLabelledLayers"):
    declared = metadata.get(key)
    if not isinstance(declared, list) or not declared:
        problems.append(f"metadata.{key} must be a non-empty list")
        continue
    absent = [i for i in declared if i not in layer_ids]
    if absent:
        problems.append(f"metadata.{key} names layers that do not exist: {absent}")

if problems:
    for p in problems:
        print(f"ERROR: {p}", file=sys.stderr)
    sys.exit(1)

fonts = sorted({f for layer in style.get("layers", [])
                for f in layer.get("layout", {}).get("text-font", [])})
print(f"    {len(layer_ids)} layers, {len(sources)} source, no external URLs")
print("    fontstacks referenced:", ", ".join(fonts))
with open(style_path + ".fontstacks", "w", encoding="utf-8") as fh:
    fh.write("\n".join(fonts))
PY

# Cross-check the style's fontstacks against the ones we are about to download. A style edit
# that adds a font would otherwise ship labels with no glyphs behind them.
# tr strips CR: Python's text mode writes CRLF on Windows, and mapfile would keep the \r,
# making every comparison below fail against an otherwise identical name.
mapfile -t REFERENCED < <(tr -d '\r' < "$STYLE.fontstacks")
rm -f "$STYLE.fontstacks"
for fs in "${REFERENCED[@]}"; do
  found=0
  for known in "${FONTSTACKS[@]}"; do [ "$fs" = "$known" ] && found=1; done
  if [ "$found" -eq 0 ]; then
    echo "ERROR: style references fontstack '$fs', which this script does not fetch." >&2
    echo "       Add it to FONTSTACKS and re-run." >&2
    exit 1
  fi
done

# --- 2. Glyphs -----------------------------------------------------------------------

echo "==> Fetching glyphs"
mkdir -p "$(dirname "$GLYPH_ZIP")"
if [ ! -f "$GLYPH_ZIP" ]; then
  echo "    downloading noto-sans.zip (60 MB, cached for later runs)"
  curl --fail --location --progress-bar "$GLYPH_ZIP_URL" --output "$GLYPH_ZIP.partial"
  mv "$GLYPH_ZIP.partial" "$GLYPH_ZIP"
fi

"$PYTHON" - "$GLYPH_ZIP" "$ASSETS/fonts" "${FONTSTACKS[@]}" -- "${RANGES[@]}" <<'PY'
import sys, os, zipfile

# Field 1 (stacks), wire type 2. Built from an int so no escape survives the shell.
GLYPH_PBF_MAGIC = bytes([0x0A])

zip_path, out_dir = sys.argv[1:3]
rest = sys.argv[3:]
split = rest.index("--")
fontstacks, ranges = rest[:split], rest[split + 1:]

with zipfile.ZipFile(zip_path) as z:
    names = set(z.namelist())
    for fs in fontstacks:
        dest = os.path.join(out_dir, fs)
        os.makedirs(dest, exist_ok=True)
        for rng in ranges:
            entry = f"{fs}/{rng}.pbf"
            if entry not in names:
                sys.exit(f"ERROR: {entry} missing from the glyph archive")
            data = z.read(entry)
            # A glyph PBF starts with field 1 (stacks), wire type 2 -> 0x0a. Anything else,
            # an HTML error page above all, must never reach the APK: MapLibre only complains
            # at render time and the map merely loses every label.
            if data[:1] != GLYPH_PBF_MAGIC:
                sys.exit(f"ERROR: {entry} is not a glyph PBF (starts with {data[:8]!r})")
            if len(data) < 1000:
                sys.exit(f"ERROR: {entry} is implausibly small ({len(data)} bytes)")
            with open(os.path.join(dest, f"{rng}.pbf"), "wb") as fh:
                fh.write(data)
        print(f"    {fs} - {len(ranges)} ranges")
PY

# --- 3. Sprites ----------------------------------------------------------------------

echo "==> Fetching sprites"
for f in sprite.png sprite.json sprite@2x.png sprite@2x.json; do
  curl --fail --silent --location "$SPRITE_BASE/$f" --output "$ASSETS/$f"
done

# Same trap as the glyphs: an HTTP 200 carrying an HTML page is not a sprite sheet, and the
# failure would only show up as missing map icons at render time.
#
# Also checks that every icon the style names is actually in the sheet. A missing icon draws
# nothing and reports nothing, so a style edit referencing an icon Bright's sprite lacks would
# otherwise be found on a hillside rather than here.
"$PYTHON" - "$ASSETS" "$STYLE" <<'PY'
import json, sys, os

PNG_MAGIC = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])

assets, style_path = sys.argv[1:3]
for name in ("sprite.png", "sprite@2x.png"):
    with open(os.path.join(assets, name), "rb") as fh:
        head = fh.read(8)
    if head != PNG_MAGIC:
        sys.exit(f"ERROR: {name} is not a PNG (starts with {head!r})")
for name in ("sprite.json", "sprite@2x.json"):
    try:
        with open(os.path.join(assets, name), encoding="utf-8") as fh:
            json.load(fh)
    except Exception as exc:
        sys.exit(f"ERROR: {name} is not valid JSON: {exc}")

with open(os.path.join(assets, "sprite.json"), encoding="utf-8") as fh:
    icons = set(json.load(fh))
with open(style_path, encoding="utf-8") as fh:
    style = json.load(fh)

# Only literal icon names can be checked; "{class}_11" is resolved per feature at render time.
missing = sorted({
    name for layer in style.get("layers", [])
    if isinstance(name := layer.get("layout", {}).get("icon-image"), str) and "{" not in name
} - icons)
if missing:
    sys.exit(f"ERROR: style names icons the sprite does not contain: {missing}")

print("    sprites verified")
PY

# --- Done ------------------------------------------------------------------------------

echo
echo "==> Done — $(du -sh "$ASSETS" | cut -f1) in app/src/main/assets/map"
echo "    Tile URL placeholder: $TILE_PLACEHOLDER (substituted at load time)"
