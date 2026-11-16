// API-level tests: the local pairing page (src/admin.js) – password on every route, keys and QR, list and remove,
// and none of it reachable on the public app.
'use strict';
const assert = require('assert');
const { wire, startApp, http, basic, bearer } = require('./helpers');
const { createAdminApp } = require('../src/admin');

(async () => {
  const w = wire(), A = basic('anyone', 'pw');
  const admin = (await startApp(createAdminApp({ config: w.config, devices: w.devices, log() {} }))).url;
  const pub = (await startApp(w.app)).url;

  assert.strictEqual((await http('GET', admin + '/')).status, 401);
  assert.strictEqual((await http('GET', admin + '/', null, basic('anyone', 'wrong'))).status, 401);
  assert.strictEqual((await http('GET', admin + '/api/devices')).status, 401);
  const page = await http('GET', admin + '/', null, A);
  assert.strictEqual(page.status, 200);
  assert.ok(page.text.includes('Ny telefon for Mor') && page.text.includes('Ny telefon for Far'));
  console.log('ok - every route needs the password; the page offers a key for Mor and for Far');

  // With ADMIN_USER set the user name counts too (the operator uses one login for several of the house's pages)
  const wu = wire({ ADMIN_USER: 'kari' });
  const named = (await startApp(createAdminApp({ config: wu.config, devices: wu.devices, log() {} }))).url;
  assert.strictEqual((await http('GET', named + '/', null, basic('kari', 'pw'))).status, 200);
  assert.strictEqual((await http('GET', named + '/', null, basic('anyone', 'pw'))).status, 401);
  assert.strictEqual((await http('GET', named + '/', null, basic('kari', 'wrong'))).status, 401);
  assert.strictEqual((await http('GET', named + '/', null, basic('', 'pw'))).status, 401);
  assert.strictEqual((await http('GET', named + '/api/devices', null, basic('Kari', 'pw'))).status, 401);
  console.log('ok - with a user name set, the page wants that name and the password, exactly');

  for (const r of [page, await http('GET', admin + '/'), await http('GET', admin + '/api/devices', null, A)]) {
    assert.strictEqual(r.headers.get('x-frame-options'), 'DENY');
    assert.match(r.headers.get('content-security-policy') || '', /frame-ancestors 'none'/);
  }
  console.log('ok - no other site can show the page inside a frame (clickjacking)');

  const pc = await http('POST', admin + '/api/pair-code', { who: 'far' }, A);
  assert.match(pc.body.code, /^[0-9A-HJKMNP-TV-Z]{20}$/);
  assert.match(pc.body.pretty, /^(\w{5}-){3}\w{5}$/);
  assert.strictEqual(pc.body.pretty.replace(/-/g, ''), pc.body.code);
  assert.ok(pc.body.qrSvg.startsWith('<svg'));
  assert.ok(pc.body.expiresAt > Date.now());
  assert.strictEqual((await http('POST', admin + '/api/pair-code', { who: 'bestemor' }, A)).status, 400);
  console.log('ok - a key comes as text, grouped text and a QR code, for mor or far only');

  const dev = w.devices.redeem(pc.body.code, { name: 'S26' });
  const listed = (await http('GET', admin + '/api/devices', null, A)).body.devices;
  assert.strictEqual(listed.length, 1);
  assert.strictEqual(listed[0].name, 'S26');
  assert.strictEqual(listed[0].who, 'far');
  assert.ok(!('secretHash' in listed[0]));
  console.log('ok - the page lists the paired phones without their secrets');

  const foreign = Object.assign({ Origin: 'http://evil.example' }, A);
  assert.strictEqual((await http('POST', admin + '/api/pair-code', { who: 'far' }, foreign)).status, 403);
  assert.strictEqual((await http('DELETE', admin + '/api/devices/' + dev.deviceId, null, foreign)).status, 403);
  assert.strictEqual(w.devices.list().length, 1);
  console.log('ok - another web site cannot make the browser create keys or remove phones');

  assert.strictEqual((await http('DELETE', admin + '/api/devices/' + dev.deviceId, null, A)).status, 204);
  assert.strictEqual((await http('DELETE', admin + '/api/devices/' + dev.deviceId, null, A)).status, 404);
  assert.strictEqual((await http('GET', pub + '/api/devices/me', null, bearer(dev))).status, 401);
  console.log('ok - a removed phone is out at once');

  for (const p of ['/api/pair-code', '/api/devices'])
    assert.strictEqual((await http('GET', pub + p, null, A)).status, 404);
  assert.strictEqual((await http('POST', pub + '/api/pair-code', { who: 'far' }, A)).status, 404);
  console.log('ok - none of it exists on the public port');

  console.log('ALL GOOD');
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
