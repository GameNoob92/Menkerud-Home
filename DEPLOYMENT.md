# DEPLOYMENT.md — Menkerud Home production deployment

**Source of truth for deployment.** Target: a permanently mounted **Ubuntu Desktop touchscreen** at **1920×1080 landscape** running the dashboard in Firefox, with a local Node call-backend, talking to LiveKit + SWAG + Home Assistant on Unraid.

```
Development PC ──git push──▶ private Git repo ──git pull──▶ Ubuntu touchscreen PC
                                                            ├─ nginx        (serves the repo, proxies /api)
                                                            ├─ Firefox      (fullscreen, http://localhost)
                                                            └─ call-backend (Node, systemd :3000)
Unraid ─ Home Assistant · LiveKit (rtc.noobventure.com, UDP 7882) · SWAG (rtc. + call.)
```

LiveKit is **not** on the touchscreen PC. There is **no Docker** on the touchscreen PC. The Git checkout **is** the production directory — never copy into `/var/www`.

## Paths (Ubuntu)

| Purpose | Path |
|---|---|
| Repo = web root | `/home/menkerud/menkerud-home` |
| Backend | `/home/menkerud/menkerud-home/call-backend` |

## 1. One-time Ubuntu setup

```bash
# Node 20 (for the backend), nginx, Firefox, fonts, F11 helper
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs nginx firefox fonts-noto-color-emoji speech-dispatcher espeak-ng xdotool

# The git checkout is the web root
sudo -u menkerud git clone <your-private-repo-url> /home/menkerud/menkerud-home
cd /home/menkerud/menkerud-home
cp config.example.js config.js      # then fill in real values (Discord webhook/token, etc.)
```

### nginx (serves the repo, proxies /api to the backend)

```bash
sudo cp call-backend/deploy/nginx-menkerud.conf /etc/nginx/sites-available/menkerud
sudo ln -sf /etc/nginx/sites-available/menkerud /etc/nginx/sites-enabled/menkerud
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

The site is bound to `127.0.0.1` on purpose: `http://localhost` is a secure context (camera/mic work) and `config.js` (which holds the Discord token) never reaches the LAN. Do **not** serve it on the LAN IP over plain http.

### Backend (systemd)

```bash
cd call-backend
npm ci
cp .env.example .env     # fill LIVEKIT_API_KEY/SECRET (match Unraid's livekit.yaml), DISCORD_BOT_TOKEN, DEVICE_KEY
sudo cp deploy/menkerud-backend.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now menkerud-backend
curl -s localhost:3000/healthz     # expect {"ok":true,"livekit":true,"discord":true}
```

Set `DEVICE_KEY` in `.env` and the same value in `config.js` (`call.deviceKey`) so only the screen can start calls. The kiosk's `call.backend` stays blank (same-origin `/api`).

### Firefox (fullscreen appliance)

```bash
firefox -CreateProfile "Menkerud Home"    # once
mkdir -p ~/.config/autostart
cat > ~/.config/autostart/menkerud-home.desktop <<'EOF'
[Desktop Entry]
Type=Application
Name=Menkerud Home
Exec=sh -c 'firefox -P "Menkerud Home" http://localhost/ & sleep 8; xdotool search --sync --onlyvisible --class firefox windowactivate key F11'
X-GNOME-Autostart-enabled=true
EOF
```

Pre-grant camera/mic so Firefox never prompts (SETUP.md §3 has the `policies.json`). No Chromium, no Electron.

### GDM automatic login

Edit `/etc/gdm3/custom.conf`:

```ini
[daemon]
AutomaticLoginEnable=true
AutomaticLogin=menkerud
```

After boot the dashboard appears with no interaction.

## 2. Unraid side (once)

LiveKit + the two SWAG vhosts + the UDP 7882 forward — see **`call-backend/README.md` §A**. In `swag/call.subdomain.conf`, set `$upstream_app` to the **Ubuntu PC's LAN IP** (give it a DHCP reservation), because `call.noobventure.com` must reach the backend on that PC for the parents' answer page.

## 3. Updating

```bash
cd ~/menkerud-home && git pull
# only if backend deps changed:
cd call-backend && npm ci && sudo systemctl restart menkerud-backend
```

Front-end changes need no restart — nginx serves the files straight from the checkout. Run `npm test` on the dev PC before pushing.

## Secrets

Never commit `.env`, `config.js`, the Discord bot token or the LiveKit secret. Commit only `config.example.js` and `.env.example`. Production secrets live only on the Ubuntu PC (`config.js`, `call-backend/.env`) and Unraid (`livekit.yaml`).

## Acceptance checklist

- [ ] Ubuntu boots and logs in automatically (GDM)
- [ ] Firefox launches to `http://localhost`, fullscreen
- [ ] nginx serves the dashboard from the git checkout
- [ ] `menkerud-backend` runs under systemd, restarts on failure
- [ ] `localhost:3000/healthz` and `/api/...` respond
- [ ] LiveKit reachable at `wss://rtc.noobventure.com`; UDP 7882 forwarded
- [ ] `call.noobventure.com/answer/<token>` reaches the backend from mobile data
- [ ] "Ring far" → Discord DM → parent taps Svar → video both ways
- [ ] `git pull` updates the system with no manual file copying

## Constraints for future changes

Preserve the single-repo structure; no Docker on the touchscreen PC; LiveKit stays on Unraid; nginx stays the static server; Firefox stays the client; design for 1920×1080 landscape; keep everything Git-update compatible.
