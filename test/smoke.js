const { JSDOM, VirtualConsole } = require('jsdom');
const fs = require('fs');
const path = require('path');
const html = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
const errors = [];
function mkdom(beforeParse) {
  const vc = new VirtualConsole();
  vc.on('jsdomError', e => { if (!/Could not load|not implemented/i.test(e.message)) errors.push(e.message); });
  vc.on('error', m => errors.push(String(m)));
  const fetchCalls = [];
  const dom = new JSDOM(html, {
    runScripts: 'dangerously', pretendToBeVisual: true, url: 'http://localhost/menkerud/index.html', virtualConsole: vc,
    beforeParse(window) {
      window.fetch = (url, opts) => {
        fetchCalls.push({ url, opts });
        if (/open-meteo.*forecast/.test(url)) return Promise.resolve({ ok: true, json: async () => ({ current: { temperature_2m: 13.6, weather_code: 61, is_day: 1 } }) });
        if (/geocoding/.test(url)) return Promise.resolve({ ok: true, json: async () => ({ results: [{ name: 'Raufoss', admin1: 'Innlandet', country: 'Norge', latitude: 60.72, longitude: 10.61 }] }) });
        if (/\/api\/calls$/.test(url) && opts && opts.method === 'POST') return Promise.resolve({ ok: true, json: async () => ({ callId: 'c_test', status: 'ringing', livekit: { url: 'wss://rtc.test', token: 'tok' } }) });
        return Promise.resolve({ ok: true, status: 200, json: async () => ({}) });
      };
      window.HTMLMediaElement.prototype.play = () => Promise.resolve();
      window.HTMLCanvasElement.prototype.getContext = () => ({ clearRect(){}, save(){}, restore(){}, translate(){}, rotate(){}, fillRect(){}, drawImage(){} });
      // Minimal LiveKit client stub so the call path runs without the real CDN library.
      window.LivekitClient = {
        Room: class { constructor(){ this._h = {}; } on(ev, cb){ this._h[ev] = cb; return this; } async connect(){ } get localParticipant(){ return { enableCameraAndMicrophone: async () => {}, getTrackPublication: () => null }; } disconnect(){} },
        RoomEvent: { TrackSubscribed: 'TrackSubscribed', Disconnected: 'Disconnected' },
        Track: { Source: { Camera: 'camera' } }
      };
      if (beforeParse) beforeParse(window);
    }
  });
  return { dom, fetchCalls };
}
const assert = (c, m) => { if (!c) errors.push('ASSERT: ' + m); else console.log('ok -', m); };
const wait = ms => new Promise(r => setTimeout(r, ms));

(async () => {
  const { dom, fetchCalls } = mkdom();
  const w = dom.window, d = w.document;
  const q = s => d.querySelector(s), qa = s => [...d.querySelectorAll(s)];
  const click = el => el.dispatchEvent(new w.MouseEvent('click', { bubbles: true }));
  await wait(300);

  assert(qa('#notes .note').length === 5, 'sample notes stored locally and rendered');
  assert(qa('#today-list .srow-day').length === 2, 'I DAG lists the two timed sample notes: ' + qa('#today-list .srow-day').length);
  assert(q('#evening-panel').classList.contains('hidden') && q('#bottom-grid').classList.contains('no-evening'), 'KVELD panel hidden when Home Assistant not connected');
  assert(q('#wx-temp').textContent === '14°' && q('#wx-icon').textContent === '🌧️', 'Open-Meteo weather shown: ' + q('#wx-temp').textContent + ' ' + q('#wx-icon').textContent);
  assert(q('#status').classList.contains('off'), 'HA dot grey (not in use)');

  // PIN + menu
  click(q('#menu-btn'));
  ['1', '2', '3', '4'].forEach(k => click(qa('#pin-pad button').find(b => b.textContent === k)));
  assert(!q('#menu').classList.contains('hidden'), 'menu opens with PIN');

  // New note with docked keyboard
  click(q('.tile[data-pane="note"]'));
  const inp = q('#note-text');
  inp.focus();
  await wait(20);
  assert(!q('#kbd').classList.contains('hidden'), 'keyboard shows on focus');
  const key = t => qa('#kbd .key').find(b => b.textContent === t);
  assert(!!key('Q'), 'auto-shift on empty field');
  click(key('G')); click(key('y')); click(key('m'));
  assert(inp.value === 'Gym', 'typed with on-screen keyboard: ' + inp.value);
  click(key('?#/')); click(key('!'));
  assert(inp.value === 'Gym!', 'symbol layout works: ' + inp.value);
  click(q('#time-toggle'));
  click(qa('#stepper button').find(b => b.dataset.d === 'h+'));
  click(q('#note-save'));
  await wait(20);
  assert(qa('#notes .note').length === 6, 'local note added to board');
  assert(q('#toast').textContent.includes('hengt opp'), 'toast: ' + q('#toast').textContent);
  const last = qa('#notes .note')[5];
  assert(last.querySelector('.time').textContent === '18:00' && last.querySelector('.txt').textContent === 'Gym!', 'new note content');
  assert(JSON.parse(w.localStorage.getItem('menkerud.notes')).length === 6, 'notes persisted in localStorage');
  click(qa('#note-list .chip button')[0]);
  await wait(20);
  assert(qa('#notes .note').length === 5, 'note removed');

  // Calendar: add an event to today, see it under I DAG, then remove it
  click(q('#menu-back'));
  click(q('.tile[data-pane="calendar"]'));
  await wait(20);
  assert(qa('#cal-grid .cal-cell.today').length === 1, 'calendar shows today');
  q('#cal-text').value = 'Bursdag'; q('#cal-text').dispatchEvent(new w.Event('input', { bubbles: true }));
  click(q('#cal-add'));
  await wait(20);
  assert(qa('#cal-day-list .chip').length === 1, 'event added to the day list: ' + qa('#cal-day-list .chip').length);
  assert([...qa('#today-list .srow-day .n')].some(n => n.textContent === 'Bursdag'), 'calendar event shows under I DAG');
  assert(JSON.parse(w.localStorage.getItem('menkerud.calendar')).length === 1, 'event persisted in localStorage');
  click(qa('#cal-day-list .chip button')[0]);
  await wait(20);
  assert(qa('#cal-day-list .chip').length === 0 && ![...qa('#today-list .srow-day .n')].some(n => n.textContent === 'Bursdag'), 'event removed from calendar and I DAG');

  // Settings
  click(q('#menu-back'));
  click(q('.tile[data-pane="settings"]'));
  assert(qa('#status-grid .srow').length === 7, 'status rows: ' + qa('#status-grid .srow').length);
  assert(qa('#settings-form .frow').length > 15, 'form rows: ' + qa('#settings-form .frow').length);
  const inputFor = label => { const row = qa('#settings-form .frow').find(r => r.querySelector('.flabel').textContent.startsWith(label)); return row && row.querySelector('input, select, button'); };
  const setVal = (label, v) => { const i = inputFor(label); i.value = v; i.dispatchEvent(new w.Event('input', { bubbles: true })); };
  setVal('Mor heter', 'Mamma');
  setVal('Call-backend URL', 'https://call.example.no');
  assert(!!inputFor('Discord-webhook') && !inputFor('ntfy-server'), 'discord fields shown by default');
  { const s0 = inputFor('Hvordan varsle'); s0.value = 'ntfy'; s0.dispatchEvent(new w.Event('change', { bubbles: true })); }
  setVal('Emne for mors', 'menkerud-mor-test');
  setVal('Emne kiosken', 'menkerud-kiosk-test');
  // provider select rebuilds the form
  const sel = inputFor('Hvordan varsle');
  sel.value = 'webhook'; sel.dispatchEvent(new w.Event('change', { bubbles: true }));
  assert(!!inputFor('Webhook-adresse') && !inputFor('ntfy-server'), 'provider switch rebuilds form and keeps draft');
  assert(inputFor('Mor heter').value === 'Mamma', 'draft kept after rebuild');
  const sel2 = inputFor('Hvordan varsle'); sel2.value = 'ntfy'; sel2.dispatchEvent(new w.Event('change', { bubbles: true }));
  assert(inputFor('Emne for mors').value === 'menkerud-mor-test', 'ntfy topic kept');
  // geocode
  click(inputFor('Sted for været').parentElement.querySelector('button'));
  await wait(20);
  assert(fetchCalls.some(c => /geocoding/.test(c.url)), 'geocode called');
  // test push before save uses current cfg (discord, empty webhook) -> error toast
  click(q('#t-mor'));
  await wait(20);
  assert(q('#toast').textContent.includes('mangler'), 'test push before save reports missing webhook: ' + q('#toast').textContent);
  // save
  click(q('#s-save'));
  const saved = JSON.parse(w.localStorage.getItem('menkerud.settings'));
  assert(saved.people.mor.name === 'Mamma' && saved.call.backend === 'https://call.example.no' && saved.notify.ntfy.topicMor === 'menkerud-mor-test' && saved.weather.lat === 60.72, 'settings saved: ' + JSON.stringify(saved.call) + ' ' + saved.weather.lat);

  // Second load with saved settings applied
  const second = mkdom(win => { win.localStorage.setItem('menkerud.settings', JSON.stringify(saved)); });
  const w2 = second.dom.window, d2 = w2.document;
  const q2 = s => d2.querySelector(s);
  const click2 = el => el.dispatchEvent(new w2.MouseEvent('click', { bubbles: true }));
  await wait(300);
  assert(q2('[data-name="mor"]').textContent === 'MAMMA', 'saved name applied after reload: ' + q2('[data-name="mor"]').textContent);
  // Ring mamma -> LiveKit call: POST to the call backend + ringing overlay (no ntfy/discord push from the kiosk)
  click2(q2('#card-mor'));
  await wait(60);
  const startReq = second.fetchCalls.find(c => /^https:\/\/call\.example\.no\/api\/calls$/.test(c.url) && c.opts && c.opts.method === 'POST');
  assert(!!startReq, 'call backend POST /api/calls sent');
  if (startReq) assert(JSON.parse(startReq.opts.body).target === 'mor', 'backend told target=mor: ' + startReq.opts.body);
  assert(!second.fetchCalls.some(c => /ntfy\.sh/.test(c.url) || /discord\.com\/api\/webhooks/.test(c.url)), 'no kiosk push for an outbound call (backend handles it)');
  assert(!q2('#call').classList.contains('hidden') && q2('#call-status').textContent.startsWith('Ringer mamma'), 'call overlay: ' + q2('#call-status').textContent);
  click2(q2('#hangup'));
  assert(q2('#call').classList.contains('hidden'), 'hangup closes overlay');
  // Jeg er hjemme -> push to both parents via the notify provider (only mamma topic set -> one)
  second.fetchCalls.length = 0;
  click2(q2('#card-home'));
  await wait(50);
  const homePush = second.fetchCalls.filter(c => /ntfy\.sh\/$/.test(c.url));
  assert(homePush.length === 1 && JSON.parse(homePush[0].opts.body).priority === 3, 'home push sent once with normal priority');
  assert(q2('#celebrate-sub').textContent.includes('beskjed'), 'celebrate text: ' + q2('#celebrate-sub').textContent);

  // Third load: Discord provider, incoming "ring hjem" via fake gateway; outbound still goes to the backend
  const gwSent = [];
  const third = mkdom(win => {
    win.localStorage.setItem('menkerud.settings', JSON.stringify({ notify: { provider: 'discord', discord: { webhook: 'https://discord.com/api/webhooks/1/abc', userMor: '111', userFar: '222', botToken: 'Bot.token', ringChannel: '999' } } }));
    class FakeWS {
      constructor(url) { this.url = url; this.readyState = 1; FakeWS.last = this; setTimeout(() => { this.onmessage && this.onmessage({ data: JSON.stringify({ op: 10, d: { heartbeat_interval: 100000 } }) }); }, 5); }
      send(x) { gwSent.push(JSON.parse(x)); const m = JSON.parse(x); if (m.op === 2) setTimeout(() => this.onmessage({ data: JSON.stringify({ op: 0, t: 'READY', s: 1, d: {} }) }), 5); }
      close() { this.readyState = 3; this.onclose && this.onclose(); }
    }
    win.WebSocket = FakeWS;
  });
  const w3 = third.dom.window, d3 = w3.document;
  const q3 = s => d3.querySelector(s);
  const click3 = el => el.dispatchEvent(new w3.MouseEvent('click', { bubbles: true }));
  await wait(100);
  assert(gwSent.some(m => m.op === 2 && m.d.token === 'Bot.token' && m.d.intents === 513), 'discord identify sent');
  const gw = w3.WebSocket.last;
  gw.onmessage({ data: JSON.stringify({ op: 0, t: 'MESSAGE_CREATE', s: 2, d: { channel_id: '999', author: { id: '222', bot: false }, content: '' } }) });
  await wait(10);
  assert(!q3('#incoming').classList.contains('hidden') && q3('#in-name').textContent === 'Far ringer!', 'discord message from far rings kiosk: ' + q3('#in-name').textContent);
  click3(q3('#decline'));
  gw.onmessage({ data: JSON.stringify({ op: 0, t: 'MESSAGE_CREATE', s: 3, d: { channel_id: '999', author: { id: '5', bot: false }, webhook_id: '77', content: 'x' } }) });
  await wait(10);
  assert(q3('#incoming').classList.contains('hidden'), 'webhook messages ignored');
  // Outbound call still goes to the LiveKit backend (default URL), not to a Discord webhook
  third.fetchCalls.length = 0;
  click3(q3('#card-mor'));
  await wait(60);
  const startReq3 = third.fetchCalls.find(c => /\/api\/calls$/.test(c.url) && c.opts && c.opts.method === 'POST');
  assert(!!startReq3, 'outbound call posts to the backend: ' + (startReq3 && startReq3.url));
  assert(!third.fetchCalls.some(c => /discord\.com\/api\/webhooks/.test(c.url)), 'outbound call does not hit the Discord webhook');
  click3(q3('#hangup'));

  console.log(errors.length ? 'ERRORS:\n' + errors.join('\n') : 'ALL GOOD');
  process.exit(errors.length ? 1 : 0);
})().catch(e => { console.log('EXC', e); process.exit(1); });
