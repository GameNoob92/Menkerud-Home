// Unit tests: paired phones and one-time pairing keys (src/devices.js), on a real temp file with a hand-set clock.
'use strict';
const assert = require('assert');
const fs = require('fs');
const { tmpFile } = require('./helpers');
const { createDevices } = require('../src/devices');

let t = 1_800_000_000_000;
const file = tmpFile('devices.json');
const d = createDevices({ file, now: () => t });

const { code, expiresAt } = d.createPairingCode('far');
assert.match(code, /^[0-9A-HJKMNP-TV-Z]{20}$/);
assert.strictEqual(expiresAt, t + 900000);
assert.throws(() => d.createPairingCode('bestemor'));
console.log('ok - a key is 20 unambiguous characters, valid for 15 minutes, and only for mor or far');

// typed by hand: lower case, dashes, spaces, and the look-alikes o/0 and l/1
const typed = ' ' + code.toLowerCase().replace(/0/g, 'o').replace(/1/g, 'l').match(/.{5}/g).join('-') + ' ';
const dev = d.redeem(typed, { name: 'S26', appVersion: '1.0.0' });
assert.strictEqual(dev.who, 'far');
assert.match(dev.deviceId, /^d_[0-9a-f]{12}$/);
assert.match(dev.secret, /^[0-9a-f]{64}$/);
console.log('ok - a sloppily typed key still pairs the phone as the right parent');

assert.throws(() => d.redeem(code, {}), e => e.code === 'invalid');
console.log('ok - a key works once');

const c2 = d.createPairingCode('mor');
t += 900001;
assert.throws(() => d.redeem(c2.code, {}), e => e.code === 'expired');
console.log('ok - a key is refused as expired after 15 minutes');

assert.strictEqual(d.authenticate(dev.deviceId + '.' + dev.secret).who, 'far');
assert.strictEqual(d.authenticate(dev.deviceId + '.' + 'f'.repeat(64)), null);
assert.strictEqual(d.authenticate('nonsense'), null);
console.log('ok - only the right secret authenticates a phone');

assert.ok(!fs.readFileSync(file, 'utf8').includes(dev.secret));
console.log('ok - the file never holds a phone secret');

assert.strictEqual(createDevices({ file }).authenticate(dev.deviceId + '.' + dev.secret).who, 'far');
console.log('ok - a phone stays paired across a restart');

assert.strictEqual(d.reachableFor('far').length, 0);
d.setPushToken(dev.deviceId, 'tok');
assert.strictEqual(d.reachableFor('far').length, 1);
assert.strictEqual(d.list()[0].push, 'ok');
d.markPushDead(dev.deviceId);
assert.strictEqual(d.reachableFor('far').length, 0);
assert.strictEqual(d.devicesFor('far').length, 1);
d.setPushToken(dev.deviceId, 'tok2');
assert.strictEqual(d.reachableFor('far').length, 1);
console.log('ok - a phone is reachable only with a live push address; a fresh one revives it');

assert.ok(!('secretHash' in d.list()[0]) && !('pushToken' in d.list()[0]));
console.log('ok - the list for the pairing page leaves out the hash and the push address');

for (let i = 0; i < 10; i++) assert.throws(() => d.redeem('0'.repeat(20), {}), e => e.code === 'invalid');
assert.throws(() => d.redeem(d.createPairingCode('far').code, {}), e => e.code === 'locked');
t += 600001;
assert.strictEqual(d.redeem(d.createPairingCode('far').code, {}).who, 'far');
console.log('ok - ten wrong keys lock pairing for ten minutes, then it works again');

assert.strictEqual(d.remove(dev.deviceId), true);
assert.strictEqual(d.authenticate(dev.deviceId + '.' + dev.secret), null);
console.log('ok - a removed phone no longer authenticates');

// a damaged file must not take calling down: start empty, keep the damaged file for recovery
const broken = tmpFile('devices.json');
fs.writeFileSync(broken, '{ this is not json');
const fresh = createDevices({ file: broken, log() {} });
assert.deepStrictEqual(fresh.list(), []);
assert.ok(fs.readdirSync(require('path').dirname(broken)).some(f => f.startsWith('devices.json.damaged-')));
assert.strictEqual(fresh.redeem(fresh.createPairingCode('mor').code, {}).who, 'mor');
console.log('ok - a damaged device file is set aside and the store starts empty instead of refusing to start');

console.log('ALL GOOD');
