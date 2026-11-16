# Parent App, Part 1: API and Kiosk – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the call-backend what the parent app needs – paired phones, push, calls in both directions, a quiet event stream for the kiosk, a local-only pairing page – and teach the kiosk to use it with no visible change, all runnable and tested on the dev PC against a separate copy of the backend.

**Architecture:** `call-backend/src/server.js` is split into small modules with injected dependencies (config, device store, push sender, kiosk event hub, call manager), so each is tested without HTTP or real services; `server.js` only wires them and listens on two ports (public API, local admin). The kiosk gains one streamed `fetch` to the API and reuses its existing overlays and texts. Production is not touched in this part.

**Tech Stack:** Node 20 (CommonJS, `'use strict'`), Express 5.2.1, livekit-server-sdk 2.19.0, FCM HTTP v1 signed with Node `crypto`, one new dependency `qrcode-generator` 2.0.4 (zero-dependency QR for the pairing page). Kiosk: vanilla JS inside `index.html`. Tests: plain `node` scripts with `assert`, and the jsdom smoke test.

**Spec:** `docs/superpowers/specs/2026-11-14-parent-app-design.md`

**This is part 1 of 3.** Part 2 is the Android app (`parent-app/`); part 3 is rollout to Unraid and the phones, then removing the answer page. Each gets its own plan. Part 2 builds on the **Contract** section below. Two of the spec's early checks belong to those parts and are not done here: a non-browser client through Cloudflare from a mobile network, and a Gradle build from a path with a space.

## Global Constraints

- `index.html` stays one file: vanilla JS in one IIFE, no build, no dependencies. It must keep working opened from `file://` with nothing configured.
- **Nothing visible changes on the kiosk:** no new elements, pictures, texts or settings rows. Only the existing «… ringer hjem» status row may change its text. No line of `index.html` before the `<script>` that follows `<script src="config.js">` may change.
- UI text is Norwegian bokmål; code, comments and docs are English. Parent keys are `mor` / `far`.
- The public listener (`call.noobventure.com`) gets **no new pages**. The pairing page exists only on the admin port.
- A pairing key works **once** and for **15 minutes**. The server stores only a SHA-256 hash of a phone's secret. Which parent a phone is comes from the server's record, never from the request.
- Ring time is `CALL_RING_TIMEOUT_SECONDS` (60). The kiosk's incoming overlay keeps its existing 45 s.
- Per parent: at least one phone with a live push address → the call goes to the app; otherwise → the Discord link exactly as today. A phone whose push address is dead does not count.
- Every push payload carries `type`; receivers ignore unknown types.
- Backend tests are plain node scripts ending in `ALL GOOD` (no test framework). `npm test` must pass in the repo root and in `call-backend/`.
- New npm dependencies: `qrcode-generator` only.
- Never commit secrets: `.env`, `.dev/`, any service-account file, `config.js`. Never print the contents of `call-backend/.env` or `config.js`.
- **The dev copy must never load `call-backend/.env`** (it holds the real Discord token and would message the parents). It is always started with `DOTENV_CONFIG_PATH` pointing at `.dev/callapi.env`.
- Anything that must survive on Unraid lives outside `call-backend/`: the operator's sync is `robocopy /MIR`, which deletes whatever is not in the source folder.
- Work on the local `experimental` branch, one commit per task, each message ending with the session's attribution lines. Never push, never merge to `main`, never deploy.

## Review Focus

1. **A call that was answered but never reported as ended** (kiosk lost power, tab closed) must not block the next call with «en samtale pågår allerede». → Task 7, watchdog test.
2. **The kiosk's stream to the API dies silently** (Wi-Fi blip, proxy idle timeout): the kiosk reconnects by itself, and a parent calling home meanwhile is told the screen is not connected instead of ringing into nothing. → Task 8 (503 test) and Task 9 (reconnect test).
3. **A paired phone whose push address has died** (app removed, phone reset): that parent gets the Discord link again, not silence. → Task 7, dead-token test.
4. **A key typed by hand** with dashes, spaces, lower case, or `O`/`0` and `I`/`L`/`1` mixed up still works; a used or expired key is refused with a distinct answer. → Task 2 tests.
5. **The backend restarts or is rebuilt:** paired phones stay paired, and a phone answering a call that no longer exists gets a clean "gone", not a hang. → Task 2 (reload test) and Task 7 (404 test).

## Contract (what part 2 builds on)

All bodies are JSON. Errors are `{ "error": "<code or text>" }`.

*Revised on 2026-11-15 after the whole-branch review, before part 2 was planned: C1 (what 429 means), C3 (when the app sends its push token), C4 (no device key = the kiosk's new routes are closed), C6 (the app asks for the outcome; the kiosk confirms a ringing call; the states `unreachable` and `cancelled`), C7 and C8 (a ringing call is told again to a kiosk that connects). The code and its tests are the last word.*

- **C1 Pair:** `POST /api/pair` `{ code, name, appVersion }` → 200 `{ deviceId, secret, who, label }` · 400 `invalid` (wrong, or already used) · 410 `expired` · 429 `locked`. 429 is only ever the answer to a *wrong* key (more than ten wrong keys in ten minutes, counted for everybody together); a right key always works at once. The app treats 429 like 400 – «feil nøkkel» – never as «wait ten minutes».
- **C2 Phone auth:** header `Authorization: Bearer <deviceId>.<secret>`. Failure: 401 `unauthorized`.
- **C3 Device:** `GET /api/devices/me` → 200 `{ deviceId, who, label, name }` (optional request header `X-App-Version`). `PUT /api/devices/me/push-token` `{ token }` → 204 · 400 `invalid_token`. **The app sends its push token at every start and whenever the token changes:** a phone whose address the push service reported dead counts as not paired until it does, and nothing else tells it so. A 401 on any phone route means the phone was removed on the pairing page: back to the pairing screen.
- **C4 Kiosk auth:** header `X-Device-Key`. `POST /api/calls` and the two messages routes are open when the server has no `DEVICE_KEY`, as before. Everything the kiosk got in this part – its event stream (C8) and confirming, answering or declining a parent's call (C6) – answers 403 `device_key_required` on a server without one: nobody may sit in for the screen at home.
- **C5 Kids call a parent:** `POST /api/calls` (kiosk) `{ target }` → 200 `{ callId, status: "ringing", livekit: { url, token }, expiresAt, via: "app" | "discord" }`, other answers unchanged. Phone: `POST /api/calls/:id/answer` → 200 `{ callId, livekit: { url, token } }` · 403 `wrong_parent` · 404 `unknown` · 409 `answered` · 410 `over`. `POST /api/calls/:id/decline` → 200 `{ status: "declined" }` · 404 · 410.
- **C6 A parent calls home:** `POST /api/calls/home` (phone) → 200 `{ callId, status: "ringing", livekit: { url, token }, expiresAt }` · 409 `busy` («Opptatt hjemme») · 503 `kiosk_offline` («Skjermen hjemme er ikke tilkoblet») · 429 (more than ten call starts in a minute, either direction: «prøv igjen om litt») · 500 (the server could not make the call).
  - **The app learns how it went by asking:** `GET /api/calls/:id` → `{ callId, status, target, direction }` about once a second while it shows «Ringer hjem…», and no longer than until `expiresAt`. It must *not* wait for its LiveKit room to close: when the kiosk declines within a moment the room is deleted before the app has joined, the app's join then makes a fresh empty room, and nothing ever closes.
  - `status`: `ringing` – keep waiting · `connected` – they answered at home · `declined` or `missed` – «Ingen svarte hjemme» · `unreachable` – the kiosk did not confirm the call within 8 s, so it never reached the screen: «Skjermen hjemme er ikke tilkoblet» · `cancelled` – ended while it rang (by the app itself, or by the kiosk hanging up after a failed answer): over · `ended` – over · 404 `ukjent` – a finished call is forgotten after a minute: over.
  - The app gives up with `POST /api/calls/:id/cancel` (C7); the kiosk then stops ringing.
  - Kiosk, all with the kiosk key: `POST /api/calls/:id/ringing` → 200 `{ status }` · 404 – «it is ringing here», sent for every `incoming` the kiosk shows; without it within 8 s the call ends as `unreachable`. `POST /api/calls/:id/answer` → 200 `{ callId, livekit: { url, token } }` · 404 `unknown` · 409 `answered` · 410 `over`. `POST /api/calls/:id/decline` → 200 `{ status: "declined" }` · 404 · 409 · 410.
- **C7 Ending:** `POST /api/calls/:id/end` and `/cancel`, no auth (the call id is long and random) → 200 `{ status }`, the state the call is in afterwards (`cancelled` when it was still ringing, else `ended`, or whatever it already was) · 404 `ukjent`.
- **C8 Kiosk events:** `GET /api/kiosk/events` (kiosk auth), `text/event-stream`, frames `data: <json>\n\n`: `{type:"hello"}`, `{type:"incoming", callId, from, expiresAt}`, `{type:"cancelled", callId}`, `{type:"declined", callId}`, `{type:"ended", callId}`; a comment line `: ka` every 20 s. A kiosk that connects while a parent's call is ringing gets that call's `incoming` again right after `hello`. The kiosk confirms every `incoming` it shows with `POST /api/calls/:id/ringing` (C6) and declines at once one it cannot take.
- **C9 Push data** (FCM data message, every value a string): `{type:"call", v:"1", callId, caller:"home", who, expiresAt}` with lifetime = ring seconds (revised 2026-11-16: the key was `from`, which Google reserves – it refused every real push with «Invalid data payload key: from»; no key may be `from`, `message_type`, `collapse_key` or begin with `google` or `gcm`); `{type:"call_end", v:"1", callId, reason, at}` with `reason` in `answered_elsewhere | cancelled | missed | declined | ended`, lifetime 21600 s for `cancelled` and `missed`, else 60 s.
- **C10 Pairing QR text:** `menkerudhome://pair?api=<urlencoded public URL>&code=<20 characters>`.
- **C11 Admin, local port only, HTTP Basic (any user name, password `ADMIN_PASSWORD`):** `GET /` · `POST /api/pair-code` `{ who }` → `{ code, pretty, expiresAt, qrSvg }` · `GET /api/devices` → `{ devices: [{ deviceId, who, name, appVersion, createdAt, lastSeenAt, push }] }` · `DELETE /api/devices/:id` → 204 · 404.

## File Structure

| File | Responsibility |
|---|---|
| `call-backend/src/config.js` (new) | Read the environment once into one frozen object. |
| `call-backend/src/app.js` (new) | The public Express app: routes only. |
| `call-backend/src/devices.js` (new) | Paired phones and pairing keys; the JSON file. |
| `call-backend/src/admin.js`, `src/admin-page.html` (new) | The local pairing page and its three routes. |
| `call-backend/src/push.js` (new) | Push payloads and the `fcm` / `file` / `none` transports. |
| `call-backend/src/kiosk-events.js` (new) | The event stream hub for the kiosk. |
| `call-backend/src/calls.js` (new) | Call states for both directions, timeouts, clean-up, who gets notified how. |
| `call-backend/src/livekit.js` (modify) | Add `participantCount`. |
| `call-backend/src/server.js` (modify) | Wiring and the two `listen` calls only. |
| `call-backend/test/helpers.js`, `test/run.js`, `test/*.test.js` (new) | Fakes, wiring helper, runner, one test file per module plus `api` and `e2e`. |
| `call-backend/scripts/fake-phone.js`, `call-backend/dev/join.html`, `call-backend/.env.dev.example` (new) | A stand-in phone and a join page for the dev copy. |
| `index.html` (modify, script only) | `listenBackend`, joining a waiting call, reacting to «Ikke nå». |
| `test/smoke.js` (modify) | Extra page loads covering the above. |
| `DESIGN.md`, `call-backend/README.md`, `.gitignore`, `call-backend/docker-compose.yml`, `call-backend/.env.example`, `call-backend/.dockerignore` (modify) | Docs and deployment files for what this part adds. |

Every API-level test builds its app through one helper, `wire()`, which grows with each task. Tests never call `createApp` directly, so they keep passing as the wiring grows.

---

### Task 0: Confirm the path from outside (with the operator; no code)

The spec makes this the first step: the app depends on the same media path as today's answer page.

- [ ] **Step 1: Ask the operator for one call.** Phone on mobile data with Wi-Fi off. «Ring far» on the kiosk. Open the DM link in Chrome (if Discord opens its own browser: its menu → open in browser). Allow camera and microphone.
- [ ] **Step 2: Judge it.** Pass = both sides see and hear each other for 30 seconds. Record the result on this line.
- [ ] **Step 3: If it fails,** follow the ladder in the spec (§8, "First step"): UDP 7882 forward to `192.168.68.2` on both routers → test → TCP 7881 forward → test. If it still fails, stop and report to the operator; the relay decision is theirs.

### Task 1: Split `server.js` into testable pieces, behaviour unchanged

**Files:**
- Create: `call-backend/src/config.js`, `call-backend/src/app.js`, `call-backend/test/helpers.js`, `call-backend/test/run.js`, `call-backend/test/api.test.js`
- Modify: `call-backend/src/server.js`, `call-backend/package.json` (`"test": "node test/run.js"`)

**Interfaces:**
- Produces: `loadConfig(env = process.env)` → frozen `{ port, adminPort, publicUrl, ringSec, tokenTtlSec, maxCallSec, deviceKey, dev, dataDir, adminPassword, pushTransport, fcmServiceAccount, parents: { mor: { discordUserId, label: 'Mor', identity: 'parent-mor' }, far: { discordUserId, label: 'Far', identity: 'parent-far' } } }` from `PORT` (3000), `ADMIN_PORT` (3001), `CALL_PUBLIC_URL`, `CALL_RING_TIMEOUT_SECONDS` (60), `CALL_TOKEN_TTL_SECONDS` (600), `CALL_MAX_SECONDS` (7200), `DEVICE_KEY`, `DEV`, `DATA_DIR` (`./data`), `ADMIN_PASSWORD`, `PUSH_TRANSPORT`, `FCM_SERVICE_ACCOUNT` (default `<dataDir>/fcm-service-account.json`), `DISCORD_MOR_USER_ID`, `DISCORD_FAR_USER_ID`.
- Produces: `createApp({ config, livekit, discord, log })` → Express app holding today's routes and today's in-memory call logic (it moves out in Task 7). `livekit` and `discord` have the shape of the existing modules. Later tasks add `devices`, `hub` and `calls` to this object.
- Produces in `test/helpers.js`:
  - `startApp(app)` → `Promise<{ url, close() }>` (port 0); `http(method, url, body?, headers?)` → `Promise<{ status, body, text }>`; `basic(user, pass)` and `bearer(dev)` → header objects; `tmpFile(name)` → a path in a fresh temp folder; `wait(ms)`.
  - `testConfig(env?)` → `loadConfig({ DEVICE_KEY: 'k', DISCORD_MOR_USER_ID: '111', DISCORD_FAR_USER_ID: '222', ADMIN_PASSWORD: 'pw', ...env })`.
  - `fakeLivekit()` → `{ WS_URL: 'wss://lk.test', configured: () => true, joinToken: async ({ room, identity }) => 'tok:' + room + ':' + identity, deleteRoom, participantCount: async () => state.count, deleted: [], state: { count: 2 } }`.
  - `fakeDiscord()` → `{ configured: () => true, dm, edit, channelMessages, react, dms: [], edits: [] }`; `dm(userId, content)` records and returns `{ channelId: 'ch', messageId: 'm<n>' }`.
  - `fakeTimers()` → `{ setTimeout, clearTimeout, setInterval, clearInterval, advance(ms), now() }`; `advance` runs due callbacks in time order and moves `now()`.
  - `wire(env?, deps?)` → `{ config, lk, discord, app }`, built from the fakes above and `createApp`.
- Produces: `test/run.js` runs every `test/*.test.js` in name order with the current node and stops at the first failure.

- [ ] **Step 1: Write the failing test** `test/api.test.js` (characterises today's behaviour):

```js
const w = wire(), { url } = await startApp(w.app), K = { 'X-Device-Key': 'k' };
const hz = (await http('GET', url + '/healthz')).body;
assert.ok(hz.ok === true && hz.livekit === true && hz.discord === true);
assert.strictEqual((await http('POST', url + '/api/calls', { target: 'far' })).status, 401);
const s = await http('POST', url + '/api/calls', { target: 'far' }, K);
assert.strictEqual(s.status, 200); assert.strictEqual(s.body.status, 'ringing');
assert.strictEqual(s.body.livekit.token, 'tok:family-' + s.body.callId + ':tablet-stue');
const link = w.discord.dms[0].content.match(/\/answer\/([0-9a-f]{64})/)[1];
assert.strictEqual((await http('POST', url + '/api/calls', { target: 'mor' }, K)).status, 409);
assert.strictEqual((await http('POST', url + '/api/calls/answer', { answerToken: link })).body.status, 'connected');
await http('POST', url + '/api/calls/' + s.body.callId + '/end', {});
assert.strictEqual((await http('POST', url + '/api/calls', { target: 'mor' }, K)).status, 200);
assert.strictEqual((await http('GET', url + '/answer/abc')).status, 200);
```

- [ ] **Step 2: Run it.** `cd call-backend && node test/api.test.js` → FAIL: `Cannot find module './helpers'`.
- [ ] **Step 3: Implement** `config.js`, `app.js`, `helpers.js`, `run.js`; reduce `server.js` to `require('dotenv/config')`, `loadConfig()`, `createApp(...)`, `listen(config.port)`. Move code, do not rewrite it.
- [ ] **Step 4: Run.** `node test/api.test.js` → `ALL GOOD`. Then `npm test` → the two existing tests and this one pass.
- [ ] **Step 5: Commit.** `refactor(call-backend): split server.js into config + app so routes can be tested`

### Task 2: Paired phones and pairing keys

**Files:**
- Create: `call-backend/src/devices.js`, `call-backend/test/devices.test.js`

**Interfaces:**
- Produces: `createDevices({ file, now = Date.now, randomBytes = crypto.randomBytes })` → `{ createPairingCode(who), redeem(code, { name, appVersion }), authenticate(bearer), setPushToken(deviceId, token), markPushDead(deviceId), touch(deviceId, { appVersion }), list(), remove(deviceId), devicesFor(who), reachableFor(who) }`.
  - `createPairingCode(who)` → `{ code, expiresAt }`; `code` is 20 characters from `0123456789ABCDEFGHJKMNPQRSTVWXYZ`; throws on a `who` other than `mor` / `far`.
  - `redeem` → `{ deviceId: 'd_' + 12 hex, secret: 64 hex, who }`; throws `PairError` with `.code` `'invalid' | 'expired' | 'locked'`. Input is normalised: upper-cased, spaces and dashes removed, `O`→`0`, `I` and `L`→`1`.
  - `authenticate('<deviceId>.<secret>')` → the stored record or `null`, compared in constant time.
  - `list()` → records without `secretHash` and `pushToken`, each with `push: 'ok' | 'none' | 'dead'`.
  - `reachableFor(who)` → records with a push token that is not marked dead. `setPushToken` clears the dead mark.
  - Exports `PairError`, `PAIR_TTL_MS = 900000`, `MAX_FAILED_REDEEMS = 10`, `FAILED_WINDOW_MS = 600000`. The failed-attempt limit is one counter for the whole store, not per caller.
  - The file is rewritten atomically (temp file, then rename) on every change; pairing keys live in memory only.

- [ ] **Step 1: Write the failing test** `test/devices.test.js`:

```js
let t = 1_800_000_000_000; const file = tmpFile('devices.json');
const d = createDevices({ file, now: () => t });
const { code, expiresAt } = d.createPairingCode('far');
assert.match(code, /^[0-9A-HJKMNP-TV-Z]{20}$/); assert.strictEqual(expiresAt, t + 900000);
assert.throws(() => d.createPairingCode('bestemor'));
const typed = ' ' + code.toLowerCase().replace(/0/g, 'o').replace(/1/g, 'l').match(/.{5}/g).join('-') + ' ';
const dev = d.redeem(typed, { name: 'S26', appVersion: '1.0.0' });                 // Review Focus 4
assert.strictEqual(dev.who, 'far'); assert.match(dev.deviceId, /^d_[0-9a-f]{12}$/); assert.match(dev.secret, /^[0-9a-f]{64}$/);
assert.throws(() => d.redeem(code, {}), e => e.code === 'invalid');                // works once
const c2 = d.createPairingCode('mor'); t += 900001;
assert.throws(() => d.redeem(c2.code, {}), e => e.code === 'expired');
assert.strictEqual(d.authenticate(dev.deviceId + '.' + dev.secret).who, 'far');
assert.strictEqual(d.authenticate(dev.deviceId + '.' + 'f'.repeat(64)), null);
assert.strictEqual(d.authenticate('nonsense'), null);
assert.ok(!fs.readFileSync(file, 'utf8').includes(dev.secret));                    // only the hash is stored
assert.strictEqual(createDevices({ file }).authenticate(dev.deviceId + '.' + dev.secret).who, 'far');   // Review Focus 5
assert.strictEqual(d.reachableFor('far').length, 0);
d.setPushToken(dev.deviceId, 'tok'); assert.strictEqual(d.reachableFor('far').length, 1); assert.strictEqual(d.list()[0].push, 'ok');
d.markPushDead(dev.deviceId); assert.strictEqual(d.reachableFor('far').length, 0); assert.strictEqual(d.devicesFor('far').length, 1);
d.setPushToken(dev.deviceId, 'tok2'); assert.strictEqual(d.reachableFor('far').length, 1);   // a fresh token revives it
assert.ok(!('secretHash' in d.list()[0]) && !('pushToken' in d.list()[0]));
for (let i = 0; i < 10; i++) assert.throws(() => d.redeem('0'.repeat(20), {}), e => e.code === 'invalid');
assert.throws(() => d.redeem(d.createPairingCode('far').code, {}), e => e.code === 'locked');
t += 600001; assert.strictEqual(d.redeem(d.createPairingCode('far').code, {}).who, 'far');   // the lock lifts
assert.strictEqual(d.remove(dev.deviceId), true); assert.strictEqual(d.authenticate(dev.deviceId + '.' + dev.secret), null);
```

- [ ] **Step 2: Run it.** `node test/devices.test.js` → FAIL: `Cannot find module '../src/devices'`.
- [ ] **Step 3: Implement** `src/devices.js` to the interface above.
- [ ] **Step 4: Run.** `node test/devices.test.js` → `ALL GOOD`; `npm test` passes.
- [ ] **Step 5: Commit.** `feat(call-backend): device store with one-time pairing keys`

### Task 3: Pairing and device routes on the public API

**Files:**
- Modify: `call-backend/src/app.js`, `call-backend/src/server.js` (create the store at `config.dataDir + '/devices.json'`, creating the folder), `call-backend/test/helpers.js`
- Create: `call-backend/test/pairing-api.test.js`

**Interfaces:**
- Consumes: `createDevices` (Task 2).
- Produces: contract C1, C2, C3. `createApp` takes `devices`; `wire()` returns `devices` (a store on `tmpFile('devices.json')`). Middleware `phoneAuth(req, res, next)` in `app.js` sets `req.device` and calls `devices.touch` with the `X-App-Version` header. `label` comes from `config.parents[who].label`. `name` is cut to 40 characters, `appVersion` to 20; `token` must be a non-empty string of at most 4096.

- [ ] **Step 1: Write the failing test** `test/pairing-api.test.js`:

```js
const w = wire(), { url } = await startApp(w.app);
const { code } = w.devices.createPairingCode('mor');
assert.strictEqual((await http('POST', url + '/api/pair', { code: 'X'.repeat(20) })).status, 400);
const p = await http('POST', url + '/api/pair', { code, name: 'S25 Ultra', appVersion: '1.0.0' });
assert.strictEqual(p.status, 200); assert.deepStrictEqual(Object.keys(p.body).sort(), ['deviceId', 'label', 'secret', 'who']);
assert.strictEqual(p.body.who, 'mor'); assert.strictEqual(p.body.label, 'Mor');
assert.strictEqual((await http('POST', url + '/api/pair', { code })).status, 400);
assert.strictEqual((await http('GET', url + '/api/devices/me')).status, 401);
const me = await http('GET', url + '/api/devices/me', null, { ...bearer(p.body), 'X-App-Version': '1.0.1' });
assert.deepStrictEqual(me.body, { deviceId: p.body.deviceId, who: 'mor', label: 'Mor', name: 'S25 Ultra' });
assert.strictEqual(w.devices.list()[0].appVersion, '1.0.1');
assert.strictEqual((await http('PUT', url + '/api/devices/me/push-token', { token: '' }, bearer(p.body))).status, 400);
assert.strictEqual((await http('PUT', url + '/api/devices/me/push-token', { token: 'fcm-1' }, bearer(p.body))).status, 204);
assert.strictEqual(w.devices.reachableFor('mor').length, 1);
```

- [ ] **Step 2: Run it.** `node test/pairing-api.test.js` → FAIL: `w.devices` is undefined.
- [ ] **Step 3: Implement** the three routes and `phoneAuth`; map `PairError.code` to 400 / 410 / 429.
- [ ] **Step 4: Run.** `node test/pairing-api.test.js` → `ALL GOOD`; `npm test` passes.
- [ ] **Step 5: Commit.** `feat(call-backend): pair a phone with a key, device endpoints`

### Task 4: The local pairing page on its own port

**Files:**
- Create: `call-backend/src/admin.js`, `call-backend/src/admin-page.html`, `call-backend/test/admin.test.js`
- Modify: `call-backend/src/server.js`, `call-backend/package.json` (add `"qrcode-generator": "2.0.4"`), `call-backend/docker-compose.yml`, `call-backend/.env.example`, `call-backend/README.md`

**Interfaces:**
- Consumes: `createDevices` (Task 2), `loadConfig` (Task 1), `wire()`.
- Produces: `createAdminApp({ config, devices, log })` → Express app implementing contracts C11 and C10. Every route answers 401 with `WWW-Authenticate: Basic realm="Menkerud Home"` unless the Basic password equals `config.adminPassword` (constant-time compare; any user name). `pretty` is the code in four groups of five joined by `-`. `qrSvg` is an `<svg …>` string from `qrcode-generator` (error correction `M`).
- Produces in `server.js`: the admin app listens on `config.adminPort` only when `config.adminPassword` is non-empty; otherwise it logs `admin page disabled: set ADMIN_PASSWORD`.
- Page copy, exact: title «Menkerud Home – telefoner»; buttons «Ny telefon for Mor», «Ny telefon for Far»; under a new key «Gyldig i 15 minutter – kan brukes én gang»; table headings «Hvem», «Navn», «App», «Sist sett», «Varsler», row button «Fjern»; empty table «Ingen telefoner er koblet til ennå». «Varsler» shows «OK», «Ikke registrert» or «Virker ikke» for `ok` / `none` / `dead`. The page is one static file with inline script and style, and escapes every dynamic value.
- Deployment files: compose adds port `"3009:3001"` and volume `/mnt/user/appdata/menkerud-callapi:/app/data`; `.env.example` adds `ADMIN_PORT=3001`, `ADMIN_PASSWORD=`, `DATA_DIR=/app/data`, `PUSH_TRANSPORT=`, `FCM_SERVICE_ACCOUNT=`, `CALL_MAX_SECONDS=7200`, each with a one-line comment. README gains a short section saying the page is `http://<unraid-ip>:3009`, is never proxied by SWAG, and why the data folder is under `appdata` (the `/MIR` sync).

- [ ] **Step 1: Write the failing test** `test/admin.test.js`:

```js
const w = wire(), A = basic('anyone', 'pw');
const admin = (await startApp(createAdminApp({ config: w.config, devices: w.devices, log() {} }))).url;
const pub = (await startApp(w.app)).url;
assert.strictEqual((await http('GET', admin + '/')).status, 401);
assert.strictEqual((await http('GET', admin + '/', null, basic('anyone', 'wrong'))).status, 401);
const page = await http('GET', admin + '/', null, A);
assert.strictEqual(page.status, 200); assert.ok(page.text.includes('Ny telefon for Mor') && page.text.includes('Ny telefon for Far'));
const pc = await http('POST', admin + '/api/pair-code', { who: 'far' }, A);
assert.match(pc.body.code, /^[0-9A-HJKMNP-TV-Z]{20}$/); assert.match(pc.body.pretty, /^(\w{5}-){3}\w{5}$/); assert.ok(pc.body.qrSvg.startsWith('<svg'));
assert.strictEqual((await http('POST', admin + '/api/pair-code', { who: 'bestemor' }, A)).status, 400);
const dev = w.devices.redeem(pc.body.code, { name: 'S26' });
assert.strictEqual((await http('GET', admin + '/api/devices', null, A)).body.devices[0].name, 'S26');
assert.strictEqual((await http('DELETE', admin + '/api/devices/' + dev.deviceId, null, A)).status, 204);
assert.strictEqual((await http('GET', pub + '/api/devices/me', null, bearer(dev))).status, 401);   // a removed phone is out
for (const p of ['/api/pair-code', '/api/devices'])                                               // none of it on the public port
  assert.strictEqual((await http('GET', pub + p, null, A)).status, 404);
```

- [ ] **Step 2: Run it.** `node test/admin.test.js` → FAIL: `Cannot find module '../src/admin'`.
- [ ] **Step 3: Implement** `admin.js`, `admin-page.html`, the second listener, and the deployment-file edits. Run `npm install qrcode-generator@2.0.4` inside `call-backend/`.
- [ ] **Step 4: Run.** `node test/admin.test.js` → `ALL GOOD`; `npm test` passes.
- [ ] **Step 5: Commit.** `feat(call-backend): local-only pairing page on the admin port`

### Task 5: Push sender

**Files:**
- Create: `call-backend/src/push.js`, `call-backend/test/push.test.js`

**Interfaces:**
- Produces: `createPush({ transport, serviceAccount, outboxFile, fetch = global.fetch, now = Date.now, log })` → `{ name, send(device, data, { ttlSec }) }`. `send` resolves to `{ ok: true }` or `{ ok: false, dead: boolean, error: string }` and never rejects.
  - `transport: 'fcm'`: exchange a service-account JWT (RS256 via `crypto.createSign('RSA-SHA256')`, scope `https://www.googleapis.com/auth/firebase.messaging`, audience `serviceAccount.token_uri`) for an access token, cached until 60 s before it expires; then `POST https://fcm.googleapis.com/v1/projects/<project_id>/messages:send` with `{ message: { token, data, android: { priority: 'HIGH', ttl: '<ttlSec>s' } } }`. An answer whose error status is `NOT_FOUND` or whose details carry `errorCode: 'UNREGISTERED'` → `dead: true`.
  - `transport: 'file'`: append one line `{ at, deviceId, token, ttlSec, data }` to `outboxFile`.
  - `transport: 'none'`: `{ ok: false, dead: false, error: 'push disabled' }`.
- Produces: `callData({ callId, target, expiresAt })` and `callEndData({ callId, reason, at })` → the C9 objects; `callEndTtl(reason)` → `21600` for `cancelled` / `missed`, else `60`.
- Produces: `pushFromConfig(config, log)` → `createPush` with `config.pushTransport`, or when that is empty: `fcm` if the service-account file exists, else `none`. The `file` transport writes to `<dataDir>/push-outbox.jsonl`.

- [ ] **Step 1: Write the failing test** `test/push.test.js` (generate a key pair with `crypto.generateKeyPairSync('rsa', { modulusLength: 2048 })`; `fake` is a scripted `fetch`: `fake.script(...answers)` queues answers, `fake.calls` records `{ url, opts }`):

```js
const sa = { project_id: 'p1', client_email: 'svc@p1.iam.gserviceaccount.com', private_key: pem, token_uri: 'https://oauth2.googleapis.com/token' };
const push = createPush({ transport: 'fcm', serviceAccount: sa, fetch: fake, now: () => 1_800_000_000_000 });
fake.script({ status: 200, body: { access_token: 'AT', expires_in: 3600 } }, { status: 200, body: { name: 'x' } }, { status: 200, body: { name: 'y' } });
assert.deepStrictEqual(await push.send({ deviceId: 'd_1', pushToken: 'T' }, { type: 'call', v: '1', callId: 'c_1' }, { ttlSec: 60 }), { ok: true });
assert.strictEqual(fake.calls[0].url, 'https://oauth2.googleapis.com/token');
const jwt = new URLSearchParams(fake.calls[0].opts.body).get('assertion').split('.');
assert.ok(crypto.createVerify('RSA-SHA256').update(jwt[0] + '.' + jwt[1]).verify(publicKey, jwt[2], 'base64url'));
const claims = JSON.parse(Buffer.from(jwt[1], 'base64url'));
assert.strictEqual(claims.iss, sa.client_email); assert.strictEqual(claims.scope, 'https://www.googleapis.com/auth/firebase.messaging');
assert.strictEqual(fake.calls[1].url, 'https://fcm.googleapis.com/v1/projects/p1/messages:send');
assert.strictEqual(fake.calls[1].opts.headers.Authorization, 'Bearer AT');
assert.deepStrictEqual(JSON.parse(fake.calls[1].opts.body), { message: { token: 'T', data: { type: 'call', v: '1', callId: 'c_1' }, android: { priority: 'HIGH', ttl: '60s' } } });
await push.send({ deviceId: 'd_1', pushToken: 'T' }, { type: 'call_end' }, { ttlSec: 60 });
assert.strictEqual(fake.calls.length, 3);                                           // the access token was reused
fake.script({ status: 404, body: { error: { status: 'NOT_FOUND', details: [{ errorCode: 'UNREGISTERED' }] } } });
assert.deepStrictEqual(await push.send({ deviceId: 'd_1', pushToken: 'T' }, {}, { ttlSec: 60 }), { ok: false, dead: true, error: 'UNREGISTERED' });
fake.script(new Error('socket hang up'));
assert.strictEqual((await push.send({ deviceId: 'd_1', pushToken: 'T' }, {}, { ttlSec: 60 })).dead, false);   // a network error is not a dead phone
assert.deepStrictEqual(callData({ callId: 'c_1', target: 'far', expiresAt: 5 }), { type: 'call', v: '1', callId: 'c_1', from: 'home', who: 'far', expiresAt: '5' });
assert.strictEqual(callEndTtl('missed'), 21600); assert.strictEqual(callEndTtl('declined'), 60);
const out = tmpFile('outbox.jsonl'), filePush = createPush({ transport: 'file', outboxFile: out });
await filePush.send({ deviceId: 'd_1', pushToken: 'T' }, { type: 'call' }, { ttlSec: 60 });
assert.strictEqual(JSON.parse(fs.readFileSync(out, 'utf8').trim()).data.type, 'call');
assert.deepStrictEqual(await createPush({ transport: 'none' }).send({}, {}, {}), { ok: false, dead: false, error: 'push disabled' });
```

- [ ] **Step 2: Run it.** `node test/push.test.js` → FAIL: `Cannot find module '../src/push'`.
- [ ] **Step 3: Implement** `src/push.js` to the interface above.
- [ ] **Step 4: Run.** `node test/push.test.js` → `ALL GOOD`; `npm test` passes.
- [ ] **Step 5: Commit.** `feat(call-backend): push sender (FCM HTTP v1, file and none transports)`

### Task 6: Event stream for the kiosk

**Files:**
- Create: `call-backend/src/kiosk-events.js`, `call-backend/test/events.test.js`
- Modify: `call-backend/src/app.js` (route), `call-backend/src/server.js` (create the hub), `call-backend/test/helpers.js`

**Interfaces:**
- Produces: `createHub({ keepAliveMs = 20000 })` → `{ attach(req, res), send(event), online(), close() }`. `attach` writes status 200 with `Content-Type: text/event-stream`, `Cache-Control: no-cache, no-transform`, `X-Accel-Buffering: no`, then the frame `data: {"type":"hello"}\n\n`, then `: ka\n\n` every `keepAliveMs`; it forgets the response when the request closes. `send` writes `data: <json>\n\n` to every attached response and returns how many. `online()` is true while at least one is attached.
- Produces: `GET /api/kiosk/events` behind the existing kiosk-key check (contract C8). `createApp` takes `hub`.
- Produces in `helpers.js`: `fakeHub()` → `{ online: () => state.online, send, attach() {}, sent: [], state: { online: true } }` (`send` records the event and returns `state.online ? 1 : 0`); `wire()` returns `hub` = `deps.hub || fakeHub()`; `openEvents(url, headers)` → `Promise<{ status, contentType, next(), raw(ms), close() }>` where `next()` resolves with the next parsed `data:` frame and `raw(ms)` with the raw text received in the next `ms`.

- [ ] **Step 1: Write the failing test** `test/events.test.js`:

```js
const hub = createHub({ keepAliveMs: 30 }), w = wire({}, { hub }), { url } = await startApp(w.app);
assert.strictEqual((await openEvents(url + '/api/kiosk/events', {})).status, 401);
assert.strictEqual(hub.online(), false);
const ev = await openEvents(url + '/api/kiosk/events', { 'X-Device-Key': 'k' });
assert.strictEqual(ev.status, 200); assert.match(ev.contentType, /^text\/event-stream/);
assert.deepStrictEqual(await ev.next(), { type: 'hello' }); assert.strictEqual(hub.online(), true);
assert.strictEqual(hub.send({ type: 'incoming', callId: 'c_1', from: 'far' }), 1);
assert.deepStrictEqual(await ev.next(), { type: 'incoming', callId: 'c_1', from: 'far' });
assert.ok((await ev.raw(200)).includes(': ka'));                              // keep-alive comments flow
ev.close(); await wait(50);
assert.strictEqual(hub.online(), false); assert.strictEqual(hub.send({ type: 'x' }), 0);
```

- [ ] **Step 2: Run it.** `node test/events.test.js` → FAIL: `Cannot find module '../src/kiosk-events'`.
- [ ] **Step 3: Implement** the hub, the route and the helpers.
- [ ] **Step 4: Run.** `node test/events.test.js` → `ALL GOOD`; `npm test` passes.
- [ ] **Step 5: Commit.** `feat(call-backend): event stream for the kiosk`

### Task 7: Call manager – kids call a parent, «Ikke nå», clean-up

**Files:**
- Create: `call-backend/src/calls.js`, `call-backend/test/calls.test.js`
- Modify: `call-backend/src/app.js` (call routes delegate to the manager; add the phone's `/answer` and `/decline`), `call-backend/src/livekit.js`, `call-backend/src/server.js`, `call-backend/test/helpers.js`, `call-backend/test/api.test.js`

**Interfaces:**
- Consumes: `createDevices` (2), `callData`, `callEndData`, `callEndTtl`, `pushFromConfig` (5), `createHub` / `fakeHub` (6), helpers (1).
- Produces in `helpers.js`: `fakePush()` → `{ name: 'fake', send, sent: [], results: [] }` (each `send` records `{ device, data, opts }` and returns `results.shift() || { ok: true }`). `wire()` now also returns `push` (`deps.push || fakePush()`), `timers` (`fakeTimers()`), `calls`, and `phone(who)` – pairs a device, gives it a push token, and returns its stored record plus `secret`.
- Produces: `livekit.participantCount(room)` → `Promise<number | null>` (`null` when the admin API fails; never throws). `/healthz` gains `livekitAdmin: true | false`, set by one probe at start-up.
- Produces: `createCalls({ config, livekit, devices, push, discord, hub, timers, now, log })` → `{ startFromHome(target), answerByLink(answerToken), answerByParent(callId, device), decline(callId, by, device?), cancel(callId), end(callId), get(callId), hasActive() }`. Errors are `CallError` with `.status` and `.message`. `createApp` takes `calls` and holds no call state of its own.
  - A call is `{ id, room: 'family-' + id, target, direction: 'home_to_parent' | 'parent_to_home', status: 'ringing' | 'connected' | 'ended' | 'declined' | 'missed' | 'cancelled', via }`. `get` returns `{ callId, status, target, direction }` or `null`. Final calls are forgotten after 60 s, as today.
  - `startFromHome`: keeps today's checks (unknown target 400, one active call 409, more than 10 starts a minute 429). If `devices.reachableFor(target)` is not empty: send `callData` to each with `ttlSec = config.ringSec`; a result with `dead` → `devices.markPushDead`; if at least one send was `ok` → `via: 'app'`. Otherwise fall through to the Discord link exactly as today → `via: 'discord'`. The "parent has a Discord user id" check applies only on the Discord path.
  - `answerByParent`: 404 `unknown`, 403 `wrong_parent` when `device.who !== call.target`, 410 `over` when final, 409 `answered` when already connected; else connected, and `callEndData` with reason `answered_elsewhere` to that parent's other reachable phones.
  - `decline(callId, 'parent', device)`: only while ringing → `declined`; `hub.send({ type: 'declined', callId })`; `call_end` `declined` to the other phones.
  - Ring timeout → `missed`; `cancel` or `end` while ringing → `cancelled`. Both send `call_end` with `callEndTtl(reason)` to every reachable phone of the target when `via === 'app'`, and edit the Discord DM when `via === 'discord'`. The DM texts are today's four, unchanged; the one difference is that hanging up while it still rings now gets the existing «⚪ Anropet ble avbrutt hjemmefra.» instead of «✅ Samtalen er avsluttet.». The `/end` and `/cancel` routes answer `{ status: <the call's final status> }`.
  - Every way of finishing deletes the LiveKit room and sends `{ type: 'ended', callId }` to the hub.
  - Watchdog, every 15 s, for each connected call: `participantCount(room) < 2` on two checks in a row → `ended`; `null` never counts; `now - connectedAt > config.maxCallSec * 1000` → `ended`.

- [ ] **Step 1: Write the failing test** `test/calls.test.js` (each block starts from a fresh `wire()`):

```js
{ const x = wire(), far = x.phone('far');                                          // the app path
  const s = await x.calls.startFromHome('far');
  assert.strictEqual(s.via, 'app'); assert.strictEqual(x.discord.dms.length, 0);
  assert.deepStrictEqual(x.push.sent[0].data, { type: 'call', v: '1', callId: s.callId, from: 'home', who: 'far', expiresAt: String(s.expiresAt) });
  assert.strictEqual(x.push.sent[0].opts.ttlSec, 60);
  const a = await x.calls.answerByParent(s.callId, far);
  assert.strictEqual(a.livekit.token, 'tok:family-' + s.callId + ':parent-far'); }
{ const x = wire();                                                                // no phone: the Discord link, as today
  const s = await x.calls.startFromHome('mor'); assert.strictEqual(s.via, 'discord'); assert.strictEqual(x.discord.dms.length, 1); }
{ const x = wire(); x.phone('far'); x.push.results.push({ ok: false, dead: true, error: 'UNREGISTERED' });   // Review Focus 3
  const s = await x.calls.startFromHome('far');
  assert.strictEqual(s.via, 'discord'); assert.strictEqual(x.discord.dms.length, 1); assert.strictEqual(x.devices.reachableFor('far').length, 0); }
{ const x = wire(), far = x.phone('far'); const s = await x.calls.startFromHome('far');     // «Ikke nå»
  x.calls.decline(s.callId, 'parent', far);
  assert.ok(x.hub.sent.some(e => e.type === 'declined' && e.callId === s.callId));
  assert.strictEqual(x.calls.get(s.callId).status, 'declined'); assert.strictEqual(x.calls.hasActive(), false);
  assert.deepStrictEqual(x.lk.deleted, ['family-' + s.callId]); }
{ const x = wire(), a = x.phone('far'), b = x.phone('far'); const s = await x.calls.startFromHome('far');   // two phones
  await x.calls.answerByParent(s.callId, a);
  await assert.rejects(x.calls.answerByParent(s.callId, b), e => e.status === 409);
  assert.ok(x.push.sent.some(p => p.device.deviceId === b.deviceId && p.data.reason === 'answered_elsewhere')); }
{ const x = wire(), far = x.phone('far'), mor = x.phone('mor'); const s = await x.calls.startFromHome('far');
  await assert.rejects(x.calls.answerByParent(s.callId, mor), e => e.status === 403);
  await assert.rejects(x.calls.answerByParent('c_nope', far), e => e.status === 404); }     // Review Focus 5
{ const x = wire(); x.phone('far'); const s = await x.calls.startFromHome('far');           // nobody answers
  x.timers.advance(60000);
  assert.strictEqual(x.calls.get(s.callId).status, 'missed');
  const end = x.push.sent.find(p => p.data.type === 'call_end'); assert.strictEqual(end.data.reason, 'missed'); assert.strictEqual(end.opts.ttlSec, 21600); }
{ const x = wire(); x.phone('far'); const s = await x.calls.startFromHome('far');           // the child hangs up while it rings
  x.calls.end(s.callId);
  assert.strictEqual(x.calls.get(s.callId).status, 'cancelled');
  assert.strictEqual(x.push.sent.find(p => p.data.type === 'call_end').data.reason, 'cancelled'); }
{ const x = wire(), far = x.phone('far'); const s = await x.calls.startFromHome('far');     // Review Focus 1
  await x.calls.answerByParent(s.callId, far);
  x.lk.state.count = null; x.timers.advance(60000); assert.strictEqual(x.calls.hasActive(), true);   // API errors end nothing
  x.lk.state.count = 1; x.timers.advance(15000); assert.strictEqual(x.calls.hasActive(), true);
  x.timers.advance(15000); assert.strictEqual(x.calls.hasActive(), false);
  assert.strictEqual((await x.calls.startFromHome('far')).status, 'ringing'); }               // the line is free again
{ const x = wire({ CALL_MAX_SECONDS: '120' }), far = x.phone('far'); const s = await x.calls.startFromHome('far');
  await x.calls.answerByParent(s.callId, far); x.timers.advance(135000); assert.strictEqual(x.calls.hasActive(), false); }
```

- [ ] **Step 2: Run it.** `node test/calls.test.js` → FAIL: `x.phone is not a function`.
- [ ] **Step 3: Implement** `src/calls.js`, `participantCount`, the helper additions, and move the call routes in `app.js` onto the manager. Add to `test/api.test.js`: a paired phone answers over HTTP (`POST /api/calls/:id/answer` with `bearer` → 200 and a `parent-far` token), declines another call over HTTP (200), and gets 401 without a bearer or kiosk key.
- [ ] **Step 4: Run.** `node test/calls.test.js` and `node test/api.test.js` → `ALL GOOD`; `npm test` passes, including the Task 1 assertions unchanged.
- [ ] **Step 5: Commit.** `feat(call-backend): call manager – app or Discord link per parent, decline, stale-call clean-up`

### Task 8: Call manager – a parent calls home

**Files:**
- Modify: `call-backend/src/calls.js`, `call-backend/src/app.js`, `call-backend/test/calls.test.js`, `call-backend/test/api.test.js`

**Interfaces:**
- Consumes: Task 7's manager and `wire()`.
- Produces: `startFromParent(device)` → `{ callId, status: 'ringing', livekit: { url, token }, expiresAt }` (token identity `config.parents[device.who].identity`); `answerByKiosk(callId)` → `{ callId, livekit }` (identity `tablet-stue`); `decline(callId, 'kiosk')`.
  - `startFromParent`: `hub.online()` false → `CallError(503, 'kiosk_offline')`; an active call → `CallError(409, 'busy')`; the same 10-a-minute limit → 429; else a `parent_to_home` call with `target = device.who`, and `hub.send({ type: 'incoming', callId, from: device.who, expiresAt })`.
  - `answerByKiosk`: only a ringing `parent_to_home` call; otherwise 404 / 410 / 409 as in Task 7.
  - `decline(callId, 'kiosk')` → `declined`. Ring timeout → `missed`. `cancel` while ringing → `cancelled` and `hub.send({ type: 'cancelled', callId })`. No push is sent for this direction: the caller reads the outcome from `GET /api/calls/:id` (contract C6).
- Produces: contract C6 routes. `POST /api/calls/:id/answer` and `/decline` choose by credential: a valid bearer → the parent functions; otherwise the kiosk-key check → the kiosk functions; neither → 401.

- [ ] **Step 1: Write the failing tests** (append to `test/calls.test.js`):

```js
{ const x = wire(), far = x.phone('far'); x.hub.state.online = false;                        // Review Focus 2
  await assert.rejects(x.calls.startFromParent(far), e => e.status === 503 && e.message === 'kiosk_offline');
  assert.strictEqual(x.calls.hasActive(), false); }
{ const x = wire(), far = x.phone('far');
  const s = await x.calls.startFromParent(far);
  assert.strictEqual(s.livekit.token, 'tok:family-' + s.callId + ':parent-far');
  assert.deepStrictEqual(x.hub.sent[0], { type: 'incoming', callId: s.callId, from: 'far', expiresAt: s.expiresAt });
  assert.strictEqual(x.push.sent.length, 0);
  await assert.rejects(x.calls.startFromHome('mor'), e => e.status === 409);                  // the kiosk is busy
  const k = await x.calls.answerByKiosk(s.callId);
  assert.strictEqual(k.livekit.token, 'tok:family-' + s.callId + ':tablet-stue');
  assert.strictEqual(x.calls.get(s.callId).direction, 'parent_to_home'); }
{ const x = wire(), far = x.phone('far'), mor = x.phone('mor'); await x.calls.startFromParent(far);
  await assert.rejects(x.calls.startFromParent(mor), e => e.status === 409 && e.message === 'busy'); }
{ const x = wire(), far = x.phone('far'); const s = await x.calls.startFromParent(far);
  x.calls.decline(s.callId, 'kiosk'); assert.strictEqual(x.calls.get(s.callId).status, 'declined'); }
{ const x = wire(), far = x.phone('far'); const s = await x.calls.startFromParent(far);
  x.calls.cancel(s.callId); assert.ok(x.hub.sent.some(e => e.type === 'cancelled' && e.callId === s.callId)); }
{ const x = wire(), far = x.phone('far'); const s = await x.calls.startFromParent(far);
  x.timers.advance(60000); assert.strictEqual(x.calls.get(s.callId).status, 'missed'); }
```

and to `test/api.test.js`: `POST /api/calls/home` without a bearer → 401; with one → 200; `POST /api/calls/<id>/answer` with `X-Device-Key` → 200 and a `tablet-stue` token; `GET /api/calls/<id>` → `direction: 'parent_to_home'`.

- [ ] **Step 2: Run them.** `node test/calls.test.js` → FAIL: `x.calls.startFromParent is not a function`.
- [ ] **Step 3: Implement** the three functions and the routes.
- [ ] **Step 4: Run.** `npm test` → every file `ALL GOOD`.
- [ ] **Step 5: Commit.** `feat(call-backend): a parent can ring the kiosk`

### Task 9: Kiosk – listen to the API, join a waiting call, react to «Ikke nå»

**Files:**
- Modify: `index.html` (only inside the main `<script>`: the Calls section around `incomingCall` / `startCallLiveKit`, `renderStatus`, `init`), `test/smoke.js`, `DESIGN.md` (§3.8, §3.10, §4 «Parent rings home», §5)

**Interfaces:**
- Consumes: contracts C4, C6, C8 and the `declined` event of C5.
- Produces, inside the IIFE:
  - `let beState = 'off'` (`'off' | 'connecting' | 'on' | 'denied'`) and `let incomingCallId = null`.
  - `listenBackend()` – started from `init()` after `listenDiscord()` when `cfg.call.backend` is non-empty. One `fetch(base + '/api/kiosk/events', { headers: { 'X-Device-Key': … }, cache: 'no-store', signal })`, read with `r.body.getReader()` and a `TextDecoder`, split on blank lines, `JSON.parse` each `data:` payload → `onBackendEvent`. A response that is not ok, has no readable body, ends, errors, or stays silent for 45 s → reconnect after a backoff that starts at 2 s and grows ×1.5 to at most 60 s. Status 401 or 403 → `beState = 'denied'` and no retry. Nothing is ever shown on screen.
  - `onBackendEvent(ev)`: `incoming` → `incomingCall(ev.from, ev.callId)`, or – when the kiosk is already in a call or already showing an incoming call – an immediate `POST …/api/calls/<id>/decline`; `cancelled` → `dismissIncoming(false)` when `incomingCallId === ev.callId`; `declined` → when `currentCallId === ev.callId` and the calling layer is still showing: stop the ring, set `#call-status` to the existing `p.name + ' kunne ikke svare nå 😢'`, speak the existing sentence, `endCall` after 4500 ms; `ended` → `endCall()` when `currentCallId === ev.callId`. Unknown types are ignored.
  - `incomingCall(who, callId)` – unchanged look; remembers `callId`. `dismissIncoming(tellBackend)` – when `tellBackend` and there is an `incomingCallId`, `POST …/api/calls/<id>/decline` with the kiosk key. «Ikke nå» and the 45 s timer pass `true`.
  - `joinCall(who, callId)` – used by «Svar» when `incomingCallId` is set (otherwise «Svar» calls `startCall(who)` as today, which keeps #ring-hjem working): same overlay, no ring tone, `#call-status` = the existing `p.name + ' er med – venter på bildet…'`, `POST …/api/calls/<id>/answer` with the kiosk key, then the same LiveKit connect as an outgoing call. A failed answer shows the existing «Klarte ikke å starte samtalen 😕».
  - `connectCall(who, livekit)` – the LiveKit part lifted out of `startCallLiveKit` so both paths share it.
  - `renderStatus`: when `beState === 'on'` the «… ringer hjem» row reads `'Appen ✓'`, followed by `' · '` and the row's previous text when another listener is on, with the green dot.
- No markup, style or settings entry changes.

- [ ] **Step 1: Write the failing tests** – a fifth load at the end of `test/smoke.js`. In `beforeParse`: `win.TextDecoder = require('util').TextDecoder`, saved settings `{ call: { backend: 'https://api.test', deviceKey: 'k' }, notify: { provider: 'none' } }`, and a `fetch` whose `/api/kiosk/events` answer has a `body.getReader()` fed by `feed(obj)` (enqueues `data: <json>\n\n` as bytes) and `endStream()`, and whose `/api/calls/<id>/answer` answers `{ callId, livekit: { url: 'wss://rtc.test', token: 'tok' } }`. `evReqs()` are the recorded fetches to `/api/kiosk/events`; `posts(re)` the recorded POSTs whose URL matches:

```js
await wait(60);
assert(evReqs().length === 1 && evReqs()[0].opts.headers['X-Device-Key'] === 'k', 'kiosk opens the event stream with its key');
feed({ type: 'hello' }); feed({ type: 'incoming', callId: 'c_h1', from: 'far' }); await wait(30);
assert(!q5('#incoming').classList.contains('hidden') && q5('#in-name').textContent === 'Far ringer!', 'a parent calling from the app rings the kiosk');
feed({ type: 'incoming', callId: 'c_h0', from: 'mor' }); await wait(30);
assert(posts(/\/api\/calls\/c_h0\/decline$/).length === 1 && q5('#in-name').textContent === 'Far ringer!', 'a second caller is declined at once');
click5(q5('#answer')); await wait(60);
assert(posts(/\/api\/calls\/c_h1\/answer$/).length === 1 && posts(/\/api\/calls\/c_h1\/answer$/)[0].opts.headers['X-Device-Key'] === 'k', 'Svar joins the waiting call');
assert(posts(/\/api\/calls$/).length === 0, 'Svar does not place a new outgoing call');
assert(q5('#call-status').textContent === 'Far er med – venter på bildet…', 'joining text: ' + q5('#call-status').textContent);
click5(q5('#hangup'));
feed({ type: 'incoming', callId: 'c_h2', from: 'mor' }); await wait(30); click5(q5('#decline')); await wait(30);
assert(posts(/\/api\/calls\/c_h2\/decline$/).length === 1, 'Ikke nå tells the API');
feed({ type: 'incoming', callId: 'c_h3', from: 'mor' }); await wait(30); feed({ type: 'cancelled', callId: 'c_h3' }); await wait(30);
assert(q5('#incoming').classList.contains('hidden') && posts(/\/api\/calls\/c_h3\/decline$/).length === 0, 'the caller giving up closes the overlay quietly');
click5(q5('#card-far')); await wait(60);                                   // the mocked POST /api/calls answers callId 'c_test'
feed({ type: 'declined', callId: 'c_test' }); await wait(30);
assert(q5('#call-status').textContent === 'Far kunne ikke svare nå 😢', 'the parent pressing Ikke nå ends the ringing: ' + q5('#call-status').textContent);
endStream(); await wait(2300);
assert(evReqs().length === 2, 'the kiosk reconnects by itself when the stream ends');          // Review Focus 2
```

Also: in the **first** load (defaults only), after its initial `wait(300)`: `assert(q('#incoming').classList.contains('hidden') && q('#toast').classList.contains('hidden'), 'a failing event stream shows nothing')`. And a sixth load whose `/api/kiosk/events` answers 401: after `wait(2500)` there is still exactly one request to it.

- [ ] **Step 2: Run them.** `npm test` in the repo root → FAIL: `ASSERT: kiosk opens the event stream with its key`.
- [ ] **Step 3: Implement** the functions above in `index.html`.
- [ ] **Step 4: Run.** `npm test` → `ALL GOOD`.
- [ ] **Step 5: Prove nothing visible changed.** `grep -n '<script src="config.js">' index.html` gives line N. `git diff -U0 index.html | grep '^@@'` → every hunk starts after line N.
- [ ] **Step 6: Update `DESIGN.md`** – §3.8 gains `listenBackend`, §3.10 `joinCall` / `connectCall`, §4 «Parent rings home» describes the app path next to #ring-hjem, §5 gets the event stream (contract C8). Say plainly that this is built but not yet deployed.
- [ ] **Step 7: Commit.** `feat(call): the kiosk listens to the call API – a parent can ring home from the app, «Ikke nå» ends the ringing`

### Task 10: A separate dev copy, a stand-in phone, and one run of everything together

**Files:**
- Create: `call-backend/.env.dev.example`, `call-backend/scripts/fake-phone.js`, `call-backend/dev/join.html`, `call-backend/test/e2e.test.js`
- Modify: `.gitignore` (add `.dev/`), `call-backend/.dockerignore` (add `dev`, `scripts`, `test`, `.env.dev.example`), `call-backend/README.md` (section «Developing on a separate copy»), `DESIGN.md` §11

**Interfaces:**
- Consumes: everything above.
- Produces: `.env.dev.example` with `PORT=3100`, `ADMIN_PORT=3101`, `ADMIN_PASSWORD=dev`, `DEVICE_KEY=dev-key`, `DATA_DIR=../.dev/callapi-data`, `PUSH_TRANSPORT=file`, `CALL_PUBLIC_URL=http://127.0.0.1:3100`, `DISCORD_BOT_TOKEN=` (empty on purpose: the dev copy can never message anyone), `DISCORD_MOR_USER_ID=1`, `DISCORD_FAR_USER_ID=2`, `LIVEKIT_URL=wss://rtc.noobventure.com`, and empty `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` for the developer to fill. Start command, exact, from `call-backend/`: `DOTENV_CONFIG_PATH=../.dev/callapi.env node src/server.js`.
- Produces: `scripts/fake-phone.js` with commands `pair <code>` (redeems, stores the credential in `<DATA_DIR>/fake-phone.json`, registers push token `fake-<deviceId>`), `listen [--answer | --decline]` (follows `<DATA_DIR>/push-outbox.jsonl`, prints each push, and on `type: call` answers or declines through the API, printing a `dev/join.html#url=…&token=…` link on answer), and `ring-home` (calls `POST /api/calls/home`, prints the join link, then polls `GET /api/calls/:id` and prints «Ingen svarte hjemme», «Opptatt hjemme» or «Skjermen hjemme er ikke tilkoblet» as the app would).
- Produces: `dev/join.html` – opened from disk, never served: joins the room given in the URL fragment with camera and microphone using `livekit-client@2.22.3`, shows the other side's video, has «Legg på».

- [ ] **Step 1: Write the failing test** `test/e2e.test.js` – the real modules together: `wire({}, { hub: createHub(), push: createPush({ transport: 'file', outboxFile }) })`, both apps started with `startApp`; `outbox()` parses the outbox file into an array:

```js
const pc = await http('POST', admin + '/api/pair-code', { who: 'far' }, basic('x', 'pw'));
const dev = (await http('POST', pub + '/api/pair', { code: pc.body.pretty, name: 'test', appVersion: '0' })).body;
await http('PUT', pub + '/api/devices/me/push-token', { token: 'fake-1' }, bearer(dev));
const s = (await http('POST', pub + '/api/calls', { target: 'far' }, K)).body;            // kids call far
assert.strictEqual(s.via, 'app');
assert.strictEqual(outbox()[0].data.callId, s.callId);                                    // the push went out
assert.strictEqual((await http('POST', pub + '/api/calls/' + s.callId + '/answer', {}, bearer(dev))).status, 200);
await http('POST', pub + '/api/calls/' + s.callId + '/end', {});
const kiosk = await openEvents(pub + '/api/kiosk/events', K);                             // far calls home
assert.deepStrictEqual(await kiosk.next(), { type: 'hello' });
const h = (await http('POST', pub + '/api/calls/home', {}, bearer(dev))).body;
assert.strictEqual((await kiosk.next()).callId, h.callId);
assert.strictEqual((await http('POST', pub + '/api/calls/' + h.callId + '/decline', {}, K)).status, 200);
assert.strictEqual((await http('GET', pub + '/api/calls/' + h.callId)).body.status, 'declined');
kiosk.close(); await wait(50);
assert.strictEqual((await http('POST', pub + '/api/calls/home', {}, bearer(dev))).status, 503);
```

- [ ] **Step 2: Run it.** `node test/e2e.test.js`. It can only fail where two modules disagree; fix the one that departs from the Contract.
- [ ] **Step 3: Write** `.env.dev.example`, `fake-phone.js`, `dev/join.html`, the ignore entries and the README section.
- [ ] **Step 4: Run the dev copy by hand, both directions.** Copy the example to `.dev/callapi.env`, fill in the LiveKit key, start the backend. Open the pairing page at `http://127.0.0.1:3101`, make a key for Far, run `node scripts/fake-phone.js pair <key>`. Serve the repo folder on `http://127.0.0.1:<port>` and open `index.html` in a Chrome profile whose saved settings set `call.backend` to `http://127.0.0.1:3100`, `call.deviceKey` to `dev-key` and `notify.provider` to `none`. Then:
  - `fake-phone.js listen --answer`, press «Ring far», open the printed join link → picture and sound both ways; «Legg på» on either side ends both.
  - `listen --decline`, press «Ring far» → the kiosk shows «Far kunne ikke svare nå 😢» within about 3 seconds.
  - `fake-phone.js ring-home`, open its join link → the kiosk shows «Far ringer!»; «Svar» connects. Repeat with «Ikke nå» → the script prints «Ingen svarte hjemme».
  - Close the kiosk page, `ring-home` → «Skjermen hjemme er ikke tilkoblet».
  - Remove the phone on the pairing page → `listen` gets 401 on its next request.
- [ ] **Step 5: Run everything.** `npm test` in the repo root and in `call-backend/` → `ALL GOOD` in both.
- [ ] **Step 6: Update `DESIGN.md` §11** with what part 1 added, and that production is unchanged until part 3.
- [ ] **Step 7: Commit.** `feat(call-backend): dev copy, stand-in phone and an end-to-end test`
