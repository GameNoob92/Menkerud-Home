# Parent app for Menkerud Home calls – design

Date: 2026-11-14 · Status: approved by the operator on 2026-11-14 · Nothing in here is built yet. · Read by an independent builder on 2026-11-14: no open questions; its suggestions are folded into §8.

## 1. Intent and why

The kids' touchscreen ("the kiosk") can video-call mor or far with one tap. The kiosk side works. The parents' side does not work reliably, and it runs through Discord only because Discord happened to be on both phones already:

- The backend sends the parent a Discord DM with a link to a web answer page. A DM does not alert like a phone call, and the parent has to find it and tap the link within 60 seconds.
- The link often opens in Discord's built-in browser, which cannot use the camera and microphone. The page then shows an «Åpne i Chrome» step that may dead-end.
- On 2026-11-14 the kiosk made five call attempts and published its picture and sound each time, but LiveKit's log shows that **no parent phone reached the call server at all** that day.

This project replaces the parents' side of *calling* with a small Android app that is installed by hand (sideloaded) on the two parents' phones. The app rings when the kids call and lets a parent call home. Everything else stays where it is.

## 2. Goals

1. A child presses «Ring far» (or mor): the parent's phone alerts loudly within a few seconds, at home or away on mobile data, with «Svar» and «Ikke nå». «Svar» opens straight into the call with picture and sound both ways. No Discord, no link, no browser.
2. A parent presses «Ring hjem» in the app: the kiosk shows the incoming-call screen it already has («Far ringer!»), a child taps «Svar», and they are connected.
3. Only approved phones can receive or place calls. A phone is approved with a one-time key that the operator generates on a page reachable only on the home network.
4. `call.noobventure.com` ends up as an API only, with no pages.
5. The kiosk looks exactly as it does today.

## 3. Non-goals for this version

- **Messages in the app.** Beskjeder stays on Discord. The operator wants messages in the app later, so this design must not block it (see §9), but nothing for it is built now.
- «Jeg er hjemme» and the #ring-hjem channel stay on Discord and keep working as today.
- Ringing through silent mode or Do Not Disturb. The operator said a loud notification with «Svar» is enough; the alert follows the phone's own sound setting.
- iOS, the Play Store, more than the two identities mor and far, more than one kiosk.
- Better call quality on the kiosk. That is limited by its 2-core Celeron and is a known, accepted ceiling.

## 4. Anti-goals (these would make it a failure even if it "works")

- Any page on `call.noobventure.com` or anything else new that is reachable from the internet apart from the API.
- A stranger's phone being able to pair, be rung, answer or call home.
- Any visible change on the kiosk: no new buttons, pictures, texts or settings rows.
- Calls breaking during the changeover. A parent who has not installed the app yet must stay reachable the old way.

## 5. What exists today (so the builder can find it)

- **Kiosk**, `index.html` (one file, vanilla JS, no build; see `CLAUDE.md` and `DESIGN.md`). `startCallLiveKit(who)` POSTs `{target}` to `<cfg.call.backend>/api/calls` with `X-Device-Key`, joins the returned LiveKit room as `tablet-stue`, and waits for the parent. `incomingCall(who)` shows the «… ringer!» overlay with «Svar» / «Ikke nå»; today it is triggered by a message in the Discord #ring-hjem channel, and «Svar» simply places a new outgoing call.
- **Backend**, `call-backend/` (Express, container `menkerud-callapi` on Unraid, public as `https://call.noobventure.com` through SWAG, also published on host port 3008). Calls live in memory. `POST /api/calls` creates the room, mints the kiosk's LiveKit token and DMs the parent a link to `/answer/<token>`; `public/answer.html` redeems it at `POST /api/calls/answer`. It also proxies two Discord calls for Beskjeder (`/api/messages…`). `DEVICE_KEY` is set in production (unauthenticated requests get 401).
- **LiveKit** 1.13.6 on Unraid. Since 2026-11-14 it advertises both its LAN address and the home's public address; devices on the home network are verified to connect. The path **from the internet** (UDP 7882 forwarded through both routers) has not been verified.
- **Phones:** both parents use recent Samsung phones (Android 15/16).
- **Tools on the dev PC:** Android Studio, SDK 36, and an existing Kotlin + Compose app of the operator's (`DrySpool`) whose setup can be mirrored.

## 6. Decided by the operator

These are the operator's own decisions, in the order they were made:

- A sideloaded **.apk**, not a web page or installable web app.
- `call.noobventure.com` stays **an API only**; "maybe a relay if needed".
- **Pairing:** the backend offers a page that is served only locally, not on `call.noobventure.com`, where the operator generates a key for mor or far. The app asks for a key the first time it starts. The two identities already exist; no accounts.
- Using **Google's push service** (Firebase Cloud Messaging) to wake the phone is fine. The operator creates the Firebase project and hands over the two files it produces.
- **Both directions** from the start: kids call a parent, and a parent calls home.
- **Nothing changes visually on the kiosk.**
- Messages and the rest stay on Discord for now; messages in the app is a future expansion.

## 7. Behaviour

### Kids call a parent

1. A child presses «Ring far». The kiosk does what it does today.
2. Every phone paired as far rings: a call-style notification with ringtone and vibration, the text «Det ringer hjemmefra», and «Svar» / «Ikke nå». On a locked phone it shows over the lock screen where Android allows it.
3. «Svar» opens the call screen and joins. The kiosk sees the parent arrive exactly as today. Other phones of the same parent stop ringing.
4. «Ikke nå» ends the attempt at once. The kiosk shows the message it already shows when nobody answers («Far kunne ikke svare nå») and hangs up.
5. No answer within the ring time (60 s today): the phone stops ringing and leaves «Tapt anrop hjemmefra kl. 14:32». The kiosk shows the same message as in step 4.
6. The child hangs up while it is ringing: the phone stops ringing and shows the missed call.
7. Either side hangs up during the call: the call ends on both.

### A parent calls home

1. The parent presses «Ring hjem». The app shows «Ringer hjem…» with the parent's own picture.
2. The kiosk shows its existing incoming-call overlay («Far ringer!», ring tone, spoken announcement; the screen wakes if it was dark, as it does today for an incoming call).
3. A child taps «Svar»: the kiosk joins the parent's waiting call and both see and hear each other.
4. «Ikke nå», or no tap within the kiosk's existing 45 s: the app shows «Ingen svarte hjemme».
5. The parent cancels: the kiosk's overlay goes away.
6. The kiosk is already in a call: the app shows «Opptatt hjemme». The kiosk is not connected to the backend: the app shows «Skjermen hjemme er ikke tilkoblet».

### Pairing a phone

1. On the home network the operator opens the local page, enters its password, and presses «Ny telefon» for Mor or Far.
2. The page shows a one-time key as text and as a QR code.
3. The app, on first start, asks for the key (type or scan). The phone is then paired as mor or far and shows its home screen.
4. The local page lists the paired phones (whose, a name, app version, last seen) and can remove one. A removed phone stops ringing and can no longer call.

## 8. How it gets made

### My calls (the operator has not decided these; say so if any is unwanted)

- **App:** native Kotlin with Jetpack Compose and the LiveKit Android SDK (`io.livekit:livekit-android`), in a new `parent-app/` folder in this repo, set up like the operator's DrySpool project (Gradle Kotlin DSL, application id `com.noobventure.menkerud`, minSdk 26, release keystore kept out of git). App name «Menkerud Home». UI text in Norwegian bokmål, code and docs in English, as in the rest of the project.
- **Screens:** pairing · home (who you are, connection state, a large «Ring hjem», last call, app version) · incoming call · ringing home · in call (their picture full screen, your own small, «Legg på», mute, camera off; loudspeaker by default). Same visual language as the kiosk (dark blue scenic background, glass panels, pink / blue / green accents), reusing art from `assets/`.
- **The key:** valid once and for 15 minutes, with enough random characters that guessing is hopeless (the QR code carries the same key, so its length costs nothing when scanning). The app redeems it at the public API – the phone never has to reach the local page – and gets a long-lived secret that is stored encrypted on the phone; the server stores only a hash. Wrong attempts are capped for the redeem endpoint as a whole, not per caller address, because every request arrives through Cloudflare and SWAG. Which parent a phone is comes from the server's record, never from what the phone claims. Several phones per parent are allowed and all ring.
- **Local page:** a second listener in the same container on its own port, published on the Unraid host (for example 3009) and never proxied by SWAG, so it does not exist on the public hostname. It asks for a password set in the container's `.env`. Norwegian UI.
- **Remembering phones:** a small JSON file in a data folder mounted into the container, so it survives a rebuild. Calls stay in memory as today.
- **Push:** FCM data messages with high priority, sent by the backend with the Firebase service-account file. The ring itself lives only as long as the ring time; the «missed» notice may live longer, so a phone that was offline still shows the missed call. Prefer no new npm dependency (sign the token request with Node's own crypto); a small official Google auth library is acceptable if that proves fragile.
- **Kiosk, invisible changes only:** the kiosk keeps one light connection open to the API (a streamed response read with `fetch`, authenticated with the existing device key) so the API can tell it «Far ringer», «the caller gave up» and «the parent pressed Ikke nå». It reuses the existing overlays and texts. No new setting; the existing status row may change its text. If the connection fails the kiosk retries quietly and shows nothing. `index.html` stays one file and still works from `file://` with nothing configured.
- **Call bookkeeping in the backend** is reworked to cover both directions, declining, and cleaning up after itself. Today an answered call that is never reported as ended blocks every later call until the container restarts; the new version must not have that weakness (for example: end a call when the room has emptied, and cap its length).
- **Changeover:** per parent. If the parent has at least one paired phone, the call goes to the app. If not, the parent gets the Discord link exactly as today. A phone whose push address Google reports as dead does not count as paired, so a stale pairing can never leave a parent with neither the app nor the Discord link. When both parents are on the app and the operator says so, a separate last step removes the answer page, its routes and the Discord call DM, leaving the public side an API only.
- **Development and testing use a separate copy of the backend,** not the one the family uses. Pairing a phone in production moves that parent off Discord at once, so production gets its first paired phone only at rollout.
- **Discord #ring-hjem** keeps ringing the kiosk as before; «Svar» there calls the parent through whichever path that parent has.
- **No second alert** through Discord once a parent is on the app. It can be added later if a ring is ever missed.

### First step, before anything is built

Verify the path from outside with the *current* flow: one call from the kiosk, answered in Chrome (not Discord's built-in browser) on a phone using mobile data with Wi-Fi off. The app depends on exactly this path.

- Picture and sound both ways: proceed.
- No media: check that UDP 7882 is forwarded to `192.168.68.2` on both routers, and test again.
- Still no media: also forward TCP 7881, which LiveKit already listens on as a fallback, and test again.
- Only if both fail: LiveKit's built-in relay (TURN). This is the "relay if needed". It is a LiveKit setting, not a new container – but a relay on the same server sits behind the same routers, so it needs its own forwarded port, and its TLS variant also needs a certificate and a hostname that Cloudflare does not proxy. It helps on networks that block UDP; it cannot help when forwarding itself is broken.

### Left to the builder

- Endpoint names, payloads and the exact call states; how the kiosk's connection reconnects and stays alive behind Cloudflare and SWAG.
- The push payload format. Give every payload a `type` and tolerate unknown types, so new kinds can be added without breaking installed apps.
- Android structure: the service that rings, the notification and lock-screen behaviour, permissions and how they are asked for, audio routing, asking Samsung not to put the app to sleep, what happens when the push token changes or the phone has been removed.
- Tests: backend unit tests in `call-backend/test/` (pairing, authentication, call states, clean-up); extend `test/smoke.js` for the kiosk's new connection and the join-a-waiting-call path; app tests where they are cheap.
- Build and install instructions for the .apk, the Firebase steps for the operator, and the docker-compose and `.env` changes (data folder, second port, password, service-account file).
- Documentation: `DESIGN.md`, `SETUP.md`, `DEPLOYMENT.md`, `call-backend/README.md`.
- Three checks to do early, because a surprise in any of them changes the plan: the LiveKit admin API is reachable from inside the container (today `deleteRoom` swallows its errors, and call clean-up will lean on that API); a client that is not a browser gets through Cloudflare on `call.` and `rtc.` from a mobile network; and a Gradle build works from a folder with a space in its path («Menkerud Home»).

## 9. Room for messages later

Messages are not built now, but three things keep the door open at no extra cost: the list of paired phones and their authentication are not specific to calls; every push carries a `type`; and the app's home screen is laid out so a second section can be added without redesigning it.

## 10. Risks and unknowns

- **The outside path is unverified** (see the first step). If it does not work, neither will the app away from home.
- **Samsung battery saving** can delay pushes to apps it has put to sleep. The app must ask to be excluded and this must be tested on both phones.
- **Lock-screen call display** on recent Android needs a permission that the user may have to grant in settings for a sideloaded app; without it the phone still rings with a normal heads-up notification.
- **Dependence on Google's push service:** if it is down, the phone does not ring. Accepted for now.
- **Updating the app** means installing a new .apk on each phone by hand.

## 11. Done means

1. A fresh phone: install the .apk, generate a key on the local page, enter it, and the phone appears in the page's list.
2. «Ring far» with the phone locked and on mobile data: it rings within about 5 seconds; «Svar» gives picture and sound both ways within about 5 more; hanging up on either side ends both and the kiosk returns to its front page.
3. «Ikke nå»: the kiosk shows «Far kunne ikke svare nå» within about 3 seconds.
4. No answer: after the ring time the kiosk shows the same message and the phone shows the missed call.
5. «Ring hjem» from mobile data: the kiosk shows «Far ringer!» and wakes its screen; «Svar» connects; «Ikke nå» or no tap shows «Ingen svarte hjemme» in the app.
6. A removed phone no longer rings and cannot call. A request with a wrong or missing secret gets 401. The local page cannot be reached through `call.noobventure.com`.
7. A parent without the app still gets the Discord link.
8. The kiosk's front page, call screen and incoming-call screen look the same before and after, and `npm test` passes in the repo root and in `call-backend/`.
