// API-level tests: the event stream the kiosk keeps open (src/kiosk-events.js, contract C8), read over real HTTP.
'use strict';
const assert = require('assert');
const { wire, startApp, openEvents, wait } = require('./helpers');
const { createHub } = require('../src/kiosk-events');

(async () => {
  const hub = createHub({ keepAliveMs: 30 }), w = wire({}, { hub }), { url } = await startApp(w.app);

  assert.strictEqual((await openEvents(url + '/api/kiosk/events', {})).status, 401);
  assert.strictEqual(hub.online(), false);
  console.log('ok - the stream needs the kiosk key, and nobody is online before it connects');

  const ev = await openEvents(url + '/api/kiosk/events', { 'X-Device-Key': 'k' });
  assert.strictEqual(ev.status, 200);
  assert.match(ev.contentType, /^text\/event-stream/);
  assert.deepStrictEqual(await ev.next(), { type: 'hello' });
  assert.strictEqual(hub.online(), true);
  console.log('ok - the kiosk connects, gets a hello, and counts as online');

  assert.strictEqual(hub.send({ type: 'incoming', callId: 'c_1', from: 'far' }), 1);
  assert.deepStrictEqual(await ev.next(), { type: 'incoming', callId: 'c_1', from: 'far' });
  console.log('ok - an event reaches the connected kiosk');

  assert.ok((await ev.raw(200)).includes(': ka'));
  console.log('ok - keep-alive comments flow while nothing happens');

  ev.close();
  await wait(50);
  assert.strictEqual(hub.online(), false);
  assert.strictEqual(hub.send({ type: 'x' }), 0);
  console.log('ok - when the kiosk goes away it stops counting as online');

  console.log('ALL GOOD');
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
