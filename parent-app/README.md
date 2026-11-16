# Menkerud Home – the parent app

The Android app on mor's and far's phones. It rings when the kids call from the screen at home, joins the call on
«Svar», and can ring home. It is installed by hand as an `.apk`, not from the Play Store, and it talks to three
things only: the call API (`call-backend/`), the LiveKit server whose address that API hands out, and Google's push
service. There are no accounts, no analytics and no crash reporting.

Design: `docs/superpowers/specs/2026-11-14-parent-app-design.md`. How it fits the rest: `DESIGN.md` §11.

## Building

Android Studio must be installed: the build uses its Java and the Android SDK. From this folder, in Git Bash:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleMinified :app:assembleRelease --console=plain
```

or in PowerShell:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:assembleMinified :app:assembleRelease --console=plain
```

`JAVA_HOME` is needed because the `java` on this PC's PATH is a 32-bit Java 8, which cannot run the build.
`:app:lintDebug` should stay free of findings.

### The three builds

| Build | App on the phone | Server it may be paired with | Lands in `app/build/outputs/apk/…` |
|---|---|---|---|
| **debug** | «Menkerud Home (test)», `com.noobventure.menkerud.debug` – a second app beside the real one | any address a QR code names, plain http allowed: the dev copy | `debug/app-debug.apk` (48 MB) |
| **minified** | the same test app | the same | `minified/app-minified.apk` (17 MB) |
| **release** | «Menkerud Home», `com.noobventure.menkerud` | `https://call.noobventure.com` only, https only | `release/app-release.apk` – `app-release-unsigned.apk` until a keystore is set up |

`minified` is the release build in everything the shrinker (R8) does to the code – and that is what breaks apps like
this one – but pointed at the dev copy. A release build cannot be tried out against the dev copy at all, so
`minified` is what gets tried out before a release build goes to the phones. The release and minified builds carry
arm64 code only (both phones are arm64).

Version: `versionCode` goes up by one for every build that reaches a phone; `versionName` is MINOR for features and
PATCH for fixes (`app/build.gradle.kts`).

### Two files the build wants that are never committed

- **`local.properties`** – one line saying where the Android SDK is. Android Studio writes it when it opens the
  project; by hand it is `sdk.dir=C\:/Users/Kjetil/AppData/Local/Android/Sdk` (the colon after the drive letter
  needs the backslash).
- **`app/google-services.json`** – from the Firebase project (below). The build reads the four values Firebase needs
  out of this file itself; the google-services Gradle plugin is not used. Without the file the app still builds and
  runs, but it cannot be rung, and its home screen says «Push er ikke satt opp i denne versjonen».

### Firebase, once

The phone is woken by Google's push service. In the Firebase console:

1. *Add project* «Menkerud Home», Analytics off.
2. *Add app → Android*, package name `com.noobventure.menkerud`. Then *Add app → Android* once more, package name
   `com.noobventure.menkerud.debug`.
3. Download `google-services.json` (it now holds both apps) and save it as `parent-app/app/google-services.json`.
4. For the server: *Project settings → Service accounts → Generate new private key*. That file is a secret; where it
   goes is in `call-backend/README.md`.

### The release keystore, once

The keystore is the app's identity on the two phones: an installed app can only be replaced by a build signed with
the same key. Lose it, and the app must be uninstalled before a new build can go on – which unpairs the phone. Make it
outside the repo and back it up:

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore <a folder outside the repo>\menkerud-release.jks -alias menkerud -keyalg RSA -keysize 2048 -validity 10000
```

Then copy `keystore.properties.template` to `keystore.properties` and fill it in. Neither file with real values is
ever committed.

## Installing on a phone

Either over a USB cable (Developer options → USB debugging on):

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\release\app-release.apk
```

or copy the `.apk` to the phone and open it there; Android asks once whether files from that app may be installed.
On a Samsung, **Auto Blocker** (Innstillinger → Sikkerhet og personvern) must be switched off while installing; it
can be switched on again afterwards.

**Updating** is installing a newer build over the old one. That works as long as it is signed with the same key and
its `versionCode` is higher; the pairing is kept. A build signed with another key is refused until the old app is
uninstalled, and uninstalling unpairs the phone.

## First start: pairing

A phone is paired once, with a key that the operator makes on the pairing page – a page of the call backend that is
only reachable on the home network (`call-backend/README.md`). Press «Ny telefon» for Mor or Far there; the page shows
a key and a QR code, good for one try within 15 minutes.

In the app: type the key and press «Koble til», or press «Skann QR-kode». A QR code read by the phone's own camera
opens the app with the key filled in; «Koble til» still has to be pressed. The phone does not have to be on the home
network to pair. Which parent a phone is comes from the key, never from the phone.

A phone that is removed on the pairing page stops ringing and is back on this screen the next time the app is
opened, with «Telefonen er koblet fra. Koble til på nytt med en ny nøkkel.»

## The phone's settings

The home screen says «Klar – telefonen ringer når de ringer hjemmefra» when everything is in place. Otherwise it says
«Telefonen er ikke klar til å ringe ennå» – in red when the phone will not ring at all, in yellow when it will ring
but not as surely or as well as it could – and lists what is missing. A tap on a row asks for the permission or opens
the setting that decides it:

| Row | What it is | Without it |
|---|---|---|
| «Telefonen er ikke meldt inn for anrop ennå – trykk for å prøve igjen» | the server has not been told where this phone's pushes go | the phone cannot be rung; a tap tries again, and so does every opening of the app |
| «Varsler» | notifications, and the kind «Anrop hjemmefra» left as it came – not turned down to «Standard» or «Lydløs» (a long press on the ringing notification is enough to do that) | the phone does not ring, or makes a sound without showing a call |
| «Vis anrop over låseskjermen» | Android 14 and newer: full-screen notifications | a locked phone still rings or vibrates, but its screen stays dark: it has to be woken and unlocked before the call can be seen and answered (tried on the S26, 2026-11-16) |
| «Kamera og mikrofon» | the two permissions; Android asks about «nearby devices» (Bluetooth, for a headset) in the same round, and a no to that changes nothing | «Svar» and «Ring hjem» ask for them first – which takes its time in the middle of answering (14 s from the first ring to the call when tried, against the usual 2–4 s) |
| «Ikke la batterisparing stoppe appen» | no battery restriction for the app | pushes may be held back for minutes when the phone has been lying still; set to «Begrenset», Android hands the app no pushes at all, and the headline is red |
| «Ikke sett appen på pause når den ikke brukes» | Android's «pause app activity if unused» off | after months without being opened the app stops getting pushes |
| «Push er ikke satt opp i denne versjonen» | the build had no `google-services.json` | the phone cannot be rung; nothing on the phone can change that |

Samsung keeps a list of its own that an app cannot read: Innstillinger → Batteri → Grenser for bakgrunnsbruk →
**Aldri sovende apper** → add Menkerud Home. Do this on both phones.

## What the app cannot do

- It does not ring after it has been **force-stopped** (Innstillinger → Apper → Tving stopp) until it is opened again –
  and the screen at home cannot know: it says «Ringer …» for the whole minute. Swiping the app away from the list of
  recent apps is fine, and so is a phone in deep sleep: both ring within a second (tried on 2026-11-16).
- It follows the phone's own sound setting. In **silent mode** and **Do Not Disturb** it does not ring aloud.
- On the banner that shows while the phone is in use, the two buttons carry Android's own words, in the phone's own
  language («Svar» / «Avslå»; «Answer» / «Decline» on a phone set to English). The full ringing screen says
  «Svar» / «Ikke nå».
- If Google's push service is down, the phone does not ring. The missed call shows when the push arrives.
- When the phone loses its network in the middle of a call, the picture stands still and the call ends by itself
  after 10–15 seconds («Legg på» ends it at once). The screen at home takes longer to notice: the phone's picture
  stands still there for some 20 seconds – that is how long LiveKit takes to give a silent phone up – and ten
  seconds later the screen at home closes the call by itself: about half a minute in all. (Until 2026-11-16 the
  screen left it to the server, and it took a minute.) The kids can press «Legg på» at any time.
- When the screen at home goes away in the middle of a call, the phone ends the call by itself: 10 seconds after
  the page was closed, about 30 after it lost power or crashed (LiveKit needs 20 of them to notice).
- Upright, the picture from home is a band across the screen: it is wider than a phone. Turn the phone and it fills
  the screen.

**For 2027:** Google is bringing developer verification to apps installed outside the Play Store. Installing over
`adb` is to stay exempt. If copying the `.apk` to a phone stops working one day, install over the cable.

## Trying it out against the dev copy

Development and testing use a separate copy of the backend on the dev PC, never the one the family uses: a phone
that is paired in production moves that parent off the Discord link at once. `call-backend/README.md`, «Developing
on a separate copy», says how to start it; its pairing page is `http://127.0.0.1:3101`.

The phone has to reach the dev copy at the address its QR code names. With the phone on a cable that is one command –

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" reverse tcp:3100 tcp:3100
```

– after which `http://127.0.0.1:3100` on the phone is the dev copy on the PC, and that is what the dev copy's QR code
says as it comes. Without a cable: start the dev copy with
`CALL_PUBLIC_URL=http://<the PC's address on the home network>:3100` in `.dev/callapi.env`, and let Windows' firewall
admit the phone to port 3100. Then install the **debug** or the **minified** build (they accept any server, and plain
http), make a key on the dev copy's pairing page, and scan it. Scan it – do not type it: a key that is typed goes to
`call.noobventure.com` in a test build as in every other build; only a QR code or a link can name another server.
With nobody at the PC's screen, the link can be handed to the app over adb instead, and «Koble til» pressed on the
phone:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell "am start -a android.intent.action.VIEW -d 'menkerudhome://pair?api=http%3A%2F%2F127.0.0.1%3A3100&code=<the key>' com.noobventure.menkerud.debug"
```

`adb reverse` works over **wireless debugging** as well, with no cable – as long as the phone stays on the Wi-Fi:
flight mode, or Wi-Fi off, switches wireless debugging off, and it has to be switched on again by hand. (A call
over mobile data therefore needs the cable.) Test builds have a button «Test forbindelsen ut» on the home screen: it asks
`call.noobventure.com` and `rtc.noobventure.com` for a sign of life and shows the two answers – `call 200 · rtc 401`
is what a phone that gets through sees, on Wi-Fi and on mobile data.

What the app writes to the phone's log is under one tag: each push that arrives, each turn a call takes, the ringing
notification going up and coming down, and whether the server knows where to send the pushes. Never a secret, a
ticket or a push address; a call is named by the last four characters of its id. When a phone «did not ring», this
is where to look first – it tells «no push came» from «the notification could not be shown»:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -s MenkerudHome
```

## Tests

- `:app:testDebugUnitTest` – everything that decides something, on the PC: reading a key, the API, reading a push,
  the life of a call (`CallControllerTest`), pairing, the texts, «is the phone ready».
- `DevCopyTest` – the app's own pairing, API client and call controller against a **running dev copy**: a key from
  its pairing page, its «pushes» read from the outbox file, a stand-in for the screen at home on the event stream.
  It answers «do the app and the server mean the same by the contract?» without a phone. Skipped unless the dev
  copy is named: `MENKERUD_DEV_COPY=http://127.0.0.1:3100 ./gradlew :app:testDebugUnitTest --tests '*DevCopyTest*'`
  (the dev copy as it comes: `PUSH_TRANSPORT=file`). Run it after any change to `call-backend/` or to `api/`.
- `:app:connectedDebugAndroidTest` – two small tests that need a phone on the cable: the Android Keystore
  (`KeystoreSecretBoxTest`) and the notification that rings (`AndroidRingNotifierTest`; the phone rings for a moment).
- The rest – ringing over the lock screen, a call with picture and sound, the background – is a written run on a
  real phone: Task 11 of `docs/superpowers/plans/2026-11-15-parent-app-2-android-app.md`.

## Where things are

```
app/src/main/kotlin/com/noobventure/menkerud/
  App.kt  AppContainer.kt       the process; the one place the app's objects are made and wired
  pairing/                      reading a key or a QR text (PairLink), and what «Koble til» does (Pairing)
  store/                        the pairing, sealed with a Keystore key (CredentialStore); the last call (AppPrefs)
  api/                          the call API as Kotlin (CallApi, HttpCallApi)
  push/                         reading a push; telling the server where pushes go; the push service
  call/                         CallController – the life of the one call there can be – and what it drives:
                                the LiveKit room, the notification that rings, the foreground service
  setup/                        is the phone ready to ring, and what is missing
  ui/                           MainActivity (pairing, home), CallActivity (a call), the screens, Texts.kt
```

One rule holds it together: everything that *decides* something is plain Kotlin behind a small interface with a test
on the PC; the Android pieces only show `CallController.state` and report taps to it. All text is Norwegian bokmål
and lives in `ui/Texts.kt`; code, comments and this file are English.

The typeface is Nunito (SIL Open Font License 1.1), one variable font file in `app/src/main/res/font/`. The
background, the avatars and the home badge are the kiosk's own art from `assets/`.

## Why the app targets Android 16

`targetSdk` is 36 on purpose, not 37. An app that targets 36 keeps its access to the home network without a new
permission prompt (at home the call's picture and sound go straight to the server on the LAN), and it stays clear
of Android 17's stricter rule for sound started from the background. Raise it only together with a test of both.
