// Menkerud Home call backend.
// Owns LiveKit tokens, call state, one-time answer tokens and the Discord answer-link DM.
// Flow: kiosk POST /api/calls -> ringing + tablet token + DM to parent; parent opens
// /answer/<token> -> POST /api/calls/answer -> parent token -> both join the same LiveKit room.
'use strict';
require('dotenv/config'); // load call-backend/.env when started via `npm start` (systemd)
const path = require('path');
const crypto = require('crypto');
const express = require('express');
const { joinToken, deleteRoom, WS_URL, configured: lkOk } = require('./livekit');
const discord = require('./discord');

const PORT = Number(process.env.PORT) || 3000;
const PUBLIC_URL = (process.env.CALL_PUBLIC_URL || 'https://call.noobventure.com').replace(/\/+$/, '');
const RING = Number(process.env.CALL_RING_TIMEOUT_SECONDS) || 60;
const TOKEN_TTL = Number(process.env.CALL_TOKEN_TTL_SECONDS) || 600;
const DEVICE_KEY = process.env.DEVICE_KEY || '';
const DEV = process.env.DEV === '1';

// Only these targets are allowed; Discord ids come from the environment, never the browser.
const PARENTS = {
  mor: { discordUserId: process.env.DISCORD_MOR_USER_ID || '', label: 'Mor', identity: 'parent-mor' },
  far: { discordUserId: process.env.DISCORD_FAR_USER_ID || '', label: 'Far', identity: 'parent-far' }
};

const sha256 = s => crypto.createHash('sha256').update(s).digest('hex');
const now = () => Date.now();
const log = (...a) => console.log(new Date().toISOString(), ...a);

// In-memory call store. Restarting the backend drops active calls (acceptable for MVP).
const calls = new Map();

function finish(id, status, dmText) {
  const c = calls.get(id);
  if (!c || c.final) return;
  c.final = true;
  c.status = status;
  c.endedAt = now();
  clearTimeout(c.timer);
  deleteRoom(c.room);
  if (dmText) discord.edit(c.discord, dmText);
  log('call', id, '->', status);
  setTimeout(() => calls.delete(id), 60000); // keep briefly so stale answer links get a clean message
}
function missed(id) { const c = calls.get(id); if (c && c.status === 'ringing') finish(id, 'missed', '🔴 Ubesvart anrop hjemmefra.'); }

const app = express();
app.disable('x-powered-by');
app.use(express.json({ limit: '16kb' }));

// CORS: the kiosk is a file:// page (Origin "null"), so allow any origin. Start-call is
// additionally protected by DEVICE_KEY; the answer endpoint is same-origin with the answer page.
app.use((req, res, next) => {
  res.header('Access-Control-Allow-Origin', '*');
  res.header('Access-Control-Allow-Headers', 'Content-Type, X-Device-Key');
  res.header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  if (req.method === 'OPTIONS') return res.sendStatus(204);
  next();
});

function deviceAuth(req, res, next) {
  if (!DEVICE_KEY) return next();
  if (req.get('X-Device-Key') === DEVICE_KEY) return next();
  return res.status(401).json({ error: 'unauthorized' });
}

// Rate limit + single active outbound call from the living-room tablet.
let starts = [];
function rateLimit(req, res, next) {
  const t = now();
  starts = starts.filter(x => t - x < 60000);
  if (starts.length >= 10) return res.status(429).json({ error: 'for mange forsøk' });
  const active = [...calls.values()].some(c => !c.final);
  if (active) return res.status(409).json({ error: 'en samtale pågår allerede' });
  starts.push(t);
  next();
}

app.get('/healthz', (req, res) => res.json({ ok: true, livekit: lkOk(), discord: discord.configured() }));

// Start a call: create room + tablet token, DM the parent an answer link, arm the ring timeout.
app.post('/api/calls', deviceAuth, rateLimit, async (req, res) => {
  const target = String((req.body && req.body.target) || '');
  const p = PARENTS[target];
  if (!p) return res.status(400).json({ error: 'ukjent mottaker' });
  if (!p.discordUserId) return res.status(500).json({ error: 'mottaker ikke konfigurert' });
  if (!lkOk()) return res.status(500).json({ error: 'LiveKit ikke konfigurert' });

  const id = 'c_' + crypto.randomBytes(9).toString('hex');
  const room = 'family-' + id;
  const answerToken = crypto.randomBytes(32).toString('hex');
  const call = { id, room, target, status: 'ringing', final: false, createdAt: now(), expiresAt: now() + RING * 1000, answerHash: sha256(answerToken) };
  calls.set(id, call);

  let tabletToken;
  try { tabletToken = await joinToken({ room, identity: 'tablet-stue', name: 'Hjemme', ttl: TOKEN_TTL }); }
  catch (e) { calls.delete(id); log('token error', e.message); return res.status(500).json({ error: 'kunne ikke starte samtalen' }); }

  const link = PUBLIC_URL + '/answer/' + answerToken;
  try {
    call.discord = await discord.dm(p.discordUserId, '🏠 **Det ringer hjemmefra**\n\nBarna vil snakke med deg.\n\n📞 Trykk for å svare:\n' + link + '\n\nAnropet utløper om ' + RING + ' sekunder.');
  } catch (e) {
    calls.delete(id);
    log('discord error', e.message);
    return res.status(502).json({ error: 'kunne ikke varsle ' + p.label });
  }

  call.timer = setTimeout(() => missed(id), RING * 1000);
  log('call', id, 'ringing', target);
  res.json({ callId: id, status: 'ringing', livekit: { url: WS_URL, token: tabletToken }, expiresAt: call.expiresAt });
});

// Parent redeems the answer token and gets their own room token.
app.post('/api/calls/answer', async (req, res) => {
  const token = String((req.body && req.body.answerToken) || '');
  if (!token) return res.status(400).json({ error: 'mangler token' });
  const hash = sha256(token);
  const call = [...calls.values()].find(c => c.answerHash === hash);
  if (!call) return res.status(404).json({ error: 'Dette anropet finnes ikke.' });
  if (call.final) return res.status(410).json({ error: 'Dette anropet er avsluttet.' });
  if (now() > call.expiresAt) { missed(call.id); return res.status(410).json({ error: 'Dette anropet er utløpt.' }); }

  const p = PARENTS[call.target];
  let token2;
  try { token2 = await joinToken({ room: call.room, identity: p.identity, name: p.label, ttl: TOKEN_TTL }); }
  catch (e) { return res.status(500).json({ error: 'kunne ikke koble til' }); }

  if (call.status === 'ringing') {
    call.status = 'connected';
    call.connectedAt = now();
    clearTimeout(call.timer);
    discord.edit(call.discord, '🟢 Samtale besvart.');
    log('call', call.id, 'connected');
  }
  res.json({ callId: call.id, status: call.status, target: call.target, livekit: { url: WS_URL, token: token2 } });
});

app.post('/api/calls/:id/cancel', (req, res) => {
  const c = calls.get(req.params.id);
  if (!c) return res.status(404).json({ error: 'ukjent' });
  if (!c.final) finish(c.id, 'cancelled', '⚪ Anropet ble avbrutt hjemmefra.');
  res.json({ status: 'cancelled' });
});

app.post('/api/calls/:id/end', (req, res) => {
  const c = calls.get(req.params.id);
  if (!c) return res.status(404).json({ error: 'ukjent' });
  if (!c.final) finish(c.id, 'ended', '✅ Samtalen er avsluttet.');
  res.json({ status: 'ended' });
});

app.get('/api/calls/:id', (req, res) => {
  const c = calls.get(req.params.id);
  if (!c) return res.status(404).json({ error: 'ukjent' });
  res.json({ callId: c.id, status: c.status, target: c.target });
});

// The parent answer page. The token stays in the path; the page redeems it via POST.
app.get('/answer/:token', (req, res) => res.sendFile(path.join(__dirname, '..', 'public', 'answer.html')));
app.use(express.static(path.join(__dirname, '..', 'public')));

// DEV-only: two tokens for one room, no Discord, to prove LiveKit media end-to-end.
if (DEV) {
  app.get('/dev/pair', async (req, res) => {
    const room = 'dev-' + crypto.randomBytes(4).toString('hex');
    const a = await joinToken({ room, identity: 'dev-a', name: 'A', ttl: 600 });
    const b = await joinToken({ room, identity: 'dev-b', name: 'B', ttl: 600 });
    res.json({ url: WS_URL, room, a, b });
  });
  log('DEV mode: /dev/pair enabled');
}

app.listen(PORT, () => log('call backend on :' + PORT, '| public', PUBLIC_URL, '| ring', RING + 's', '| livekit', lkOk(), '| discord', discord.configured()));
