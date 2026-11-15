// Everything together, in one process: the pairing page, the public API, the phone list, the file push transport, the
// kiosk's event stream and the call manager – the real modules, as server.js wires them. Only LiveKit and Discord are
// stand-ins. It can only fail where two modules disagree about the contract.
'use strict';
const assert = require('assert');
const fs = require('fs');
const { wire, startApp, http, basic, bearer, tmpFile, openEvents, wait } = require('./helpers');
const { createHub } = require('../src/kiosk-events');
const { createPush } = require('../src/push');
const { createAdminApp } = require('../src/admin');

(async () => {
  const outboxFile = tmpFile('push-outbox.jsonl');
  const outbox = () => fs.existsSync(outboxFile) ? fs.readFileSync(outboxFile, 'utf8').split('\n').filter(Boolean).map(l => JSON.parse(l)) : [];
  const w = wire({}, { hub: createHub(), push: createPush({ transport: 'file', outboxFile }) });
  const pub = (await startApp(w.app)).url;
  const admin = (await startApp(createAdminApp({ config: w.config, devices: w.devices, log() {} }))).url;
  const K = { 'X-Device-Key': 'k' };

  // --- a phone is paired with a key from the local page, typed as the page shows it ---
  const pc = await http('POST', admin + '/api/pair-code', { who: 'far' }, basic('x', 'pw'));
  const dev = (await http('POST', pub + '/api/pair', { code: pc.body.pretty, name: 'test', appVersion: '0' })).body;
  assert.strictEqual(dev.who, 'far');
  assert.strictEqual((await http('PUT', pub + '/api/devices/me/push-token', { token: 'fake-1' }, bearer(dev))).status, 204);
  console.log('ok - a key from the pairing page pairs a phone on the public API');

  // --- the kids call far: the push goes out, the phone answers ---
  const s = (await http('POST', pub + '/api/calls', { target: 'far' }, K)).body;
  assert.strictEqual(s.via, 'app');
  assert.strictEqual(outbox().length, 1);
  assert.deepStrictEqual(outbox()[0].data, { type: 'call', v: '1', callId: s.callId, from: 'home', who: 'far', expiresAt: String(s.expiresAt) });
  assert.strictEqual(outbox()[0].token, 'fake-1');
  assert.strictEqual((await http('POST', pub + '/api/calls/' + s.callId + '/answer', {}, bearer(dev))).status, 200);
  await http('POST', pub + '/api/calls/' + s.callId + '/end', {});
  console.log('ok - kids call far: the push is written for that phone, the phone answers, the call ends');

  // --- far calls home: the kiosk hears it on its stream and says «Ikke nå» ---
  const kiosk = await openEvents(pub + '/api/kiosk/events', K);
  assert.deepStrictEqual(await kiosk.next(), { type: 'hello' });
  const h = (await http('POST', pub + '/api/calls/home', {}, bearer(dev))).body;
  const inc = await kiosk.next();
  assert.deepStrictEqual(inc, { type: 'incoming', callId: h.callId, from: 'far', expiresAt: h.expiresAt });
  assert.deepStrictEqual((await http('POST', pub + '/api/calls/' + h.callId + '/ringing', {}, K)).body, { status: 'ringing' });
  // a kiosk that connects in the middle of the ring (a reload, a network blip) is told about the call again
  const late = await openEvents(pub + '/api/kiosk/events', K);
  assert.deepStrictEqual([await late.next(), await late.next()], [{ type: 'hello' }, inc]);
  late.close(); await wait(30);
  assert.strictEqual((await http('POST', pub + '/api/calls/' + h.callId + '/decline', {}, K)).status, 200);
  assert.strictEqual((await http('GET', pub + '/api/calls/' + h.callId)).body.status, 'declined');
  assert.deepStrictEqual(await kiosk.next(), { type: 'ended', callId: h.callId });
  console.log('ok - far calls home: the kiosk is told, declines, and the app can read «declined»');

  // --- far calls home and gives up: the kiosk is told to stop ringing ---
  const h2 = (await http('POST', pub + '/api/calls/home', {}, bearer(dev))).body;
  assert.strictEqual((await kiosk.next()).callId, h2.callId);
  assert.deepStrictEqual((await http('POST', pub + '/api/calls/' + h2.callId + '/cancel', {})).body, { status: 'cancelled' });
  assert.deepStrictEqual([await kiosk.next(), await kiosk.next()], [{ type: 'cancelled', callId: h2.callId }, { type: 'ended', callId: h2.callId }]);
  console.log('ok - the caller giving up reaches the kiosk as «cancelled», then «ended»');

  // --- the kids call far and far presses «Ikke nå»: the kiosk hears it at once ---
  const s2 = (await http('POST', pub + '/api/calls', { target: 'far' }, K)).body;
  assert.deepStrictEqual((await http('POST', pub + '/api/calls/' + s2.callId + '/decline', {}, bearer(dev))).body, { status: 'declined' });
  assert.deepStrictEqual([await kiosk.next(), await kiosk.next()], [{ type: 'declined', callId: s2.callId }, { type: 'ended', callId: s2.callId }]);
  console.log('ok - «Ikke nå» in the app reaches the kiosk as «declined», then «ended»');

  // --- the kiosk goes away: a parent calling home is told so ---
  kiosk.close(); await wait(50);
  const off = await http('POST', pub + '/api/calls/home', {}, bearer(dev));
  assert.strictEqual(off.status, 503);
  assert.strictEqual(off.body.error, 'kiosk_offline');
  console.log('ok - with no kiosk on the stream the app gets 503 kiosk_offline');

  // --- the phone is removed on the pairing page: it is no longer let in, and far gets the Discord link again ---
  assert.strictEqual((await http('DELETE', admin + '/api/devices/' + dev.deviceId, null, basic('x', 'pw'))).status, 204);
  assert.strictEqual((await http('GET', pub + '/api/devices/me', null, bearer(dev))).status, 401);
  const s3 = (await http('POST', pub + '/api/calls', { target: 'far' }, K)).body;
  assert.strictEqual(s3.via, 'discord');
  assert.strictEqual(w.discord.dms.length, 1);
  console.log('ok - a removed phone is refused, and far is reached over the Discord link again');

  console.log('ALL GOOD');
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
