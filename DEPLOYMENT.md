# DEPLOYMENT.md — Menkerud Home production deployment

**Source of truth for deployment.** Target: a permanently mounted **Ubuntu Desktop touchscreen** at **1920×1080 landscape** showing the dashboard in Firefox. It serves only static files and runs Firefox — no Node, no backend. The call-backend and LiveKit run on **Unraid**.

```
Ubuntu touchscreen                         Unraid
├─ nginx  → serves /var/www/menkerud-home  ├─ SWAG            (rtc. + call. subdomains)
└─ Firefox (kiosk, http://localhost)       ├─ LiveKit  Docker (rtc.noobventure.com, UDP 7882)
                                           └─ call-backend Docker (menkerud-callapi, :3000 → host 3008)
```

Data flow: the kiosk calls `https://call.noobventure.com/api/*` → SWAG → the `menkerud-callapi` container, which mints LiveKit tokens and DMs the parent an answer link. Both ends join a room on LiveKit; media is a single UDP port, **7882**.

Ubuntu must **not** host or proxy the backend. There is **no Node and no systemd service** on the touchscreen.

## Paths / identity (Ubuntu)

| Purpose | Value |
|---|---|
| User | `menkerud-hjem` |
| Repo = web root | `/var/www/menkerud-home` |

The git checkout at `/var/www/menkerud-home` is the web root. Do not clone a second copy under `/home`.

## 1. Ubuntu touchscreen (static + Firefox)

```bash
sudo apt install -y nginx firefox fonts-noto-color-emoji speech-dispatcher espeak-ng xdotool

# The git checkout is the web root; owned by the kiosk user
sudo mkdir -p /var/www/menkerud-home
sudo chown -R menkerud-hjem:menkerud-hjem /var/www/menkerud-home
sudo -u menkerud-hjem git clone <your-private-repo-url> /var/www/menkerud-home
cd /var/www/menkerud-home
sudo -u menkerud-hjem cp config.example.js config.js   # then fill in real values (see §Settings below)
```

### nginx (static only; no /api proxy)

```bash
sudo cp /var/www/menkerud-home/call-backend/deploy/nginx-menkerud.conf /etc/nginx/sites-available/menkerud
sudo ln -sf /etc/nginx/sites-available/menkerud /etc/nginx/sites-enabled/menkerud
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

Bound to `127.0.0.1` so `config.js` (which holds the Discord token) never leaves the machine and the page is served from `http://localhost` — a secure context, required for camera/mic. The kiosk reaches the backend over the internet at `https://call.noobventure.com`.

### Screen off + photo frame (as the kiosk user, no sudo)

```bash
gsettings set org.gnome.desktop.session idle-delay 15                                   # display off 15 s after the page drops its wake lock
gsettings set org.gnome.desktop.screensaver lock-enabled false                          # no lock screen on wake
gsettings set org.gnome.settings-daemon.plugins.power sleep-inactive-ac-type nothing    # never suspend the PC itself
```

The page holds a Screen Wake Lock while it should be visible, so the display only blanks when the page lets it (⏻ button or the «Skjerm av» window). Pictures for the photo frame go in `/var/www/menkerud-home/photos/` (gitignored); the nginx conf above enables the folder listing for `/photos/`. Details in SETUP.md §3.

### Firefox (fullscreen appliance) + GDM autologin

```bash
sudo -u menkerud-hjem firefox -CreateProfile "Menkerud Home"   # once
sudo -u menkerud-hjem mkdir -p /home/menkerud-hjem/.config/autostart
sudo -u menkerud-hjem tee /home/menkerud-hjem/.config/autostart/menkerud-home.desktop >/dev/null <<'EOF'
[Desktop Entry]
Type=Application
Name=Menkerud Home
Exec=sh -c 'firefox -P "Menkerud Home" http://localhost/ & sleep 8; xdotool search --sync --onlyvisible --class firefox windowactivate key F11'
X-GNOME-Autostart-enabled=true
EOF
```

Pre-grant camera/mic so Firefox never prompts (SETUP.md §3 has the `policies.json`). GDM autologin — edit `/etc/gdm3/custom.conf`:

```ini
[daemon]
AutomaticLoginEnable=true
AutomaticLogin=menkerud-hjem
```

No Chromium, no Electron, no Node.

## 2. Unraid (SWAG + LiveKit + call-backend)

Full detail in `call-backend/README.md`. In short:

- **LiveKit** already runs (`/mnt/user/appdata/LiveKit/config.yaml`; TCP 7880/7881, single UDP mux **7882** — never the 50000-60000 range). It runs on the **`br0` macvlan network with a dedicated static LAN IP `192.168.68.2`** (not host networking) and **`rtc.use_external_ip: true`**. Host networking crash-looped on the shared UDP 7882 bind (`address already in use`); a dedicated br0 IP binds cleanly, advertises a real LAN address, and `use_external_ip` STUN-discovers the public IP for phones. SWAG reaches it through Unraid's macvlan shim — keep *Settings → Docker → "Host access to custom networks"* enabled. SWAG conf `swag/rtc.subdomain.conf` proxies `rtc.noobventure.com` → `192.168.68.2:7880`.
- **call-backend**: put the `call-backend/` folder on Unraid (e.g. `/mnt/user/appdata/menkerud-call/`), fill `.env` (LiveKit `key`/`secret` matching `config.yaml`, `DISCORD_BOT_TOKEN`, `DEVICE_KEY`), then `docker compose up -d --build`. It listens on 3000, publishes host **3008**, and joins **noobventure-network**. SWAG conf `swag/call.subdomain.conf` proxies `call.noobventure.com` → `menkerud-callapi:3000`.
- **Router**: forward **UDP 7882** → `192.168.68.2` (LiveKit's br0 IP; both hops of the double-router; required); optionally **TCP 7881**; do not forward 7880.
- The `DEVICE_KEY` in the backend `.env` must equal `call.deviceKey` in the kiosk's `config.js`, so only the screen can start calls (the backend is internet-facing).

Verify: `curl https://call.noobventure.com/healthz` → `{"ok":true,"livekit":true,"discord":true}`.

## Settings (why they must not reset)

On-screen settings (names, PIN, weather place, night mode) are saved in the browser's **localStorage**, which is tied to the exact URL and to the Firefox profile. They persist across restarts **only if** the kiosk always opens the same `http://localhost` in the same persistent "Menkerud Home" profile, and Firefox is not set to clear history/site data on close (and is not in private mode). To be safe against any storage wipe, put the values you want permanent into **`config.js`**, which is loaded fresh every boot and underlies localStorage:

```js
weather: { place: "Raufoss", lat: 60.725, lon: 10.617 },
pin: "1234",
people: { mor: { name: "Mor", ... }, far: { name: "Far", ... } },
```

Anything in `config.js` is the baseline at every boot; on-screen edits still override it but are no longer the only copy.

## 3. Updating

Frontend (Ubuntu): `cd /var/www/menkerud-home && sudo -u menkerud-hjem git pull` — no service to restart, nginx serves the files.
Backend (Unraid): `git pull` the call-backend folder, then `docker compose up -d --build`.
Run `npm test` on the dev PC before pushing.

## Secrets

Never commit `.env`, `config.js`, `livekit.yaml`, the Discord bot token or the LiveKit secret. Commit only `config.example.js` and `.env.example`. Production secrets live on the Ubuntu PC (`config.js`) and Unraid (call-backend `.env`, `LiveKit/config.yaml`).

## Acceptance checklist

- [ ] Ubuntu boots and logs in automatically (GDM, user `menkerud-hjem`)
- [ ] Firefox launches to `http://localhost`, fullscreen; nginx serves `/var/www/menkerud-home`
- [ ] No Node/systemd/backend on the touchscreen
- [ ] `menkerud-callapi` container runs on Unraid, on `noobventure-network`, published on host 3008
- [ ] `https://call.noobventure.com/healthz` returns all-true from outside
- [ ] LiveKit reachable at `wss://rtc.noobventure.com`; UDP 7882 forwarded
- [ ] "Ring far" → Discord DM → parent taps Svar → video both ways on mobile data
- [ ] `git pull` updates each side with no manual file copying

## Constraints for future changes

Single repo; the backend is Docker on Unraid (never on the touchscreen); LiveKit stays on Unraid on the `br0` macvlan network with a dedicated static IP (`192.168.68.2`, not host networking) and the single UDP mux 7882 (never 50000-60000); nginx on Ubuntu serves static files only; Firefox is the client; design for 1920×1080 landscape; keep everything Git-update compatible.
