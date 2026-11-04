// Building height coverage of the real extract, read straight from the PMTiles file.
// Counts `buildings` features at zoom 15 first inside the downtown viewport bbox, then across the
// whole file bbox, and reports how many carry `height` / `min_height` plus height statistics.
import { closeSync, openSync, readSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { PMTiles } from 'pmtiles';
import { VectorTile } from '@mapbox/vector-tile';
import Pbf from 'pbf';

const here = dirname(fileURLToPath(import.meta.url));
const file = process.env.TILES ?? resolve(here, 'site/tiles/twin-cities.pmtiles');
const Z = 15;
const DOWNTOWN = [-93.3, 44.94, -93.22, 45.0]; // w, s, e, n of the 3D viewport

// pmtiles' own FileSource expects a browser File; in node read ranges off the fd directly.
class NodeFileSource {
  constructor(path) {
    this.path = path;
    this.fd = openSync(path, 'r');
  }
  getKey() { return this.path; }
  async getBytes(offset, length) {
    const buf = Buffer.alloc(length);
    const read = readSync(this.fd, buf, 0, length, offset);
    return { data: buf.buffer.slice(buf.byteOffset, buf.byteOffset + read) };
  }
}

const archive = new PMTiles(new NodeFileSource(file));
const header = await archive.getHeader();
const fileBbox = [header.minLon, header.minLat, header.maxLon, header.maxLat];

function lonLatToTile(lon, lat, z) {
  const n = 2 ** z;
  const latRad = (lat * Math.PI) / 180;
  return [
    Math.floor(((lon + 180) / 360) * n),
    Math.floor(((1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2) * n),
  ];
}

function stats(heights) {
  if (!heights.length) return { count: 0 };
  const sorted = [...heights].sort((a, b) => a - b);
  const q = (p) => sorted[Math.min(sorted.length - 1, Math.floor(p * sorted.length))];
  return {
    count: sorted.length,
    mean: +(sorted.reduce((a, b) => a + b, 0) / sorted.length).toFixed(1),
    median: +q(0.5).toFixed(1),
    p95: +q(0.95).toFixed(1),
    min: +sorted[0].toFixed(1),
    max: +sorted[sorted.length - 1].toFixed(1),
  };
}

async function collect([w, s, e, n], label) {
  const [x0, y0] = lonLatToTile(w, n, Z);
  const [x1, y1] = lonLatToTile(e, s, Z);
  let tilesProbed = 0;
  let tilesPresent = 0;
  let total = 0;
  let withHeight = 0;
  let withMinHeight = 0;
  let sampleProps = null;
  const heights = [];
  for (let x = x0; x <= x1; x++) {
    for (let y = y0; y <= y1; y++) {
      tilesProbed++;
      const bytes = await archive.getZxy(Z, x, y);
      if (!bytes) continue;
      tilesPresent++;
      const tile = new VectorTile(new Pbf(bytes.data));
      const layer = tile.layers?.buildings;
      if (!layer) continue;
      for (let i = 0; i < layer.length; i++) {
        const props = layer.feature(i).properties;
        if (!sampleProps) sampleProps = props;
        total++;
        const h = props.height;
        if (h != null && h !== '') {
          withHeight++;
          const num = Number(h);
          if (Number.isFinite(num)) heights.push(num);
        }
        const mh = props.min_height;
        if (mh != null && mh !== '') withMinHeight++;
      }
    }
  }
  return {
    label,
    bbox: [w, s, e, n],
    zoom: Z,
    tilesProbed,
    tilesPresent,
    buildingsTotal: total,
    withHeight,
    withMinHeight,
    ratioWithHeight: total ? +(withHeight / total).toFixed(4) : 0,
    heightStats: stats(heights),
    sampleProperties: sampleProps,
  };
}

const out = {
  file,
  header: { minZoom: header.minZoom, maxZoom: header.maxZoom, bounds: fileBbox, tileType: header.tileType },
  note: 'Zoom <= 14 stores rounded heights (DR-111); these counts are the exact z15 tiles.',
  downtown: await collect(DOWNTOWN, 'downtown viewport'),
  wholeFile: await collect(fileBbox, 'whole file bbox'),
};
console.log(JSON.stringify(out, null, 2));
closeSync(archive.source.fd);
