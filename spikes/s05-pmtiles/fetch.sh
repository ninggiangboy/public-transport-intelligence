#!/bin/sh
# Spike S-05: rebuilds site/ (tiles, glyphs, sprites, map libraries). Needs the go-pmtiles CLI on PATH.
set -eu
cd "$(dirname "$0")/site"
BUILD=${BUILD:-20260927}
ASSETS_COMMIT=028c18f713baecad011301ff7a69acc39bcc2ae7

mkdir -p tiles lib
[ -f tiles/twin-cities.pmtiles ] || pmtiles extract "https://build.protomaps.com/${BUILD}.pmtiles" tiles/twin-cities.pmtiles \
  --bbox=-93.730,44.707,-92.806,45.330 --maxzoom=15

if [ ! -d tiles/fonts ]; then
  curl -fsSL "https://codeload.github.com/protomaps/basemaps-assets/tar.gz/${ASSETS_COMMIT}" -o /tmp/basemaps-assets.tgz
  tar -xzf /tmp/basemaps-assets.tgz --strip-components=1 -C tiles \
    "basemaps-assets-${ASSETS_COMMIT}/fonts/Noto Sans Regular" "basemaps-assets-${ASSETS_COMMIT}/fonts/Noto Sans Medium" \
    "basemaps-assets-${ASSETS_COMMIT}/fonts/Noto Sans Italic" "basemaps-assets-${ASSETS_COMMIT}/fonts/OFL.txt" \
    "basemaps-assets-${ASSETS_COMMIT}/sprites/v4"
fi

for f in maplibre-gl.mjs maplibre-gl-shared.mjs maplibre-gl-worker.mjs maplibre-gl.css; do
  curl -fsSL -o "lib/$f" "https://cdn.jsdelivr.net/npm/maplibre-gl@6.11.2/dist/$f"
done
curl -fsSL -o lib/pmtiles.js https://cdn.jsdelivr.net/npm/pmtiles@4.5.0/dist/pmtiles.js
curl -fsSL -o lib/basemaps.js https://cdn.jsdelivr.net/npm/@protomaps/basemaps@5.7.2/dist/basemaps.js
