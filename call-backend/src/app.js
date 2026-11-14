// The public Express app of the Menkerud Home call backend (what SWAG proxies as call.noobventure.com).
// Owns LiveKit tokens, call state, one-time answer tokens and the Discord answer-link DM.
// Flow: kiosk POST /api/calls -> ringing + tablet token + DM to parent; parent opens
// /answer/<token> -> POST /api/calls/answer -> parent token -> both join the same LiveKit room.
// Everything it needs comes in through createApp(), so tests can hand it fakes.
'use strict';
const path = require('path');
const crypto = require('crypto');
const express = require('express');

const sha256 = s => crypto.createHash('sha256').update(s).digest('hex');
const now = () => Date.now();

function createApp({ config, livekit, discord, devices, hub, log }) {
  const PUBLIC_URL = config.publicUrl, RING = config.ringSec, TOKEN_TTL = config.tokenTtlSec, DEVICE_KEY = config.deviceKey;
  // Only these targets are allowed; Discord ids come from the environment, never the browser.
  const PARENTS = config.parents;

  // In-memory call store. Restarting the backend drops active calls (acceptable for MVP).
  const calls = new Map();

  function finish(id, status, dmText) {
    const c = calls.get(id);
    if (!c || c.final) return;
    c.final = true;
    c.status = status;
    c.endedAt = now();
    clearTimeout(c.timer);
    livekit.deleteRoom(c.room);
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

  app.get('/healthz', (req, res) => res.json({ ok: true, livekit: livekit.configured(), discord: discord.configured() }));

  // The kiosk keeps this one request open; we write events down it (see kiosk-events.js).
  app.get('/api/kiosk/events', deviceAuth, (req, res) => hub.attach(req, res));

  // --- Paired phones (the parent app). A phone proves who it is with the secret it got when it was paired;
  // which parent it belongs to comes from our record, never from the request.
  function phoneAuth(req, res, next) {
    const m = /^Bearer\s+(\S+)$/i.exec(req.get('Authorization') || '');
    const device = m ? devices.authenticate(m[1]) : null;
    if (!device) return res.status(401).json({ error: 'unauthorized' });
    devices.touch(device.deviceId, { appVersion: req.get('X-App-Version') });
    req.device = device;
    next();
  }
  const PAIR_STATUS = { invalid: 400, expired: 410, locked: 429 };

  // The app redeems a one-time key from the local pairing page and gets its long-lived secret – once.
  app.post('/api/pair', (req, res) => {
    const body = req.body || {};
    try {
      const d = devices.redeem(body.code, { name: body.name, appVersion: body.appVersion });
      log('paired a phone for', d.who, d.deviceId);
      res.json({ deviceId: d.deviceId, secret: d.secret, who: d.who, label: PARENTS[d.who].label });
    } catch (e) {
      if (!PAIR_STATUS[e.code]) { log('pair error', e.message); return res.status(500).json({ error: 'pairing failed' }); }
      res.status(PAIR_STATUS[e.code]).json({ error: e.code });
    }
  });

  app.get('/api/devices/me', phoneAuth, (req, res) => {
    const d = req.device;
    res.json({ deviceId: d.deviceId, who: d.who, label: PARENTS[d.who].label, name: d.name });
  });

  // The app tells us where pushes for this phone go (its FCM token); it does so again whenever the token changes.
  app.put('/api/devices/me/push-token', phoneAuth, (req, res) => {
    const token = req.body && req.body.token;
    if (typeof token !== 'string' || !token || token.length > 4096) return res.status(400).json({ error: 'invalid_token' });
    devices.setPushToken(req.device.deviceId, token);
    res.sendStatus(204);
  });

  // Beskjeder: Discord refuses bot tokens sent from a browser (403, code 40333), so the kiosk reads its messages
  // channel and acknowledges with a ✅ through here. The kiosk only names the channel; the token stays on the server.
  const SNOWFLAKE = /^\d{5,25}$/;
  app.get('/api/messages', deviceAuth, async (req, res) => {
    const channel = String(req.query.channel || '');
    if (!SNOWFLAKE.test(channel)) return res.status(400).json({ error: 'ugyldig kanal' });
    try { res.json(await discord.channelMessages(channel, req.query.limit)); }
    catch (e) { log('messages error', e.message); res.status(e.status === 403 || e.status === 404 ? 404 : 502).json({ error: e.message }); }
  });
  app.post('/api/messages/:channel/:id/ack', deviceAuth, async (req, res) => {
    const { channel, id } = req.params;
    if (!SNOWFLAKE.test(channel) || !SNOWFLAKE.test(id)) return res.status(400).json({ error: 'ugyldig id' });
    try { await discord.react(channel, id, '✅'); res.json({ ok: true }); }
    catch (e) { log('ack error', e.message); res.status(502).json({ error: e.message }); }
  });

  // Start a call: create room + tablet token, DM the parent an answer link, arm the ring timeout.
  app.post('/api/calls', deviceAuth, rateLimit, async (req, res) => {
    const target = String((req.body && req.body.target) || '');
    const p = PARENTS[target];
    if (!p) return res.status(400).json({ error: 'ukjent mottaker' });
    if (!p.discordUserId) return res.status(500).json({ error: 'mottaker ikke konfigurert' });
    if (!livekit.configured()) return res.status(500).json({ error: 'LiveKit ikke konfigurert' });

    const id = 'c_' + crypto.randomBytes(9).toString('hex');
    const room = 'family-' + id;
    const answerToken = crypto.randomBytes(32).toString('hex');
    const call = { id, room, target, status: 'ringing', final: false, createdAt: now(), expiresAt: now() + RING * 1000, answerHash: sha256(answerToken) };
    calls.set(id, call);

    let tabletToken;
    try { tabletToken = await livekit.joinToken({ room, identity: 'tablet-stue', name: 'Hjemme', ttl: TOKEN_TTL }); }
    catch (e) { calls.delete(id); log('token error', e.message); return res.status(500).json({ error: 'kunne ikke starte samtalen' }); }

    const link = PUBLIC_URL + '/answer/' + answerToken;
    try {
      call.discord = await discord.dm(p.discordUserId, '🏠 **Det ringer hjemmefra**\n\nBarna vil snakke med deg.\n\n📞 Trykk for å svare:\n' + link + '\n\n💡 Åpne lenken i **Chrome** – nettleseren inni Discord får ikke bruke kamera og mikrofon.\nAnropet utløper om ' + RING + ' sekunder.');
    } catch (e) {
      calls.delete(id);
      log('discord error', e.message);
      return res.status(502).json({ error: 'kunne ikke varsle ' + p.label });
    }

    call.timer = setTimeout(() => missed(id), RING * 1000);
    log('call', id, 'ringing', target);
    res.json({ callId: id, status: 'ringing', livekit: { url: livekit.WS_URL, token: tabletToken }, expiresAt: call.expiresAt });
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
    try { token2 = await livekit.joinToken({ room: call.room, identity: p.identity, name: p.label, ttl: TOKEN_TTL }); }
    catch (e) { return res.status(500).json({ error: 'kunne ikke koble til' }); }

    if (call.status === 'ringing') {
      call.status = 'connected';
      call.connectedAt = now();
      clearTimeout(call.timer);
      discord.edit(call.discord, '🟢 Samtale besvart.');
      log('call', call.id, 'connected');
    }
    res.json({ callId: call.id, status: call.status, target: call.target, livekit: { url: livekit.WS_URL, token: token2 } });
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
  if (config.dev) {
    app.get('/dev/pair', async (req, res) => {
      const room = 'dev-' + crypto.randomBytes(4).toString('hex');
      const a = await livekit.joinToken({ room, identity: 'dev-a', name: 'A', ttl: 600 });
      const b = await livekit.joinToken({ room, identity: 'dev-b', name: 'B', ttl: 600 });
      res.json({ url: livekit.WS_URL, room, a, b });
    });
    log('DEV mode: /dev/pair enabled');
  }

  return app;
}

module.exports = { createApp };
