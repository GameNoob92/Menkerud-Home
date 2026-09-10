# Menkerud Home – call backend (LiveKit + Discord)

The one-tap calling service. It is **separate** from `index.html` and runs as a **Docker container on Unraid** (`menkerud-callapi`); **LiveKit** also runs on Unraid. The Ubuntu touchscreen only serves the static frontend and runs Firefox — it does not host or proxy the backend. See `../DEPLOYMENT.md` for the whole picture.

```
Kiosk (Ubuntu, http://localhost)  --https /api-->  SWAG (Unraid)  -->  menkerud-callapi:3000 (Docker, Unraid)
                                                                          |  LiveKit token + call state
                                                                          |  Discord DM with the answer link
                                                                          v
Parent's phone  --https://call.noobventure.com/answer/<token>-->  SWAG  -->  menkerud-callapi
Both join the same room on LiveKit (Unraid): wss://rtc.noobventure.com, media UDP 7882.
```

- The kiosk calls `https://call.noobventure.com/api/*` (routes unchanged: `/api/calls`, `/api/calls/answer`, `/api/calls/:id/cancel|end`, `/api/calls/:id`).
- SWAG proxies `call.noobventure.com` to the `menkerud-callapi` container by name over the shared `noobventure-network`.
- Secrets (LiveKit API secret, Discord token, device key) come from `.env` only — never in the browser or committed.

## Files
`src/server.js` API + call state · `src/livekit.js` tokens/room cleanup · `src/discord.js` DM · `public/answer.html` parent page · `Dockerfile` + `docker-compose.yml` (this container) · `livekit.example.yaml` (LiveKit reference) · `deploy/nginx-menkerud.conf` (Ubuntu static site) · `swag/*.subdomain.conf` (Unraid SWAG).

## A. LiveKit on Unraid (already running)
LiveKit is installed at `/mnt/user/appdata/LiveKit/config.yaml` (single node: `port: 7880`, `rtc.tcp_port: 7881`, `rtc.udp_port: 7882`). It **must** run with **host networking** and **`rtc.use_external_ip: true`**. In bridge mode without that, LiveKit advertises its internal container IP (`172.x`) as the media address and **all WebRTC media fails** — signalling connects, then every call drops after the ~16s ICE timeout. Host networking lets it advertise the LAN IP (`192.168.68.74`) for on-LAN clients like the kiosk, and `use_external_ip` STUN-discovers the public IP (`195.1.x`) for phones on mobile data. **Never enable the 50000-60000 UDP range** — single mux 7882 only. (Verified 2026-09-10: bridge + no `use_external_ip` caused exactly this failure; switching to host + `use_external_ip` fixed the advertised candidates.)
1. SWAG on Unraid: copy `swag/rtc.subdomain.conf` to `/config/nginx/proxy-confs/` (proxies `rtc.noobventure.com` → `192.168.68.74:7880`, WebSocket upgrade via SWAG's `proxy.conf`).
2. Router: forward **UDP 7882** → Unraid `192.168.68.74` (both hops). Optionally **TCP 7881** for WebRTC-over-TCP fallback. Do not forward 7880 (it rides SWAG/443), and never open 50000-60000.

## B. call-backend on Unraid (Docker)
1. Put this `call-backend/` folder on Unraid, e.g. `/mnt/user/appdata/menkerud-call/` (git clone the repo there, or copy the folder).
2. `cp .env.example .env` and fill: `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` (the exact `key: secret` pair from `/mnt/user/appdata/LiveKit/config.yaml`), `DISCORD_BOT_TOKEN`, and a `DEVICE_KEY`. Put that same `DEVICE_KEY` in the kiosk's `config.js` (`call.deviceKey`). Leave `LIVEKIT_URL=wss://rtc.noobventure.com`.
3. `docker compose up -d --build`. The container listens on 3000, is published on host **3008**, and joins **noobventure-network** so SWAG resolves it by name.
4. SWAG on Unraid: copy `swag/call.subdomain.conf` to `/config/nginx/proxy-confs/` (proxies `call.noobventure.com` → `menkerud-callapi:3000`; SWAG must be on `noobventure-network`), then `nginx -t && nginx -s reload`.
5. Verify from anywhere: `curl https://call.noobventure.com/healthz` → `{"ok":true,"livekit":true,"discord":true}`. On the host you can also hit `http://<unraid-ip>:3008/healthz`.

## Verify media before going live
Temporarily set `DEV=1` in `.env`, `docker compose up -d`, and use the kiosk's "Ring far" (or `/dev/pair`) to prove a real call to a phone on mobile data. Set `DEV=0` again after.

## Local development
`npm install && npm test` runs the token unit test. `DEV=1 LIVEKIT_API_KEY=devkey LIVEKIT_API_SECRET=devsecret_long_enough node src/server.js` boots without Docker.
