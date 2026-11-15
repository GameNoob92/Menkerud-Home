// Unit tests: participantCount() in src/livekit.js, through the real LiveKit server SDK with a scripted fetch.
// The call watchdog depends on one distinction: "the room is empty" (a number) versus "LiveKit did not answer" (null).
'use strict';
process.env.LIVEKIT_API_KEY = 'testkey';
process.env.LIVEKIT_API_SECRET = 'testsecret_that_is_long_enough_for_hmac_signing';
process.env.LIVEKIT_API_URL = 'https://lk.test';
const assert = require('assert');

const urls = [];
let answer;
global.fetch = async url => { urls.push(String(url)); if (answer instanceof Error) throw answer; return answer; };
const { participantCount } = require('../src/livekit');

(async () => {
  answer = { ok: true, status: 200, json: async () => ({ participants: [{ identity: 'tablet-stue' }, { identity: 'parent-far' }] }) };
  assert.strictEqual(await participantCount('family-c_1'), 2);
  assert.ok(/^https:\/\/lk\.test\/twirp\/livekit\.RoomService\/ListParticipants$/.test(urls[0]), urls[0]);
  console.log('ok - counts the participants LiveKit lists for the room');

  answer = { ok: true, status: 200, json: async () => ({}) };
  assert.strictEqual(await participantCount('family-c_1'), 0);
  answer = { ok: false, status: 404, statusText: 'Not Found', headers: { get: () => 'application/json' }, json: async () => ({ code: 'not_found', msg: 'requested room does not exist' }) };
  assert.strictEqual(await participantCount('family-gone'), 0);
  console.log('ok - an empty or vanished room counts as zero');

  answer = { ok: false, status: 401, statusText: 'Unauthorized', headers: { get: () => 'application/json' }, json: async () => ({ code: 'unauthenticated', msg: 'invalid token' }) };
  assert.strictEqual(await participantCount('family-c_1'), null);
  answer = new Error('connect ECONNREFUSED');
  assert.strictEqual(await participantCount('family-c_1'), null);
  console.log('ok - when LiveKit refuses or cannot be reached the answer is null, never a number');

  // a 404 that is not LiveKit's own – a proxy's error page, a wrong address – says nothing about the room
  answer = { ok: false, status: 404, statusText: 'Not Found', headers: { get: () => 'text/html' }, text: async () => '<html><h1>404 Not Found</h1></html>', json: async () => { throw new SyntaxError('Unexpected token <'); } };
  assert.strictEqual(await participantCount('family-c_1'), null);
  console.log('ok - a 404 from something that is not LiveKit is "don\'t know", not "the room is empty"');

  console.log('ALL GOOD');
})().catch(e => { console.error('FAIL', e); process.exit(1); });
