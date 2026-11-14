// The public Express app of the Menkerud Home call backend (what SWAG proxies as call.noobventure.com): routes only.
// Call state lives in calls.js, paired phones in devices.js, the kiosk's event stream in kiosk-events.js.
// Everything comes in through createApp(), so tests can hand it fakes.
'use strict';
const path = require('path');
const crypto = require('crypto');
const express = require('express');

function createApp({ config, livekit, discord, devices, hub, calls, health, log }) {
  const DEVICE_KEY = config.deviceKey;
  // Only these targets are allowed; Discord ids come from the environment, never the browser.
  const PARENTS = config.parents;

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

  // The call manager refuses with a status and a text of its own; anything else is a bug and becomes a 500.
  function refuse(res, e) {
    if (e && e.status) return res.status(e.status).json({ error: e.message });
    log('unexpected error', e && e.message);
    res.status(500).json({ error: 'internal error' });
  }

  app.get('/healthz', (req, res) => res.json({ ok: true, livekit: livekit.configured(), discord: discord.configured(), livekitAdmin: !!(health && health.livekitAdmin) }));

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

  // --- Calls. The rules are in calls.js; these routes only say who is asking.

  // The kiosk: a child pressed «Ring mor / far».
  app.post('/api/calls', deviceAuth, async (req, res) => {
    try { res.json(await calls.startFromHome(String((req.body && req.body.target) || ''))); } catch (e) { refuse(res, e); }
  });

  // The link in the Discord message (until both parents use the app): the answer page redeems its token here.
  app.post('/api/calls/answer', async (req, res) => {
    try { res.json(await calls.answerByLink(req.body && req.body.answerToken)); } catch (e) { refuse(res, e); }
  });

  // The app: a parent pressed «Ring hjem». The kiosk is told over its event stream.
  app.post('/api/calls/home', phoneAuth, async (req, res) => {
    try { res.json(await calls.startFromParent(req.device)); } catch (e) { refuse(res, e); }
  });

  // «Svar» and «Ikke nå» – by whichever side is being called: a phone (its secret as bearer) for a call from home,
  // the kiosk (its device key) for a call from a parent. A bearer, once presented, has to be valid.
  function phoneOrKiosk(req, res, next) {
    if (/^Bearer\s/i.test(req.get('Authorization') || '')) return phoneAuth(req, res, next);
    return deviceAuth(req, res, next);
  }
  app.post('/api/calls/:id/answer', phoneOrKiosk, async (req, res) => {
    try { res.json(req.device ? await calls.answerByParent(req.params.id, req.device) : await calls.answerByKiosk(req.params.id)); }
    catch (e) { refuse(res, e); }
  });
  app.post('/api/calls/:id/decline', phoneOrKiosk, (req, res) => {
    try { res.json(req.device ? calls.decline(req.params.id, 'parent', req.device) : calls.decline(req.params.id, 'kiosk')); }
    catch (e) { refuse(res, e); }
  });

  // Either side hangs up. A call id is long and random; knowing it is the permission, as before.
  app.post('/api/calls/:id/cancel', (req, res) => {
    try { res.json(calls.cancel(req.params.id)); } catch (e) { refuse(res, e); }
  });
  app.post('/api/calls/:id/end', (req, res) => {
    try { res.json(calls.end(req.params.id)); } catch (e) { refuse(res, e); }
  });

  app.get('/api/calls/:id', (req, res) => {
    const c = calls.get(req.params.id);
    if (!c) return res.status(404).json({ error: 'ukjent' });
    res.json(c);
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
