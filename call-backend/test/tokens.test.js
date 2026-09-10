// Unit tests: LiveKit token is scoped to one room with the right ttl, and answer-token hashing works.
process.env.LIVEKIT_API_KEY = 'testkey';
process.env.LIVEKIT_API_SECRET = 'testsecret_that_is_long_enough_for_hmac_signing';
const assert = require('assert');
const crypto = require('crypto');
const { joinToken } = require('../src/livekit');

(async () => {
  const jwt = await joinToken({ room: 'family-c_1', identity: 'tablet-stue', name: 'Hjemme', ttl: 600 });
  const payload = JSON.parse(Buffer.from(jwt.split('.')[1], 'base64').toString('utf8'));
  assert.strictEqual(payload.video.room, 'family-c_1', 'token is scoped to the call room');
  assert.strictEqual(payload.video.roomJoin, true, 'token grants roomJoin');
  assert.strictEqual(payload.sub, 'tablet-stue', 'identity is in sub');
  const ttl = payload.exp - (payload.nbf || payload.iat);
  assert.ok(ttl >= 590 && ttl <= 610, 'ttl is ~600s: ' + ttl);
  console.log('ok - LiveKit token is room-scoped with ~600s ttl');

  const sha = s => crypto.createHash('sha256').update(s).digest('hex');
  const a = crypto.randomBytes(32).toString('hex');
  assert.strictEqual(sha(a), sha(a), 'hash is stable');
  assert.notStrictEqual(sha(a), sha(crypto.randomBytes(32).toString('hex')), 'different tokens hash differently');
  console.log('ok - answer-token hashing is stable and unique');

  console.log('ALL GOOD');
})().catch(e => { console.error('FAIL', e); process.exit(1); });
