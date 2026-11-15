// A stand-in for the parent app, for the DEV COPY only. It pairs like a phone, "receives" pushes by following the dev
// backend's push outbox file, answers or declines through the API, and can ring home. No Android involved.
//   node scripts/fake-phone.js pair <key from the pairing page>
//   node scripts/fake-phone.js listen [--answer | --decline]
//   node scripts/fake-phone.js ring-home
// It reads the dev copy's settings (<repo>/.dev/callapi.env) and only ever talks to a backend on this machine.
'use strict';
const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');

function die(msg) { console.error(msg); process.exit(1); }
const wait = ms => new Promise(r => setTimeout(r, ms));

const root = path.join(__dirname, '..');
const envFile = path.resolve(root, process.env.DOTENV_CONFIG_PATH || '../.dev/callapi.env');
if (!fs.existsSync(envFile)) die('No ' + envFile + '\nCopy call-backend/.env.dev.example there first.');
const env = require('dotenv').parse(fs.readFileSync(envFile));
const api = String(env.CALL_PUBLIC_URL || 'http://127.0.0.1:' + (env.PORT || 3100)).replace(/\/+$/, '');
if (!/^http:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/.test(api)) die('This stand-in only talks to a dev copy on this machine, not to ' + api);
const dataDir = path.resolve(root, env.DATA_DIR || './data');
const credFile = path.join(dataDir, 'fake-phone.json');
const outboxFile = path.join(dataDir, 'push-outbox.jsonl');

function cred() {
  try { return JSON.parse(fs.readFileSync(credFile, 'utf8')); }
  catch (e) { return die('Not paired yet. Make a key on the pairing page, then: node scripts/fake-phone.js pair <key>'); }
}

/** One request to the dev backend, as the phone `me` when given. A phone that is no longer paired ends the script. */
async function call(method, pathname, body, me) {
  const headers = { 'X-App-Version': 'fake-phone' };
  if (me) headers.Authorization = 'Bearer ' + me.deviceId + '.' + me.secret;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let r;
  try { r = await fetch(api + pathname, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) }); }
  catch (e) { return die('The dev backend does not answer on ' + api + ' – is it running? (npm run dev)'); }
  const text = await r.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch (e) { /* not JSON */ }
  if (r.status === 401 && me) die('401 – this phone is no longer paired (removed on the pairing page?). Pair it again.');
  return { status: r.status, body: json || {} };
}

/** What the app's call screen would do, as a page to open: dev/join.html with the room and the ticket in the fragment. */
function joinLink(callId, lk) {
  return pathToFileURL(path.join(root, 'dev', 'join.html')).href + '#api=' + encodeURIComponent(api) + '&call=' + encodeURIComponent(callId) +
    '&url=' + encodeURIComponent(lk.url) + '&token=' + encodeURIComponent(lk.token);
}

async function pair(code) {
  if (!code) die('Usage: node scripts/fake-phone.js pair <key from the pairing page>');
  const r = await call('POST', '/api/pair', { code, name: 'Stand-in phone', appVersion: 'dev' });
  if (r.status !== 200) die('Pairing refused: ' + r.status + ' ' + (r.body.error || ''));
  fs.mkdirSync(dataDir, { recursive: true });
  fs.writeFileSync(credFile, JSON.stringify(r.body, null, 2));
  const t = await call('PUT', '/api/devices/me/push-token', { token: 'fake-' + r.body.deviceId }, r.body);
  console.log('Paired as ' + r.body.label + ' (' + r.body.deviceId + '). Push address registered: ' + (t.status === 204 ? 'yes' : 'NO (' + t.status + ')'));
}

/** The pushes written for this phone since `listen` started, as they arrive. */
async function listen(mode) {
  const me = cred();
  const who = await call('GET', '/api/devices/me', undefined, me);
  if (who.status !== 200) die('The backend does not know this phone (' + who.status + ').');
  console.log('Listening as ' + who.body.label + ' – ' + (mode === 'answer' ? 'every call is answered' : mode === 'decline' ? 'every call gets «Ikke nå»' : 'pushes are only shown') + '. Ctrl+C stops.');
  const size = () => { try { return fs.statSync(outboxFile).size; } catch (e) { return 0; } };
  let pos = size(), rest = '', beat = 0;
  for (;;) {
    await wait(300);
    const now = size();
    if (now < pos) { pos = 0; rest = ''; }                 // the file was removed or started over
    if (now > pos) {
      const fd = fs.openSync(outboxFile, 'r'), buf = Buffer.alloc(now - pos);
      fs.readSync(fd, buf, 0, buf.length, pos);
      fs.closeSync(fd);
      pos = now;
      const lines = (rest + buf.toString('utf8')).split('\n');
      rest = lines.pop();
      for (const line of lines) {
        let p = null;
        try { p = JSON.parse(line); } catch (e) { /* half a line */ }
        if (p && p.deviceId === me.deviceId) await onPush(p.data || {}, me, mode);
      }
    }
    if (++beat % 17 === 0) await call('GET', '/api/devices/me', undefined, me);   // about every 5 s, as the app looks in: a removed phone gets its 401 here
  }
}

async function onPush(d, me, mode) {
  console.log(new Date().toLocaleTimeString() + '  push  ' + JSON.stringify(d));
  if (d.type !== 'call' || !mode) return;
  const r = await call('POST', '/api/calls/' + encodeURIComponent(d.callId) + '/' + mode, {}, me);
  if (mode === 'decline') console.log('  -> «Ikke nå»: ' + r.status + ' ' + JSON.stringify(r.body));
  else if (r.status !== 200) console.log('  -> could not answer: ' + r.status + ' ' + JSON.stringify(r.body));
  else console.log('  -> answered. Open this in Chrome to be the phone:\n' + joinLink(d.callId, r.body.livekit));
}

/** «Ring hjem», and what the app would tell the parent about how it went. */
async function ringHome() {
  const me = cred();
  const r = await call('POST', '/api/calls/home', {}, me);
  if (r.status === 409) return console.log('Opptatt hjemme');
  if (r.status === 503) return console.log('Skjermen hjemme er ikke tilkoblet');
  if (r.status !== 200) return die('Could not ring home: ' + r.status + ' ' + JSON.stringify(r.body));
  const id = r.body.callId;
  console.log('Ringing home (' + id + '). Open this in Chrome to be the phone:\n' + joinLink(id, r.body.livekit));
  process.on('SIGINT', async () => { await call('POST', '/api/calls/' + id + '/cancel', {}); console.log('\nGave up.'); process.exit(0); });
  let said = null;
  for (;;) {
    await wait(1000);
    const s = (await call('GET', '/api/calls/' + id)).body.status || '';
    if (s === said) continue;
    said = s;
    if (s === 'connected') console.log('Svarte hjemme – samtalen er i gang');
    else if (s === 'declined' || s === 'missed') return console.log('Ingen svarte hjemme');
    else if (s === 'unreachable') return console.log('Skjermen hjemme er ikke tilkoblet');   // the kiosk never confirmed the call
    else if (s !== 'ringing') return console.log('Samtalen er avsluttet');
  }
}

const [cmd, ...args] = process.argv.slice(2);
const mode = args.includes('--answer') ? 'answer' : args.includes('--decline') ? 'decline' : '';
const job = cmd === 'pair' ? pair(args[0]) : cmd === 'listen' ? listen(mode) : cmd === 'ring-home' ? ringHome()
  : Promise.reject(new Error('Usage: node scripts/fake-phone.js pair <key> | listen [--answer | --decline] | ring-home'));
job.catch(e => die(e.message));
