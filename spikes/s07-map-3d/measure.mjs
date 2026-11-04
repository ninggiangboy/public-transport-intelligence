// S-07 measurement harness.
//
// Starts `vite preview` of the production build, launches a HEADED real Chrome (channel 'chrome',
// default GPU — never SwiftShader) and, for every implementation, runs RUN_MS at pitch 60 with 600
// vehicles while recording requestAnimationFrame frame times. Reports average fps, the lowest
// 1-second-window fps, p95 frame time, the WebGL renderer string, the JS heap, and takes a
// screenshot. Each implementation is run twice.
//
// If a headed GPU run is impossible (no window server), it falls back to headless + SwiftShader and
// labels the run explicitly as NOT representative.
import { spawn } from 'node:child_process';
import { writeFileSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { chromium } from 'playwright-core';

const here = dirname(fileURLToPath(import.meta.url));
const viteDir = resolve(here, 'vite');
const PORT = Number(process.env.PORT ?? 4174);
const RUN_MS = Number(process.env.RUN_MS ?? 30000);
const WARMUP_MS = Number(process.env.WARMUP_MS ?? 3000);
const RUNS = Number(process.env.RUNS ?? 2);
const IMPLS = (process.env.IMPLS ?? 'custom,deck').split(',');
const FORCE_HEADLESS = process.env.HEADLESS === '1';
// STRESS=1 disables the vsync cap so the raw GPU cost of each implementation shows through
// (rAF then runs as fast as the GPU allows instead of being pinned to the display refresh).
const STRESS = process.env.STRESS === '1';
const URL_BASE = `http://127.0.0.1:${PORT}`;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitForServer(url, proc) {
  for (let i = 0; i < 150; i++) {
    try {
      const res = await fetch(url);
      if (res.ok) return;
    } catch {}
    if (proc.exitCode != null) throw new Error(`vite preview exited early (${proc.exitCode})`);
    await sleep(200);
  }
  throw new Error('vite preview did not come up');
}

function frameStats(times) {
  const n = times.length;
  if (n < 2) return { frames: n, fpsAvg: 0, fpsMin1s: 0, frameP95Ms: 0, durationMs: 0 };
  const durationMs = times[n - 1] - times[0];
  const fpsAvg = (n - 1) / (durationMs / 1000);
  const buckets = new Array(Math.max(1, Math.floor(durationMs / 1000))).fill(0);
  for (const t of times) {
    const b = Math.floor((t - times[0]) / 1000);
    if (b >= 0 && b < buckets.length) buckets[b]++;
  }
  const fpsMin1s = Math.min(...buckets);
  const deltas = [];
  for (let i = 1; i < n; i++) deltas.push(times[i] - times[i - 1]);
  deltas.sort((a, b) => a - b);
  const frameP95Ms = deltas[Math.min(deltas.length - 1, Math.floor(0.95 * deltas.length))];
  return { frames: n, fpsAvg: +fpsAvg.toFixed(1), fpsMin1s, frameP95Ms: +frameP95Ms.toFixed(1), durationMs: Math.round(durationMs) };
}

async function measure(browser, impl, run, interleaved) {
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1 });
  const errors = [];
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', (e) => errors.push(String(e)));
  const url = `${URL_BASE}/?impl=${impl}${interleaved ? '&interleaved=1' : ''}`;
  await page.goto(url, { waitUntil: 'load' });
  await page.waitForFunction(() => document.getElementById('status')?.dataset.ready === 'true', null, { timeout: 90000 });
  const info = await page.evaluate(() => {
    const g = window.__s07 ?? {};
    return { impl: g.impl, interleaved: g.interleaved, vehicleCount: g.vehicleCount, routeCount: g.routeCount, pitch: g.pitch, renderer: g.renderer };
  });
  await sleep(WARMUP_MS);
  const probe = await page.evaluate(() => {
    const s = window.__s07;
    const positions = (s.positions && s.positions()) || [];
    const canvas = s.map.getCanvas();
    const w = canvas.clientWidth, h = canvas.clientHeight;
    let onScreen = 0;
    for (const p of positions) {
      const q = s.map.project(p);
      if (q.x >= 0 && q.x <= w && q.y >= 0 && q.y <= h) onScreen++;
    }
    return {
      buildingsRendered: s.map.queryRenderedFeatures({ layers: ['buildings-3d'] }).length,
      vehicles: positions.length,
      onScreen,
      canvas: [w, h],
      foreignRequests: performance.getEntriesByType('resource')
        .map((r) => r.name)
        .filter((u) => !u.startsWith(location.origin) && !u.startsWith('data:') && !u.startsWith('blob:')).length,
    };
  });
  const times = await page.evaluate((ms) => new Promise((resolve) => {
    const t = [];
    let start = 0;
    function tick(now) {
      if (!start) start = now;
      t.push(now);
      if (now - start < ms) requestAnimationFrame(tick);
      else { t.push(now); resolve(t); }
    }
    requestAnimationFrame(tick);
  }), RUN_MS);
  const heap = await page.evaluate(() => (performance.memory
    ? { usedMB: +(performance.memory.usedJSHeapSize / 1048576).toFixed(1), totalMB: +(performance.memory.totalJSHeapSize / 1048576).toFixed(1) }
    : null));
  const shotPath = resolve(here, `shot-${impl}.png`);
  if (run === 1) {
    await page.setViewportSize({ width: 960, height: 560 });
    await sleep(800);
    await page.screenshot({ path: shotPath });
  }
  await page.close();
  const stats = frameStats(times);
  const software = /swiftshader|llvmpipe|software/i.test(info.renderer ?? '');
  return { impl, run, interleaved: Boolean(interleaved), url, ...info, ...stats, probe, heap, software, errors };
}

function table(rows) {
  const cols = ['impl', 'run', 'fpsAvg', 'fpsMin1s', 'frameP95Ms', 'frames', 'heapMB'];
  const line = (r) => cols.map((c) => String(c === 'heapMB' ? r.heap?.usedMB ?? '-' : r[c] ?? '-').padStart(c === 'impl' ? 7 : 9)).join(' ');
  console.log(line(Object.fromEntries(cols.map((c) => [c, c]))));
  for (const r of rows) console.log(line(r));
}

const server = spawn('pnpm', ['exec', 'vite', 'preview', '--port', String(PORT), '--strictPort', '--host', '127.0.0.1'], {
  cwd: viteDir, stdio: ['ignore', 'pipe', 'pipe'],
});
let serverLog = '';
server.stdout.on('data', (d) => { serverLog += d; });
server.stderr.on('data', (d) => { serverLog += d; });

const results = { when: new Date().toISOString(), runs: RUNS, runMs: RUN_MS, stress: STRESS, results: [], mode: null };

try {
  await waitForServer(`${URL_BASE}/`, server);

  let browser = null;
  let headed = false;
  const baseArgs = [
    '--no-first-run', '--no-default-browser-check',
    '--disable-background-timer-throttling', '--disable-renderer-backgrounding',
    '--disable-backgrounding-occluded-windows', '--disable-features=CalculateNativeWinOcclusion',
  ];
  if (STRESS) baseArgs.push('--disable-gpu-vsync', '--disable-frame-rate-limit');
  if (!FORCE_HEADLESS) {
    try {
      browser = await chromium.launch({ channel: 'chrome', headless: false, args: baseArgs });
      headed = true;
    } catch (e) {
      console.error(`headed launch failed: ${e.message}`);
    }
  }
  if (!browser) {
    console.error('falling back to headless SwiftShader — NOT representative of a dev machine');
    browser = await chromium.launch({
      channel: 'chrome',
      headless: true,
      args: [...baseArgs, '--use-angle=swiftshader', '--enable-unsafe-swiftshader'],
    });
  }
  results.mode = headed ? 'headed' : 'headless-swiftshader';

  for (const impl of IMPLS) {
    for (let run = 1; run <= RUNS; run++) {
      const r = await measure(browser, impl, run, false);
      results.results.push(r);
      console.log(`${impl} run ${run}: ${r.fpsAvg} fps avg, ${r.fpsMin1s} fps min, p95 ${r.frameP95Ms} ms, renderer "${r.renderer}"`);
      if (r.errors.length) console.log(`  page errors: ${r.errors.join(' | ')}`);
    }
    if (impl === 'deck' && process.env.INTERLEAVED === '1') {
      const r = await measure(browser, 'deck', 'I', true);
      results.results.push(r);
      console.log(`deck interleaved: ${r.fpsAvg} fps avg, ${r.fpsMin1s} fps min, errors ${r.errors.length}`);
    }
  }
  await browser.close();

  console.log('\n--- summary ---');
  table(results.results);
  const shot = resolve(here, 'shot-custom.png');
  try { console.log(`\nshot-custom.png ${(statSync(shot).size / 1024).toFixed(0)} KB`); } catch {}
  writeFileSync(resolve(here, STRESS ? 'measure-stress-results.json' : 'measure-results.json'), JSON.stringify(results, null, 2));
  console.log(`wrote ${STRESS ? 'measure-stress-results.json' : 'measure-results.json'}`);
} finally {
  server.kill('SIGTERM');
}
