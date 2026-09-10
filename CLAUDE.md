# Menkerud Home – notes for Claude Code

Read `DESIGN.md` first; it is the source of truth for architecture, protocols and conventions. `SETUP.md` is the operator guide.

- The app is `index.html` alone: vanilla JS in one IIFE, CSS in one `<style>`, no build, no dependencies, no bundler. Keep it that way.
- `assets/` holds static art (backgrounds, avatars, icons) referenced by relative path; it is not code. Video calls run on **LiveKit** via the `call-backend/` service (DESIGN.md §11) — a *separate* app on Unraid, never bundled into `index.html`, so the single-file rule stands; the LiveKit client is lazy-loaded from a CDN only during a call. Jitsi was removed on 2026-09-10.
- It must keep working when opened from `file://` with nothing configured. In production it is served as static files by a local nginx and shown in Firefox at `http://localhost` (a secure context, so camera/mic work) — nginx serves files only, never server-side logic. Never assume `http://<LAN-IP>`: that is not a secure context, so it kills the camera/mic (getUserMedia) the LiveKit call needs. In production the git checkout at `/var/www/menkerud-home` is the web root (nginx serves static files only, no `/api` proxy) and the call-backend runs as a Docker container on Unraid (`menkerud-callapi`), which the kiosk calls at `https://call.noobventure.com`; `DEPLOYMENT.md` is the source of truth for deployment.
- UI text is Norwegian bokmål; code, comments and docs are English. Parent keys are `mor`/`far`; display names come from `cfg.people.X.name`.
- Settings are schema-driven (`SETTINGS` array). New options go into `DEFAULTS` + `SETTINGS`; do not hand-write form HTML.
- After any change: `npm install` (once) then `npm test`. `scripts/check.js` syntax-checks the inline script; `test/smoke.js` is the jsdom end-to-end test – extend it for new features, don't add a test framework.
- Escape dynamic text with `esc()`. Sizes in `rem` (1 rem = 1 % of screen width on 16:9). Network failures become a toast or a status row, never a broken screen.
- When you change behaviour, update `DESIGN.md` (module map / flows / backlog) and, if the operator must do something differently, `SETUP.md`.
