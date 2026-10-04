#!/usr/bin/env node
// Bundle budgets of DOC-34 §7 (UX-08), checked against dist/.vite/manifest.json after `pnpm build`.
//
// - Initial JS of /stops/$stopId: the entry, the stop route and everything they import statically. Dynamic imports
//   (lazy routes, lazily loaded menus, the OIDC client, the event stream) load after the first paint, so they do not
//   count.
// - The /map route chunk, ECharts and CodeMirror: what each adds on top of the initial JS. They are skipped until the
//   screen that brings them exists.
//
// Sizes are gzip -9, as nginx serves the assets (precompressed in the Dockerfile, gzip_static).
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';

const KB = 1000;
const dist = fileURLToPath(new URL('../dist/', import.meta.url));
const manifest = JSON.parse(readFileSync(`${dist}.vite/manifest.json`, 'utf8'));

const gzip = (file) => gzipSync(readFileSync(dist + file), { level: 9 }).length;

/** The chunk keys `keys` load statically, themselves included. */
function closure(keys, seen = new Set()) {
  for (const key of keys) {
    if (seen.has(key)) continue;
    seen.add(key);
    closure(manifest[key].imports ?? [], seen);
  }
  return seen;
}

const size = (keys) => [...keys].reduce((total, key) => total + gzip(manifest[key].file), 0);
const keysOf = (match) => Object.keys(manifest).filter((key) => match(key, manifest[key]));

const entry = closure(keysOf((key, chunk) => chunk.isEntry && key.endsWith('index.html')));

/** Chunks a route adds over the entry, through every part the router plugin split it into. */
function routeExtra(routeFile) {
  const own = keysOf((key) => key.startsWith(`src/routes/${routeFile}`));
  if (own.length === 0) return undefined;
  return [...closure(own)].filter((key) => !entry.has(key));
}

/** Chunks made of a library, found by the chunk name rolldown gives them. */
function libraryExtra(pattern) {
  const own = keysOf((_key, chunk) => pattern.test(chunk.name ?? '') || pattern.test(chunk.file));
  if (own.length === 0) return undefined;
  return [...closure(own)].filter((key) => !entry.has(key));
}

const budgets = [
  {
    name: 'Initial JS of /stops/$stopId',
    limit: 200 * KB,
    keys: (() => {
      const extra = routeExtra('stops/$stopId.tsx');
      return extra === undefined ? undefined : [...entry, ...extra];
    })(),
  },
  { name: '/map chunk (MapLibre, pmtiles)', limit: 330 * KB, keys: routeExtra('map.tsx') },
  // 200 KB rather than the 160 KB first planned: ECharts 6 needs 165 KB for a bare line chart (DR-106).
  { name: 'ECharts chunk', limit: 200 * KB, keys: libraryExtra(/echarts/i) },
  { name: 'CodeMirror chunk', limit: 130 * KB, keys: libraryExtra(/codemirror/i) },
];

let failed = false;
for (const budget of budgets) {
  if (budget.keys === undefined) {
    console.log(`  -  ${budget.name}: not built yet`);
    continue;
  }
  const bytes = size(new Set(budget.keys));
  const ok = bytes <= budget.limit;
  failed ||= !ok;
  const used = `${(bytes / KB).toFixed(1)} KB of ${budget.limit / KB} KB`;
  console.log(`${ok ? ' ok' : 'FAIL'} ${budget.name}: ${used}`);
}

if (failed) {
  console.error('\nOver budget (DOC-34 §7). Load what the first paint does not need with a dynamic import.');
  process.exit(1);
}
