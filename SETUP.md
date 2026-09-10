# Menkerud Home – setup

Nothing here needs Home Assistant. The screen works on its own; HA is an optional add-on for lights, cameras and adding notes from your phone.

```
Screen PC (Ubuntu; nginx serves the files, Firefox opens http://localhost fullscreen)
   ├─ Clock, weather (Open-Meteo), notes stored on the PC
   ├─ "Ring mor/far"   →  Discord DM with an "Svar" link  →  tap it  →  video in LiveKit
   ├─ "Jeg er hjemme"  →  Discord message to both of you
   └─ You write anything in #ring-hjem on Discord  →  the screen rings  →  kid taps "Svar"
LiveKit + call-backend (Docker on Unraid, rtc./call.noobventure.com)  – the video itself
Home Assistant (optional)  – lights, cameras, notes from the phone, natural TTS voice
```

Files: `index.html` (the app), `config.js` (optional defaults), `assets/` (backgrounds, avatars and icons the screen shows – copy it along), `ring.html` (only needed with ntfy), `photos/` (optional – photos can also be picked on-screen).

All settings live on the screen: **☰ → PIN 1234 → Innstillinger**. Names, photos, PIN, place for the weather, call-backend URL, how to ring the phones, night dimming. Anything saved there overrides `config.js`. The one thing that's painful to type on a touchscreen is a token (Discord bot or HA), so those are happier in `config.js`, or plug in a USB keyboard for a minute.

Note format on the board: `<emoji> [HH:MM] [text]`, e.g. `⚽ 17:00 Fotball`. The emoji is the big picture, tapping the note reads it aloud ("Fotball klokka fem", "Tannpuss halv ni"). Notes are added under **☰ → Ny lapp** (with an on-screen keyboard), or from your phone if you later connect Home Assistant. Dated events (birthdays, matches) go under **☰ → Kalender** — pick a day, add an emoji, optional time and repeat — and show up under **I DAG** on the day.

---

## 1. Phones: Discord (nothing new to install)

**Screen → phones (5 minutes).**

1. Make a private server (or use one you have) with a channel, say `#hjemme`. Channel → Edit channel → Integrations → Webhooks → New webhook → Copy webhook URL.
2. Discord → Settings → Advanced → Developer mode on. Then tap your own profile → Copy User ID; same for the other parent.
3. On the screen: Innstillinger → "Hvordan varsle" → Discord, paste the webhook URL and both user IDs → **Lagre** → "Test varsel til mor". A message like `@Mor 📞 Test fra Menkerud Home…` should land in `#hjemme` and buzz the phone.

The user IDs matter: they make every message an @-mention, so the phone notifies even when the server is on "Only @mentions" (the Discord default). Make sure Discord notifications are allowed on both phones, and set `#hjemme` to "All messages" if you like.

**Phones → screen ("ring hjem"), 10 minutes, once.** The screen runs a tiny Discord bot and rings when either of you writes anything in one channel.

1. `discord.com/developers/applications` → New Application "Menkerud Home" → Bot → Reset Token → copy it.
2. OAuth2 → URL Generator → scope `bot`, permission `View Channels` → open the generated URL → add it to your server.
3. Create a channel `#ring-hjem`. Developer mode → long-press the channel → Copy Channel ID.
4. On the screen: Innstillinger → Bot-token and Kanal-ID → Lagre. The status row "Mor/far ringer hjem" should say "Discord-bot lytter ✓".
5. Write "ring" (or anything) in `#ring-hjem`. The screen shows your photo and rings; the kid taps **Svar** and joins the video room. You tap the room link the screen posted in `#hjemme` (or just open the room URL you've bookmarked).

Keep `#ring-hjem` for that one purpose – every human message there rings the screen. The bot needs no privileged intents; it only looks at who wrote, not what.

Already running a bot in the same server? Reuse its token instead of creating a new application – a bot token can have several gateway sessions at once, and the screen only needs `View Channels` in `#ring-hjem`. This family's `config.js` uses GameNoobBot's token (`locals/Bots/Discord/GameNoobBot/.env`, `BOT_TOKEN`). If that token is ever reset, update `config.js` the same day: the screen keeps retrying with the old token, and Discord resets a bot's token when it sees too many failed logins in 24 hours, which would take GameNoobBot down too.

### Alternative: ntfy

Discord can't ring through a phone on silent/Do Not Disturb; **ntfy** can (free app, Android/iOS, no account). If that matters more than "no new app": install ntfy on both phones, subscribe each to an unguessable topic (e.g. `menkerud-mor-7f3k9q`), and on the screen pick "ntfy-appen" and fill in the topics. On Android, let the ntfy "Max priority" channel override Do Not Disturb. For "ring hjem" with ntfy the screen listens on a third topic and you use `ring.html` from a home-screen link:

```
https://<any https host>/ring.html?who=mor&topic=menkerud-kiosk-9k2m4p&room=https://meet.noobventure.com/menkerud-familie-x7k2
```

Both can be on at the same time: Discord for the messages, the ntfy kiosk topic for ringing home – the screen listens to whatever is configured.

---

## 2. Video calls (LiveKit)

Calls run on **LiveKit** plus a small **call-backend** that mints tokens and rings the parent over Discord. Both run on Tower — LiveKit and the call-backend (a Docker container, `menkerud-callapi`, on `noobventure-network`) — behind SWAG; the screen calls the backend at `https://call.noobventure.com`. Full steps: **`DEPLOYMENT.md`** and **`call-backend/README.md`**.

In short:

- `rtc.noobventure.com` → LiveKit signaling; media on **UDP 7882** (forwarded on both routers — ISP router → mesh → Tower).
- `call.noobventure.com` → the backend API and the parent's answer page.
- The screen calls the backend and joins a private room; the backend DMs the parent an **Svar** link that joins the same room. No app to install — it opens in the phone's browser.

On the screen: Innstillinger → Videosamtale → **Call-backend URL** = `https://call.noobventure.com` (already the default in `config.js`). The camera and mic are pre-granted by the Firefox policy in §3 and work because the page is served from `http://localhost` (a secure context).

---

## 3. The screen PC (Ubuntu + nginx + Firefox)

Full production setup — the git checkout at `/var/www/menkerud-home` as the web root, nginx (static only), Firefox fullscreen and GDM autologin — is in **`DEPLOYMENT.md`**. The call-backend is not here; it runs as a Docker container on Unraid. Two things worth calling out:

**Serve from localhost, never the LAN IP.** The camera and mic (the LiveKit call) need a *secure context*; `http://localhost` and `https://` qualify, `http://<LAN-IP>` does not, so the LAN IP would silently kill calling. nginx is bound to `127.0.0.1` so `config.js` and its token stay on the machine, and Firefox opens `http://localhost`. The page also still works opened from `file://` for a quick test.

**Pre-grant camera/mic so Firefox never prompts** (enterprise policy):

```bash
sudo mkdir -p /etc/firefox/policies
sudo tee /etc/firefox/policies/policies.json >/dev/null <<'POLICY'
{
  "policies": {
    "Permissions": {
      "Camera":     { "Allow": ["http://localhost"], "BlockNewRequests": true },
      "Microphone": { "Allow": ["http://localhost"], "BlockNewRequests": true }
    },
    "Autoplay": { "Default": "allow-audio-video" },
    "PasswordManagerEnabled": false,
    "DisableTelemetry": true,
    "DisableFirefoxStudies": true,
    "DontCheckDefaultBrowser": true,
    "OverrideFirstRunPage": ""
  }
}
POLICY
```

Also: Settings → Power → never blank the screen (or use the page's night dimming), and check the default audio device (`pavucontrol` / `wpctl status`) so the call uses the right mic and speakers.

## 4. Optional: Home Assistant

Adds: lights and cameras in the ☰ menu, notes from your phone (Companion app → To-do → Husk), a natural Google-Translate/Piper voice instead of the browser's, and a third way to ring the phones.

Run HA as a Docker container on Unraid (Community Apps → Home-Assistant-Container, `network_mode: host`, `TZ=Europe/Oslo`), then in HA:

1. Settings → Devices & services → Add integration → **Local To-do** → name `Husk`.
2. Add integration → **Google Translate text-to-speech** → language Norwegian.
3. Profile (bottom left) → Security → **Long-lived access tokens** → create "Kiosk".
4. Put the URL and token in `config.js` (`ha.url`, `ha.token`) or under Innstillinger. Entities are auto-detected; the footer dot turns green when connected.

Only if you want HA to ring the phones instead of Discord/ntfy (Innstillinger → "Hvordan varsle" → Home Assistant), add these scripts in `scripts.yaml` and a notify group in `configuration.yaml`:

```yaml
# configuration.yaml
notify:
  - platform: group
    name: foreldre
    services:
      - action: mobile_app_mors_telefon   # your Companion-app names (Developer tools → Actions → "notify")
      - action: mobile_app_fars_telefon
```

```yaml
# scripts.yaml
ring_mor:
  alias: Ring mor (fra kiosk)
  sequence:
    - action: notify.mobile_app_mors_telefon
      data:
        title: "📞 Hjemme ringer"
        message: "Barna vil snakke med deg – trykk for å svare ❤️"
        data:
          clickAction: "https://meet.noobventure.com/menkerud-familie-x7k2#config.disableDeepLinking=true"  # Android
          url: "https://meet.noobventure.com/menkerud-familie-x7k2#config.disableDeepLinking=true"          # iOS
          channel: Ring
          importance: max
          priority: high
          ttl: 0
          timeout: 90
          push:
            sound:
              name: default
              critical: 1
              volume: 1.0

ring_far:
  alias: Ring far (fra kiosk)
  sequence:
    - action: notify.mobile_app_fars_telefon
      data:
        title: "📞 Hjemme ringer"
        message: "Barna vil snakke med deg – trykk for å svare ❤️"
        data:
          clickAction: "https://meet.noobventure.com/menkerud-familie-x7k2#config.disableDeepLinking=true"
          url: "https://meet.noobventure.com/menkerud-familie-x7k2#config.disableDeepLinking=true"
          channel: Ring
          importance: max
          priority: high
          ttl: 0
          timeout: 90
          push:
            sound:
              name: default
              critical: 1
              volume: 1.0

jeg_er_hjemme:
  alias: Jeg er hjemme (fra kiosk)
  sequence:
    - action: notify.foreldre
      data:
        title: "🏠 Barna er hjemme!"
        message: "«Jeg er hjemme» trykket kl. {{ now().strftime('%H:%M') }} ❤️"

# Parent → screen through HA instead of Discord: run from the Companion app, then join the room.
ring_hjemme_mor:
  alias: Ring hjem (mor)
  icon: mdi:phone-incoming
  sequence:
    - event: menkerud_call
      event_data:
        from: mor
```

---

## 5. First run

- [ ] The dashboard (`http://localhost`, or `index.html` for a quick test) opens with sample notes and today's weather
- [ ] Innstillinger: names, photos, PIN saved (survives a reload)
- [ ] Discord webhook + user IDs; "Test varsel" lands in `#hjemme` and buzzes the phone
- [ ] Call backend deployed (`call-backend/README.md`); `https://call.noobventure.com/healthz` returns ok
- [ ] "Ring mor" → parent gets a Discord DM → taps **Svar** → video both ways; screen shows the big red "Legg på"
- [ ] "Jeg er hjemme" → both of you get the message
- [ ] Bot token + channel ID; writing in `#ring-hjem` rings the screen; "Svar" connects
- [ ] Ny lapp → note appears on the board, tap reads it aloud
