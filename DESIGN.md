# Menkerud Home – design document

Kids' touchscreen dashboard for a family home in Raufoss, Norway. The children cannot read yet, so the main screen is three giant picture buttons (call mor, call far, "I'm home") and a sticky-note board with emoji. Parents configure everything behind a PIN. Norwegian UI (bokmål), English code and docs.

Status: v0.4. **Video calls run on LiveKit** (`call-backend/`, §11); Jitsi was removed on 2026-09-10. The front-end was redesigned around a scenic background, glass panels, richer call cards and two new panels — **I DAG** (today's timed notes) and **KVELD** (Home Assistant evening actions); the art pack lives in `assets/`. The LiveKit backend is built and unit-tested but **not yet deployed on Tower**, so calls show a "not set up" message until `cfg.call.backend` points at a running service. Not yet run on the real touchscreen PC. Everything in this document is implemented unless listed under *Backlog* or *Planned*.

## 1. Files

| File | Role |
|---|---|
| `index.html` | The entire app: CSS + HTML + one IIFE of vanilla JS. No build step, no dependencies, no bundler. ~1700 lines. |
| `config.js` | Optional defaults, loaded by a `<script src>` before the app. Sets `window.MENKERUD_CONFIG`. Missing file = fine. Holds the family's real webhook/tokens → gitignored. `config.example.js` is the committed template. |
| `ring.html` | Tiny standalone page for the parents' phones (ntfy path only): publishes to the kiosk's ntfy topic, then opens the call-room link. |
| `photos/mor.jpg`, `photos/far.jpg` | Optional photos. Photos picked on-screen are stored as data URLs in settings instead. |
| `assets/` | Redesign art pack referenced by relative path from `index.html`: `backgrounds/` (day/evening/night, 1920×1080), `avatars/` (placeholder mor/far), plus `icons/`, `notes/`, `ui/`, `status/`, `decoration/`. `ASSETS.md` documents it. |
| `SETUP.md` | Operator guide (Discord, ntfy, the touchscreen PC, optional Home Assistant). Video calls: `call-backend/README.md`. |
| `test/smoke.js` | jsdom end-to-end smoke test. `npm test`. |
| `scripts/check.js` | Extracts the inline script and runs `node --check`. Part of `npm test`. |

## 2. Runtime model

One page of static files, shown on a Linux desktop PC (Ubuntu) with a touchscreen, webcam and mic. In production a **local nginx serves it and Firefox opens `http://localhost`** fullscreen (SETUP.md §3); it also still works opened straight from `file://`. Non-negotiable constraints on wherever it is served:

- The origin must be a **secure context** so `getUserMedia` (the LiveKit call) works. `http://localhost`, `https://` and `file://` all qualify; **`http://<LAN-IP>` does not** and would silently lose camera/mic — never serve the screen on the LAN IP over plain http.
- The page must not be subject to **mixed-content blocking** of the `ws://` link to Home Assistant on the LAN. An `http://localhost` page (or `file://`) is fine; a `https://` page would need Home Assistant over `wss://`.
- **Skjerm av** relies on the Screen Wake Lock API (secure context – `http://localhost` qualifies) plus a short GNOME `idle-delay` on the kiosk; without the API the page simply never blanks (keep `idle-delay` 0 there). **Bilderamme** relies on an nginx folder listing of `photos/` or a hand-written `photos/index.json`.
- nginx serves static files only — **no server-side logic**, and the page keeps working with nothing configured (clock/notes/night mode offline). Serving from localhost also keeps `config.js` and its token off the network.

The page must keep working with *nothing* configured (first run shows sample notes, live weather for the default place, and a friendly "not set up" message when calling). Every external dependency degrades gracefully.

### Configuration layering

```
DEFAULTS (in index.html)  ←  window.MENKERUD_CONFIG (config.js)  ←  localStorage "menkerud.settings"
```

`cfg = deepMerge(deepMerge(DEFAULTS, MENKERUD_CONFIG), SAVED)`. Objects merge recursively, arrays replace. `localStorage` access is wrapped in `store` with an in-memory fallback (sandboxed previews throw on `localStorage`). Saving settings writes only the keys the settings form manages (plus photos and weather lat/lon) and then reloads the page; there is no live re-config.

Storage keys: `menkerud.settings` (object), `menkerud.notes` (array of `{uid, summary}`, seeded with 5 sample notes on first run), `menkerud.calendar` (array of `{uid, date 'YYYY-MM-DD', time?, icon, text, repeat 'none'|'daily'|'weekly'|'weekdays'}`, empty on first run).

### Full config schema

```js
{
  home:    { name, subtitle, tagline /* "\n" = line break */, footer,
             homeSub /* line under "Jeg er hjemme" */, background /* 'auto'|'day'|'evening'|'night'|'none' */ },
  weather: { place, lat, lon },                       // Open-Meteo; "Slå opp" geocodes place → lat/lon
  people:  { mor: { name, photo /* path or data URL, "" = none */, emoji /* fallback */, callSub /* line under "Ring mor" */ }, far: {…} },
  call:    { backend /* '' = same-origin /api via nginx; or a full URL */, deviceKey /* optional shared secret */, timeoutSec },
  notify: {
    provider: 'discord' | 'ntfy' | 'ha' | 'webhook' | 'none',
    discord: { webhook, userMor, userFar, botToken, ringChannel },
    ntfy:    { server, topicMor, topicFar, topicKiosk, token },
    webhook: { url }
  },
  ha: { url, token, weatherEntity, todoEntity, ttsEntity /* "" = auto: first weather.* / todo.* / tts.* */,
        ttsLanguage, includeSwitches, scripts: { mor, far, home }, callEvent },
  pin,                                                // string of digits, "" = no PIN
  night: { enabled, from, to },                       // "HH:MM" – dim overlay
  screen: { offEnabled, offFrom, offTo },             // "HH:MM" – display off (wake lock released); ⏻ button any time
  photos: { dir, intervalSec },                       // Bilderamme: folder (index.json or nginx autoindex), seconds per picture
  emojis: [ … ]                                       // picker under Ny lapp
}
```

Person keys are `mor` / `far` throughout (`cfg.people`, `data-who`, `data-name`, card ids `#card-mor`, CSS classes `.mor`/`.far`, push targets). Display names come from `cfg.people.X.name`; never hard-code "Mor"/"Far" in UI strings.

### Layout system

`html { font-size: min(1vw, 1.7778vh) }` → **1rem = 1 % of screen width on a 16:9 screen**, so every size in the CSS is in `rem` and the whole layout scales to any resolution. `body` is a 4-row grid (header / cards `1fr` / **bottom-grid** / footer) locked to `100vh`, `overflow: hidden`. The bottom row is a three-column grid — **I DAG · HUSK… · KVELD** (`.bottom-grid`, `~1 / 2 / 1`); when Home Assistant is not connected the KVELD column is removed and the grid falls back to two columns (`.no-evening`). A fixed scenic photo sits behind everything (`#bg`, set by `pickBackground`) over a CSS aurora/gradient fallback, under a legibility gradient. The primary target is 1920×1080 landscape (design canvas); narrower landscape sizes only tighten spacing. Portrait shows a "snu skjermen" message rather than reflowing. Fonts: Nunito (UI) and Caveat (handwriting: tagline, notes, footer) from Google Fonts with system fallbacks; emoji rely on the OS emoji font (`fonts-noto-color-emoji` on Linux).

## 3. Module map (order inside the IIFE in `index.html`)

1. **Defaults & storage** – `DEFAULTS`, `SAMPLE_NOTES`, `deepMerge`, `getPath`/`setPath`, `store`, `cfg`, `haOn`, `toast`, overlay helpers, `setAvatar`.
2. **`class HA`** – minimal Home Assistant WebSocket client: auth, incrementing ids, `send`, `subscribe`, `callService`, ping every 30 s, reconnect with backoff. `ha.onready` fetches `get_states`, picks entities, subscribes to `state_changed`, `todo/item/subscribe`, and the custom call event.
3. **Clock / weather / night / background** – `tick` (nb-NO locale; re-renders the schedule on the minute), `renderWeather` (HA entity wins, else Open-Meteo `wxOpen`), `fetchOpenMeteo` every 15 min, `geocode`, night overlay (`nightMode` auto/on/off, `awakeUntil`, never while an overlay is open), `pickBackground` swaps the scenic background by clock or `home.background`. **Screen off / wake lock:** `checkNight` also derives `dark` (`restMode`, set by the ⏻ header button, or the `cfg.screen` window via `inScreenOff`/`inWindow`) → `#night.dark` (solid black) and `syncWakeLock(!dark)`: the page holds a Screen Wake Lock whenever it should be visible and releases it while dark, so GNOME's `idle-delay` blanks the display (SETUP.md §3); a touch on `#night` clears `restMode` and buys two minutes (`awakeUntil`). The page cannot un-blank a display – only a touch can. **Bilderamme:** `startFrame` → `listPhotos` (`<cfg.photos.dir>/index.json`, else the folder's nginx autoindex as JSON or HTML) → shuffled slides in `#frame` (`showSlide`: blurred backdrop + `object-fit: contain` image, 1.2 s crossfade every `intervalSec`, broken files skipped); any touch → `stopFrame` (+2 min awake); `showOverlay` (incoming call) and Escape stop it too. A running frame keeps the wake lock and ignores the night window.
4. **Sound** – WebAudio only: `ring` (425 Hz, 1 s on / 4 s off = Norwegian ringback), `chime` (C-E-G).
5. **`speak(text)`** – HA TTS via `media_source/resolve_media` → `<audio>`; fallback `speechSynthesis` `nb-NO`.
6. **Notes** – `localNotes` vs `haNotes`, `notesSource()`, `parseNote` (leading emoji cluster via `\p{Extended_Pictographic}`, optional `HH:MM`), `spokenTime` (klokka fem / kvart over / halv / kvart på, 12-hour), `renderNotes`, `addNote`, `removeNote`. `renderSchedule` merges the notes that carry a time **and today's calendar events** into the **I DAG** panel (all-day events first, then by time; past ones dimmed, one highlighted when near now) with a 06:00→21:00 day-progress bar. The **Kalender** pane (`renderCalendar`, `eventsOn`/`occursOn`, `addEvent`/`removeEvent`) is a month grid you tap to add dated events with an optional time and a simple repeat (daily / weekly / weekdays), stored in `menkerud.calendar`; it reuses the emoji picker, time stepper and docked keyboard.
7. **`sendPush(target, kind)`** – `target` = `'mor' | 'far' | 'both'`, `kind` = `'call' | 'again' | 'home' | 'test'`. Branches per provider. Throws with a Norwegian message on failure; callers toast it.
8. **Incoming listeners** – `listenNtfy` (WebSocket `/<topic>/ws`) and `listenDiscord` (gateway). Both reconnect with backoff and update `ntfyState` / `dcState` for the status panel. Both may run at once.
9. **`imHome`** – chime, confetti, overlay, TTS, push to both.
10. **Calls (LiveKit)** – `startCall(who)` → `startCallLiveKit`: `loadLiveKit` lazy-loads `livekit-client` from a CDN, POSTs `cfg.call.backend`/`api/calls` (with `X-Device-Key` if set), connects the returned room, attaches the parent’s remote video into `#callvideo` and hides the calling layer; `endCall` disconnects and POSTs `…/end`. `incomingCall(who)`/`dismissIncoming` still handle "parent rings home" (ntfy/Discord); **Svar** calls the parent back via LiveKit.
11. **Confetti** – canvas, 3.8 s.
12. **PIN** – `pinKey`, `openMenuGate` (`cfg.pin === ''` skips the gate).
13. **Menu** – `showPane(name)` for `home | note | calendar | lights | cams | settings`; `openMenu`/`closeMenu`.
14. **On-screen keyboard** – docked at the bottom of the panel, attaches to any focused `input.inp` inside `#panel` (`focusin`), `abc`/`sym` layouts, shift, caret-aware insert via `setRangeText`, dispatches `input` events so bindings update. `data-num="1"` inputs open in the symbol layout.
15. **Lights / cameras / KVELD** – all `light.*` (+ `switch.*` if configured) as tiles → `homeassistant.toggle`; cameras via MJPEG `/api/camera_proxy_stream/<id>?token=<access_token>`. `renderEvening` builds the **KVELD** panel's quick actions from HA state — *Demp belysning* (lights to 30 %), *God natt* (lights off + night mode on), *Lås dører* (`lock.lock`, only if a `lock.*` exists) — and hides the whole panel when HA is not connected.
16. **Settings** – `renderStatus` (7 status rows + test buttons), `SETTINGS` schema array → `buildForm(keepDraft)` → `draft` object → `saveSettings` → reload. Field kinds: text (`k`), `num` (numeric keyboard), `number` (coerced on save), `select`, `toggle`, `photo`, `geocode`, `show: '<provider>'` to hide unless that provider is selected.
17. **Ny lapp form** – emoji strip, time stepper, text input, live preview note, chip list with delete.
18. **`init()`** – applies texts/avatars, binds events, starts clock, weather, HA (if configured) and both listeners.

**To add a setting:** add a default in `DEFAULTS`, one entry in `SETTINGS` (it renders, drafts and persists automatically), and read it from `cfg` where needed. Add a status row in `renderStatus` if it has a connection state. Update the smoke test's "form rows" count if you assert on it.

## 4. Flows

**Kid presses "Ring mor"** → `startCallLiveKit('mor')` → overlay with photo + "Ringer mor…", ring tone, TTS "Ringer Mor" → `POST cfg.call.backend/api/calls {target:'mor'}` (the backend DMs the parent an **Svar** link) → connect to the returned LiveKit room; on the parent’s `TrackSubscribed` the remote video fills `#callvideo` and the calling layer hides → after `call.timeoutSec` still ringing: "Mor kunne ikke svare nå" and hang up. Big red "Legg på" always visible → `endCall` disconnects and tells the backend.

**Parent rings home** → message arrives on the kiosk topic (ntfy) or in the ring channel (Discord bot) or as the HA event → `incomingCall(who)` → overlay with photo, ring, TTS "Mor ringer!", green **Svar** / red **Ikke nå**, auto-dismiss after 45 s → Svar = `startCall(who, false)` (no push, no ring, "Kobler til…").

**Jeg er hjemme** → `imHome()` → celebration 4.5 s → `sendPush('both','home')`.

**Notes** → HA connected and a `todo.*` entity exists: the board mirrors that list (parents add from the Companion app; format `<emoji> [HH:MM] [text]`). Otherwise notes live in `localStorage` and are managed under Ny lapp. Tapping a note wiggles it and speaks `spokenNote`. **Calendar** → dated events added under Kalender live in `localStorage` (`menkerud.calendar`); today's events (including recurring ones) appear in the I DAG panel alongside timed notes.

**⏻ Skjerm av** → `screenOff()` → `#night.dark` + wake lock released → GNOME blanks the display after `idle-delay` → a touch: GNOME turns the display on, the touch hits `#night` → `restMode = false`, overlay hidden, lock re-acquired. The `screen.offFrom`–`offTo` window does the same on a schedule, but a touch there only buys two minutes. **🖼 Bilderamme** → `startFrame()` → `listPhotos()` → `#frame` shows shuffled slides (wake lock held) until a touch → `stopFrame()` → home screen, two minutes awake.

## 5. External protocols (exact)

**LiveKit** – the kiosk connects with `livekit-client` (`new Room(...)`, `room.connect(url, token)`, `localParticipant.enableCameraAndMicrophone()`, `RoomEvent.TrackSubscribed`/`Disconnected`). Rooms, participant tokens and the Discord answer-link DM are the backend’s job (`call-backend/`, `livekit-server-sdk`); the kiosk never holds the API secret. Signaling is WSS through SWAG (`rtc.`), media is a single UDP port (7882) direct to Tower’s public IP.

**ntfy** – publish: `POST <server>/` with JSON body `{topic, title, message, priority (5 = max), click, actions:[{action:'view', label, url, clear:true}]}`; optional `Authorization: Bearer tk_…`. Sent as a plain string body (no Content-Type) so the request is CORS-simple. Subscribe: `WebSocket <server ws(s)>/<topic>/ws`, optional `?auth=<base64('Bearer tk_…') without '='>`; messages are JSON lines with `event: 'open' | 'keepalive' | 'message'`. A message whose text contains `mor`/`far` (word) or the legacy `mamma`/`pappa` rings the kiosk as that parent.

**Discord** – outgoing via **webhook**: `POST <webhook url>` JSON `{content, username}`; `content` starts with `<@userId>` mentions (or `@here` if no ids) so phones get notified under "only @mentions". Incoming via **gateway**: `wss://gateway.discord.gg/?v=10&encoding=json`; op 10 Hello → start heartbeat (op 1 with last `s`) and send op 2 Identify `{token, intents: 513 /* GUILDS | GUILD_MESSAGES */, properties}`; op 11 = ack (missing ack → close/reconnect); op 7/9 → reconnect; `t: 'READY'` → state on; `t: 'MESSAGE_CREATE'` in `ringChannel` from a non-bot, non-webhook author → `incomingCall(author.id === userFar ? 'far' : 'mor')`. No privileged intents (message content is not read), so any human message in that channel rings. A bot token may have several concurrent sessions, so the parents' existing bot can be reused.

**Home Assistant WebSocket** – `/api/websocket`: `auth`, `get_states`, `subscribe_events {event_type}`, `todo/item/subscribe {entity_id}` (events carry `{items:[{uid, summary, status}]}`), `call_service {domain, service, service_data, target}` (`todo.add_item {item}`, `todo.remove_item {item: uid}`, `script.turn_on`, `homeassistant.toggle`, `light.turn_off`), `media_source/resolve_media {media_content_id: 'media-source://tts/<tts entity>?message=…&language=no'}` → `{url}` (relative `/api/tts_proxy/…`, unauthenticated). Camera MJPEG uses the entity's `access_token` attribute.

**Open-Meteo** – `https://api.open-meteo.com/v1/forecast?latitude&longitude&current=temperature_2m,weather_code,is_day&timezone=auto`; geocoding `https://geocoding-api.open-meteo.com/v1/search?name&count=1&language=no&format=json`. WMO code → emoji in `wmoIcon`.

## 6. Conventions

- Vanilla JS, no framework, no build. Keep it one file; the operator copies three files to the PC.
- All user-visible text is Norwegian bokmål, sentence case, plain verbs. Kids never need to read: anything the child must act on is a picture or a colour (green = yes, red = stop).
- Escape everything dynamic with `esc()`. Never `innerHTML` raw user data.
- Every network feature must fail into a toast or a status row, never into a broken screen. Offline = clock, notes, night mode still work.
- Sizes in `rem` only (see layout system). Touch targets ≥ 3 rem. `button` elements for anything tappable.
- Motion: one ambient animation (aurora drift, disabled under `prefers-reduced-motion`); everything else answers a tap.
- Names/ids: `mor`/`far` internal keys; `cfg` is the single source of truth after load; settings changes reload the page.
- Secrets (HA token, Discord bot token) are stored in plain text in `localStorage`/`config.js` on a LAN-only device. Never host the folder publicly. Say so in docs if you add new secrets.

## 7. Testing

```
npm install        # jsdom only
npm test           # scripts/check.js (syntax of inline scripts + config.js) then test/smoke.js
```

`test/smoke.js` loads `index.html` in jsdom three times with mocked `fetch`, canvas and `WebSocket`: (1) fresh start → local notes, weather, PIN, keyboard, note add/remove, settings form, geocode, save; (2) reload with saved settings → renamed parent, ntfy push body, call overlay, home push; (3) Discord provider with a fake gateway → identify, incoming ring from far, webhook ignored, webhook POST body. Extend it rather than adding a framework. There is no real-browser test; after UI changes, open `index.html` in Chromium and walk SETUP.md §5. To exercise a real call from a dev PC without buzzing the phones: serve the folder on `http://127.0.0.1:<port>` (localhost is a secure context too), set `localStorage["menkerud.settings"]` to `{"notify":{"provider":"none"}}`, press Ring mor, and join the room from a second tab as the parent.

Nothing about LiveKit, ntfy, Discord or HA is exercised against real services in tests (the smoke test stubs `livekit-client`). When touching those, verify by hand with the settings panel's test buttons (`Test varsel til …`, `Test ringelyd`, `Test opplesing`) and the status rows.

## 8. Decisions and why

- **Standalone page, not a Lovelace dashboard**: HA became optional, and Lovelace can't host the Jitsi iframe with camera access from `http://`.
- **Jitsi, self-hosted on Unraid** *(historical; replaced by LiveKit on 2026-09-10, §11)*: browser-only video, IFrame API join/leave events for the "ringing" UX. The Jitsi UX still required joining a meeting, which is wrong for a child.
- **Discord default for notifications**: the family already uses it, zero new apps, and the channel doubles as a log. Known limit: cannot ring through a phone on silent/DND. ntfy kept as the alternative that can (priority 5).
- **Discord bot in the page** instead of a server-side bot: keeps the install to three files; the token is on a LAN device only.
- **Settings in `localStorage`** with `config.js` defaults: parents can set up everything on the touchscreen; tokens can be pre-seeded in the file.
- **Local notes fallback**: the board must work without HA; HA to-do list wins when present so parents can add from phones.
- **`rem` = 1 % width**: one number system for a fixed-aspect screen, no media-query soup.

## 9. Integrating with the family's existing Discord bot

The parents already run a Discord bot (its code is available alongside this repo) and the screen will live on the same server. Two ways to wire them; pick per situation, don't build both:

**A. Screen talks Discord directly (current default).** Webhook out, gateway session in with the existing bot's token. Zero changes to the bot. Fine as long as the bot doesn't already react to every message in the ring channel.

**B. The bot owns Discord, the screen talks to the bot.** Move the Discord logic into the bot and keep the screen dumb:
- Outgoing: set `notify.provider = 'webhook'` and point `notify.webhook.url` at an HTTP endpoint on the bot. The screen POSTs `{event: 'call'|'again'|'home'|'test', to: ['mor'|'far'], title, message, link}`; the bot posts to Discord however it likes (buttons, embeds, DMs, slash-command replies – things a webhook can't do). The endpoint must answer CORS (`Access-Control-Allow-Origin: *`, allow `Content-Type`) because the screen is a `file://` page.
- Incoming: the bot detects "ring hjem" (a slash command, a reaction, a message) and needs to reach the screen. Cheapest with what exists today: the bot publishes `mor`/`far` to the screen's **ntfy kiosk topic** (self-hosted ntfy or ntfy.sh) – the screen already listens there. If you'd rather not involve ntfy, add a small generic listener to the screen (SSE or WebSocket to a bot endpoint, same message shape as ntfy: `{event:'message', message:'mor'}`) as a new `notify.custom` block in `DEFAULTS` + `SETTINGS` + a `listenCustom()` next to `listenNtfy()`.

Rule of thumb: if the bot is stable and always on, B keeps the token out of the screen and gives richer Discord UX; if the bot is a hobby process that restarts a lot, A keeps calling independent of it.

## 10. Backlog / ideas (not implemented)

- Real-browser check on the actual touchscreen PC: touch scrolling of the emoji strip and settings form, photo picker dialog under the chosen desktop/Wayland, audio device selection.
- QR code (inline, no CDN) in Innstillinger for the `ring.html` links and the call room.
- Settings export/import (JSON download/upload) and a "copy diagnostics" button.
- Outgoing push retry policy: currently one push + one repeat at 25 s; consider a third at 45 s or a Discord message edit.
- LiveKit hardening: rotate the API key/secret periodically, add a TURN server for restrictive networks, review per-call token scoping.
- Incoming call while a note/settings pane is open closes nothing – decide whether to auto-close the menu.
- Voice: Piper (Wyoming) via HA for natural Norwegian, or a `/tts` HTTP endpoint provider so TTS works without HA.
- Camera streams via HLS (`camera/stream` WS command + hls.js) instead of MJPEG.
- Multiple children / per-child "Jeg er hjemme" (who came home), with per-child colour.
- **Redesign the PIN-locked menu/admin area** (operator, 2026-09-10: «it works, but looks bad») – next up after Skjerm av / Bilderamme.
- Skjerm av: the page cannot wake a blanked display for an incoming call; a tiny local helper (Mutter `PowerSaveMode` over D-Bus) could. Also a Ken Burns / slow-zoom option for Bilderamme.
- Unit tests for `parseNote`, `spokenTime`, `wmoIcon` outside jsdom.
- Simple update mechanism: `git pull` + reload button in Innstillinger.

## 11. Planned: call migration to LiveKit + Discord

Jitsi's UX was wrong for a child (join a meeting, type a name, pick a room), so it was replaced with a one-tap flow: tap a parent → the screen rings and joins a private LiveKit room → the parent gets a Discord message with an **Svar** link → tapping it opens a tiny answer page that joins the same room. Full spec: the LiveKit design doc supplied by the operator.

Decisions locked for this project (2026-09-10):

- **Separate backend, not in `index.html`.** A small Node service — a **Docker container on Unraid** (`menkerud-callapi`, `call-backend/`, listens on 3000, host-published 3008, joined to `noobventure-network`) — owns the LiveKit API secret, the Discord bot token (reuse GameNoobBot's), the call state, one-time answer tokens and short-lived LiveKit JWTs. The kiosk keeps every `CLAUDE.md` rule: one file, still works from `file://`; the LiveKit client is lazy-loaded from a CDN only during a call. The Ubuntu screen only serves static files and runs Firefox — no Node, no backend.
- **Domains** (SWAG + Cloudflare, proxied): `rtc.noobventure.com` → LiveKit `:7880` on Unraid (WSS); `call.noobventure.com` → the `menkerud-callapi` container (SWAG resolves it by name over `noobventure-network`) for the kiosk's `/api` calls and the parent answer page. **UDP 7882** forwarded WAN → Tower for WebRTC media (two hops; see SETUP.md §2 for the double-router note).
- **Parent notification:** a private Discord **DM** from the bot with the answer link (fallback: post to `#hjemme` like today).
- **Single call engine:** LiveKit is now the only call path (`startCallLiveKit`). The Jitsi client, the `cfg.call.provider` toggle, the SWAG `meet` conf and the UDP 10000 forward were all removed on 2026-09-10.
- **Child UI reuses the redesigned call states** (idle → ringer → tilkoblet → svarte ikke) already in `index.html`; only the transport underneath changes.

**Implemented so far (2026-09-10):** the `call-backend/` service (Express + `livekit-server-sdk` + Discord DM over REST) with `POST /api/calls`, `/api/calls/answer`, `/api/calls/:id/cancel|end`, `GET /api/calls/:id`, the parent answer page (`public/answer.html`, `livekit-client` from CDN), `Dockerfile` + `docker-compose.yml` (the `menkerud-callapi` container on `noobventure-network`, host 3008→3000) + `livekit.example.yaml` (LiveKit reference), `deploy/nginx-menkerud.conf` (Ubuntu static site), the two SWAG confs, plus `README.md` and the top-level **`DEPLOYMENT.md`** (source of truth for deployment). In `index.html`, `startCall(who)` → `startCallLiveKit` + `loadLiveKit` (lazy CDN); `cfg.call.backend` defaults to `https://call.noobventure.com` (a blank value would fall back to same-origin), with `cfg.call.deviceKey` / `cfg.call.timeoutSec`; Jitsi is gone. The LiveKit signalling path (SWAG → LiveKit, token accepted, WS handshake) was verified from the dev PC on 2026-09-10. **Not yet done:** deploy the container per DEPLOYMENT.md, real media test on mobile data, and a proper parent→screen (incoming) flow over LiveKit (today “Svar” on an incoming call simply calls the parent back).
