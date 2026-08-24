#!/usr/bin/env bash
#
# Fetch the map style, font glyphs and sprites into the app's assets.
#
# Unlike build-tiles.sh this is quick (a few MB) and its output IS committed — the assets are
# small, and bundling them means the app always has a valid style even before any region
# archive has been downloaded. That is what lets a route render over a blank background
# instead of an error when tiles are unavailable.
#
# Runs anywhere with bash, curl and python3 — no WSL requirement.
#
# WHY THIS SCRIPT EXISTS: the upstream OSM Bright style points `glyphs` and `sources` at
# api.maptiler.com (with an API key placeholder) and `sprite` at openmaptiles.github.io. As
# shipped it is a privacy violation under CLAUDE.md — three third-party hosts contacted at
# render time, one of them on every label draw. This rewrites all of them to local URLs.
#
# Downloading from those hosts at BUILD time is fine and is what this does. The rule is about
# what the app does at RUN time. Same reasoning as fetching Geofabrik extracts.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ASSETS="$REPO_ROOT/app/src/main/assets/map"

STYLE_URL="https://raw.githubusercontent.com/openmaptiles/osm-bright-gl-style/master/style.json"
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

# The three fontstacks OSM Bright actually references. Checked against the style below, so a
# style change that introduces a fourth fails loudly rather than rendering empty labels.
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
# A placeholder is written here and substituted when the style is loaded.
SOURCE_NAME="openmaptiles"
TILE_PLACEHOLDER="__TRAILOG_TILE_URL__"

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

mkdir -p "$ASSETS/fonts"

# --- 1. Style ------------------------------------------------------------------------

echo "==> Fetching OSM Bright style"
curl --fail --silent --location "$STYLE_URL" --output "$ASSETS/style.json.orig"

echo "==> Rewriting external URLs to local assets"
"$PYTHON" - "$ASSETS/style.json.orig" "$ASSETS/style.json" "$SOURCE_NAME" "$TILE_PLACEHOLDER" <<'PY'
import json, sys

src_path, out_path, source_name, placeholder = sys.argv[1:5]
with open(src_path, encoding="utf-8") as fh:
    style = json.load(fh)

# Fonts and sprites move into the APK. asset:// is MapLibre Native's scheme for bundled files.
style["glyphs"] = "asset://map/fonts/{fontstack}/{range}.pbf"
style["sprite"] = "asset://map/sprite"

sources = style.get("sources", {})
if source_name not in sources:
    sys.exit(f"ERROR: expected a '{source_name}' source, found {list(sources)}")

# Replace the hosted TileJSON reference with an inline source carrying a placeholder tile URL.
# Substituted at load time with either a pmtiles://file:// path or a pmtiles://https:// URL.
sources[source_name] = {
    "type": "vector",
    "tiles": [placeholder],
    "minzoom": 0,
    "maxzoom": 14,
    # MapLibre renders a source's `attribution` in its own attribution control. Putting it
    # here rather than drawing it ourselves means it cannot be forgotten by a screen, which
    # matters because OSM data is ODbL and attribution is a licence condition.
    "attribution": "© OpenStreetMap contributors",
}

# Fail loudly rather than shipping a style that quietly reaches a third party.
leaked = [
    v for v in (style.get("glyphs"), style.get("sprite"))
    if isinstance(v, str) and v.startswith("http")
]
for name, s in sources.items():
    for url in s.get("tiles", []) + ([s["url"]] if "url" in s else []):
        if url.startswith("http"):
            leaked.append(f"{name}: {url}")
if leaked:
    sys.exit(f"ERROR: external URLs remain in the style: {leaked}")

with open(out_path, "w", encoding="utf-8") as fh:
    json.dump(style, fh, indent=2, ensure_ascii=False)

fonts = sorted({f for layer in style.get("layers", [])
                for f in layer.get("layout", {}).get("text-font", [])})
print("    fontstacks referenced:", ", ".join(fonts))
with open(out_path + ".fontstacks", "w", encoding="utf-8") as fh:
    fh.write("\n".join(fonts))
PY

rm -f "$ASSETS/style.json.orig"

# Cross-check the style's fontstacks against the ones we are about to download. A style update
# that adds a font would otherwise ship labels with no glyphs behind them.
# tr strips CR: Python's text mode writes CRLF on Windows, and mapfile would keep the \r,
# making every comparison below fail against an otherwise identical name.
mapfile -t REFERENCED < <(tr -d '\r' < "$ASSETS/style.json.fontstacks")
rm -f "$ASSETS/style.json.fontstacks"
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
"$PYTHON" - "$ASSETS" <<'PY'
import json, sys, os

PNG_MAGIC = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])

assets = sys.argv[1]
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
print("    sprites verified")
PY

# --- Done ------------------------------------------------------------------------------

echo
echo "==> Done — $(du -sh "$ASSETS" | cut -f1) in app/src/main/assets/map"
echo "    Tile URL placeholder: $TILE_PLACEHOLDER (substituted at load time)"
