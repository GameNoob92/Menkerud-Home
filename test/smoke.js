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
        // Bilderamme: photos/ has no manifest → folder walk (JSON autoindex, one subfolder, share clutter); bilder/ has a manifest
        if (/^photos\/index\.json$/.test(url)) return Promise.resolve({ ok: false, status: 404, json: async () => ({}) });
        if (/^photos\/$/.test(url)) return Promise.resolve({ ok: true, status: 200, headers: { get: () => 'application/json' }, json: async () => [{ name: '2025', type: 'directory' }, { name: '@eaDir', type: 'directory' }, { name: 'a.jpg', type: 'file' }, { name: 'Thumbs.db', type: 'file' }, { name: '._b.jpg', type: 'file' }] });
        if (/^photos\/2025\/$/.test(url)) return Promise.resolve({ ok: true, status: 200, headers: { get: () => 'application/json' }, json: async () => [{ name: 'b.jpg', type: 'file' }, { name: 'notes.txt', type: 'file' }] });
        if (/^bilder\/index\.json$/.test(url)) return Promise.resolve({ ok: true, json: async () => ['c.jpg', 'tur/d.jpg'] });
        if (/geocoding/.test(url)) return Promise.resolve({ ok: true, json: async () => ({ results: [{ name: 'Raufoss', admin1: 'Innlandet', country: 'Norge', latitude: 60.72, longitude: 10.61 }] }) });
        if (/\/api\/calls$/.test(url) && opts && opts.method === 'POST') return Promise.resolve({ ok: true, json: async () => ({ callId: 'c_test', status: 'ringing', livekit: { url: 'wss://rtc.test', token: 'tok' } }) });
        // kiosk screen helper: version + update (the page's «Hent oppdatering» button)
        if (/:7777\/version$/.test(url)) return Promise.resolve({ ok: true, status: 200, json: async () => ({ head: '000b6d4', date: '2026-09-11T21:12:00+02:00', subject: 'feat(menu)' }) });
        if (/:7777\/update$/.test(url) && opts && opts.method === 'POST') return Promise.resolve({ ok: true, status: 200, json: async () => ({ ok: true, before: 'a7e0907', after: '000b6d4', changed: true, helper_restart: false }) });
        return Promise.resolve({ ok: true, status: 200, json: async () => ({}) });
      };
      // Deterministic clock (12:00 on a fixed date) so night dimming and the screen-off window never depend on when the test runs.
      const FIXED = new window.Date(2026, 8, 10, 12, 0, 0).getTime(), RealDate = window.Date;
      window.Date = class extends RealDate { constructor(...a) { if (a.length) super(...a); else super(FIXED); } static now() { return FIXED; } };
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

  // Skjerm av: black overlay + the kiosk screen helper is asked to switch the display off; a touch brings both back
  const helperCalls = () => fetchCalls.filter(c => /^http:\/\/127\.0\.0\.1:7777\//.test(c.url)).map(c => c.url.replace(/^.*:7777/, ''));
  assert(helperCalls().join() === '/on', 'helper asked for display on at start: ' + helperCalls().join());
  click(q('#rest-btn'));
  await wait(20);
  assert(!q('#night').classList.contains('hidden') && q('#night').classList.contains('dark'), 'Skjerm av shows the black overlay');
  assert(helperCalls().slice(-1)[0] === '/off', 'helper asked for display off: ' + helperCalls().join());
  q('#night').dispatchEvent(new w.Event('pointerdown', { bubbles: true, cancelable: true }));
  assert(!q('#night').classList.contains('hidden'), 'overlay stays through the touch-down (no click-through to the cards)');
  click(q('#night'));
  await wait(20);
  assert(q('#night').classList.contains('hidden') && helperCalls().slice(-1)[0] === '/on', 'completed tap hides the overlay and asks for display on: ' + helperCalls().join());
  assert(q('#call').classList.contains('hidden'), 'waking the screen did not start a call');

  // Bilderamme: folder walked through the (mocked) nginx listing, subfolder included, clutter skipped; shown full-screen until touched
  click(q('#frame-btn'));
  await wait(50);
  assert(!q('#frame').classList.contains('hidden'), 'photo frame opens');
  const slideImg = q('#frame .slide img');
  assert(!!slideImg && /^photos\/(a\.jpg|2025\/b\.jpg)$/.test(slideImg.getAttribute('src')), 'first photo shown (clutter and non-images skipped): ' + (slideImg && slideImg.getAttribute('src')));
  assert(fetchCalls.some(c => c.url === 'photos/2025/') && !fetchCalls.some(c => /@eaDir/.test(c.url)), 'subfolder walked, @eaDir skipped');
  q('#frame').dispatchEvent(new w.Event('pointerdown', { bubbles: true, cancelable: true }));
  assert(!q('#frame').classList.contains('hidden'), 'frame stays through the touch-down');
  click(q('#frame'));
  assert(q('#frame').classList.contains('hidden') && qa('#frame .slide').length === 0, 'completed tap closes the photo frame');

  // PIN + menu
  click(q('#menu-btn'));
  ['0', '6', '1', '1', '1', '1'].forEach(k => click(qa('#pin-pad button').find(b => b.textContent === k)));
  assert(!q('#menu').classList.contains('hidden'), 'menu opens with PIN');
  assert(!q('#pane-note').classList.contains('hidden') && q('.nav-item[data-pane="note"]').classList.contains('on'), 'menu opens on Ny lapp with the section marked in the list');
  assert(q('.nav-item[data-pane="note"] small').textContent === '5 lapper på tavla', 'section list shows the note count: ' + q('.nav-item[data-pane="note"] small').textContent);

  // New note with docked keyboard
  click(q('.nav-item[data-pane="note"]'));
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
  click(q('.nav-item[data-pane="calendar"]'));
  await wait(20);
  assert(qa('#cal-grid .cal-cell.today').length === 1, 'calendar shows today');
  q('#cal-text').value = 'Bursdag'; q('#cal-text').dispatchEvent(new w.Event('input', { bubbles: true }));
  click(q('#cal-add'));
  await wait(20);
  assert(qa('#cal-day-list .chip').length === 1, 'event added to the day list: ' + qa('#cal-day-list .chip').length);
  assert(q('.nav-item[data-pane="calendar"] small').textContent === '1 plan i dag', 'section list shows the plan count for today: ' + q('.nav-item[data-pane="calendar"] small').textContent);
  assert([...qa('#today-list .srow-day .n')].some(n => n.textContent === 'Bursdag'), 'calendar event shows under I DAG');
  assert(JSON.parse(w.localStorage.getItem('menkerud.calendar')).length === 1, 'event persisted in localStorage');
  click(qa('#cal-day-list .chip button')[0]);
  await wait(20);
  assert(qa('#cal-day-list .chip').length === 0 && ![...qa('#today-list .srow-day .n')].some(n => n.textContent === 'Bursdag'), 'event removed from calendar and I DAG');

  // Settings
  click(q('.nav-item[data-pane="settings"]'));
  await wait(20);
  assert(qa('#status-grid .srow').length === 8, 'status rows: ' + qa('#status-grid .srow').length);
  { const v = qa('#status-grid .srow').find(r => r.querySelector('.sk').textContent === 'Versjon'); assert(v && v.classList.contains('on') && /^000b6d4 · /.test(v.querySelector('.sv').textContent), 'version card from the helper: ' + (v && v.querySelector('.sv').textContent)); }
  assert(qa('#stabs .stab').length === 7 && q('#stabs .stab.on').textContent === 'Status', 'settings tabs: ' + qa('#stabs .stab').map(b => b.textContent).join(','));
  assert(!q('#settings-status').classList.contains('hidden') && q('.sgroup[data-tab="familie"]').classList.contains('hidden') && q('#save-row').classList.contains('hidden'), 'status tab open, form tabs and save row hidden');
  click(qa('#stabs .stab').find(b => b.textContent === 'Familie'));
  assert(!q('.sgroup[data-tab="familie"]').classList.contains('hidden') && q('#settings-status').classList.contains('hidden') && !q('#save-row').classList.contains('hidden'), 'Familie tab shows its rows and the save row');
  assert(qa('#settings-form .frow').length > 15, 'form rows: ' + qa('#settings-form .frow').length);
  const inputFor = label => { const row = qa('#settings-form .frow').find(r => r.querySelector('.flabel').textContent.startsWith(label)); return row && row.querySelector('input, select, button'); };
  const setVal = (label, v) => { const i = inputFor(label); i.value = v; i.dispatchEvent(new w.Event('input', { bubbles: true })); };
  setVal('Mor heter', 'Mamma');
  setVal('Call-backend URL', 'https://call.example.no');
  setVal('Bildemappe', 'bilder/');
  setVal('Start av seg selv', '15');
  assert(!!inputFor('Discord-webhook') && !inputFor('ntfy-server'), 'discord fields shown by default');
  { const s0 = inputFor('Hvordan varsle'); s0.value = 'ntfy'; s0.dispatchEvent(new w.Event('change', { bubbles: true })); }
  setVal('Emne for mors', 'menkerud-mor-test');
  setVal('Emne kiosken', 'menkerud-kiosk-test');
  // provider select rebuilds the form
  const sel = inputFor('Hvordan varsle');
  sel.value = 'webhook'; sel.dispatchEvent(new w.Event('change', { bubbles: true }));
  assert(!!inputFor('Webhook-adresse') && !inputFor('ntfy-server'), 'provider switch rebuilds form and keeps draft');
  assert(inputFor('Mor heter').value === 'Mamma', 'draft kept after rebuild');
  assert(q('#stabs .stab.on').textContent === 'Familie', 'rebuild keeps the open tab: ' + q('#stabs .stab.on').textContent);
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
  // Hent oppdatering: POST to the helper, toast with before → after (the reload that follows is a no-op in jsdom)
  click(q('#t-update'));
  await wait(20);
  assert(fetchCalls.some(c => /:7777\/update$/.test(c.url) && c.opts && c.opts.method === 'POST'), 'update button POSTs to the helper');
  assert(q('#toast').textContent.startsWith('Oppdatert a7e0907 → 000b6d4'), 'update toast: ' + q('#toast').textContent);
  // save
  click(q('#s-save'));
  const saved = JSON.parse(w.localStorage.getItem('menkerud.settings'));
  assert(saved.people.mor.name === 'Mamma' && saved.call.backend === 'https://call.example.no' && saved.notify.ntfy.topicMor === 'menkerud-mor-test' && saved.weather.lat === 60.72 && saved.photos.dir === 'bilder/' && saved.photos.idleMin === 15 && saved.call.timeoutSec === 60, 'settings saved: ' + JSON.stringify(saved.call) + ' ' + saved.weather.lat + ' ' + saved.photos.dir);

  // Second load with saved settings applied
  const second = mkdom(win => { win.localStorage.setItem('menkerud.settings', JSON.stringify(saved)); });
  const w2 = second.dom.window, d2 = w2.document;
  const q2 = s => d2.querySelector(s);
  const click2 = el => el.dispatchEvent(new w2.MouseEvent('click', { bubbles: true }));
  await wait(300);
  assert(q2('[data-name="mor"]').textContent === 'MAMMA', 'saved name applied after reload: ' + q2('[data-name="mor"]').textContent);
  // Bilderamme from another folder with a manifest (subfolder path encoded, no folder walk)
  click2(q2('#frame-btn'));
  await wait(50);
  const img2 = q2('#frame .slide img');
  assert(!!img2 && /^bilder\/(c\.jpg|tur\/d\.jpg)$/.test(img2.getAttribute('src')), 'manifest photos from the saved folder: ' + (img2 && img2.getAttribute('src')));
  assert(!second.fetchCalls.some(c => c.url === 'bilder/'), 'no folder walk when the manifest lists pictures');
  click2(q2('#frame'));
  assert(q2('#frame').classList.contains('hidden'), 'frame closed before the call test');
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

  // Fourth load: Home Assistant through a fake WebSocket → page strip, presence badges, Huset, Lys (rooms + PIN), Kamera, the Sider tab
  const haSent = [];
  const STATES = [
    { entity_id: 'person.mor_test', state: 'home', attributes: { friendly_name: 'Mor' } },
    { entity_id: 'person.far_test', state: 'not_home', attributes: { friendly_name: 'Far' } },
    { entity_id: 'light.barnerom', state: 'off', attributes: { friendly_name: 'Barnerom' } },
    { entity_id: 'light.stue', state: 'on', attributes: { friendly_name: 'Stue' } },
    { entity_id: 'binary_sensor.ytterdor', state: 'on', attributes: { friendly_name: 'Ytterdør', device_class: 'door' } },
    { entity_id: 'sensor.stue_temp', state: '21.5', attributes: { friendly_name: 'Stue temperatur', device_class: 'temperature', unit_of_measurement: '°C' } },
    { entity_id: 'camera.inngang', state: 'idle', attributes: { friendly_name: 'Inngang', access_token: 'abc' } }
  ];
  const fourth = mkdom(win => {
    win.MENKERUD_CONFIG = { ha: { url: 'http://ha.test:8123', token: 'tok' }, rooms: [{ id: 'r1', name: 'Barnerommet', icon: '🧸', lights: ['light.barnerom'], kids: ['light.barnerom'] }], house: { entities: ['binary_sensor.ytterdor', 'sensor.stue_temp'] } };
    class FakeHA {
      constructor(url) { this.url = url; this.readyState = 1; setTimeout(() => this.onmessage && this.onmessage({ data: JSON.stringify({ type: 'auth_required' }) }), 5); }
      send(x) {
        const m = JSON.parse(x); haSent.push(m);
        const reply = o => setTimeout(() => this.onmessage({ data: JSON.stringify(o) }), 5);
        if (m.type === 'auth') reply({ type: 'auth_ok' });
        else if (m.type === 'get_states') reply({ id: m.id, type: 'result', success: true, result: STATES });
        else if (m.id) reply({ id: m.id, type: 'result', success: true, result: null });
      }
      close() { this.readyState = 3; this.onclose && this.onclose(); }
    }
    win.WebSocket = FakeHA;
  });
  const w4 = fourth.dom.window, d4 = w4.document;
  const q4 = s => d4.querySelector(s), qa4 = s => [...d4.querySelectorAll(s)];
  const click4 = el => el.dispatchEvent(new w4.MouseEvent('click', { bubbles: true }));
  await wait(200);
  assert(q4('#status').classList.contains('on'), 'fake HA connected');
  assert(qa4('#page-dots span').length === 4 && !q4('#page-nav').classList.contains('hidden'), 'four pages with HA: ' + qa4('#page-dots span').length);
  assert(q4('#pres-mor').classList.contains('home') && q4('#pres-mor').textContent.includes('Hjemme'), 'presence badge on Ring mor: ' + q4('#pres-mor').textContent);
  assert(q4('#pres-far').classList.contains('away') && q4('#pres-far').textContent.includes('Borte'), 'presence badge on Ring far: ' + q4('#pres-far').textContent);
  // footer arrow → Huset
  click4(q4('#page-next'));
  assert(qa4('#page-dots span')[1].classList.contains('on') && q4('#strip').style.transform === 'translateX(-100%)', 'arrow moves to page 2: ' + q4('#strip').style.transform);
  assert(qa4('#who .who-tile').length === 2 && qa4('#who .who-tile')[0].classList.contains('home'), 'who-is-home tiles');
  const htiles = qa4('#house-grid .h-tile');
  assert(htiles.length === 2 && htiles[0].classList.contains('alert') && htiles[0].querySelector('.v').textContent === 'Åpen' && htiles[1].querySelector('.v').textContent === '21,5 °C', 'house tiles: ' + htiles.map(t => t.querySelector('.v').textContent).join(' | '));
  // swipe left with pointer events → Lys
  const pev = (type, x) => q4('#pages').dispatchEvent(new w4.MouseEvent(type, { bubbles: true, clientX: x, clientY: 300 }));
  pev('pointerdown', 900); pev('pointermove', 850); pev('pointermove', 600); pev('pointerup', 500);
  assert(qa4('#page-dots span')[2].classList.contains('on'), 'swipe left moves to page 3');
  await wait(420);   // the click that follows a swipe is swallowed for a moment
  const rooms = qa4('#rooms .room');
  assert(rooms.length === 2 && rooms[0].querySelector('.room-title').textContent.includes('Barnerommet') && rooms[1].querySelector('.room-title').textContent.includes('Andre lys'), 'rooms rendered: ' + rooms.map(r => r.querySelector('.room-title').textContent).join(' | '));
  const kidTile = rooms[0].querySelector('.tile'), lockedTile = rooms[1].querySelector('.tile');
  assert(kidTile && lockedTile && !kidTile.classList.contains('locked') && lockedTile.classList.contains('locked'), 'kid light free, other light locked');
  haSent.length = 0;
  click4(kidTile);
  await wait(10);
  assert(haSent.some(m => m.type === 'call_service' && m.service === 'toggle' && m.target.entity_id === 'light.barnerom'), 'kid light toggled without PIN');
  click4(lockedTile);
  assert(!q4('#pin').classList.contains('hidden'), 'locked light asks for the PIN');
  ['0', '6', '1', '1', '1', '1'].forEach(k => click4(qa4('#pin-pad button').find(b => b.textContent === k)));
  await wait(10);
  assert(q4('#pin').classList.contains('hidden') && q4('#menu').classList.contains('hidden'), 'PIN for a light does not open the menu');
  assert(haSent.some(m => m.type === 'call_service' && m.target.entity_id === 'light.stue'), 'locked light toggled after PIN');
  assert(!qa4('#rooms .room')[1].querySelector('.tile').classList.contains('locked'), 'lights unlocked for a while after the PIN');
  // Kamera
  click4(q4('#page-next'));
  const cam = q4('#cam-grid .cam-tile img');
  assert(cam && /\/api\/camera_proxy\/camera\.inngang\?token=abc/.test(cam.getAttribute('src')), 'camera snapshot from HA: ' + (cam && cam.getAttribute('src')));
  click4(q4('#cam-grid .cam-tile'));
  assert(/camera_proxy_stream\/camera\.inngang/.test(q4('#cam-full-img').getAttribute('src')) && !q4('#cam-full').classList.contains('hidden'), 'tap opens the live stream');
  click4(q4('#page-prev'));
  assert(!q4('#cam-full-img').getAttribute('src') && q4('#cam-full').classList.contains('hidden'), 'leaving the page drops the stream');
  // Sider tab in Innstillinger: entity pickers and the rooms editor
  click4(q4('#menu-btn'));
  ['0', '6', '1', '1', '1', '1'].forEach(k => click4(qa4('#pin-pad button').find(b => b.textContent === k)));
  click4(q4('.nav-item[data-pane="settings"]'));
  click4(qa4('#stabs .stab').find(b => b.textContent === 'Sider'));
  const inputFor4 = label => { const row = qa4('#settings-form .frow').find(r => r.querySelector('.flabel').textContent.startsWith(label)); return row && row.querySelector('input, select, .ent-list'); };
  const pm = inputFor4('Mor i Home Assistant');
  assert(pm && pm.tagName === 'SELECT' && [...pm.options].some(o => o.value === 'person.mor_test'), 'person picker lists HA persons');
  const we = inputFor4('Vær-entitet');
  assert(we && we.tagName === 'SELECT', 'weather entity is a picker when HA is connected');
  assert(inputFor4('Vis på Huset-siden') && qa4('.ent-list .chip-btn').length === 2 && qa4('.ent-list .chip-btn.on').length === 2, 'house entity chips: ' + qa4('.ent-list .chip-btn').length + ' (' + qa4('.ent-list .chip-btn.on').length + ' on)');
  assert(qa4('#settings-form .room-ed').length === 1 && qa4('#settings-form .room-ed .lightrow').length === 2, 'rooms editor shows the room with both lights');
  click4(qa4('#settings-form .chip-btn').find(b => b.textContent === '+ Nytt rom'));
  assert(qa4('#settings-form .room-ed').length === 2, 'new room added');

  console.log(errors.length ? 'ERRORS:\n' + errors.join('\n') : 'ALL GOOD');
  process.exit(errors.length ? 1 : 0);
})().catch(e => { console.log('EXC', e); process.exit(1); });
