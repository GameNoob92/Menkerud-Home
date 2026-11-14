// API-level tests: a phone pairs with a key and then identifies itself with its secret (contracts C1-C3).
'use strict';
const assert = require('assert');
const { wire, startApp, http, bearer } = require('./helpers');

(async () => {
  let clock = Date.now();
  const w = wire({}, { now: () => clock }), { url } = await startApp(w.app);
  const { code } = w.devices.createPairingCode('mor');

  assert.strictEqual((await http('POST', url + '/api/pair', { code: 'X'.repeat(20) })).status, 400);
  const p = await http('POST', url + '/api/pair', { code, name: 'S25 Ultra', appVersion: '1.0.0' });
  assert.strictEqual(p.status, 200);
  assert.deepStrictEqual(Object.keys(p.body).sort(), ['deviceId', 'label', 'secret', 'who']);
  assert.strictEqual(p.body.who, 'mor');
  assert.strictEqual(p.body.label, 'Mor');
  assert.strictEqual((await http('POST', url + '/api/pair', { code })).status, 400);
  console.log('ok - a key pairs a phone once and tells it which parent it is');

  assert.strictEqual((await http('GET', url + '/api/devices/me')).status, 401);
  const me = await http('GET', url + '/api/devices/me', null, { ...bearer(p.body), 'X-App-Version': '1.0.1' });
  assert.deepStrictEqual(me.body, { deviceId: p.body.deviceId, who: 'mor', label: 'Mor', name: 'S25 Ultra' });
  assert.strictEqual(w.devices.list()[0].appVersion, '1.0.1');
  console.log('ok - the phone identifies itself with its secret and reports its app version');

  assert.strictEqual((await http('PUT', url + '/api/devices/me/push-token', { token: '' }, bearer(p.body))).status, 400);
  assert.strictEqual((await http('PUT', url + '/api/devices/me/push-token', { token: 'fcm-1' }, bearer(p.body))).status, 204);
  assert.strictEqual(w.devices.reachableFor('mor').length, 1);
  console.log('ok - registering a push address makes the phone reachable');

  const expired = w.devices.createPairingCode('far');
  clock += 15 * 60 * 1000 + 1000;
  const late = await http('POST', url + '/api/pair', { code: expired.code });
  assert.strictEqual(late.status, 410);
  assert.strictEqual(late.body.error, 'expired');
  console.log('ok - an expired key is answered with 410 expired');

  console.log('ALL GOOD');
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
