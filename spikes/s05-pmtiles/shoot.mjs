// Spike S-05: drive headless Chrome over CDP, wait for the map's idle status, record every request, take a screenshot.
import { spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';

const [url, out] = process.argv.slice(2);
const chrome = spawn('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', [
  '--headless=new', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--window-size=1280,900',
  '--remote-debugging-port=9333', `--user-data-dir=${process.cwd()}/chrome`,
  '--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE localhost', 'about:blank',
], { stdio: 'ignore' });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const kill = () => { try { chrome.kill('SIGKILL'); } catch {} };
const logs = [];
setTimeout(() => { console.log(JSON.stringify({ url, timeout: true, logs: logs.slice(0, 8) })); kill(); process.exit(2); }, 30000);

let targets;
for (let i = 0; i < 50; i++) {
  try { targets = await (await fetch('http://127.0.0.1:9333/json')).json(); if (targets.length) break; } catch {}
  await sleep(200);
}
const ws = new WebSocket(targets.find((t) => t.type === 'page').webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener('open', r));
let id = 0; const pending = new Map(); const requests = [];
ws.addEventListener('message', (m) => {
  const msg = JSON.parse(m.data);
  if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg.result); pending.delete(msg.id); }
  if (msg.method === 'Network.requestWillBeSent') requests.push(msg.params.request.url);
  if (msg.method === 'Log.entryAdded') logs.push(msg.params.entry.text);
  if (msg.method === 'Runtime.consoleAPICalled') logs.push(msg.params.args.map((a) => a.value ?? a.description).join(' '));
  if (msg.method === 'Runtime.exceptionThrown') logs.push(msg.params.exceptionDetails.exception?.description ?? msg.params.exceptionDetails.text);
});
const send = (method, params = {}) => new Promise((r) => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
await send('Network.enable'); await send('Page.enable'); await send('Log.enable'); await send('Runtime.enable');
const t0 = Date.now();
await send('Page.navigate', { url });
let status;
for (;;) {
  const r = await send('Runtime.evaluate', { expression: "document.getElementById('status')?.dataset.done === 'true' ? document.getElementById('status').textContent : ''", returnByValue: true });
  if (r.result.value) { status = JSON.parse(r.result.value); break; }
  await sleep(250);
}
const shot = await send('Page.captureScreenshot', { format: 'png' });
writeFileSync(out, Buffer.from(shot.data, 'base64'));
const origin = new URL(url).origin;
const foreign = requests.filter((u) => !u.startsWith(origin) && !u.startsWith('blob:') && !u.startsWith('data:'));
const tiles = requests.filter((u) => u.includes('.pmtiles')).length;
const glyphs = [...new Set(requests.filter((u) => u.includes('/fonts/')).map((u) => decodeURIComponent(u.split('/tiles/fonts/')[1])))];
console.log(JSON.stringify({ url, msToIdle: Date.now() - t0, ...status, pmtilesRangeRequests: tiles, glyphs, foreignRequests: foreign }));
kill(); process.exit(0);
