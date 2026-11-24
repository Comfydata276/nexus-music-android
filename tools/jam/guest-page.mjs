#!/usr/bin/env node
// A real browser as a guest of a jam: opens the relay's own page for the jam's link in a
// headless Chromium, joins under a name, searches the host's library, adds the first song
// found, presses the page's skip, and writes a screenshot. What the page shows is printed.
//
//   node tools/jam/guest-page.mjs <chrome-headless-shell> <jam link> <query> [shot.png]
//
// It drives the browser through its DevTools protocol (Node's own WebSocket), so it needs
// nothing installed but the browser.
import { spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';

const [browser, link, query, shot = 'guest-page.png'] = process.argv.slice(2);
if (!browser || !link || !query) {
  console.error('usage: guest-page.mjs <chrome-headless-shell> <jam link> <query> [shot.png]');
  process.exit(2);
}
const port = 9300 + Math.floor(Math.random() * 500);
const chrome = spawn(browser, [`--remote-debugging-port=${port}`, '--no-sandbox', '--window-size=420,900', 'about:blank'], { stdio: 'ignore' });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let targets;
for (let i = 0; i < 50 && !targets; i++) {
  await sleep(200);
  try { targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json(); } catch { /* not yet */ }
}
const page = targets.find((t) => t.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener('open', r, { once: true }));
let next = 0;
const waiting = new Map();
ws.addEventListener('message', (event) => {
  const message = JSON.parse(event.data);
  if (message.id && waiting.has(message.id)) { waiting.get(message.id)(message); waiting.delete(message.id); }
});
const send = (method, params = {}) => new Promise((r) => { const id = ++next; waiting.set(id, r); ws.send(JSON.stringify({ id, method, params })); });
const run = async (expression) => (await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })).result?.result?.value;
const say = (...words) => console.log(new Date().toISOString().slice(11, 19), ...words);

await send('Page.enable');
await send('Page.navigate', { url: link });
await sleep(1500);
say('page:', await run(`document.getElementById('gate-title')?.textContent + ' / ' + document.getElementById('gate-text')?.textContent`));
await run(`document.getElementById('name').value = 'Browser guest'; document.getElementById('join-button').click(); true`);
await sleep(2500);
say('joined:', await run(`document.getElementById('host').textContent + ' | now: ' + document.getElementById('now-title').textContent + ' | ' + document.getElementById('people-count')?.textContent`));
await run(`const q = document.getElementById('query'); q.value = ${JSON.stringify(query)}; q.dispatchEvent(new Event('input')); true`);
await sleep(2500);
say('found:', await run(`[...document.querySelectorAll('#list li')].slice(0, 5).map(li => li.innerText.replace(/\\s+/g, ' ')).join(' || ')`));
const added = await run(`(() => { const b = document.querySelector('#list li button:not([disabled])'); if (!b) return 'no button'; b.click(); return b.getAttribute('aria-label') || b.title || 'clicked'; })()`);
say('add:', added);
await sleep(2500);
say('toast:', await run(`document.getElementById('toast')?.textContent`));
await run(`document.getElementById('query').value = ''; document.getElementById('query').dispatchEvent(new Event('input')); true`);
await sleep(1500);
say('queue:', await run(`document.getElementById('list-title').textContent + ': ' + [...document.querySelectorAll('#list li')].slice(0, 6).map(li => li.innerText.replace(/\\s+/g, ' ')).join(' || ')`));
await run(`document.getElementById('skip').click(); true`);
await sleep(2500);
say('after skip, now:', await run(`document.getElementById('now-title').textContent + ' | ' + (document.getElementById('controls-note')?.textContent || '')`));
const picture = await send('Page.captureScreenshot', { format: 'png' });
writeFileSync(shot, Buffer.from(picture.result.data, 'base64'));
say('screenshot:', shot);
ws.close();
chrome.kill();
