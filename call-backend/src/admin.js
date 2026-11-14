// The local pairing page: a second, small Express app on its own port (config.adminPort).
// It is published on the Unraid host only and is NEVER proxied by SWAG, so nothing of it exists on
// call.noobventure.com. Here the operator makes a one-time key for Mor or Far, sees the paired phones and
// removes one. Every route needs the password from ADMIN_PASSWORD (HTTP Basic, any user name).
'use strict';
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const express = require('express');
const qrcode = require('qrcode-generator');

const digest = s => crypto.createHash('sha256').update(String(s)).digest();
const samePassword = (a, b) => crypto.timingSafeEqual(digest(a), digest(b));   // equal-length digests: constant time

/** The text inside the QR code (contract C10): the app learns which API to pair with and the key in one scan. */
function pairingLink(publicUrl, code) {
  return 'menkerudhome://pair?api=' + encodeURIComponent(publicUrl) + '&code=' + code;
}

function qrSvg(text) {
  const qr = qrcode(0, 'M');               // 0 = smallest version that fits; M = medium error correction
  qr.addData(text);
  qr.make();
  return qr.createSvgTag({ cellSize: 6, margin: 12, scalable: true });
}

function createAdminApp({ config, devices, log }) {
  const page = fs.readFileSync(path.join(__dirname, 'admin-page.html'));
  const app = express();
  app.disable('x-powered-by');
  // The browser keeps this page's password, so the page must never be shown inside another site's frame
  // (where a click could be steered onto «Fjern» or «Ny telefon»). Sent on every answer, the 401 included.
  app.use((req, res, next) => {
    res.set('X-Frame-Options', 'DENY');
    res.set('Content-Security-Policy', "frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
    res.set('X-Content-Type-Options', 'nosniff');
    res.set('Referrer-Policy', 'no-referrer');
    next();
  });

  app.use(express.json({ limit: '4kb' }));

  // Password on everything. With no password configured, nothing is served (server.js does not even listen then).
  app.use((req, res, next) => {
    const m = /^Basic\s+(\S+)$/i.exec(req.get('Authorization') || '');
    let given = null;
    if (m) {
      const raw = Buffer.from(m[1], 'base64').toString('utf8');
      const colon = raw.indexOf(':');
      if (colon >= 0) given = raw.slice(colon + 1);
    }
    if (given !== null && config.adminPassword && samePassword(given, config.adminPassword)) return next();
    res.set('WWW-Authenticate', 'Basic realm="Menkerud Home"').status(401).type('text/plain').send('Passord kreves');
  });

  // A browser sends this page's saved password along with requests that OTHER web sites make to it. Requests that
  // change something must therefore come from this page itself: a foreign Origin is refused. (Clients that are not
  // browsers send no Origin and are not affected.)
  app.use((req, res, next) => {
    if (req.method === 'GET' || req.method === 'HEAD') return next();
    const origin = req.get('Origin');
    if (origin && origin !== req.protocol + '://' + req.get('Host')) return res.status(403).json({ error: 'foreign origin' });
    next();
  });

  app.get('/', (req, res) => res.set('Cache-Control', 'no-store').type('html').send(page));

  app.post('/api/pair-code', (req, res) => {
    const who = req.body && req.body.who;
    if (who !== 'mor' && who !== 'far') return res.status(400).json({ error: 'unknown parent' });
    const { code, expiresAt } = devices.createPairingCode(who);
    log('pairing key made for', who);
    res.json({ code, pretty: code.match(/.{5}/g).join('-'), expiresAt, qrSvg: qrSvg(pairingLink(config.publicUrl, code)) });
  });

  app.get('/api/devices', (req, res) => res.json({ devices: devices.list() }));

  app.delete('/api/devices/:id', (req, res) => {
    if (!devices.remove(req.params.id)) return res.status(404).json({ error: 'unknown' });
    log('removed phone', req.params.id);
    res.sendStatus(204);
  });

  return app;
}

module.exports = { createAdminApp, pairingLink };
