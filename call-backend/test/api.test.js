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

  console.log('ALL GOOD');
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
