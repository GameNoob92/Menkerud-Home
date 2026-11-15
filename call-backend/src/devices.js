// Paired phones and one-time pairing keys.
// A parent's phone becomes "paired" by redeeming a key that the operator generated on the local pairing page.
// The phone then holds a long-lived secret; this store keeps only its SHA-256 hash, in one small JSON file that
// survives a container rebuild. Which parent a phone belongs to is recorded here and never taken from a request.
'use strict';
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const PAIR_TTL_MS = 15 * 60 * 1000;        // a key is valid for 15 minutes
const MAX_FAILED_REDEEMS = 10;             // wrong keys answered ...
const FAILED_WINDOW_MS = 10 * 60 * 1000;   // ... per ten minutes, for the whole store (every caller arrives via the proxy); real keys are never blocked
const SEEN_WRITE_MS = 60 * 1000;           // "last seen" is written to disk at most this often
const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';   // no I, L, O, U: nothing that can be misread when typed
const CODE_LENGTH = 20;                    // 20 x 5 bits = 100 bits
const WHO = ['mor', 'far'];

class PairError extends Error {
  constructor(code) { super('pairing failed: ' + code); this.code = code; }
}

const sha256 = s => crypto.createHash('sha256').update(String(s)).digest('hex');

/** What a person typed -> the canonical key: upper case, no spaces or dashes, look-alikes folded. */
function normalizeCode(input) {
  return String(input == null ? '' : input).toUpperCase().replace(/[\s-]+/g, '').replace(/O/g, '0').replace(/[IL]/g, '1');
}

function createDevices({ file, now = Date.now, randomBytes = crypto.randomBytes, log = console.error }) {
  let devices = [];                        // { deviceId, who, secretHash, name, appVersion, createdAt, lastSeenAt, pushToken, pushDead }
  const codes = new Map();                 // canonical key -> { who, expiresAt }; memory only
  let failed = [];                         // times of wrong keys inside the window
  const seenWritten = new Map();           // deviceId -> when lastSeenAt was last written to disk

  if (fs.existsSync(file)) {
    try {
      const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
      devices = Array.isArray(parsed && parsed.devices) ? parsed.devices : [];
    } catch (e) {
      // Writes are atomic, so this should not happen. If it does, calling must stay up: set the damaged file aside
      // for recovery and start empty (parents without a paired phone get the Discord link again).
      const aside = file + '.damaged-' + now();
      try { fs.renameSync(file, aside); } catch (e2) {}
      log('device list at ' + file + ' is unreadable (' + e.message + ') – moved to ' + aside + ', starting with no paired phones');
    }
  }

  function save() {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    const tmp = file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify({ version: 1, devices }, null, 1));
    fs.renameSync(tmp, file);              // atomic: a crash never leaves half a file
  }

  const find = deviceId => devices.find(d => d.deviceId === deviceId) || null;
  const isReachable = d => !!d.pushToken && !d.pushDead;

  function createPairingCode(who) {
    if (!WHO.includes(who)) throw new Error('unknown parent: ' + who);
    const t = now();
    for (const [c, v] of codes) if (v.expiresAt < t - FAILED_WINDOW_MS) codes.delete(c);   // drop long-dead keys
    const bytes = randomBytes(CODE_LENGTH);
    let code = '';
    for (let i = 0; i < CODE_LENGTH; i++) code += ALPHABET[bytes[i] & 31];
    const expiresAt = t + PAIR_TTL_MS;
    codes.set(code, { who, expiresAt });
    return { code, expiresAt };
  }

  function redeem(input, info) {
    const t = now();
    const code = normalizeCode(input);
    const entry = codes.get(code);
    if (!entry) {
      // Only wrong keys are capped. A real key always goes through, so nobody on the outside can keep the family
      // from pairing a phone by spraying wrong keys at this public endpoint. (Guessing a 100-bit key is hopeless
      // either way; the cap just keeps a guesser from getting more than a handful of answers.)
      failed = failed.filter(x => t - x < FAILED_WINDOW_MS);
      if (failed.length >= MAX_FAILED_REDEEMS) throw new PairError('locked');
      failed.push(t);
      throw new PairError('invalid');
    }
    codes.delete(code);                    // one use, whether it is still valid or not
    if (t > entry.expiresAt) throw new PairError('expired');   // a real key that ran out is not a guess: it does not count as a wrong one
    const secret = randomBytes(32).toString('hex');
    const device = {
      deviceId: 'd_' + randomBytes(6).toString('hex'),
      who: entry.who,
      secretHash: sha256(secret),
      name: String((info && info.name) || '').slice(0, 40),
      appVersion: String((info && info.appVersion) || '').slice(0, 20),
      createdAt: t,
      lastSeenAt: t,
      pushToken: '',
      pushDead: false
    };
    devices.push(device);
    save();
    return { deviceId: device.deviceId, secret, who: device.who };
  }

  function authenticate(bearer) {
    const s = String(bearer == null ? '' : bearer);
    const dot = s.indexOf('.');
    if (dot < 1) return null;
    const device = find(s.slice(0, dot));
    const given = Buffer.from(sha256(s.slice(dot + 1)), 'hex');
    // Compare against something of the same length even when the id is unknown, so timing tells nothing.
    const stored = Buffer.from(device ? device.secretHash : sha256('no such device'), 'hex');
    const same = given.length === stored.length && crypto.timingSafeEqual(given, stored);
    return device && same ? device : null;
  }

  function setPushToken(deviceId, token) {
    const d = find(deviceId);
    if (!d) return;
    d.pushToken = String(token);
    d.pushDead = false;                    // a fresh address revives a phone that was marked dead
    save();
  }

  function markPushDead(deviceId) {
    const d = find(deviceId);
    if (!d || d.pushDead) return;
    d.pushDead = true;
    save();
  }

  function touch(deviceId, info) {
    const d = find(deviceId);
    if (!d) return;
    const t = now();
    const version = info && info.appVersion ? String(info.appVersion).slice(0, 20) : '';
    const versionChanged = !!version && version !== d.appVersion;
    if (versionChanged) d.appVersion = version;
    d.lastSeenAt = t;
    if (versionChanged || t - (seenWritten.get(deviceId) || 0) > SEEN_WRITE_MS) {
      seenWritten.set(deviceId, t);
      // "Last seen" only serves the pairing page. A phone must never be turned away because it could not be written.
      try { save(); } catch (e) { log('could not write the phone list (' + e.message + ') – carrying on'); }
    }
  }

  function list() {
    return devices.map(d => ({
      deviceId: d.deviceId, who: d.who, name: d.name, appVersion: d.appVersion, createdAt: d.createdAt, lastSeenAt: d.lastSeenAt,
      push: !d.pushToken ? 'none' : d.pushDead ? 'dead' : 'ok'
    }));
  }

  function remove(deviceId) {
    const before = devices.length;
    devices = devices.filter(d => d.deviceId !== deviceId);
    if (devices.length === before) return false;
    save();
    return true;
  }

  const devicesFor = who => devices.filter(d => d.who === who);
  const reachableFor = who => devices.filter(d => d.who === who && isReachable(d));

  return { createPairingCode, redeem, authenticate, setPushToken, markPushDead, touch, list, remove, devicesFor, reachableFor };
}

module.exports = { createDevices, PairError, PAIR_TTL_MS, MAX_FAILED_REDEEMS, FAILED_WINDOW_MS };
