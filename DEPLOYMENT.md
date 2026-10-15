# DEPLOYMENT.md — Menkerud Home production deployment

**Source of truth for deployment.** Target: a permanently mounted **Ubuntu Desktop touchscreen** at **1920×1080 landscape** showing the dashboard in Google Chrome (kiosk mode). It serves only static files and runs Chrome — no Node, no backend. (This family's screen: an Asus Vivo AIO, Ubuntu 26.04 on Wayland, Chrome 153.) The call-backend and LiveKit run on **Unraid**.

```
Ubuntu touchscreen                         Unraid
├─ nginx  → serves /var/www/menkerud-home  ├─ SWAG            (rtc. + call. + ha. subdomains)
├─ Chrome (kiosk, http://localhost)        ├─ LiveKit  Docker (rtc.noobventure.com, UDP 7882)
└─ screen helper (127.0.0.1:7777)          ├─ call-backend Docker (menkerud-callapi, :3000 → host 3008)
                                           └─ Home Assistant Docker (br0 192.168.68.3:8123; ha.noobventure.com for the phones)
```

Data flow: the kiosk calls `https://call.noobventure.com/api/*` → SWAG → the `menkerud-callapi` container, which mints LiveKit tokens and DMs the parent an answer link. Both ends join a room on LiveKit; media is a single UDP port, **7882**.

Ubuntu must **not** host or proxy the backend. There is **no Node and no system service** of ours on the touchscreen; the only process besides nginx and Chrome is the kiosk screen helper, a user-level systemd unit (Python, stdlib only) for screen off/on and «Hent oppdatering».

## Paths / identity (Ubuntu)

| Purpose | Value |
|---|---|
| User | `menkerud-hjem` |
| Repo = web root | `/var/www/menkerud-home` |

The git checkout at `/var/www/menkerud-home` is the web root. Do not clone a second copy under `/home`.

## 1. Ubuntu touchscreen (static + Chrome)

```bash
sudo apt install -y nginx curl git fonts-noto-color-emoji speech-dispatcher espeak-ng
wget -O /tmp/chrome.deb https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb
sudo apt install -y /tmp/chrome.deb      # Google Chrome; also adds Google's apt repo, so it updates with the system

# The git checkout is the web root; owned by the kiosk user
sudo mkdir -p /var/www/menkerud-home
sudo chown -R menkerud-hjem:menkerud-hjem /var/www/menkerud-home
sudo -u menkerud-hjem git clone <your-private-repo-url> /var/www/menkerud-home
cd /var/www/menkerud-home
sudo -u menkerud-hjem cp config.example.js config.js   # then fill in real values (see §Settings below)
```

### nginx (static only; no /api proxy)

```bash
sudo cp /var/www/menkerud-home/call-backend/deploy/nginx-menkerud.conf /etc/nginx/sites-available/menkerud   # re-run after a conf change (2026-09-12: Cache-Control no-cache on /; `grep no-cache /etc/nginx/sites-enabled/menkerud` shows whether it is in)
sudo ln -sf /etc/nginx/sites-available/menkerud /etc/nginx/sites-enabled/menkerud
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl restart nginx   # restart, not reload: see below
```

Use `restart` (not `reload`) whenever the `listen` address changes – e.g. when replacing a site that listened on `0.0.0.0:80` with this one (`127.0.0.1:80`). A reload cannot bind the new address while the old wildcard socket is open, returns success anyway, and silently keeps the old config running (workers keep their old start time in `ps`, `ss -ltn` still shows `0.0.0.0:80`). Any other enabled site in `/etc/nginx/sites-enabled/` must go, or it wins for port 80.

Bound to `127.0.0.1` so `config.js` (which holds the Discord token) never leaves the machine and the page is served from `http://localhost` — a secure context, required for camera/mic. The kiosk reaches the backend over the internet at `https://call.noobventure.com`.

### Screen off + photo frame (as the kiosk user, no sudo)

```bash
mkdir -p ~/.config/systemd/user
cp /var/www/menkerud-home/call-backend/deploy/menkerud-screen.service ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now menkerud-screen.service
curl -s http://127.0.0.1:7777/status      # {"power": 0, ...} = running
```

`scripts/kiosk-screen.py` blacks out the screen for the page (⏻ button, «Skjerm av» window, incoming calls) by setting the backlight to 0 through logind – not DPMS, which would also power down the AIO's USB touch controller; keep GNOME's own screen blanking at Never. Pictures for the photo frame go in `/var/www/menkerud-home/photos/` (gitignored) or on a network share mounted at `/mnt/bilder` (`cifs-utils`, fstab with `x-systemd.automount,nofail`); the nginx conf above lists both (`/photos/`, `/bilder/`). Details in SETUP.md §3.

### Chrome (kiosk appliance) + GDM autologin

A launcher script waits for nginx, then starts Chrome full-screen with its own persistent profile; a GNOME autostart entry runs it at login. As `menkerud-hjem`:

```bash
mkdir -p ~/.local/bin ~/.config/autostart
tee ~/.local/bin/menkerud-home.sh >/dev/null <<'EOF'
#!/bin/bash
# Menkerud Home kiosk launcher -- Google Chrome on Wayland.
until curl -s http://127.0.0.1/ >/dev/null; do sleep 1; done
exec google-chrome-stable \
  --kiosk http://localhost \
  --ozone-platform=wayland \
  --user-data-dir="$HOME/.config/menkerud-chrome" \
  --password-store=basic \
  --no-first-run --no-default-browser-check \
  --disable-session-crashed-bubble --disable-infobars
EOF
chmod +x ~/.local/bin/menkerud-home.sh
tee ~/.config/autostart/menkerud-home.desktop >/dev/null <<'EOF'
[Desktop Entry]
Type=Application
Name=Menkerud Home
Comment=Start Menkerud Home Dashboard
Exec=/home/menkerud-hjem/.local/bin/menkerud-home.sh
Terminal=false
X-GNOME-Autostart-enabled=true
EOF
```

Why the flags: `--kiosk` = full-screen with no way out by touch; `--ozone-platform=wayland` = native Wayland (touch and scaling behave); `--user-data-dir` = a dedicated profile that keeps the page's localStorage (on-screen settings, notes, calendar); `--password-store=basic` = no GNOME-keyring unlock prompt after autologin; the rest silences first-run and «restore pages?» bubbles. The live kiosk's script also carries `--enable-features=VaapiVideoDecoder,VaapiVideoEncoder --disable-features=UseChromeOSDirectVideoDecoder` from an attempt at hardware video decode; on this Celeron/UHD 600 it never took effect for WebRTC, so it is harmless and not needed – don't spend time on it again.

Pre-grant camera/mic so Chrome never prompts (SETUP.md §3 has the policy file, `/etc/opt/chrome/policies/managed/menkerud.json`). Leave GNOME's blanking and lock off – the screen helper does all the switching:

```bash
gsettings set org.gnome.desktop.session idle-delay 0
gsettings set org.gnome.desktop.screensaver lock-enabled false
gsettings set org.gnome.settings-daemon.plugins.power idle-dim false
```

GDM autologin — edit `/etc/gdm3/custom.conf`:

```ini
[daemon]
AutomaticLoginEnable=true
AutomaticLogin=menkerud-hjem
```

To restart Chrome without a reboot (e.g. after a policy change): log the kiosk user out and back in, or reboot. No Electron, no Node.

## 2. Unraid (SWAG + LiveKit + call-backend + Home Assistant)

Full detail in `call-backend/README.md`. In short:

- **LiveKit** already runs (`/mnt/user/appdata/LiveKit/config.yaml`; TCP 7880/7881, single UDP mux **7882** — never the 50000-60000 range). It runs on the **`br0` macvlan network with a dedicated static LAN IP `192.168.68.2`** (not host networking) and **`rtc.use_external_ip: true`**. Host networking crash-looped on the shared UDP 7882 bind (`address already in use`); a dedicated br0 IP binds cleanly, advertises a real LAN address, and `use_external_ip` STUN-discovers the public IP for phones. SWAG reaches it through Unraid's macvlan shim — keep *Settings → Docker → "Host access to custom networks"* enabled. SWAG conf `swag/rtc.subdomain.conf` proxies `rtc.noobventure.com` → `192.168.68.2:7880`.
- **call-backend**: put the `call-backend/` folder on Unraid (e.g. `/mnt/user/appdata/menkerud-call/`), fill `.env` (LiveKit `key`/`secret` matching `config.yaml`, `DISCORD_BOT_TOKEN`, `DEVICE_KEY`), then `docker compose up -d --build`. It listens on 3000, publishes host **3008**, and joins **noobventure-network**. SWAG conf `swag/call.subdomain.conf` proxies `call.noobventure.com` → `menkerud-callapi:3000`.
- **Router**: forward **UDP 7882** → `192.168.68.2` (LiveKit's br0 IP; both hops of the double-router; required); optionally **TCP 7881**; do not forward 7880.
- The `DEVICE_KEY` in the backend `.env` must equal `call.deviceKey` in the kiosk's `config.js`, so only the screen can start calls (the backend is internet-facing).
- **Home Assistant** (`Home-Assistant-Container`, official image, config in `/mnt/user/appdata/Home-Assistant-Container/`) runs on **`br0` with the static LAN IP `192.168.68.3`**. The kiosk talks to it over the LAN (`ha.url` in `config.js`, plain `http://192.168.68.3:8123`). The **Companion apps** must reach it from outside too, otherwise presence never turns Borte: SWAG conf `call-backend/swag/ha.subdomain.conf` proxies `ha.noobventure.com` → `192.168.68.3:8123` (Cloudflare-proxied DNS, SWAG's wildcard cert, no new port forward). HA then has to trust SWAG: **Settings → System → Network → "HTTP server"** → *Trust X-Forwarded-For* on, *Trusted proxies* = `192.168.68.4` (SWAG's own br0 IP – SWAG connects over br0, not through the host). Saving restarts HA and an admin must **confirm the new settings within 5 minutes** on the LAN address, or HA reverts them. Since HA 2026.8 these settings live in `.storage/http`; a `http:` block in `configuration.yaml` is imported once and ignored afterwards, so do not set it there. Until trust is set every proxied request gets **400** and HA's log says «not set-up for reverse proxies». SWAG does not unwrap Cloudflare's client IP, so HA sees Cloudflare's address as the visitor – leave HA's login-attempt banning off. Verify: `curl -o /dev/null -w '%{http_code}' https://ha.noobventure.com/` → `200`, `…/api/` → `401`.

Verify: `curl https://call.noobventure.com/healthz` → `{"ok":true,"livekit":true,"discord":true}`.

## Settings (why they must not reset)

On-screen settings (names, PIN, weather place, night mode) are saved in the browser's **localStorage**, which is tied to the exact URL and to the Chrome profile. They persist across restarts **only if** the kiosk always opens the same `http://localhost` with the same `--user-data-dir` (`~/.config/menkerud-chrome`), and Chrome is not set to clear site data on exit (and is not in incognito/guest mode). To be safe against any storage wipe, put the values you want permanent into **`config.js`**, which is loaded fresh every boot and underlies localStorage:

```js
weather: { place: "Raufoss", lat: 60.725, lon: 10.617 },
pin: "1234",
people: { mor: { name: "Mor", ... }, far: { name: "Far", ... } },
```

Anything in `config.js` is the baseline at every boot; on-screen edits still override it but are no longer the only copy.

## 3. Updating

Frontend (Ubuntu): on the screen, ☰ → Innstillinger → Status → **«Hent oppdatering»** (the kiosk screen helper runs `git pull --ff-only` and the page reloads; the helper restarts itself if the pull changed it). From a shell: `cd /var/www/menkerud-home && sudo -u menkerud-hjem git pull`, then reload the page — no service to restart, nginx serves the files.
Backend (Unraid): `git pull` the call-backend folder, then `docker compose up -d --build`.
Run `npm test` on the dev PC before pushing.

## Secrets

Never commit `.env`, `config.js`, `livekit.yaml`, the Discord bot token or the LiveKit secret. Commit only `config.example.js` and `.env.example`. Production secrets live on the Ubuntu PC (`config.js`) and Unraid (call-backend `.env`, `LiveKit/config.yaml`).

## Acceptance checklist

- [ ] Ubuntu boots and logs in automatically (GDM, user `menkerud-hjem`)
- [ ] Chrome launches to `http://localhost` in kiosk mode; nginx serves `/var/www/menkerud-home`; `chrome://policy` shows the camera/mic allow-list
- [ ] `menkerud-screen.service` runs as a user unit (`curl -s http://127.0.0.1:7777/status`); no Node/backend on the touchscreen
- [ ] `menkerud-callapi` container runs on Unraid, on `noobventure-network`, published on host 3008
- [ ] `https://call.noobventure.com/healthz` returns all-true from outside
- [ ] LiveKit reachable at `wss://rtc.noobventure.com`; UDP 7882 forwarded
- [ ] `https://ha.noobventure.com/` returns 200 from outside (HA trusts `192.168.68.4`); both Companion apps have it as External URL
- [ ] "Ring far" → Discord DM → parent taps Svar → video both ways on mobile data
- [ ] `git pull` updates each side with no manual file copying

## Constraints for future changes

Single repo; the backend is Docker on Unraid (never on the touchscreen); LiveKit stays on Unraid on the `br0` macvlan network with a dedicated static IP (`192.168.68.2`, not host networking) and the single UDP mux 7882 (never 50000-60000); nginx on Ubuntu serves static files only; Google Chrome (kiosk mode) is the client; design for 1920×1080 landscape; keep everything Git-update compatible.
