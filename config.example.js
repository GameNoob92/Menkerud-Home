// Menkerud Home – optional defaults.
// Everything in here can also be set on the screen itself (☰ → PIN → Innstillinger), and what you
// save there overrides this file. Keep this for values you'd rather not type on a touchscreen
// (the Home Assistant token) or for re-installs. Delete the file if you don't need it.
window.MENKERUD_CONFIG = {
  home: {
    name: "Menkerud",
    subtitle: "FAMILIE • HJEM • SAMMEN • ALLTID",
    tagline: "Samme plass.\nSterkere dager.",
    footer: "Et bedre hjem, hver dag",
    homeSub: "Gi oss beskjed",                 // small line under "Jeg er hjemme"
    background: "auto"                          // "auto" (day/evening/night by clock) | "day" | "evening" | "night" | "none"
  },
  weather: { place: "Raufoss", lat: 60.725, lon: 10.617 },   // Open-Meteo; "Slå opp" in settings fills lat/lon from a place name

  people: {
    // photo: a path or a data URL; "" = use the emoji fallback. Defaults point at the placeholder art in assets/avatars/.
    mor: { name: "Mor", photo: "assets/avatars/mor.png", emoji: "👩", callSub: "Hun blir så glad!" },   // callSub = small line under "Ring Mor"
    far: { name: "Far", photo: "assets/avatars/far.png", emoji: "👨", callSub: "Rett hjem, uansett hvor du er!" }
  },

  // Video calls run on LiveKit via the call-backend (see DEPLOYMENT.md).
  call: {
    backend: "https://call.noobventure.com",  // the call-backend Docker container on Unraid (reached via SWAG)
    deviceKey: "",                       // must match DEVICE_KEY in the backend .env if you set one
    timeoutSec: 60                       // give up ringing after this many seconds
  },

  // How "Ring mor/far" and "Jeg er hjemme" reach the phones. No Home Assistant needed.
  notify: {
    provider: "discord",                 // "discord" | "ntfy" | "ha" | "webhook" | "none"
    discord: {
      webhook: "",                       // channel → Edit channel → Integrations → Webhooks → Copy URL
      userMor: "",                       // Discord user IDs (Developer mode → tap user → Copy User ID) so the phone
      userFar: "",                       //   gets @-mentioned even when the channel is set to "only @mentions"
      botToken: "",                      // only for "ring hjem" from the phone – see SETUP.md
      ringChannel: ""                    // channel ID the kiosk listens in; any message there from a parent rings it
    },
    ntfy: {
      server: "https://ntfy.sh",         // or your own, e.g. https://ntfy.noobventure.com
      topicMor: "",                      // e.g. menkerud-mor-7f3k9q  (what her ntfy app subscribes to)
      topicFar: "",
      topicKiosk: "",                    // e.g. menkerud-kiosk-2d8x1v (the kiosk listens here; ring.html publishes to it)
      token: ""                          // only for access-protected topics on a self-hosted server
    },
    webhook: { url: "" }                 // POST JSON {event, to, title, message, link} to anything you like
  },

  // Optional. Adds lights, cameras, notes from the phone (To-do list) and a natural TTS voice.
  ha: {
    url: "",                             // e.g. http://192.168.1.10:8123 – empty = not used
    token: "",                           // long-lived access token
    weatherEntity: "", todoEntity: "", ttsEntity: "",   // empty = auto-detect
    ttsLanguage: "no",
    includeSwitches: false,
    scripts: { mor: "script.ring_mor", far: "script.ring_far", home: "script.jeg_er_hjemme" },  // used when provider = "ha"
    callEvent: "menkerud_call"
  },

  pin: "1234",
  night: { enabled: true, from: "21:00", to: "06:30" },
  screen: { offEnabled: true, offFrom: "23:00", offTo: "06:00",     // display off at night; ⏻ in the top bar does it any time, a touch wakes it
            helper: "http://127.0.0.1:7777" },                       // the kiosk screen helper (scripts/kiosk-screen.py, SETUP.md §3); "" = black overlay only
  photos: { dir: "photos/", intervalSec: 20 },                      // 🖼 photo frame: "photos/", "/bilder/" (network share mounted on the screen PC, SETUP.md §3) or an http:// folder; seconds per picture

  emojis: ["⚽","🏀","🏊","⛷️","🎿","🚲","🥾","🏃","🎒","📚","✏️","🎨","🎵","🎹","🎮","📺","🦷","🛁","🧼","😴","🌙","⏰","🍎","🥪","🍕","🍦","🎂","🎁","🎉","❤️","⭐","😊","🧸","🐶","🐱","🐴","🧥","🧦","👟","🧤","☀️","🌧️","❄️","🚌","🚗","🏠","👵","👴","🎈","🧹"]
};
