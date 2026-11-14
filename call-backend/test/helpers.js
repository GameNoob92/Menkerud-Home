// Shared test helpers: fakes for the outside world and one place that wires the app together.
// Tests build their app through wire(), never through createApp() directly, so they keep working as the wiring grows.
'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const { loadConfig } = require('../src/config');
const { createApp } = require('../src/app');
const { createDevices } = require('../src/devices');

const wait = ms => new Promise(r => setTimeout(r, ms));

/** Start an Express app on a free port. */
function startApp(app) {
  return new Promise((resolve, reject) => {
    const server = app.listen(0, '127.0.0.1');
    server.once('error', reject);
    server.once('listening', () => resolve({
      url: 'http://127.0.0.1:' + server.address().port,
      close: () => new Promise(r => { if (server.closeAllConnections) server.closeAllConnections(); server.close(() => r()); })
    }));
  });
}

/** One HTTP request; `body` is sent as JSON unless null/undefined. Resolves to { status, body, text, headers }. */
async function http(method, url, body, headers) {
  const opts = { method, headers: Object.assign({}, headers) };
  if (body !== undefined && body !== null) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
  const r = await fetch(url, opts);
  const text = await r.text();
  let parsed = null;
  try { parsed = text ? JSON.parse(text) : null; } catch (e) { parsed = null; }
  return { status: r.status, body: parsed, text, headers: r.headers };
}

const basic = (user, pass) => ({ Authorization: 'Basic ' + Buffer.from(user + ':' + pass).toString('base64') });
const bearer = dev => ({ Authorization: 'Bearer ' + dev.deviceId + '.' + dev.secret });

/** A path inside a fresh temp folder (the file itself is not created). */
function tmpFile(name) {
  return path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'menkerud-test-')), name);
}

function testConfig(env) {
  return loadConfig(Object.assign({ DEVICE_KEY: 'k', DISCORD_MOR_USER_ID: '111', DISCORD_FAR_USER_ID: '222', ADMIN_PASSWORD: 'pw' }, env));
}

function fakeLivekit() {
  const lk = {
    WS_URL: 'wss://lk.test',
    configured: () => true,
    joinToken: async ({ room, identity }) => 'tok:' + room + ':' + identity,
    deleteRoom: async room => { lk.deleted.push(room); },
    participantCount: async () => lk.state.count,
    deleted: [],
    state: { count: 2 }
  };
  return lk;
}

function fakeDiscord() {
  const d = {
    configured: () => true,
    dm: async (userId, content) => { d.dms.push({ userId, content }); return { channelId: 'ch', messageId: 'm' + d.dms.length }; },
    edit: async (ref, content) => { d.edits.push({ ref, content }); },
    channelMessages: async () => [],
    react: async () => {},
    dms: [],
    edits: []
  };
  return d;
}

/** A hand-cranked clock. advance(ms) runs the timers that fall due, in time order, letting async work settle between them. */
function fakeTimers(start) {
  let t = start || 1_800_000_000_000, seq = 0;
  const jobs = new Map();
  const add = (fn, ms, every) => { const id = ++seq; jobs.set(id, { at: t + Math.max(0, Number(ms) || 0), fn, every }); return id; };
  const settle = () => new Promise(r => setImmediate(r));
  return {
    now: () => t,
    setTimeout: (fn, ms) => add(fn, ms, 0),
    setInterval: (fn, ms) => add(fn, ms, Math.max(1, Number(ms) || 1)),
    clearTimeout: id => { jobs.delete(id); },
    clearInterval: id => { jobs.delete(id); },
    async advance(ms) {
      const end = t + ms;
      for (;;) {
        let nextId = null, next = null;
        for (const [id, j] of jobs) if (j.at <= end && (!next || j.at < next.at || (j.at === next.at && id < nextId))) { nextId = id; next = j; }
        if (!next) break;
        t = next.at;
        if (next.every) next.at += next.every; else jobs.delete(nextId);
        next.fn();
        await settle();
      }
      t = end;
      await settle();
    }
  };
}

/** Build the app the way server.js does, with fakes for everything outside the process. */
function wire(env, deps) {
  deps = deps || {};
  const config = testConfig(env);
  const lk = deps.livekit || fakeLivekit();
  const discord = deps.discord || fakeDiscord();
  const now = deps.now || Date.now;
  const devices = deps.devices || createDevices({ file: tmpFile('devices.json'), now, log() {} });
  const app = createApp({ config, livekit: lk, discord, devices, log() {} });
  return { config, lk, discord, devices, app };
}

module.exports = { wait, startApp, http, basic, bearer, tmpFile, testConfig, fakeLivekit, fakeDiscord, fakeTimers, wire };
