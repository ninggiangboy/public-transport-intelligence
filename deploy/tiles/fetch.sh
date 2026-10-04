#!/usr/bin/env bash
# `make tiles` (ADR-0021): the offline base map of the live map. Cuts the Twin Cities out of the newest Protomaps daily
# build, and downloads the glyphs and sprites the style needs. Everything lands next to this script, gitignored, and
# compose mounts the folder into the frontend at /tiles/. Parts that are already there are skipped.
#
# BUILD=<YYYYMMDD> forces a build instead of the newest one. Needs the go-pmtiles CLI (`mise install`), or Docker.
set -euo pipefail

cd "$(dirname "$0")"

BBOX=-93.730,44.707,-92.806,45.330
MAX_ZOOM=15
TILES=twin-cities.pmtiles
# protomaps/basemaps-assets at the commit verified by spike S-05.
ASSETS_COMMIT=028c18f713baecad011301ff7a69acc39bcc2ae7

# The daily builds are kept for a short time only: try yesterday, then up to 7 days back.
newest_build() {
  local day
  for back in 1 2 3 4 5 6 7; do
    day=$(date -u -d "-${back} day" +%Y%m%d 2>/dev/null || date -u -v "-${back}d" +%Y%m%d)
    if curl -fsSI "https://build.protomaps.com/${day}.pmtiles" >/dev/null 2>&1; then
      echo "$day"
      return
    fi
  done
  echo "No Protomaps build found in the last 7 days; set BUILD=<YYYYMMDD>." >&2
  exit 1
}

# Writes to a temporary file and renames it at the end, so that an interrupted download is not taken for a finished
# one by the next run.
extract() {
  local source=$1 partial="$TILES.partial"
  rm -f "$partial"
  if command -v pmtiles >/dev/null 2>&1; then
    pmtiles extract "$source" "$partial" --bbox="$BBOX" --maxzoom="$MAX_ZOOM"
  else
    docker run --rm -v "$PWD:/out" -w /out protomaps/go-pmtiles:v1.31.2 \
      extract "$source" "$partial" --bbox="$BBOX" --maxzoom="$MAX_ZOOM"
  fi
  mv "$partial" "$TILES"
}

if [ -f "$TILES" ]; then
  echo "$TILES is already there."
else
  build=${BUILD:-$(newest_build)}
  echo "Extracting the Twin Cities from Protomaps build $build (about 84 MB)…"
  extract "https://build.protomaps.com/${build}.pmtiles"
  echo "build=$build" >BUILD.txt
fi

if [ -d fonts ] && [ -d sprites/v4 ]; then
  echo "Fonts and sprites are already there."
else
  echo "Downloading fonts and sprites (about 13 MB)…"
  archive=$(mktemp)
  trap 'rm -f "$archive"' EXIT
  curl -fsSL "https://codeload.github.com/protomaps/basemaps-assets/tar.gz/${ASSETS_COMMIT}" -o "$archive"
  prefix="basemaps-assets-${ASSETS_COMMIT}"
  tar -xzf "$archive" --strip-components=1 \
    "$prefix/fonts/Noto Sans Regular" "$prefix/fonts/Noto Sans Medium" "$prefix/fonts/Noto Sans Italic" \
    "$prefix/fonts/OFL.txt" "$prefix/sprites/v4"
fi

{
  grep '^build=' BUILD.txt 2>/dev/null || true
  echo "assets=$ASSETS_COMMIT"
  echo
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$TILES"; else shasum -a 256 "$TILES"; fi
} >BUILD.txt.new
mv BUILD.txt.new BUILD.txt
echo "Base map ready in deploy/tiles/ ($(du -sh . | cut -f1))."
