# S-05: PMTiles base map for the Twin Cities, offline, with MapLibre

Answers P0-06 / ADR-0021: how large is a Protomaps extract for the feed bbox at maxzoom 15, and can MapLibre render it
with no request leaving the origin, under the CSP planned in DOC-27?

## Run

```sh
./fetch.sh                                   # pmtiles extract + fonts/sprites + map libraries into site/
docker run -d --name s05-nginx -p 127.0.0.1:18090:80 -v "$PWD/site:/usr/share/nginx/html:ro" \
  -v "$PWD/site/nginx.conf:/etc/nginx/conf.d/default.conf:ro" nginx:1.30-alpine
node shoot.mjs "http://localhost:18090/index.html?theme=dark&z=17&lat=44.9778&lon=-93.2650" shot.png
# Vite production build of the same page:
(cd vite && pnpm install && pnpm exec vite build --base=/vite/ --outDir ../site/vite --emptyOutDir)
node shoot.mjs "http://localhost:18090/vite/index.html?theme=light" shot-vite.png
```

`shoot.mjs` starts headless Chrome with every non-localhost host name unresolvable, waits for the map's `idle` event,
lists every request the page made and saves a screenshot.

## Results

| Item | Value |
| --- | --- |
| Source build | `https://build.protomaps.com/20260927.pmtiles` (basemap tiles v4.15.2, OSM replication 2026-09-27T04:00Z) |
| CLI | go-pmtiles 1.31.2 (`pmtiles extract … --bbox=-93.730,44.707,-92.806,45.330 --maxzoom=15`) |
| Extract | 9,403 tiles, **83,916,795 bytes (80.0 MiB)**, SHA-256 `1c99d337b1a732e2d9eec2d6aa5c7b6541ffa8efe68e49c2224eaa37829bd68b`; 22.7 s over 74 HTTP range requests (88 MB transferred) |
| Glyphs + sprites | `protomaps/basemaps-assets` at `028c18f`: Noto Sans Regular/Medium/Italic (all ranges, 13 MB, 771 files, OFL) and `sprites/v4` (180 KB) |
| Libraries | maplibre-gl 6.11.2 (ESM only), pmtiles 4.5.0, @protomaps/basemaps 5.7.2, Vite 8.3.1 |
| nginx 1.30.5 | `Accept-Ranges: bytes`, 206 on range requests. `.mjs` is not in `mime.types` and must be mapped to `text/javascript` (only needed when serving raw ESM, not a Vite build) |
| Offline | Light and dark flavors at z9.7 (whole bbox), z14–z18 (overzoom from z15 tiles): idle in about 3.7 s under SwiftShader, no map errors, **zero requests to other origins** |
| CSP | Works with `worker-src 'self'` and `img-src 'self' data:`; no `blob:` needed. MapLibre 6 starts a module worker from a same-origin URL directly |
| Vite | The default worker URL (`./maplibre-gl-worker.mjs` next to the bundle) 404s after bundling. Fix: `import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url'; maplibregl.setWorkerUrl(workerUrl)`. Main chunk (MapLibre + pmtiles + basemaps) 289.5 KB gzip; worker chunk 510 KB raw, loaded by the worker only |
| Flavors | `light`, `dark`, `grayscale`, `black` all render offline; `grayscale` and `black` are chosen for the app (neutral background under route and status colors) |
| Glyph ranges requested | `0-255` for all three fonts; `8192-8447` (punctuation) at z17. Other ranges are needed only for non-Latin labels, so all ranges are shipped |
