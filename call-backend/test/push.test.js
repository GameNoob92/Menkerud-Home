// Unit tests: the push sender (src/push.js). FCM is spoken to through a scripted fetch; the signature on the
// token request is checked with the real public key, so a wrong signing step cannot pass.
'use strict';
const assert = require('assert');
const crypto = require('crypto');
const fs = require('fs');
const { tmpFile } = require('./helpers');
const { createPush, callData, callEndData, callEndTtl } = require('../src/push');

function fakeFetch() {
  const queue = [], calls = [];
  const f = async (url, opts) => {
    calls.push({ url, opts: opts || {} });
    const a = queue.shift();
    if (a instanceof Error) throw a;
    const { status, body } = a || { status: 500, body: {} };
    return { ok: status >= 200 && status < 300, status, json: async () => body, text: async () => JSON.stringify(body) };
  };
  f.calls = calls;
  f.script = (...answers) => { queue.push(...answers); };
  return f;
}

(async () => {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const pem = privateKey.export({ type: 'pkcs8', format: 'pem' });
  const sa = { project_id: 'p1', client_email: 'svc@p1.iam.gserviceaccount.com', private_key: pem, token_uri: 'https://oauth2.googleapis.com/token' };
  const fake = fakeFetch();
  const push = createPush({ transport: 'fcm', serviceAccount: sa, fetch: fake, now: () => 1_800_000_000_000, log() {} });
  const phone = { deviceId: 'd_1', pushToken: 'T' };

  fake.script({ status: 200, body: { access_token: 'AT', expires_in: 3600 } }, { status: 200, body: { name: 'x' } }, { status: 200, body: { name: 'y' } });
  assert.deepStrictEqual(await push.send(phone, { type: 'call', v: '1', callId: 'c_1' }, { ttlSec: 60 }), { ok: true });
  assert.strictEqual(fake.calls[0].url, 'https://oauth2.googleapis.com/token');
  const form = new URLSearchParams(fake.calls[0].opts.body);
  assert.strictEqual(form.get('grant_type'), 'urn:ietf:params:oauth:grant-type:jwt-bearer');
  const jwt = form.get('assertion').split('.');
  assert.ok(crypto.createVerify('RSA-SHA256').update(jwt[0] + '.' + jwt[1]).verify(publicKey, jwt[2], 'base64url'));
  const claims = JSON.parse(Buffer.from(jwt[1], 'base64url'));
  assert.strictEqual(claims.iss, sa.client_email);
  assert.strictEqual(claims.scope, 'https://www.googleapis.com/auth/firebase.messaging');
  assert.strictEqual(claims.aud, sa.token_uri);
  assert.strictEqual(claims.exp - claims.iat, 3600);
  console.log('ok - the token request is a correctly signed service-account JWT for the messaging scope');

  assert.strictEqual(fake.calls[1].url, 'https://fcm.googleapis.com/v1/projects/p1/messages:send');
  assert.strictEqual(fake.calls[1].opts.headers.Authorization, 'Bearer AT');
  assert.deepStrictEqual(JSON.parse(fake.calls[1].opts.body), { message: { token: 'T', data: { type: 'call', v: '1', callId: 'c_1' }, android: { priority: 'HIGH', ttl: '60s' } } });
  console.log('ok - a push is a data-only, high-priority FCM message with the given lifetime');

  await push.send(phone, { type: 'call_end' }, { ttlSec: 60 });
  assert.strictEqual(fake.calls.length, 3);
  console.log('ok - the access token is reused instead of being fetched for every push');

  fake.script({ status: 404, body: { error: { status: 'NOT_FOUND', details: [{ errorCode: 'UNREGISTERED' }] } } });
  assert.deepStrictEqual(await push.send(phone, {}, { ttlSec: 60 }), { ok: false, dead: true, error: 'UNREGISTERED' });
  console.log('ok - an unregistered phone is reported as dead');

  fake.script(new Error('socket hang up'));
  const netFail = await push.send(phone, {}, { ttlSec: 60 });
  assert.strictEqual(netFail.ok, false);
  assert.strictEqual(netFail.dead, false);
  fake.script({ status: 503, body: { error: { status: 'UNAVAILABLE' } } });
  assert.strictEqual((await push.send(phone, {}, { ttlSec: 60 })).dead, false);
  fake.script({ status: 400, body: { error: { status: 'INVALID_ARGUMENT' } } });
  assert.strictEqual((await push.send(phone, {}, { ttlSec: 60 })).dead, false);
  console.log('ok - a network error, an outage or a rejected message does not mark the phone dead');

  assert.deepStrictEqual(await push.send({ deviceId: 'd_2', pushToken: '' }, {}, { ttlSec: 60 }), { ok: false, dead: false, error: 'no push address' });
  console.log('ok - a phone without a push address is skipped without calling FCM');

  // a rejected access token is thrown away, so the next push fetches a fresh one
  const n = fake.calls.length;
  fake.script({ status: 401, body: { error: { status: 'UNAUTHENTICATED' } } }, { status: 200, body: { access_token: 'AT2', expires_in: 3600 } }, { status: 200, body: {} });
  assert.strictEqual((await push.send(phone, {}, { ttlSec: 60 })).ok, false);
  assert.deepStrictEqual(await push.send(phone, {}, { ttlSec: 60 }), { ok: true });
  assert.strictEqual(fake.calls[n + 2].opts.headers.Authorization, 'Bearer AT2');
  console.log('ok - a rejected access token is replaced on the next push');

  assert.deepStrictEqual(callData({ callId: 'c_1', target: 'far', expiresAt: 5 }), { type: 'call', v: '1', callId: 'c_1', from: 'home', who: 'far', expiresAt: '5' });
  assert.deepStrictEqual(callEndData({ callId: 'c_1', reason: 'missed', at: 7 }), { type: 'call_end', v: '1', callId: 'c_1', reason: 'missed', at: '7' });
  assert.strictEqual(callEndTtl('missed'), 21600);
  assert.strictEqual(callEndTtl('cancelled'), 21600);
  assert.strictEqual(callEndTtl('declined'), 60);
  console.log('ok - call and call_end payloads carry type and version, all values as strings; missed/cancelled notices live six hours');

  const out = tmpFile('outbox.jsonl'), filePush = createPush({ transport: 'file', outboxFile: out, now: () => 42, log() {} });
  assert.deepStrictEqual(await filePush.send(phone, { type: 'call' }, { ttlSec: 60 }), { ok: true });
  await filePush.send(phone, { type: 'call_end' }, { ttlSec: 60 });
  const lines = fs.readFileSync(out, 'utf8').trim().split('\n').map(l => JSON.parse(l));
  assert.deepStrictEqual(lines[0], { at: 42, deviceId: 'd_1', token: 'T', ttlSec: 60, data: { type: 'call' } });
  assert.strictEqual(lines.length, 2);
  console.log('ok - the file transport appends one JSON line per push (for the dev copy)');

  assert.deepStrictEqual(await createPush({ transport: 'none' }).send({}, {}, {}), { ok: false, dead: false, error: 'push disabled' });
  console.log('ok - the none transport refuses politely');

  console.log('ALL GOOD');
})().catch(e => { console.error('FAIL', e); process.exit(1); });
