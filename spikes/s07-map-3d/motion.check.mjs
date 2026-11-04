// Self-check for the shared motion module. Run with `node motion.check.mjs`.
// Verifies the three DR-111 invariants the spike depends on: never backwards, the 20 s estimate
// cut-off, and the 1 s blend of a fix that is ahead.
import * as M from './vite/src/motion.js';

const shape = [];
for (let i = 0; i <= 40; i++) shape.push([-93.3 + i * 0.0005, 44.95]); // ~1.6 km, straight east
const { cum } = M.shapeLengths(shape);
const at = (along) => M.pointAt(shape, cum, along);

let failures = 0;
const check = (name, ok, detail = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
  if (!ok) failures++;
};

// 1. A fix behind the drawn position must never make the vehicle reverse.
{
  const s = M.createVehicleState({ shape, along: 500, speedMps: 10 });
  M.applyFix(s, { ...lonLat(500), speedMps: 10 }, 0);
  let prev = s.drawnAlong;
  let backwards = false;
  let now = 0;
  // a fix 60 m behind arrives at t = 0.5 s (jitter / stale GPS)
  for (let f = 0; f < 600; f++) {
    now += 1 / 60;
    if (f === 30) M.applyFix(s, { ...lonLat(s.drawnAlong - 60), speedMps: 10 }, now);
    M.advance(s, now, 1 / 60);
    if (s.drawnAlong + 1e-9 < prev) backwards = true;
    prev = s.drawnAlong;
  }
  check('never goes backwards after a behind-fix', !backwards, `final along ${s.drawnAlong.toFixed(1)} m`);
}

// 2. The estimate stops 20 s after the last fix.
{
  const s = M.createVehicleState({ shape, along: 100, speedMps: 10 });
  M.applyFix(s, { ...lonLat(100), speedMps: 10 }, 0);
  let now = 0;
  while (now < 20) { now += 1 / 60; M.advance(s, now, 1 / 60); }
  const atTwenty = s.drawnAlong;
  while (now < 35) { now += 1 / 60; M.advance(s, now, 1 / 60); }
  check('stops estimating after 20 s', Math.abs(s.drawnAlong - atTwenty) < 1e-6,
    `frozen at ${atTwenty.toFixed(1)} m for 15 s`);
}

// 3. A fix 100 m ahead is blended in over 1 s (and is half there at 0.5 s).
{
  const s = M.createVehicleState({ shape, along: 0, speedMps: 0 });
  const fix = { ...lonLat(100), speedMps: 0 };
  M.applyFix(s, fix, 0);
  let now = 0;
  const half = [];
  let atHalf = null;
  let atOne = null;
  for (let f = 0; f < 90; f++) {
    now += 1 / 60;
    M.advance(s, now, 1 / 60);
    if (Math.abs(now - 0.5) < 1 / 120) atHalf = s.drawnAlong;
    if (Math.abs(now - 1.0) < 1 / 120) atOne = s.drawnAlong;
    half.push(s.drawnAlong);
  }
  check('blend reaches the fix after 1 s', atOne != null && Math.abs(atOne - 100) < 1,
    `drawn ${atOne?.toFixed(1)} m at t=1 s`);
  check('blend is about halfway at 0.5 s', atHalf != null && atHalf > 30 && atHalf < 70,
    `drawn ${atHalf?.toFixed(1)} m at t=0.5 s`);
  check('blend is monotone', half.every((v, i) => i === 0 || v + 1e-9 >= half[i - 1]));
}

function lonLat(along) {
  const p = at(along);
  return { lon: p[0], lat: p[1] };
}

console.log(failures === 0 ? '\nAll motion checks passed.' : `\n${failures} motion check(s) failed.`);
process.exit(failures === 0 ? 0 : 1);
