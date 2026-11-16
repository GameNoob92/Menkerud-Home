// Unit tests: the call manager (src/calls.js) – real device store, fake LiveKit / Discord / push / kiosk hub and a
// hand-cranked clock. Each block starts from a fresh wire().
'use strict';
const assert = require('assert');
const { wire } = require('./helpers');

(async () => {
  // ---------- kids call a parent ----------
  { const x = wire(), far = x.phone('far');
    const s = await x.calls.startFromHome('far');
    assert.strictEqual(s.via, 'app');
    assert.strictEqual(s.status, 'ringing');
    assert.strictEqual(s.livekit.token, 'tok:family-' + s.callId + ':tablet-stue');
    assert.strictEqual(x.discord.dms.length, 0);
    assert.deepStrictEqual(x.push.sent[0].data, { type: 'call', v: '1', callId: s.callId, caller: 'home', who: 'far', expiresAt: String(s.expiresAt) });
    assert.strictEqual(x.push.sent[0].opts.ttlSec, 60);
    const a = await x.calls.answerByParent(s.callId, far);
    assert.strictEqual(a.livekit.token, 'tok:family-' + s.callId + ':parent-far');
    assert.strictEqual(x.calls.get(s.callId).status, 'connected');
    console.log('ok - a parent with a paired phone is rung through the app and answers there'); }

  { const x = wire();
    const s = await x.calls.startFromHome('mor');
    assert.strictEqual(s.via, 'discord');
    assert.strictEqual(x.discord.dms.length, 1);
    assert.strictEqual(x.push.sent.length, 0);
    const link = x.discord.dms[0].content.match(/\/answer\/([0-9a-f]{64})/)[1];
    assert.strictEqual((await x.calls.answerByLink(link)).status, 'connected');
    console.log('ok - a parent without a phone gets the Discord link exactly as before'); }

  { const x = wire(); x.phone('far');
    x.push.results.push({ ok: false, dead: true, error: 'UNREGISTERED' });
    const s = await x.calls.startFromHome('far');
    assert.strictEqual(s.via, 'discord');
    assert.strictEqual(x.discord.dms.length, 1);
    assert.strictEqual(x.devices.reachableFor('far').length, 0);
    console.log('ok - a phone whose push address is dead falls back to the Discord link and stops counting as paired'); }

  { const x = wire(); x.phone('far');
    x.push.results.push({ ok: false, dead: false, error: 'UNAVAILABLE' });
    const s = await x.calls.startFromHome('far');
    assert.strictEqual(s.via, 'discord');
    assert.strictEqual(x.devices.reachableFor('far').length, 1);
    console.log('ok - when the push service is down the call still goes out by Discord, and the phone stays paired'); }

  { const x = wire(), far = x.phone('far');
    const s = await x.calls.startFromHome('far');
    assert.deepStrictEqual(x.calls.decline(s.callId, 'parent', far), { status: 'declined' });
    assert.ok(x.hub.sent.some(e => e.type === 'declined' && e.callId === s.callId));
    assert.strictEqual(x.calls.get(s.callId).status, 'declined');
    assert.strictEqual(x.calls.hasActive(), false);
    assert.deepStrictEqual(x.lk.deleted, ['family-' + s.callId]);
    console.log('ok - «Ikke nå» ends the call at once and tells the kiosk'); }

  { const x = wire(), a = x.phone('far'), b = x.phone('far');
    const s = await x.calls.startFromHome('far');
    assert.strictEqual(x.push.sent.filter(p => p.data.type === 'call').length, 2);
    await x.calls.answerByParent(s.callId, a);
    await assert.rejects(x.calls.answerByParent(s.callId, b), e => e.status === 409 && e.message === 'answered');
    assert.ok(x.push.sent.some(p => p.device.deviceId === b.deviceId && p.data.type === 'call_end' && p.data.reason === 'answered_elsewhere'));
    assert.ok(!x.push.sent.some(p => p.device.deviceId === a.deviceId && p.data.type === 'call_end'));
    console.log('ok - every phone of the parent rings; the first to answer wins and the other is told to stop'); }

  { const x = wire(), far = x.phone('far'), mor = x.phone('mor');
    const s = await x.calls.startFromHome('far');
    await assert.rejects(x.calls.answerByParent(s.callId, mor), e => e.status === 403 && e.message === 'wrong_parent');
    await assert.rejects(x.calls.answerByParent('c_nope', far), e => e.status === 404 && e.message === 'unknown');
    assert.throws(() => x.calls.decline(s.callId, 'parent', mor), e => e.status === 403);
    assert.strictEqual(x.calls.get(s.callId).status, 'ringing');
    console.log('ok - only the parent who is being called can answer or decline; an unknown call is a clean 404'); }

  { const x = wire(), far = x.phone('far');
    const s = await x.calls.startFromHome('far');
    await x.timers.advance(60000);
    assert.strictEqual(x.calls.get(s.callId).status, 'missed');
    const end = x.push.sent.find(p => p.data.type === 'call_end');
    assert.strictEqual(end.data.reason, 'missed');
    assert.strictEqual(end.opts.ttlSec, 21600);
    await assert.rejects(x.calls.answerByParent(s.callId, far), e => e.status === 410 && e.message === 'over');
    console.log('ok - nobody answers: the call is missed, the phone gets a lasting notice, a late answer is a clean 410'); }

  { const x = wire(); x.phone('far');
    const s = await x.calls.startFromHome('far');
    assert.deepStrictEqual(x.calls.end(s.callId), { status: 'cancelled' });
    assert.strictEqual(x.calls.get(s.callId).status, 'cancelled');
    const end = x.push.sent.find(p => p.data.type === 'call_end');
    assert.strictEqual(end.data.reason, 'cancelled');
    assert.strictEqual(end.opts.ttlSec, 21600);
    console.log('ok - the child hanging up while it rings stops the phone and leaves a missed-call notice'); }

  // ---------- clean-up of calls nobody reported as ended ----------
  { const x = wire(), far = x.phone('far');
    const s = await x.calls.startFromHome('far');
    await x.calls.answerByParent(s.callId, far);
    x.lk.state.count = null;
    await x.timers.advance(120000);
    assert.strictEqual(x.calls.hasActive(), true);
    console.log('ok - a LiveKit admin API that does not answer never ends a call');
    x.lk.state.count = 1;
    await x.timers.advance(15000);
    assert.strictEqual(x.calls.hasActive(), true);
    await x.timers.advance(15000);
    assert.strictEqual(x.calls.hasActive(), false);
    assert.strictEqual(x.calls.get(s.callId).status, 'ended');
    assert.strictEqual((await x.calls.startFromHome('far')).status, 'ringing');
    console.log('ok - an answered call whose room has emptied is ended by the watchdog, and the line is free again'); }

  { const x = wire(), far = x.phone('far');
    const s = await x.calls.startFromHome('far');
    await x.calls.answerByParent(s.callId, far);
    x.lk.state.count = 1;                       // the parent's app is still joining
    await x.timers.advance(30000);
    assert.strictEqual(x.calls.hasActive(), true);
    x.lk.state.count = 2;
    await x.timers.advance(60000);
    assert.strictEqual(x.calls.hasActive(), true);
    console.log('ok - a slow joiner is not cut off: the watchdog waits out the first 45 seconds'); }

  { const x = wire({ CALL_MAX_SECONDS: '120' }), far = x.phone('far');
    const s = await x.calls.startFromHome('far');
    await x.calls.answerByParent(s.callId, far);
    await x.timers.advance(135000);
    assert.strictEqual(x.calls.hasActive(), false);
    console.log('ok - a call is ended at the maximum length whatever happens'); }

  // ---------- starting ----------
  { const x = wire();
    await assert.rejects(x.calls.startFromHome('bestemor'), e => e.status === 400);
    await x.calls.startFromHome('far');
    await assert.rejects(x.calls.startFromHome('mor'), e => e.status === 409);
    console.log('ok - an unknown target is refused; one call at a time'); }

  { const x = wire();
    for (let i = 0; i < 10; i++) { const s = await x.calls.startFromHome('far'); x.calls.end(s.callId); }
    await assert.rejects(x.calls.startFromHome('far'), e => e.status === 429);
    await x.timers.advance(61000);
    assert.strictEqual((await x.calls.startFromHome('far')).status, 'ringing');
    console.log('ok - more than ten starts a minute are refused, then allowed again'); }

  // ---------- a parent calls home ----------
  { const x = wire(), far = x.phone('far');
    x.hub.state.online = false;
    await assert.rejects(x.calls.startFromParent(far), e => e.status === 503 && e.message === 'kiosk_offline');
    assert.strictEqual(x.calls.hasActive(), false);
    x.hub.state.online = true;
    assert.strictEqual((await x.calls.startFromParent(far)).status, 'ringing');
    console.log('ok - with no kiosk connected the parent is told so at once instead of ringing into nothing'); }

  { const x = wire(), far = x.phone('far');
    const s = await x.calls.startFromParent(far);
    assert.strictEqual(s.livekit.token, 'tok:family-' + s.callId + ':parent-far');
    assert.deepStrictEqual(x.hub.sent[0], { type: 'incoming', callId: s.callId, from: 'far', expiresAt: s.expiresAt });
    assert.strictEqual(x.push.sent.length, 0);
    assert.strictEqual(x.discord.dms.length, 0);
    await assert.rejects(x.calls.startFromHome('mor'), e => e.status === 409);
    const k = await x.calls.answerByKiosk(s.callId);
    assert.strictEqual(k.livekit.token, 'tok:family-' + s.callId + ':tablet-stue');
    assert.deepStrictEqual(x.calls.get(s.callId), { callId: s.callId, status: 'connected', target: 'far', direction: 'parent_to_home' });
    await assert.rejects(x.calls.answerByKiosk(s.callId), e => e.status === 409);
    console.log('ok - a parent rings home: the kiosk is told who calls, answers, and both get tokens for the same room'); }

  { const x = wire(), far = x.phone('far'), mor = x.phone('mor');
    await x.calls.startFromParent(far);
    await assert.rejects(x.calls.startFromParent(mor), e => e.status === 409 && e.message === 'busy');
    console.log('ok - a second parent calling while it rings is told the kiosk is busy'); }

  { const x = wire(), far = x.phone('far');
    const s = await x.calls.startFromParent(far);
    assert.deepStrictEqual(x.calls.decline(s.callId, 'kiosk'), { status: 'declined' });
    assert.strictEqual(x.calls.get(s.callId).status, 'declined');
    assert.deepStrictEqual(x.lk.deleted, ['family-' + s.callId]);
    assert.strictEqual(x.push.sent.length, 0);
    console.log('ok - «Ikke nå» on the kiosk ends the parent\'s call as declined'); }

  { const x = wire(), far = x.phone('far');
    const s = await x.calls.startFromParent(far);
    assert.deepStrictEqual(x.calls.cancel(s.callId), { status: 'cancelled' });
    assert.ok(x.hub.sent.some(e => e.type === 'cancelled' && e.callId === s.callId));
    console.log('ok - the parent giving up tells the kiosk to stop ringing'); }

  { const x = wire(), far = x.phone('far');
    const s = await x.calls.startFromParent(far);
    x.calls.seen(s.callId);                                                // the kiosk says it is ringing there
    await x.timers.advance(60000);
    assert.strictEqual(x.calls.get(s.callId).status, 'missed');
    await assert.rejects(x.calls.answerByKiosk(s.callId), e => e.status === 410);
    console.log('ok - nobody at home answers: the call is missed after the ring time'); }

  { const x = wire(), far = x.phone('far');
    const home = await x.calls.startFromHome('far');
    await assert.rejects(x.calls.answerByKiosk(home.callId), e => e.status === 404);            // the kiosk cannot answer its own call
    assert.throws(() => x.calls.decline(home.callId, 'kiosk'), e => e.status === 404);
    x.calls.end(home.callId);
    const out = await x.calls.startFromParent(far);
    await assert.rejects(x.calls.answerByParent(out.callId, far), e => e.status === 404);     // nor a parent theirs
    console.log('ok - each side can only answer or decline a call that was made to it'); }

  // ---------- a start that goes wrong must never hold the line ----------
  { const x = wire(); x.phone('far');
    x.devices.markPushDead = () => { throw new Error('ENOSPC: no space left on device'); };   // the data folder cannot be written
    x.push.results.push({ ok: false, dead: true, error: 'UNREGISTERED' });
    const s = await x.calls.startFromHome('far');
    assert.strictEqual(s.via, 'discord');
    x.calls.end(s.callId);
    assert.strictEqual(x.calls.hasActive(), false);
    console.log('ok - a phone list that cannot be written does not keep a call from going out'); }

  { const x = wire(), far = x.phone('far');
    const send = x.push.send; let once = true;
    x.push.send = async (...a) => { if (once) { once = false; throw new Error('boom'); } return send(...a); };
    await assert.rejects(x.calls.startFromHome('far'), /boom/);
    assert.strictEqual(x.calls.hasActive(), false);
    const ok = await x.calls.startFromHome('far');
    assert.strictEqual(ok.status, 'ringing');
    x.calls.end(ok.callId);
    const hubSend = x.hub.send; let hubOnce = true;
    x.hub.send = e => { if (hubOnce && e.type === 'incoming') { hubOnce = false; throw new Error('boom'); } return hubSend(e); };
    await assert.rejects(x.calls.startFromParent(far), /boom/);
    assert.strictEqual(x.calls.hasActive(), false);
    assert.strictEqual((await x.calls.startFromParent(far)).status, 'ringing');
    console.log('ok - an error nobody foresaw while a call starts leaves the line free for the next one'); }

  { const x = wire(), far = x.phone('far');
    x.hub.send = e => { x.hub.sent.push(e); return 0; };                     // the kiosk dropped off while the ticket was being made
    await assert.rejects(x.calls.startFromParent(far), e => e.status === 503 && e.message === 'kiosk_offline');
    assert.strictEqual(x.calls.hasActive(), false);
    console.log('ok - an «incoming» that reaches no kiosk is answered as not connected, and holds nothing'); }

  // ---------- two phones ----------
  { const x = wire(), a = x.phone('far'), b = x.phone('far');
    const s = await x.calls.startFromHome('far');
    x.calls.decline(s.callId, 'parent', a);
    assert.ok(x.push.sent.some(p => p.device.deviceId === b.deviceId && p.data.type === 'call_end' && p.data.reason === 'declined'));
    assert.ok(!x.push.sent.some(p => p.device.deviceId === a.deviceId && p.data.type === 'call_end'));
    console.log('ok - «Ikke nå» on one phone stops the parent\'s other phone ringing'); }

  { const x = wire(), a = x.phone('far'), b = x.phone('far');
    const s = await x.calls.startFromHome('far');
    let open; const gate = new Promise(r => { open = r; });
    const tok = x.lk.joinToken;
    x.lk.joinToken = async o => { await gate; return tok(o); };              // both phones are past the first check while the tickets are made
    const both = [a, b].map(p => x.calls.answerByParent(s.callId, p).then(() => 'in', e => e.status));
    open();
    assert.deepStrictEqual((await Promise.all(both)).sort(), [409, 'in']);
    console.log('ok - two phones answering in the same instant: one gets in, the other is told it was answered'); }

  // ---------- the kiosk has to say that a call reached it ----------
  { const x = wire(), far = x.phone('far');
    const s = await x.calls.startFromParent(far);
    await x.timers.advance(7900);
    assert.strictEqual(x.calls.get(s.callId).status, 'ringing');
    await x.timers.advance(200);
    assert.strictEqual(x.calls.get(s.callId).status, 'unreachable');
    assert.deepStrictEqual(x.lk.deleted, ['family-' + s.callId]);
    assert.strictEqual(x.calls.hasActive(), false);
    console.log('ok - a call the kiosk never confirms ends as «unreachable» after 8 seconds instead of ringing into nothing'); }

  { const x = wire(), far = x.phone('far');
    const s = await x.calls.startFromParent(far);
    assert.deepStrictEqual(x.calls.seen(s.callId), { status: 'ringing' });
    await x.timers.advance(30000);
    assert.strictEqual(x.calls.get(s.callId).status, 'ringing');
    await x.timers.advance(30000);
    assert.strictEqual(x.calls.get(s.callId).status, 'missed');
    assert.deepStrictEqual(x.calls.seen(s.callId), { status: 'missed' });
    assert.throws(() => x.calls.seen('c_nope'), e => e.status === 404);
    console.log('ok - a confirmed call rings its full time; confirming a finished call just says how it ended'); }

  { const x = wire(), far = x.phone('far');
    const s = await x.calls.startFromParent(far);
    await x.calls.answerByKiosk(s.callId);                                 // «Svar» before any confirmation got through
    await x.timers.advance(20000);
    assert.strictEqual(x.calls.get(s.callId).status, 'connected');
    x.calls.end(s.callId);
    const home = await x.calls.startFromHome('far');
    assert.throws(() => x.calls.seen(home.callId), e => e.status === 404);   // the kiosk confirms calls made to it, not its own
    console.log('ok - answering counts as confirming'); }

  { const x = wire(), far = x.phone('far');
    assert.strictEqual(x.calls.ringingHome(), null);
    const s = await x.calls.startFromParent(far);
    assert.deepStrictEqual(x.calls.ringingHome(), { type: 'incoming', callId: s.callId, from: 'far', expiresAt: s.expiresAt });
    await x.calls.answerByKiosk(s.callId);
    assert.strictEqual(x.calls.ringingHome(), null);
    console.log('ok - the call manager can say which call is ringing at home right now'); }

  console.log('ALL GOOD');
  process.exit(0);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
