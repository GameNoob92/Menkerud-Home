// API-level tests: the public Express app, driven over real HTTP with fake LiveKit and Discord.
'use strict';
const assert = require('assert');
const { wire, startApp, http, bearer } = require('./helpers');

(async () => {
  // --- the routes the kiosk and the answer page use today ---
  const w = wire(), { url } = await startApp(w.app), K = { 'X-Device-Key': 'k' };
  const hz = (await http('GET', url + '/healthz')).body;
  assert.ok(hz.ok === true && hz.livekit === true && hz.discord === true);
  console.log('ok - healthz reports livekit and discord');

  assert.strictEqual((await http('POST', url + '/api/calls', { target: 'far' })).status, 401);
  const s = await http('POST', url + '/api/calls', { target: 'far' }, K);
  assert.strictEqual(s.status, 200);
  assert.strictEqual(s.body.status, 'ringing');
  assert.strictEqual(s.body.livekit.token, 'tok:family-' + s.body.callId + ':tablet-stue');
  console.log('ok - the kiosk starts a call with its device key and gets a room token');

  const link = w.discord.dms[0].content.match(/\/answer\/([0-9a-f]{64})/)[1];
  assert.strictEqual((await http('POST', url + '/api/calls', { target: 'mor' }, K)).status, 409);
  console.log('ok - a second call while one is active is refused');

  assert.strictEqual((await http('POST', url + '/api/calls/answer', { answerToken: link })).body.status, 'connected');
  console.log('ok - the link from the Discord message answers the call');

  await http('POST', url + '/api/calls/' + s.body.callId + '/end', {});
  assert.strictEqual((await http('POST', url + '/api/calls', { target: 'mor' }, K)).status, 200);
  console.log('ok - ending a call frees the line');

  assert.strictEqual((await http('GET', url + '/answer/abc')).status, 200);
  console.log('ok - the answer page is still served');

  // --- the parent app answers and declines over HTTP (contract C5) ---
  const v = wire(), far = v.phone('far'), base = (await startApp(v.app)).url;
  const c1 = (await http('POST', base + '/api/calls', { target: 'far' }, K)).body;
  assert.strictEqual(c1.via, 'app');
  assert.strictEqual((await http('POST', base + '/api/calls/' + c1.callId + '/answer', {})).status, 401);
  const ans = await http('POST', base + '/api/calls/' + c1.callId + '/answer', {}, bearer(far));
  assert.strictEqual(ans.status, 200);
  assert.strictEqual(ans.body.livekit.token, 'tok:family-' + c1.callId + ':parent-far');
  assert.strictEqual((await http('GET', base + '/api/calls/' + c1.callId)).body.status, 'connected');
  assert.deepStrictEqual((await http('POST', base + '/api/calls/' + c1.callId + '/end', {})).body, { status: 'ended' });
  console.log('ok - a paired phone answers with its secret and gets a room token; without the secret it is refused');

  const c2 = (await http('POST', base + '/api/calls', { target: 'far' }, K)).body;
  assert.strictEqual((await http('POST', base + '/api/calls/' + c2.callId + '/decline', {})).status, 401);
  assert.deepStrictEqual((await http('POST', base + '/api/calls/' + c2.callId + '/decline', {}, bearer(far))).body, { status: 'declined' });
  assert.deepStrictEqual((await http('GET', base + '/api/calls/' + c2.callId)).body, { callId: c2.callId, status: 'declined', target: 'far', direction: 'home_to_parent' });
  const unknown = await http('POST', base + '/api/calls/c_unknown/answer', {}, bearer(far));
  assert.strictEqual(unknown.status, 404);
  assert.strictEqual(unknown.body.error, 'unknown');
  console.log('ok - the phone declines over HTTP; a call that does not exist is a clean 404');

  // --- a parent calls home over HTTP (contract C6) ---
  assert.strictEqual((await http('POST', base + '/api/calls/home', {})).status, 401);
  assert.strictEqual((await http('POST', base + '/api/calls/home', {}, K)).status, 401);          // the kiosk key is not a phone
  const h1 = await http('POST', base + '/api/calls/home', {}, bearer(far));
  assert.strictEqual(h1.status, 200);
  assert.strictEqual(h1.body.status, 'ringing');
  assert.strictEqual(h1.body.livekit.token, 'tok:family-' + h1.body.callId + ':parent-far');
  assert.deepStrictEqual(v.hub.sent.filter(e => e.type === 'incoming').map(e => e.from), ['far']);
  console.log('ok - a paired phone rings home; nobody else can');

  assert.strictEqual((await http('POST', base + '/api/calls/' + h1.body.callId + '/answer', {})).status, 401);
  const picked = await http('POST', base + '/api/calls/' + h1.body.callId + '/answer', {}, K);
  assert.strictEqual(picked.status, 200);
  assert.strictEqual(picked.body.livekit.token, 'tok:family-' + h1.body.callId + ':tablet-stue');
  assert.deepStrictEqual((await http('GET', base + '/api/calls/' + h1.body.callId)).body, { callId: h1.body.callId, status: 'connected', target: 'far', direction: 'parent_to_home' });
  await http('POST', base + '/api/calls/' + h1.body.callId + '/end', {});
  console.log('ok - the kiosk answers the waiting call with its device key');

  const h2 = (await http('POST', base + '/api/calls/home', {}, bearer(far))).body;
  assert.deepStrictEqual((await http('POST', base + '/api/calls/' + h2.callId + '/decline', {}, K)).body, { status: 'declined' });
  assert.strictEqual((await http('GET', base + '/api/calls/' + h2.callId)).body.status, 'declined');
  v.hub.state.online = false;
  const off = await http('POST', base + '/api/calls/home', {}, bearer(far));
  assert.strictEqual(off.status, 503);
  assert.strictEqual(off.body.error, 'kiosk_offline');
  console.log('ok - the kiosk declines with its device key; with no kiosk connected the phone gets 503 kiosk_offline');

  // --- the kiosk confirms that a call is ringing there; only the kiosk can (contract C6) ---
  v.hub.state.online = true;
  const h3 = (await http('POST', base + '/api/calls/home', {}, bearer(far))).body;
  assert.strictEqual((await http('POST', base + '/api/calls/' + h3.callId + '/ringing', {})).status, 401);
  assert.strictEqual((await http('POST', base + '/api/calls/' + h3.callId + '/ringing', {}, bearer(far))).status, 401);
  assert.deepStrictEqual((await http('POST', base + '/api/calls/' + h3.callId + '/ringing', {}, K)).body, { status: 'ringing' });
  assert.strictEqual((await http('POST', base + '/api/calls/c_unknown/ringing', {}, K)).status, 404);
  console.log('ok - the kiosk confirms a ringing call with its device key; nobody else can');

  // a bearer, once presented, has to be valid – even next to a correct kiosk key
  const bad = await http('POST', base + '/api/calls/' + h3.callId + '/answer', {}, Object.assign({ Authorization: 'Bearer ' + far.deviceId + '.' + 'f'.repeat(64) }, K));
  assert.strictEqual(bad.status, 401);
  assert.strictEqual((await http('GET', base + '/api/calls/' + h3.callId)).body.status, 'ringing');
  console.log('ok - a wrong phone secret is refused even when the kiosk key comes with it');

  // --- a server without a device key: what was added for the kiosk is closed, the older routes are as before ---
  const o = wire({ DEVICE_KEY: '' }), open = (await startApp(o.app)).url;
  const ev = await http('GET', open + '/api/kiosk/events');
  assert.strictEqual(ev.status, 403);
  assert.strictEqual(ev.body.error, 'device_key_required');
  for (const what of ['ringing', 'answer', 'decline']) assert.strictEqual((await http('POST', open + '/api/calls/c_x/' + what, {})).status, 403, what);
  assert.strictEqual((await http('POST', open + '/api/calls', { target: 'far' })).status, 200);
  console.log('ok - without a device key nobody can sit in for the kiosk; starting a call from it is open as before');

  console.log('ALL GOOD');
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
