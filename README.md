# Menkerud Home

Touchscreen dashboard for the kids: one-tap video-call mor or far (LiveKit), a "Jeg er hjemme" button, a sticky-note board that reads notes aloud, clock and weather. Norwegian UI, no Home Assistant required.

- **Try it:** open `index.html` in a browser. It runs in demo mode with sample notes and live weather.
- **Set it up:** `SETUP.md` (phones via Discord or ntfy, optional Home Assistant). The calling service lives in `call-backend/` (LiveKit + Discord). Production deployment (Ubuntu screen PC + Unraid): `DEPLOYMENT.md`.
- **Develop:** `DESIGN.md`, then `npm install && npm test`. `config.js` holds real secrets and is gitignored; `config.example.js` is the template.
