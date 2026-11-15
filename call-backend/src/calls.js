// The call manager: every call's state, who is told what, and cleaning up afterwards. No HTTP in here.
//
// Two directions:
//   home_to_parent  a child pressed «Ring far» on the kiosk. The parent is reached through the app when they have
//                   a paired phone with a live push address, otherwise through the Discord link as before.
//   parent_to_home  a parent pressed «Ring hjem» in the app; the kiosk is told over its event stream.
//
// States: ringing -> connected -> ended, or ringing -> declined | missed | cancelled | unreachable (a call home that
// the kiosk never confirmed). A finished call is kept for a
// minute so that late requests get a clean answer, then forgotten. Calls live in memory: a restart drops them.
'use strict';
const crypto = require('crypto');
const { callData, callEndData, callEndTtl } = require('./push');

const FORGET_MS = 60 * 1000;         // how long a finished call is remembered
const WATCH_MS = 15 * 1000;          // how often answered calls are checked
const JOIN_GRACE_MS = 45 * 1000;     // after an answer, give both sides this long to actually arrive in the room
const SEEN_MS = 8 * 1000;            // a call to the kiosk that the kiosk has not confirmed by then did not reach it
const STARTS_PER_MINUTE = 10;
const KIOSK_IDENTITY = 'tablet-stue';

class CallError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

const sha256 = s => crypto.createHash('sha256').update(s).digest('hex');

function createCalls({ config, livekit, devices, push, discord, hub, timers, now = Date.now, log = () => {} }) {
  const T = timers || { setTimeout, clearTimeout, setInterval, clearInterval };
  const calls = new Map();
  let starts = [];

  const hasActive = () => [...calls.values()].some(c => !c.final);
  const lkInfo = token => ({ url: livekit.WS_URL, token });

  /** One call at a time, and not more than ten starts a minute – for both directions. */
  function claimStart(busyText) {
    const t = now();
    starts = starts.filter(x => t - x < 60000);
    if (starts.length >= STARTS_PER_MINUTE) throw new CallError(429, 'for mange forsøk');
    if (hasActive()) throw new CallError(409, busyText);
    starts.push(t);
  }

  function newCall(target, direction) {
    const id = 'c_' + crypto.randomBytes(9).toString('hex');
    const call = { id, room: 'family-' + id, target, direction, status: 'ringing', final: false, via: null, createdAt: now(), expiresAt: now() + config.ringSec * 1000, lowChecks: 0 };
    calls.set(id, call);
    // The ring time runs from the first moment: whatever happens next, a ringing call cannot outlive it.
    call.timer = T.setTimeout(() => missed(call), config.ringSec * 1000);
    return call;
  }

  /** A call that never got going: forget it, so that it cannot block the next one. */
  function drop(call) {
    call.final = true;
    T.clearTimeout(call.timer);
    T.clearTimeout(call.seenTimer);
    calls.delete(call.id);
  }

  /** Tell the parent's phones (or the Discord message) how a kids-call-a-parent call ended. */
  function tellParentItIsOver(call, status, exceptDeviceId) {
    if (call.direction !== 'home_to_parent') return;
    if (call.via === 'discord') {
      const text = status === 'missed' ? '🔴 Ubesvart anrop hjemmefra.' : status === 'cancelled' ? '⚪ Anropet ble avbrutt hjemmefra.' : status === 'ended' ? '✅ Samtalen er avsluttet.' : null;
      if (text) discord.edit(call.discord, text);
      return;
    }
    if (status === 'ended') return;      // the phone that was in the call saw it end; the others were already told
    const data = callEndData({ callId: call.id, reason: status, at: now() });
    for (const d of devices.reachableFor(call.target)) {
      if (d.deviceId !== exceptDeviceId) push.send(d, data, { ttlSec: callEndTtl(status) });
    }
  }

  function finish(call, status, exceptDeviceId) {
    if (call.final) return;
    call.final = true;
    call.status = status;
    call.endedAt = now();
    T.clearTimeout(call.timer);
    T.clearTimeout(call.seenTimer);
    livekit.deleteRoom(call.room);       // puts everyone still in the room out of it
    tellParentItIsOver(call, status, exceptDeviceId);
    hub.send({ type: 'ended', callId: call.id });
    log('call', call.id, '->', status);
    T.setTimeout(() => calls.delete(call.id), FORGET_MS);
  }

  function missed(call) { if (!call.final && call.status === 'ringing') finish(call, 'missed'); }

  function markConnected(call) {
    call.status = 'connected';
    call.connectedAt = now();
    call.lowChecks = 0;
    T.clearTimeout(call.timer);
    log('call', call.id, 'connected');
  }

  // An answered call normally ends when either side says so. If nobody does (the kiosk lost power, a tab was
  // closed), the room empties without us hearing about it – and an unfinished call would block every later one.
  // So: look at the rooms of answered calls now and then.
  async function watch() {
    for (const call of [...calls.values()]) {
      if (call.final || call.status !== 'connected') continue;
      const age = now() - call.connectedAt;
      if (age > config.maxCallSec * 1000) { finish(call, 'ended'); continue; }
      if (age < JOIN_GRACE_MS) continue;
      const n = await livekit.participantCount(call.room);
      if (call.final || call.status !== 'connected') continue;   // it changed while we asked
      if (n === null) continue;                                  // LiveKit did not answer: never end a call on that
      if (n < 2) { if (++call.lowChecks >= 2) finish(call, 'ended'); } else call.lowChecks = 0;
    }
  }
  const watchTimer = T.setInterval(() => { watch().catch(e => log('watchdog error', e.message)); }, WATCH_MS);
  if (watchTimer && watchTimer.unref) watchTimer.unref();

  // ---------- kids call a parent ----------

  async function startFromHome(target) {
    const p = config.parents[target];
    if (!p) throw new CallError(400, 'ukjent mottaker');
    if (!livekit.configured()) throw new CallError(500, 'LiveKit ikke konfigurert');
    claimStart('en samtale pågår allerede');
    const call = newCall(target, 'home_to_parent');
    // Whatever goes wrong from here – a refusal of ours or something nobody foresaw – the line must be free again.
    try { return await notifyParent(call, p); }
    catch (e) { drop(call); throw e; }
  }

  async function notifyParent(call, p) {
    let kioskToken;
    try { kioskToken = await livekit.joinToken({ room: call.room, identity: KIOSK_IDENTITY, name: 'Hjemme', ttl: config.tokenTtlSec }); }
    catch (e) { log('token error', e.message); throw new CallError(500, 'kunne ikke starte samtalen'); }

    // The app, if this parent has a phone we can reach …
    const phones = devices.reachableFor(call.target);
    let delivered = 0;
    if (phones.length) {
      const data = callData({ callId: call.id, target: call.target, expiresAt: call.expiresAt });
      const results = await Promise.all(phones.map(d => push.send(d, data, { ttlSec: config.ringSec })));
      results.forEach((r, i) => {
        if (r && r.ok) delivered++;
        else if (r && r.dead) {
          // That phone no longer counts as paired. If that cannot be written down (a full disk), the call goes on.
          try { devices.markPushDead(phones[i].deviceId); } catch (e) { log('could not record a dead push address:', e.message); }
        }
      });
    }
    if (delivered) call.via = 'app';
    else {
      // … otherwise the Discord message with a link, exactly as before the app existed.
      if (!p.discordUserId) throw new CallError(500, 'mottaker ikke konfigurert');
      const answerToken = crypto.randomBytes(32).toString('hex');
      call.answerHash = sha256(answerToken);
      const link = config.publicUrl + '/answer/' + answerToken;
      try {
        call.discord = await discord.dm(p.discordUserId, '🏠 **Det ringer hjemmefra**\n\nBarna vil snakke med deg.\n\n📞 Trykk for å svare:\n' + link + '\n\n💡 Åpne lenken i **Chrome** – nettleseren inni Discord får ikke bruke kamera og mikrofon.\nAnropet utløper om ' + config.ringSec + ' sekunder.');
      } catch (e) {
        log('discord error', e.message);
        throw new CallError(502, 'kunne ikke varsle ' + p.label);
      }
      call.via = 'discord';
    }

    log('call', call.id, 'ringing', call.target, 'via', call.via);
    return { callId: call.id, status: 'ringing', livekit: lkInfo(kioskToken), expiresAt: call.expiresAt, via: call.via };
  }

  /** The parent opened the link from the Discord message (the path used until both parents are on the app). */
  async function answerByLink(answerToken) {
    const token = String(answerToken || '');
    if (!token) throw new CallError(400, 'mangler token');
    const hash = sha256(token);
    const call = [...calls.values()].find(c => c.answerHash === hash);
    if (!call) throw new CallError(404, 'Dette anropet finnes ikke.');
    if (call.final) throw new CallError(410, 'Dette anropet er avsluttet.');
    if (now() > call.expiresAt) { missed(call); throw new CallError(410, 'Dette anropet er utløpt.'); }

    const p = config.parents[call.target];
    let parentToken;
    try { parentToken = await livekit.joinToken({ room: call.room, identity: p.identity, name: p.label, ttl: config.tokenTtlSec }); }
    catch (e) { throw new CallError(500, 'kunne ikke koble til'); }
    if (call.final) throw new CallError(410, 'Dette anropet er avsluttet.');

    if (call.status === 'ringing') {
      markConnected(call);
      discord.edit(call.discord, '🟢 Samtale besvart.');
    }
    return { callId: call.id, status: call.status, target: call.target, livekit: lkInfo(parentToken) };
  }

  /** Who may answer or decline this ringing call from the app – or throw the right refusal. */
  function ringingForParent(callId, device) {
    const call = calls.get(callId);
    if (!call || call.direction !== 'home_to_parent') throw new CallError(404, 'unknown');
    if (!device || device.who !== call.target) throw new CallError(403, 'wrong_parent');
    if (call.final) throw new CallError(410, 'over');
    if (call.status !== 'ringing') throw new CallError(409, 'answered');
    return call;
  }

  async function answerByParent(callId, device) {
    const call = ringingForParent(callId, device);
    const p = config.parents[call.target];
    let parentToken;
    try { parentToken = await livekit.joinToken({ room: call.room, identity: p.identity, name: p.label, ttl: config.tokenTtlSec }); }
    catch (e) { throw new CallError(500, 'kunne ikke koble til'); }
    ringingForParent(callId, device);    // again: another phone may have answered while the token was being made
    markConnected(call);
    const data = callEndData({ callId: call.id, reason: 'answered_elsewhere', at: now() });
    for (const d of devices.reachableFor(call.target)) {
      if (d.deviceId !== device.deviceId) push.send(d, data, { ttlSec: callEndTtl('answered_elsewhere') });
    }
    return { callId: call.id, livekit: lkInfo(parentToken) };
  }

  // ---------- a parent calls home ----------

  /** «Ring hjem» in the app. The parent waits in the room; the kiosk is told over its event stream. */
  async function startFromParent(device) {
    const p = device && config.parents[device.who];
    if (!p) throw new CallError(401, 'unauthorized');
    if (!livekit.configured()) throw new CallError(500, 'LiveKit ikke konfigurert');
    if (!hub.online()) throw new CallError(503, 'kiosk_offline');   // say so now, rather than ring into nothing
    claimStart('busy');
    const call = newCall(device.who, 'parent_to_home');
    try { return await notifyKiosk(call, p); }
    catch (e) { drop(call); throw e; }
  }

  async function notifyKiosk(call, p) {
    let parentToken;
    try { parentToken = await livekit.joinToken({ room: call.room, identity: p.identity, name: p.label, ttl: config.tokenTtlSec }); }
    catch (e) { log('token error', e.message); throw new CallError(500, 'kunne ikke starte samtalen'); }

    // The kiosk shows its «… ringer!» overlay. If it dropped off while the token was being made, nobody was told.
    if (!hub.send(incomingEvent(call))) throw new CallError(503, 'kiosk_offline');
    // … and it has to say that it does. A stream can look open long after the kiosk is gone (a network that died
    // without a word), so a call that is not confirmed within a few seconds counts as not having reached the screen.
    call.seenTimer = T.setTimeout(() => { if (!call.final && call.status === 'ringing' && !call.seen) finish(call, 'unreachable'); }, SEEN_MS);
    log('call', call.id, 'ringing home from', call.target);
    return { callId: call.id, status: 'ringing', livekit: lkInfo(parentToken), expiresAt: call.expiresAt };
  }

  const incomingEvent = call => ({ type: 'incoming', callId: call.id, from: call.target, expiresAt: call.expiresAt });

  /** The call that is ringing on the kiosk right now, as the event to send – or null. A kiosk that connects in the
      middle of a ring (a reload, a network blip) is told again through this. */
  function ringingHome() {
    const call = [...calls.values()].find(c => !c.final && c.direction === 'parent_to_home' && c.status === 'ringing');
    return call ? incomingEvent(call) : null;
  }

  /** The kiosk says: this call is ringing here. */
  function seen(callId) {
    const call = calls.get(callId);
    if (!call || call.direction !== 'parent_to_home') throw new CallError(404, 'unknown');
    if (!call.final) { call.seen = true; T.clearTimeout(call.seenTimer); }
    return { status: call.status };
  }

  /** The call the kiosk may answer or decline – or throw the right refusal. */
  function ringingForKiosk(callId) {
    const call = calls.get(callId);
    if (!call || call.direction !== 'parent_to_home') throw new CallError(404, 'unknown');
    if (call.final) throw new CallError(410, 'over');
    if (call.status !== 'ringing') throw new CallError(409, 'answered');
    return call;
  }

  async function answerByKiosk(callId) {
    const call = ringingForKiosk(callId);
    let kioskToken;
    try { kioskToken = await livekit.joinToken({ room: call.room, identity: KIOSK_IDENTITY, name: 'Hjemme', ttl: config.tokenTtlSec }); }
    catch (e) { throw new CallError(500, 'kunne ikke koble til'); }
    ringingForKiosk(callId);             // again: it may have been cancelled while the token was being made
    markConnected(call);
    return { callId: call.id, livekit: lkInfo(kioskToken) };
  }

  /** «Ikke nå»: by 'parent' (a phone, for a call from home) or by 'kiosk' (for a call from a parent). */
  function decline(callId, by, device) {
    if (by === 'kiosk') {
      finish(ringingForKiosk(callId), 'declined');     // the waiting parent's room closes; the app reads the outcome
      return { status: 'declined' };
    }
    const call = ringingForParent(callId, device);
    hub.send({ type: 'declined', callId: call.id });   // the kiosk shows «… kunne ikke svare nå»
    finish(call, 'declined', device.deviceId);         // the other phones stop ringing
    return { status: 'declined' };
  }

  // ---------- ending ----------

  function close(callId) {
    const call = calls.get(callId);
    if (!call) throw new CallError(404, 'ukjent');
    if (!call.final) {
      const ringing = call.status === 'ringing';
      if (ringing && call.direction === 'parent_to_home') hub.send({ type: 'cancelled', callId: call.id });   // the kiosk stops ringing
      finish(call, ringing ? 'cancelled' : 'ended');
    }
    return { status: call.status };
  }

  function get(callId) {
    const c = calls.get(callId);
    return c ? { callId: c.id, status: c.status, target: c.target, direction: c.direction } : null;
  }

  return { startFromHome, startFromParent, answerByLink, answerByParent, answerByKiosk, seen, ringingHome, decline, cancel: close, end: close, get, hasActive };
}

module.exports = { createCalls, CallError };
