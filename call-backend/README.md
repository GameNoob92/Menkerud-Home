# Menkerud Home – call backend (LiveKit + Discord)

The one-tap calling service. It is **separate** from `index.html` and, in production, runs on the **Ubuntu touchscreen PC** as a systemd service; **LiveKit** runs on **Unraid**. See `../DEPLOYMENT.md` for the whole picture; this file is the calling-specific detail.

```
Kiosk (http://localhost)  --/api-->  nginx  -->  Node backend :3000 (systemd, this PC)
                                                     |  LiveKit token + call state
                                                     |  Discord DM with the answer link
                                                     v
Parent's phone  --https://call.noobventure.com/answer/<token>-->  SWAG (Unraid) --> this PC:3000
Both join the same room on LiveKit (Unraid): wss://rtc.noobventure.com, media UDP 7882.
```

- The kiosk calls `/api/*` same-origin; nginx proxies to `127.0.0.1:3000`.
- Parents reach the answer page over `call.noobventure.com`, which SWAG on Unraid proxies to this PC's `:3000` (set that PC's LAN IP in `swag/call.subdomain.conf`).
- Secrets (LiveKit API secret, Discord token) live only in `.env` on this PC and `livekit.yaml` on Unraid — never in the browser.

## Files
`src/server.js` API + call state · `src/livekit.js` tokens/room cleanup · `src/discord.js` DM · `public/answer.html` parent page · `livekit.example.yaml` + `docker-compose.yml` (LiveKit, Unraid) · `deploy/menkerud-backend.service` (systemd) · `deploy/nginx-menkerud.conf` (nginx) · `swag/*.subdomain.conf` (Unraid SWAG).

## A. LiveKit on Unraid (already running)
LiveKit is installed at `/mnt/user/appdata/LiveKit/config.yaml` (single node: `port: 7880`, `rtc.tcp_port: 7881`, `rtc.udp_port: 7882`). Leave that install and its Docker networking as-is — the repo's `docker-compose.yml` / `livekit.example.yaml` are reference only. **Never enable the 50000-60000 UDP range.**
1. Copy the `key: secret` pair from that `config.yaml` (`keys:`) into the backend `.env` as `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET`; they must match exactly.
2. SWAG on Unraid: copy `swag/rtc.subdomain.conf` to `/config/nginx/proxy-confs/` (proxies `rtc.noobventure.com` → `192.168.68.74:7880` with WebSocket upgrade), and `swag/call.subdomain.conf` with `$upstream_app` = the **Ubuntu PC's** LAN IP. `nginx -t && nginx -s reload`.
3. Router: forward **UDP 7882** → Unraid `192.168.68.74` (both hops). Optionally **TCP 7881** for WebRTC-over-TCP fallback. Do **not** forward 7880 (it rides SWAG/443), and never open 50000-60000.

## B. Backend on the Ubuntu PC (systemd)
From the git checkout at `/home/menkerud/menkerud-home`:
```bash
cd call-backend
npm ci
cp .env.example .env       # then fill in LIVEKIT_API_KEY/SECRET (same as livekit.yaml), DISCORD_BOT_TOKEN, DEVICE_KEY
sudo cp deploy/menkerud-backend.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now menkerud-backend
curl -s localhost:3000/healthz    # {"ok":true,"livekit":true,"discord":true}
```
nginx (serves the dashboard and proxies `/api`): see `deploy/nginx-menkerud.conf` and `../DEPLOYMENT.md`.

Set `DEVICE_KEY` in `.env` and the same value in the kiosk's `config.js` (`call.deviceKey`) so only the screen can start calls.

## Verify media before going live
Temporarily set `DEV=1` in `.env`, `sudo systemctl restart menkerud-backend`, and use the kiosk's "Ring far" (or `/dev/pair`) to prove a real call. Set `DEV=0` again after.

## Local development
`npm install && npm test` runs the token unit test. `DEV=1 LIVEKIT_API_KEY=devkey LIVEKIT_API_SECRET=devsecret_long_enough node src/server.js` boots without systemd.
