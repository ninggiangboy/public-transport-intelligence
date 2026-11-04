# S-07: 3D live-map vehicle layer — MapLibre custom layer vs deck.gl MapboxOverlay

Answers P5-17 / DR-111: which of the two vehicle-drawing options holds 60 fps with 600 vehicles at
pitch 60° on a dev machine, how much bundle each costs, and how many buildings in the real extract
actually carry a `height`. The recommendation is in §Recommendation and goes into a new ADR.

Two implementations share one motion model and are selected by query string on the same page:

- `?impl=custom` — a MapLibre `CustomLayerInterface` (WebGL2), one instanced draw of 600 boxes.
- `?impl=deck` — deck.gl `MapboxOverlay` + `SimpleMeshLayer` of a cube, 600 instances.

The buildings come from the real `make tiles` extract, `fill-extrusion` on the `buildings`
source-layer, height `coalesce(height, 8)`, `min_height` respected, hidden below zoom 13.

## Run

```sh
./fetch.sh                                    # site/tiles -> the real deploy/tiles dir (symlink, gitignored)

# app + measurement tooling
(cd vite && pnpm install)
pnpm install

(cd vite && pnpm exec vite build)             # production build; also re-run to refresh dist/

node motion.check.mjs                         # motion module self-check (node, no deps)
node bbox-height.mjs                          # building height coverage from the pmtiles file

node measure.mjs                              # HEADED Chrome, 30 s x 2 runs per impl, screenshots
STRESS=1 node measure.mjs                     # same but uncapped (vsync disabled) to see GPU cost
HEADLESS=1 node measure.mjs                   # only if no window server; SwiftShader, NOT representative
INTERLEAVED=1 IMPLS=deck node measure.mjs     # try deck interleaved mode (see Notes)
```

`measure.mjs` starts `vite preview` itself (ports 4174, `PORT=` to change). For a manual look use
`(cd vite && pnpm exec vite preview)` and open `http://127.0.0.1:4173/?impl=custom|deck`.

The app itself needs no network: the PMTiles file, glyphs and sprites are served from the same
origin by a small sirv middleware (Range/206 support for PMTiles) in `vite.config.js`.

## Results

Machine: **Apple M2**, macOS 27.2 (26B123), Chrome 156.0.8078.80, Node 26.10.0. Headed real Chrome
(not SwiftShader). Viewport 1280×720, center -93.265,44.978, zoom 15.5, pitch 60°, bearing -22°,
600 vehicles on 40 routes. Measured via `requestAnimationFrame`; each impl run twice.

WebGL renderer string on every run (proves a real GPU):
`ANGLE (Apple, ANGLE Metal Renderer: Apple M2, Unspecified Version)`.
Probe per run: 3,499 building features rendered, 600 vehicles simulated, 123 in view, **0 foreign
requests**, 0 page errors.

### fps — vsync-capped (display 60 Hz)

| impl | run | fps avg | fps min (worst 1 s) | p95 frame time |
| --- | --- | --- | --- | --- |
| custom | 1 | 60.0 | 59 | 17.6 ms |
| custom | 2 | 60.0 | 59 | 17.7 ms |
| deck | 1 | 60.0 | 59 | 17.7 ms |
| deck | 2 | 60.0 | 60 | 17.6 ms |

Both are pinned to the 60 Hz cap, so this table cannot separate them.

### fps — uncapped (`STRESS=1`, `--disable-gpu-vsync --disable-frame-rate-limit`)

| impl | run | fps avg | fps min (worst 1 s) | p95 frame time |
| --- | --- | --- | --- | --- |
| custom | 1 | 295.4 | 247 | 8.2 ms |
| custom | 2 | 300.8 | 266 | 8.1 ms |
| deck | 1 | 258.7 | 231 | 8.9 ms |
| deck | 2 | 229.0 | 199 | 9.9 ms |

With the cap off the custom layer is ~15–20 % faster, but both are far above 60 fps: at 600 vehicles
neither implementation is GPU-bound on this machine.

JS heap after 30 s (informational): custom ≈ 55–61 MB, deck ≈ 30–52 MB.

### Chunk sizes — `vite build`, raw + gzip -9

Entry chunk holds maplibre + pmtiles + basemaps + app (the S-05 "/map"-style cost). The 3D chunks
are dynamic `import()`s, so they are the incremental cost of entering 3D.

| chunk | raw B | gzip -9 B | contents |
| --- | ---: | ---: | --- |
| index (entry) | 1,083,895 | 288,219 | maplibre-gl + pmtiles + @protomaps/basemaps + app + sim |
| maplibre-gl-worker | 510,430 | 144,568 | MapLibre worker (loaded by the worker only) |
| css | 82,962 | 10,418 | maplibre-gl.css |
| **motion** (shared) | **2,839** | **1,322** | `motion.js` — both impls import this |
| **impl-custom** | **3,962** | **1,922** | custom WebGL2 layer (shader + box mesh + loop) |
| **impl-deck** | **1,395** | **805** | MapboxOverlay + SimpleMeshLayer wiring |
| **deckgl** | **791,022** | **219,074** | @deck.gl/{core,mapbox,mesh-layers} + @luma.gl/* + @math.gl/* |

Derived:

| 3D chunk | raw B | gzip -9 B |
| --- | ---: | ---: |
| (a) custom = motion + impl-custom | 6,801 | **3,244** |
| (b) deck = motion + impl-deck + deckgl | 795,256 | **221,201** |
| (b) − (a): deck-only extra | 792,417 | 219,879 |

`@deck.gl/layers` is declared (per the task list) but is **not** in the bundle: `SimpleMeshLayer`
lives in `@deck.gl/mesh-layers`, so `@deck.gl/layers` is unused here. That is included in the honest
cost above, i.e. the deck chunk really is ~219 KB gzip.

### Building height coverage — `bbox-height.mjs`, zoom-15 tiles

| area | tiles | buildings | with `height` | ratio | with `min_height` | mean | median | p95 | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| downtown viewport (-93.30,44.94,-93.22,45.00) | 72 | 71,546 | 4,404 | **6.16 %** | 3 | 15.0 | 8 | 47 | 241 |
| whole file bbox (-93.730,44.707,-92.806,45.330) | 6,970 | 1,104,739 | 96,770 | **8.76 %** | 15 | 7.4 | 8 | 8 | 241 |

`height` is a number in the tiles (sample `{sort_rank: 400, kind: "building", height: 14}`). Only a
small minority of buildings carry it; the rest fall back to the 8 m default. `min_height`
(`building:part`) is rare (3 downtown, 15 in the whole file). Both the file and the extract stop at
zoom 15, so **zoom ≤ 14 stores rounded heights** (DR-111); these counts are the exact z15 tiles.

### Motion self-check — `motion.check.mjs`

All pass: never goes backwards after a behind-fix; stops estimating after the 20 s cut-off; a fix
100 m ahead is blended in over 1 s (half-way at 0.5 s, monotone).

### Versions

maplibre-gl **6.12.0**, pmtiles **4.5.0**, @protomaps/basemaps **5.7.2**, Vite **8.3.5** (rolldown),
sirv **3.0.2**. deck.gl: @deck.gl/core / layers / mapbox / mesh-layers **9.4.1** (newest 9.x),
@luma.gl/engine **9.4.2** (transitively @luma.gl/core 9.4.2). Screenshots: `shot-custom.png`,
`shot-deck.png` (960×560, ~315 KB each).

## Notes and problems found

- **MapLibre 6 changed the custom-layer signature.** `render(gl, matrix)` is now `render(gl, options)`;
  the projection matrix is `options.defaultProjectionData.mainMatrix` (maps web-mercator `[0,1]` x/y
  with a conformal z, in mercator units). Passing the 2nd arg straight to `uniformMatrix4fv` throws
  `The object must have a callable @@iterator`. Cost a debugging cycle.
- **deck.gl v9 layers are immutable.** `layer.setProps()` does not exist; a new data identity
  (`data.slice()`) per frame is what makes deck re-run the accessors (or `updateTriggers`). The mesh
  (`CubeGeometry`) is created once and shared, so no per-frame geometry cost.
- **deck interleaved mode is broken on maplibre-gl 6.** `interleaved: true` draws at 60 fps but throws
  `TypeError: Cannot read properties of undefined (reading 'height')` every frame (2,235 errors in a
  5 s run). Non-interleaved (`interleaved: false`) is clean: 0 errors.
- **Occlusion differs by construction.** The custom layer shares MapLibre's framebuffer and enables
  `DEPTH_TEST`, so extruded buildings occlude vehicles correctly. Non-interleaved deck draws in its
  **own canvas on top of the map**, so vehicles are *not* occluded by buildings — an accepted
  trade-off for the spike, but a real difference for the product. (Visual confirmation was not
  possible in this session — the image tool was unavailable — so this is stated by construction, not
  observed pixel-by-pixel.)
- **Worker / Vite.** The MapLibre worker still needs `import workerUrl from
  'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url'; maplibregl.setWorkerUrl(workerUrl)` (as S-05),
  or the bundled worker 404s.
- **CSP.** No `blob:`/foreign origin was used (0 foreign requests, `worker-src 'self'` style). deck.gl
  was clean at runtime; its luma.gl uniform-block reflection prints *debug* log lines (not errors)
  for `phongMaterial/lightingUniforms` on WebGL2.
- **Not implemented / not measured:** label discs (route discs always facing the camera) and picking
  in either impl; the 2 tow-car train model, headlights, `STOPPED_AT` hold, and reduced-motion
  fallback from DR-111; camera Follow/Chase. The spike has no camera controls beyond the query string.
- **fps is capped.** The required 60 Hz numbers cannot rank the impls (both hit vsync); the uncapped
  numbers are the real signal, and they are from one machine (Apple M2) only.

## Recommendation

**Take option (a): the MapLibre custom WebGL2 layer.**

- **Bundle:** 3,244 B gzip for the whole 3D chunk vs 221,201 B gzip for deck — ~68× smaller. On a
  product where `/map` already carries 288 KB gzip (DR-107), spending another 219 KB gzip to draw 600
  boxes is not justified.
- **Performance:** 295–301 fps uncapped vs 229–259 fps; both are >4× the 60 fps target at 600
  vehicles, so performance is *sufficient* either way and does not outweigh the bundle.
- **Correctness:** the custom layer occludes against buildings (same framebuffer + depth test); deck
  non-interleaved cannot, and deck interleaved is broken on maplibre-gl 6.
- **Control:** heading-from-shape, the box/bus mesh, label discs and picking are all ours to shape;
  deck adds a second renderer and a second coordinate lifetime to reason about.

Proposed budget for the P5-18 "3D" chunk (motion + vehicle layer + camera controls + model mesh +
label discs + fallbacks), loaded by `import()` on `view=3d`: **≤ 15 KB gzip**, comfortably above the
measured 3.2 KB and far below deck's 221 KB. Re-measure after P5-18 adds meshes and labels.
