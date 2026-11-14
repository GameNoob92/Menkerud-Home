// Push to the parent app: what is sent, and how.
// The app is asleep most of the time; Google's push service (Firebase Cloud Messaging, HTTP v1) is what wakes it.
// We send data-only, high-priority messages and let the app decide what to show. No Firebase SDK: the service-account
// token request is signed with Node's own crypto, which keeps this service at its handful of dependencies.
'use strict';
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const FCM_SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';
const LONG_NOTICE_SEC = 6 * 3600;          // a "missed" or "cancelled" notice still reaches a phone that was offline
const SHORT_NOTICE_SEC = 60;

// --- payloads (contract C9): every push carries a type and a version; every value is a string ---
function callData({ callId, target, expiresAt }) {
  return { type: 'call', v: '1', callId: String(callId), from: 'home', who: String(target), expiresAt: String(expiresAt) };
}
function callEndData({ callId, reason, at }) {
  return { type: 'call_end', v: '1', callId: String(callId), reason: String(reason), at: String(at) };
}
function callEndTtl(reason) {
  return reason === 'cancelled' || reason === 'missed' ? LONG_NOTICE_SEC : SHORT_NOTICE_SEC;
}

const b64url = x => Buffer.from(typeof x === 'string' ? x : JSON.stringify(x)).toString('base64url');
const strings = data => { const out = {}; for (const k of Object.keys(data || {})) out[k] = String(data[k]); return out; };

function createPush({ transport, serviceAccount, outboxFile, fetch = global.fetch, now = Date.now, log = () => {} } = {}) {
  if (transport === 'file') {
    return {
      name: 'file',
      async send(device, data, opts) {
        try {
          fs.mkdirSync(path.dirname(outboxFile), { recursive: true });
          fs.appendFileSync(outboxFile, JSON.stringify({ at: now(), deviceId: device.deviceId, token: device.pushToken, ttlSec: opts && opts.ttlSec, data: strings(data) }) + '\n');
          return { ok: true };
        } catch (e) { return { ok: false, dead: false, error: e.message }; }
      }
    };
  }

  if (transport !== 'fcm') {
    return { name: 'none', async send() { return { ok: false, dead: false, error: 'push disabled' }; } };
  }

  let access = null;                        // { token, until }
  async function accessToken() {
    const t = now();
    if (access && t < access.until) return access.token;
    const iat = Math.floor(t / 1000);
    const head = b64url({ alg: 'RS256', typ: 'JWT' });
    const claims = b64url({ iss: serviceAccount.client_email, scope: FCM_SCOPE, aud: serviceAccount.token_uri, iat, exp: iat + 3600 });
    const signature = crypto.createSign('RSA-SHA256').update(head + '.' + claims).sign(serviceAccount.private_key, 'base64url');
    const r = await fetch(serviceAccount.token_uri, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'grant_type=' + encodeURIComponent('urn:ietf:params:oauth:grant-type:jwt-bearer') + '&assertion=' + head + '.' + claims + '.' + signature
    });
    const j = await r.json().catch(() => ({}));
    if (!r.ok || !j.access_token) throw new Error('Google refused the service account (' + r.status + ')');
    access = { token: j.access_token, until: t + (Math.max(120, Number(j.expires_in) || 3600) - 60) * 1000 };
    return access.token;
  }

  return {
    name: 'fcm',
    async send(device, data, opts) {
      if (!device || !device.pushToken) return { ok: false, dead: false, error: 'no push address' };
      try {
        const token = await accessToken();
        const r = await fetch('https://fcm.googleapis.com/v1/projects/' + serviceAccount.project_id + '/messages:send', {
          method: 'POST',
          headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
          body: JSON.stringify({ message: { token: device.pushToken, data: strings(data), android: { priority: 'HIGH', ttl: Math.max(0, Math.round((opts && opts.ttlSec) || 0)) + 's' } } })
        });
        if (r.ok) return { ok: true };
        const j = await r.json().catch(() => ({}));
        const err = (j && j.error) || {};
        const code = (Array.isArray(err.details) && (err.details.find(d => d && d.errorCode) || {}).errorCode) || err.status || ('HTTP ' + r.status);
        if (r.status === 401) access = null;            // our access token was rejected: get a new one next time
        // Only "this phone no longer exists" counts as dead. An outage or a message Google rejects must not
        // unpair anybody.
        const dead = code === 'UNREGISTERED' || err.status === 'NOT_FOUND';
        if (!dead) log('push failed', device.deviceId, code);
        return { ok: false, dead, error: String(code) };
      } catch (e) {
        log('push failed', device && device.deviceId, e.message);
        return { ok: false, dead: false, error: e.message };
      }
    }
  };
}

/** The sender server.js uses: PUSH_TRANSPORT if set, else FCM when the Firebase key file is there, else none. */
function pushFromConfig(config, log) {
  const transport = config.pushTransport || (fs.existsSync(config.fcmServiceAccount) ? 'fcm' : 'none');
  if (transport === 'file') return createPush({ transport: 'file', outboxFile: path.join(config.dataDir, 'push-outbox.jsonl'), log });
  if (transport === 'fcm') {
    try {
      const serviceAccount = JSON.parse(fs.readFileSync(config.fcmServiceAccount, 'utf8'));
      if (!serviceAccount.project_id || !serviceAccount.client_email || !serviceAccount.private_key || !serviceAccount.token_uri) throw new Error('not a service-account key file');
      return createPush({ transport: 'fcm', serviceAccount, log });
    } catch (e) {
      log('push disabled: cannot use the Firebase key at ' + config.fcmServiceAccount + ' (' + e.message + ')');
    }
  }
  return createPush({ transport: 'none', log });
}

module.exports = { createPush, pushFromConfig, callData, callEndData, callEndTtl };
