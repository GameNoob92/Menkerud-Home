# Menkerud Home – call backend (LiveKit + Discord)

The one-tap calling service. It is **separate** from `index.html` and runs as a **Docker container on Unraid** (`menkerud-callapi`); **LiveKit** also runs on Unraid. The Ubuntu touchscreen only serves the static frontend and runs Google Chrome in kiosk mode — it does not host or proxy the backend. See `../DEPLOYMENT.md` for the whole picture.

```
Kiosk (Ubuntu, http://localhost)  --https /api-->  SWAG (Unraid)  -->  menkerud-callapi:3000 (Docker, Unraid)
                                                                          |  LiveKit token + call state
                                                                          |  Discord DM with the answer link
                                                                          v
Parent's phone  --https://call.noobventure.com/answer/<token>-->  SWAG  -->  menkerud-callapi
Both join the same room on LiveKit (Unraid): wss://rtc.noobventure.com, media UDP 7882.
```

- The kiosk calls `https://call.noobventure.com/api/*` (`/api/calls`, `/api/calls/answer`, `/api/calls/:id/cancel|end`, `/api/calls/:id`, the two `/api/messages` routes). Built for the parent app and not yet deployed: `/api/pair`, `/api/devices/me`, `/api/calls/home`, `/api/calls/:id/answer|decline`, and for the kiosk its event stream `/api/kiosk/events` and `/api/calls/:id/ringing` – see `../DESIGN.md` §11. These kiosk routes are refused (403 `device_key_required`) on a server without `DEVICE_KEY`.
- SWAG proxies `call.noobventure.com` to the `menkerud-callapi` container by name over the shared `noobventure-network`.
- Secrets (LiveKit API secret, Discord token, device key) come from `.env` only — never in the browser or committed.

## Files
`src/server.js` wiring only · `src/config.js` the environment, read once · `src/app.js` the public routes · `src/calls.js` call state for both directions, time-outs, clean-up · `src/devices.js` paired phones and pairing keys · `src/push.js` push to the app (FCM, or a file for the dev copy) · `src/kiosk-events.js` the kiosk's event stream · `src/admin.js` + `src/admin-page.html` the local pairing page · `src/livekit.js` tokens/room cleanup · `src/discord.js` DM · `public/answer.html` parent page · `test/` one test file per module plus `e2e` · `scripts/` + `dev/` + `.env.dev.example` the dev copy (below) · `Dockerfile` + `docker-compose.yml` (this container) · `livekit.example.yaml` (LiveKit reference) · `deploy/nginx-menkerud.conf` (Ubuntu static site) · `swag/*.subdomain.conf` (Unraid SWAG).

## A. LiveKit on Unraid (already running)
LiveKit is installed at `/mnt/user/appdata/LiveKit/config.yaml` (single node: `port: 7880`, `rtc.tcp_port: 7881`, `rtc.udp_port: 7882`) with **`rtc.use_external_ip: true`**. It runs on the **`br0` macvlan network with a dedicated static LAN IP, `192.168.68.2`** — **not** host networking. Host mode crash-looped on `listen udp 192.168.68.74:7882: bind: address already in use` (the `unless-stopped` policy races the not-yet-released UDP socket on the shared host port) and forced LiveKit to enumerate every host virtual interface. A dedicated br0 IP binds `192.168.68.2:7882` cleanly and gives LiveKit a real LAN address; `use_external_ip` STUN-discovers the public IP (`195.1.x`) so phones on mobile data reach the media, and **`rtc.advertise_internal_ip: true`** makes LiveKit offer its LAN address (`192.168.68.2:7882`) next to it for every device at home. Both are needed, because the two routers do **not** loop traffic back to the public IP (no NAT hairpin; measured 2026-11-14: from the LAN the forwarded ports on the public IP never answer, and LiveKit logs «could not validate external IP» at every start – that warning is expected here). Until 2026-11-14 only the public address was advertised: the kiosk still got through, but only because LiveKit probes each client directly and Chrome then learns the LAN address from that probe (a peer-reflexive candidate), while a Windows PC on the same LAN – whose firewall drops the probe – could never connect. LiveKit parses its config strictly: an unknown key keeps it from starting, so check a new key against the `config-sample.yaml` of the running version first. SWAG is a bridge container, so it reaches the br0 IP through Unraid's macvlan shim — keep **Settings → Docker → "Host access to custom networks" enabled**. **Never enable the 50000-60000 UDP range** — single mux 7882 only. (Verified 2026-09-10: host mode caused the bind crash loop; moving LiveKit to br0 `.2` fixed it, and `curl` from the SWAG container to `192.168.68.2:7880` returns 200. The `.2` is outside the router's DHCP pool, which starts at `.50`.)

Launch LiveKit on br0 either from the Unraid Docker template (Network Type = **Custom: br0**, Fixed IP = `192.168.68.2`) or by command:
```
docker run -d --name LiveKit --network br0 --ip 192.168.68.2 --restart unless-stopped \
  -v /mnt/user/appdata/LiveKit:/config livekit/livekit-server --config /config/config.yaml
```
1. SWAG on Unraid: copy `swag/rtc.subdomain.conf` to `/config/nginx/proxy-confs/` (proxies `rtc.noobventure.com` → `192.168.68.2:7880`, WebSocket upgrade via SWAG's `proxy.conf`).
2. Router: forward **UDP 7882** → `192.168.68.2` (both hops of the double-router). Optionally **TCP 7881** for WebRTC-over-TCP fallback. Do not forward 7880 (it rides SWAG/443), and never open 50000-60000.

## B. call-backend on Unraid (Docker)
1. Put this `call-backend/` folder on Unraid, e.g. `/mnt/user/appdata/menkerud-call/` (git clone the repo there, or copy the folder).
2. `cp .env.example .env` and fill: `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` (the exact `key: secret` pair from `/mnt/user/appdata/LiveKit/config.yaml`), `DISCORD_BOT_TOKEN`, and a `DEVICE_KEY`. Put that same `DEVICE_KEY` in the kiosk's `config.js` (`call.deviceKey`). Leave `LIVEKIT_URL=wss://rtc.noobventure.com`.
3. `docker compose up -d --build`. The container listens on 3000, is published on host **3008**, and joins **noobventure-network** so SWAG resolves it by name.
4. SWAG on Unraid: copy `swag/call.subdomain.conf` to `/config/nginx/proxy-confs/` (proxies `call.noobventure.com` → `menkerud-callapi:3000`; SWAG must be on `noobventure-network`), then `nginx -t && nginx -s reload`.
5. Verify from anywhere: `curl https://call.noobventure.com/healthz` → `{"ok":true,"livekit":true,"discord":true}`. On the host you can also hit `http://<unraid-ip>:3008/healthz`.

6. **Beskjeder proxy.** The kiosk's messages channel is read, and «Hørt!» acknowledged with a ✅, through `GET /api/messages?channel=<id>&limit=10` and `POST /api/messages/<channel>/<id>/ack` (device-key protected, same `DISCORD_BOT_TOKEN`). Discord refuses bot tokens sent from a browser (403, code 40333), so the page cannot do this itself. To update the backend after a change: `git pull` in the checkout on Unraid, then `docker compose up -d --build` in this folder.

## C. Home Assistant behind SWAG (for the Companion apps)
HA runs as `Home-Assistant-Container` on `br0` at `192.168.68.3`; the kiosk uses that LAN address directly. The phones need HA from outside so presence can turn Borte:
1. Cloudflare: `ha.noobventure.com` (proxied) → the same public IP; SWAG's wildcard cert already covers it, no new port forward.
2. SWAG: copy `swag/ha.subdomain.conf` to `/config/nginx/proxy-confs/` and `docker exec swag nginx -s reload` (proxies `ha.noobventure.com` → `192.168.68.3:8123`).
3. HA (UI, not YAML – since 2026.8 a `http:` block in `configuration.yaml` is ignored after the one-time import): Settings → System → Network → "HTTP server" → *Trust X-Forwarded-For* on, *Trusted proxies* = `192.168.68.4` (SWAG's br0 IP). Save, let HA restart, and confirm the new settings within 5 minutes or HA reverts.
4. Verify: `curl -o /dev/null -w '%{http_code}' https://ha.noobventure.com/` → `200`, `…/api/` → `401`. Then set *External URL* = `https://ha.noobventure.com` in every Companion app.

## D. Pairing page for the parent app (local only)
The parent app pairs with a one-time key. The key is made on a small page that this container serves on a **second port**, published on the Unraid host's LAN address as **`http://192.168.68.74:3012`** (container port 3001; 3009, first planned, was another project's port on the server). It exists only on the home network: there is no SWAG conf for it and there must never be one, so nothing of it is reachable through `call.noobventure.com`. It asks for the password in `ADMIN_PASSWORD` (any user name; an empty password switches the page off) – the password travels unencrypted, which is acceptable on the home network only. On the page: «Ny telefon for Mor / Far» shows a key as text and QR code (valid once, for 15 minutes), and the table lists the paired phones and removes one.

The list of paired phones (`devices.json`) and the Firebase key live in the container's `/app/data`, mounted from **`/mnt/user/appdata/menkerud-callapi`**. That folder is deliberately *outside* this project folder: the project folder on Unraid is refreshed from the dev PC with `robocopy /MIR`, which deletes everything the dev PC does not have – a data folder inside it would lose every paired phone at the next sync. The folder appears by itself the first time the container starts.

## Verify media before going live
Temporarily set `DEV=1` in `.env`, `docker compose up -d`, and use the kiosk's "Ring far" (or `/dev/pair`) to prove a real call to a phone on mobile data. Set `DEV=0` again after.

## Developing on a separate copy
**Never start this service on the dev PC with its `.env`:** that file is the production one (real Discord token), and a test call through it messages the parents. The dev copy has its own settings, ports and data folder, writes pushes to a file instead of sending them, and has no Discord token at all – it cannot reach anybody's phone.

1. Copy `.env.dev.example` to `<repo>/.dev/callapi.env` (`.dev/` is git-ignored) and fill in `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` – the pair from `.env`. Calls use the real LiveKit server, each in a room of its own.
2. `npm run dev` (= `node scripts/dev-server.js`; from bash also `DOTENV_CONFIG_PATH=../.dev/callapi.env node src/server.js`). It will not start without that file, and refuses `.env`. The API is on `http://127.0.0.1:3100`, the pairing page on `http://127.0.0.1:3101` (any user name, password `dev`).
3. **The stand-in phone** (`scripts/fake-phone.js`) plays the parent app. Make a key for Far on the pairing page, then:
   - `node scripts/fake-phone.js pair <key>` – pairs and registers a push address.
   - `node scripts/fake-phone.js listen --answer` (or `--decline`; neither = only show the pushes) – follows the push outbox (`.dev/callapi-data/push-outbox.jsonl`) and reacts as the app would. On an answer it prints a link to `dev/join.html`: a page opened from disk (never served) that joins the room with camera and microphone and has «Legg på».
   - `node scripts/fake-phone.js ring-home` – «Ring hjem»: prints the same kind of link, then what the app would tell the parent («Ingen svarte hjemme», «Opptatt hjemme», «Skjermen hjemme er ikke tilkoblet»).
4. **The kiosk page:** serve the repo folder on `http://127.0.0.1:<port>` (for example `python -m http.server 8080 --bind 127.0.0.1` in the repo root) and open `index.html` in a Chrome profile of its own. Point that page at the dev copy from its console, then reload:
   `localStorage.setItem('menkerud.settings', JSON.stringify({ call: { backend: 'http://127.0.0.1:3100', deviceKey: 'dev-key' }, notify: { provider: 'none', discord: { botToken: '' }, ntfy: { topicKiosk: '' } }, ha: { url: '' } }))`
   The empty values matter when the folder has a real `config.js`: without them the page would also log in as the family's Discord bot and connect to Home Assistant. Innstillinger → Status shows «Appen ✓» on the «… ringer hjem» row once the page has reached the dev copy.

What to try: `listen --answer` + «Ring far» + the printed link (picture and sound both ways, «Legg på» on either side ends both) · `listen --decline` + «Ring far» («Far kunne ikke svare nå 😢» at once) · `ring-home` + «Svar» / «Ikke nå» on the kiosk page · `ring-home` with the kiosk page closed · «Fjern» on the pairing page while `listen` runs (it gets 401 and stops).

`npm test` runs every `test/*.test.js` (plain node scripts, no framework): each module on its own with stand-ins for LiveKit, Discord and the push service, and `test/e2e.test.js` with the real modules together.
