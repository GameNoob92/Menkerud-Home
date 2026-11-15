# Parent App, Part 2 – the Android App – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sideloaded Android app for mor's and far's phones that is paired once with a key, rings when the kids call from the kiosk, joins the call on «Svar», and can ring home.

**Architecture:** One Gradle module in `parent-app/`. Everything that *decides* something – reading a pairing link, reading a push, talking to the API, the life of a call – is plain Kotlin behind small interfaces and is tested on the JVM. The Android-only pieces (the notification that rings, the push service, the LiveKit room, the foreground service, the screens) are thin and are checked on a real phone against the dev copy of the backend. One `CallController` owns the state of the one call there can be; the notification, the screens and the services only show that state and report taps to it.

**Tech Stack:** Kotlin 2.2.10 (the Kotlin built into AGP 9.2.1), Gradle 9.4.1, Jetpack Compose (BOM 2024.12.01, Material 3), LiveKit Android SDK 2.30.0, Firebase Cloud Messaging (BoM 35.2.0 → firebase-messaging 26.0.2), OkHttp 4.12.0, kotlinx-serialization-json 1.7.3, kotlinx-coroutines 1.9.0, Google code scanner 16.1.0. Tests: JUnit 4.13.2, MockWebServer 4.12.0, kotlinx-coroutines-test 1.9.0.

**Spec:** `docs/superpowers/specs/2026-11-14-parent-app-design.md` (approved by the operator on 2026-11-14).
**API contract:** the section «Contract» (C1–C11, revised 2026-11-15) in `docs/superpowers/plans/2026-11-14-parent-app-1-api-and-kiosk.md`. The app is written against it; the part of it the app uses is repeated below so that every value is in this document.

## Where things stand (2026-11-15)

- Part 1 (the API and the kiosk's side) is built, reviewed and in `main`. **The backend is not deployed**: the container on Unraid still runs the September code. Rolling out is part 3.
- A **dev copy** of the backend runs on the dev PC (`call-backend/README.md`, «Developing on a separate copy»): API on `http://127.0.0.1:3100`, pairing page on `http://127.0.0.1:3101` (password `dev`), device key `dev-key`, no Discord token. The kiosk page can be pointed at it from a browser on the PC. **This plan develops and tests against the dev copy only.** Pairing a phone in production would move that parent off the Discord link at once (spec §8), so production gets its first paired phone in part 3.
- A throw-away build on this PC on 2026-11-15 proved the versions above together: debug build, a unit test with MockWebServer, and a minified release build, **from a folder with a space in its name** (one of the spec's three early checks). Release APK, arm64 only: 15 MB.
- From this PC a client that is not a browser (`okhttp`, `Dalvik` user agents) gets through Cloudflare to `https://call.noobventure.com/healthz` (200) and `https://rtc.noobventure.com/rtc/validate` (LiveKit's own 401). The same from a mobile network is checked with the app in Task 11.
- Still open from part 1, and the operator's: **one call from the kiosk answered in Chrome on mobile data with Wi-Fi off.** The app depends on exactly that path. Task 11 tests it with the app itself (phone on a USB cable, Wi-Fi off).

## Global Constraints

- The app lives in `parent-app/`. Application id **`com.noobventure.menkerud`**, app name **«Menkerud Home»**, **minSdk 26**, Gradle Kotlin DSL, the release keystore and its passwords kept out of git (spec §8).
- UI text is Norwegian bokmål; code, comments and docs are English. The two identities are `mor` and `far`; which one a phone is comes from the server, never from the app.
- A sideloaded `.apk`: no Play Store, no accounts, no analytics, no crash reporting service. The only servers the app talks to are the call API, the LiveKit server whose address the API hands out, and Google's push service.
- Every push has a `type`; the app ignores types and versions it does not know (spec §8). Nothing in the app may stand in the way of «messages later» (spec §9): the home screen has room for a second section, and pushes are dispatched by `type`.
- The ring follows the phone's own sound setting. Do not try to ring through silent mode or Do Not Disturb (spec §3).
- The phone's secret is stored encrypted and never logged, shown or backed up. The app logs under the one tag `MenkerudHome`, and never a secret, a LiveKit ticket or a push address. `google-services.json`, any keystore, `keystore.properties` and `local.properties` are never committed.
- Nothing visible changes on the kiosk, and nothing new becomes reachable from the internet (spec §4). The backend is only touched if a real gap in the contract is found, and then with a test first, in `call-backend/test/`.
- Builds use Android Studio's Java: `JAVA_HOME` = `C:\Program Files\Android\Android Studio\jbr` (the `java` on PATH is a 32-bit Java 8). The repo path contains a space: quote every path.
- Work on the local `experimental` branch, one commit per task, each message ending with the session's attribution lines. Never push, never merge to `main`, never deploy, never pair a phone with production.

## Decisions made in this plan

The operator has not decided these. Each is the planner's call; say so if any is unwanted.

1. **Toolchain = DrySpool's**, which is on this PC and proven: Gradle 9.4.1, AGP 9.2.1, Kotlin 2.2.10, Compose BOM 2024.12.01, `compileSdk 36`, Java 17 bytecode. One difference: the app uses the Kotlin that AGP 9 has built in, instead of DrySpool's two opt-out flags (`android.builtInKotlin=false`, `android.newDsl=false`), which AGP now calls deprecated. Both variants were built on 2026-11-15.
2. **`targetSdk 36`, not 37**, on purpose. The parents' phones are probably on Android 17 by now; an app that targets 36 keeps its access to the home network without a new permission prompt (the call's media goes to `192.168.68.2` when the phone is at home) and stays clear of Android 17's stricter rule for sound from the background.
3. **No Hilt, no Room, no navigation library.** One `AppContainer` wires the handful of objects by hand; there are two activities. DrySpool uses Hilt; this app is a tenth of its size.
4. **Ringing is a notification, not a service:** a call-style notification with a full-screen intent on a channel whose sound is the phone's ringtone. That is the only way Android lets an app in the background show a ringing screen. One consequence the design did not foresee: on the small banner that shows while the phone is in use, **Android writes the two buttons itself («Svar» / «Avslå») and an app cannot change them**. The full-screen ringing screen says «Svar» / «Ikke nå» as designed.
5. **No Telecom integration** (`androidx.core:core-telecom`). It would hand the audio routing to Android and fight LiveKit's own; nothing in the design needs it.
6. **Push registration through Firebase's current installation-id API** (`FirebaseMessaging.register()`, `onRegistered`), not the token API Google deprecated in June 2026. The server keeps sending to the address it is given; Google's `message.token` field accepts both during its transition. The older token API is the named fall-back (Task 5).
7. **Firebase without the google-services Gradle plugin.** The build script reads the operator's `google-services.json` itself and writes the four values the SDK needs as string resources. Without the file the app still builds and runs; it just cannot be rung (and says so).
8. **The debug build is a second app** (`com.noobventure.menkerud.debug`, «Menkerud Home (test)»), so the operator's phone can hold the test build next to the real one. Cost: two app registrations in the Firebase project (Task 0).
9. **The API address comes from the QR code, but a release build only accepts `https://call.noobventure.com`.** A QR code from anywhere else is refused, so nobody can talk a parent into pairing the app with a foreign server. Debug builds accept any address (the dev copy).
10. **«Ring hjem» joins the room at once**, with the parent's own camera picture on screen while it rings. The kiosk expects the parent to be in the room when a child taps «Svar».
11. **The release APK carries arm64 code only** (both phones are arm64): 15 MB instead of about 40.
12. **QR scanning through Google's code scanner** (in Google Play services; no camera permission for it, almost no size). A key can always be typed instead.
13. **The secret is sealed with a key in the Android Keystore** and kept in a file that Android never backs up. A phone restored from a backup has to be paired again; so does a phone whose data was cleared.
14. **Tests where they are cheap** (spec §8): JVM unit tests for everything that decides something, two small on-device tests (the Keystore; the notification that rings), and a written run on a real phone for the rest. No Robolectric, no Compose UI tests.

## Review Focus

Conditions the design implies and that the task-by-task tests would not meet by themselves. Each is pinned by the test named after the arrow.

1. **A push that comes late or twice, or a phone whose clock is wrong.** A `call` push for a call that is already over must not ring; a wrong clock must neither cut a real ring short nor let it ring for ever; a `call_end` for a call the phone never rang for (it was off) still leaves «Tapt anrop …». → Task 6: `a push for a call that is already over stops ringing at once`, `a phone clock that is wrong rings for 45 seconds`, `a missed call the phone never rang for still leaves its notice`.
2. **«Svar» when it is too late** – answered on the other phone, the kids hung up, tapped twice, or tapped from the notification after Android had killed the app. One clean «Samtalen er over» or one call; never a crash, a stuck ringing screen or two calls. → Task 6: `Svar after the call was taken elsewhere says the call is over`, `Svar twice starts one call`, `Svar from the notification works after a restart`.
3. **The connection is lost in the middle of a call, or the kiosk vanishes without hanging up.** The call ends on the phone with «Samtalen er avsluttet», camera and microphone are let go, the notification disappears. → Task 6: `the room closing ends the call`, `the other side gone for ten seconds ends the call`, `a call always lets go of its session exactly once`; Task 11, steps 9–10.
4. **The phone was removed on the pairing page, or its stored secret can no longer be read.** The app goes back to the pairing screen and says why; it does not keep asking the server and it does not ring. → Task 3: `a file that cannot be opened means not paired, and the file goes`; Task 5: `a refused push address forgets the pairing`; Task 6: `a removed phone that answers is sent back to pairing`.
5. **Android or Samsung has put the app to sleep, or a permission is missing.** The home screen must never look ready when the phone will not ring: it names what is missing and opens the right setting. → Task 9: `a phone that will not ring never looks ready`, `each missing piece is named in order`; Task 11, steps 3 and 12.

## The contract, as far as the app uses it

All bodies are JSON; errors are `{ "error": "…" }`. `{api}` is the server address the phone was paired with. Authenticated requests carry `Authorization: Bearer <deviceId>.<secret>` and `X-App-Version: <versionName>`.

| # | Request | Answers the app must tell apart |
|---|---|---|
| C1 | `POST {api}/api/pair` `{ code, name, appVersion }`, no auth | 200 `{ deviceId, secret, who, label }` · 400 and 429: wrong key · 410: expired |
| C3 | `GET {api}/api/devices/me` | 200 · 401: this phone is no longer paired |
| C3 | `PUT {api}/api/devices/me/push-token` `{ token }` – sent **at every start** and whenever the address changes | 204 · 401 |
| C5 | `POST {api}/api/calls/<id>/answer` `{}` | 200 `{ callId, livekit: { url, token } }` · 403, 404, 409, 410: the call is over or not this phone's · 401 |
| C5 | `POST {api}/api/calls/<id>/decline` `{}` | 200 · anything else means the call is over anyway · 401 |
| C6 | `POST {api}/api/calls/home` `{}` | 200 `{ callId, status: "ringing", livekit: { url, token }, expiresAt }` · 409 busy · 503 kiosk offline · 429 · 401 |
| C6 | `GET {api}/api/calls/<id>`, no auth – **asked once a second while it rings home** | 200 `{ status }`: `ringing`, `connected`, `declined`, `missed`, `unreachable`, `cancelled`, `ended` · 404: forgotten, treat as over |
| C7 | `POST {api}/api/calls/<id>/cancel` and `/end` `{}`, no auth | 200 · 404 |
| C9 | Push data, every value a string | `{ type: "call", v: "1", callId, from: "home", who, expiresAt }` · `{ type: "call_end", v: "1", callId, reason, at }` with `reason` one of `answered_elsewhere`, `cancelled`, `missed`, `declined`, `ended` |
| C10 | QR text | `menkerudhome://pair?api=<urlencoded address>&code=<20 characters>` |

The server forgives how a key is typed (dashes, spaces, small letters, `O` for `0`, `I`/`L` for `1`), so the app passes a typed key on as it is.

## Texts

Fixed by the design (spec §7): «Det ringer hjemmefra» · «Svar» · «Ikke nå» · «Tapt anrop hjemmefra kl. 14:32» · «Ring hjem» · «Ringer hjem…» · «Ingen svarte hjemme» · «Opptatt hjemme» · «Skjermen hjemme er ikke tilkoblet» · «Legg på».

The planner's, used by name in the tasks:

| Name | Text |
|---|---|
| `pair_title` / `pair_hint` | «Koble telefonen til hjemme» / «Skriv inn nøkkelen fra siden hjemme, eller skann QR-koden.» |
| `pair_scan` / `pair_go` | «Skann QR-kode» / «Koble til» |
| `pair_wrong` / `pair_expired` | «Feil nøkkel. Sjekk den, eller lag en ny.» / «Nøkkelen er utløpt. Lag en ny.» |
| `pair_foreign` / `pair_not_key` | «Denne koden hører ikke til Menkerud Home.» / «Dette ser ikke ut som en nøkkel.» |
| `home_as` | «Denne telefonen er %s» (the server's `label`: Mor / Far) |
| `home_ready` / `home_not_ready` | «Klar – telefonen ringer når de ringer hjemmefra» / «Telefonen er ikke klar til å ringe ennå» |
| `home_last` | «Siste anrop: %s» |
| `unpaired` | «Telefonen er koblet fra. Koble til på nytt med en ny nøkkel.» |
| `call_over` / `call_ended` | «Samtalen er over» / «Samtalen er avsluttet» |
| `no_server` / `could_not_join` | «Får ikke kontakt med serveren» / «Klarte ikke å koble til samtalen» |
| `try_later` | «For mange forsøk – prøv igjen om litt» |
| `need_cam_mic` | «Appen trenger kamera og mikrofon for å ringe» |
| `cancel` / `connecting` / `scan_failed` | «Avbryt» / «Kobler til…» / «Fikk ikke skannet – skriv inn nøkkelen» |
| `mic_off` / `mic_on` / `cam_off` / `cam_on` / `cam_flip` | «Lyd av» / «Lyd på» / «Kamera av» / «Kamera på» / «Bytt kamera» |
| `ongoing_title` | «Samtale med hjemme» |
| channels | «Anrop hjemmefra» · «Tapte anrop» · «Pågående samtale» |
| setup rows | «Varsler» · «Vis anrop over låseskjermen» · «Kamera og mikrofon» · «Ikke la batterisparing stoppe appen» · «Ikke sett appen på pause når den ikke brukes» · «Push er ikke satt opp i denne versjonen» |

## File Structure

```
parent-app/
  settings.gradle.kts  build.gradle.kts  gradle.properties  gradle/libs.versions.toml
  gradlew  gradlew.bat  gradle/wrapper/*            copied from DrySpool (Gradle 9.4.1)
  .gitignore  keystore.properties.template  README.md
  app/build.gradle.kts  app/proguard-rules.pro
  app/google-services.json                           from the operator; never committed
  app/src/main/AndroidManifest.xml  app/src/main/res/…
  app/src/debug/…                                    manifest overlay: plain http for the dev copy
  app/src/main/kotlin/com/noobventure/menkerud/
    App.kt  AppContainer.kt                          the Application; the one place objects are wired
    pairing/PairLink.kt                              read a QR text or a typed key; which servers are allowed
    store/Credentials.kt  SecretBox.kt  CredentialStore.kt  AppPrefs.kt
    api/CallApi.kt  HttpCallApi.kt                   the contract above, as Kotlin
    push/PushMessage.kt  PushAddressSync.kt  FirebasePushAddress.kt  MenkerudMessagingService.kt
    call/CallState.kt  CallController.kt             the life of a call
    call/CallSession.kt  RoomSignals.kt  LiveKitCallSession.kt   LiveKit behind a three-method interface
    call/RingNotifier.kt  AndroidRingNotifier.kt  CallActionReceiver.kt  CallForegroundService.kt
    setup/PhoneSetup.kt  AndroidPhoneSetup.kt        is the phone ready to ring; what is missing
    ui/MainActivity.kt  CallActivity.kt  Texts.kt  screens/*.kt  theme/*.kt
  app/src/test/kotlin/com/noobventure/menkerud/…     one test file per decision-making file, plus Fakes.kt
  app/src/androidTest/kotlin/…/KeystoreSecretBoxTest.kt
```

**Commands** (from `parent-app/`; `<tasks>` as each step says):

- Git Bash: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew <tasks> --console=plain`
- PowerShell: `$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat <tasks> --console=plain`
- adb is not on PATH: `C:\Users\Kjetil\AppData\Local\Android\Sdk\platform-tools\adb.exe` (below: `adb`).

«Run the tests» in a task means `:app:testDebugUnitTest`, and «Expected: PASS» means `BUILD SUCCESSFUL` with every test of that task green. The test names with spaces in this plan are for those JVM tests; the two on-device test classes (`androidTest`) use camelCase names, because a dex file for minSdk 26 cannot hold names with spaces.

---

### Task 0: What only the operator can supply (no code)

Tasks 1–10 build and test without any of this. Task 11 needs all of it.

- [ ] **Firebase project.** In the Firebase console: *Add project* «Menkerud Home» (Analytics off) → *Add app → Android*, package name `com.noobventure.menkerud` → register → *Add app → Android* once more, package name `com.noobventure.menkerud.debug` → download `google-services.json` (it now holds both apps) → save it as `parent-app/app/google-services.json`. It is git-ignored.
- [ ] **The server's key for sending pushes.** Firebase console → *Project settings → Service accounts → Generate new private key* → save the file as `.dev/callapi-data/fcm-service-account.json` in the repo folder (git-ignored), and make sure `.dev/callapi.env` has `PUSH_TRANSPORT=` with nothing after the `=`. The dev copy then sends real pushes. The same file goes to Unraid in part 3; it is a secret.
- [ ] **A phone for Task 11:** Developer options with USB debugging on, a USB cable, and – on a Samsung – Auto Blocker switched off while the app is installed.

### Task 1: A project that builds

**Files:**
- Create: `parent-app/settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `.gitignore`, `keystore.properties.template`, `README.md`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`, `app/src/main/res/xml/data_extraction_rules.xml`, `app/src/main/res/xml/backup_rules.xml`, `app/src/debug/AndroidManifest.xml`, `app/src/debug/res/xml/network_security_config.xml`, `app/src/main/kotlin/com/noobventure/menkerud/App.kt`, `…/ui/MainActivity.kt`, `…/ui/theme/Theme.kt`
- Copy from `D:\Nettsider\locals\DrySpool`: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`
- Create, never commit: `parent-app/local.properties` with `sdk.dir=C:/Users/Kjetil/AppData/Local/Android/Sdk`
- Modify: `.gitattributes` in the repo root – add `*.bat text eol=crlf` and `*.jar binary`
- Test: `app/src/test/kotlin/com/noobventure/menkerud/BuildConfigTest.kt`

**Interfaces:**
- Produces: a module `:app` that every later task adds to; `BuildConfig.DEFAULT_API: String` = `"https://call.noobventure.com"`, `BuildConfig.ALLOWED_API_HOSTS: String` = `"call.noobventure.com"`, `BuildConfig.ALLOW_ANY_API: Boolean` (true in debug only), `BuildConfig.PUSH_CONFIGURED: Boolean`; `class App : Application`; `MenkerudTheme { }`.

Values the build files must carry:

- `settings.gradle.kts`: `rootProject.name = "MenkerudHome"`, `include(":app")`, `repositoriesMode = FAIL_ON_PROJECT_REPOS`, repositories `google()`, `mavenCentral()` and `maven("https://jitpack.io")` **limited to the group `com.github.davidliu`** (LiveKit's audio switch lives only there).
- Plugins (root, `apply false`, through the catalog): `com.android.application` 9.2.1, `org.jetbrains.kotlin.plugin.compose` 2.2.10, `org.jetbrains.kotlin.plugin.serialization` 2.2.10. **No** `org.jetbrains.kotlin.android` and no `kotlinOptions`: `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }`.
- `gradle.properties`: `org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8`, `org.gradle.parallel=true`, `org.gradle.caching=true`, `android.useAndroidX=true`, `android.nonTransitiveRClass=true`, `kotlin.code.style=official`.
- Catalog versions: compose-bom 2024.12.01 (ui, ui-graphics, material3, ui-tooling for debug), activity-compose 1.9.3, core-ktx 1.15.0, lifecycle 2.8.7 (runtime-compose, viewmodel-compose, lifecycle-service, lifecycle-process), datastore-preferences 1.1.1, kotlinx-serialization-json 1.7.3, kotlinx-coroutines-android 1.9.0 and kotlinx-coroutines-play-services 1.9.0, okhttp 4.12.0, livekit-android 2.30.0, firebase-bom 35.2.0 with firebase-messaging, play-services-code-scanner 16.1.0; test: junit 4.13.2, kotlinx-coroutines-test 1.9.0, okhttp mockwebserver 4.12.0; androidTest: androidx.test.ext:junit 1.2.1, androidx.test:runner 1.6.2.
- `app/build.gradle.kts`: `namespace` and `applicationId` `com.noobventure.menkerud`; `compileSdk 36`, `minSdk 26`, `targetSdk 36`; `versionCode 1`, `versionName "0.1.0"` (rule, as in DrySpool: +1 on every change that reaches a phone; MINOR for features, PATCH for fixes); Java 17 source and target; `buildFeatures { compose = true; buildConfig = true }`.
  - `debug`: `applicationIdSuffix ".debug"`, `versionNameSuffix "-debug"`, app name «Menkerud Home (test)», `ALLOW_ANY_API = true`, `ndk.abiFilters` `arm64-v8a` and `x86_64`.
  - `release`: `isMinifyEnabled = true`, `isShrinkResources = true`, `proguard-android-optimize.txt` + `proguard-rules.pro`, `ndk.abiFilters` `arm64-v8a` only, `ALLOW_ANY_API = false`. Signing as in DrySpool: `keystore.properties` in `parent-app/` (keys `storeFile`, `storePassword`, `keyAlias`, `keyPassword`), else the environment variables `MENKERUD_STORE_FILE`, `MENKERUD_STORE_PASSWORD`, `MENKERUD_KEY_ALIAS`, `MENKERUD_KEY_PASSWORD`, else an unsigned release build.
  - **Firebase values without the plugin:** if `app/google-services.json` exists, find the entry in `client[]` whose `client_info.android_client_info.package_name` equals the build type's application id and write four string resources for that build type: `google_app_id` ← `client_info.mobilesdk_app_id`, `google_api_key` ← `api_key[0].current_key`, `project_id` ← `project_info.project_id`, `gcm_defaultSenderId` ← `project_info.project_number`; `PUSH_CONFIGURED = true`. No file, or no entry for that package: none of the four, `PUSH_CONFIGURED = false`, and the build still succeeds.
- `.gitignore` (in `parent-app/`): `*.apk`, `*.aab`, `.gradle/`, `build/`, `.kotlin/`, `local.properties`, `.idea/`, `*.iml`, `captures/`, `.cxx/`, `*.jks`, `*.keystore`, `keystore.properties`, `google-services.json`, `*.log`.
- Manifest: permissions `INTERNET`, `POST_NOTIFICATIONS`, `CAMERA`, `RECORD_AUDIO`, `BLUETOOTH_CONNECT`, `VIBRATE`, `USE_FULL_SCREEN_INTENT`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; camera and microphone features `required="false"`. Remove what LiveKit merges in for screen sharing, with `tools:node="remove"`: the permission `android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION` and the service `io.livekit.android.room.track.screencapture.ScreenCaptureService`. `<application android:name=".App" android:allowBackup="false" android:fullBackupContent="@xml/backup_rules" android:dataExtractionRules="@xml/data_extraction_rules">`, both rule files excluding everything, for cloud backup and for device transfer. `MainActivity`: exported, launcher, portrait. The debug overlay allows plain http (network security config); the release build never does.
- Theme: Material 3, dark only, no action bar, background `#07162F`; accents from the kiosk: pink `#FF5C96` / `#D91669`, blue `#19B9FF` / `#0872D1`, green `#25E08F` / `#05885D`. `MainActivity` calls `enableEdgeToEdge()` and, until Task 9, shows the app name.

- [ ] **Step 1:** Copy the four wrapper files; write `settings.gradle.kts`, the root `build.gradle.kts`, `gradle.properties`, the catalog, `.gitignore`, `local.properties`, and an `app` module with an empty `<application/>` and no `buildConfigField` yet.
- [ ] **Step 2: Write the failing test** `BuildConfigTest`:

```kotlin
@Test fun `the app knows its server and which server it may be paired with`() {
    assertEquals("https://call.noobventure.com", BuildConfig.DEFAULT_API)
    assertEquals("call.noobventure.com", BuildConfig.ALLOWED_API_HOSTS)
    assertTrue(BuildConfig.APPLICATION_ID.startsWith("com.noobventure.menkerud"))
}
```

- [ ] **Step 3: Run the tests.** Expected: FAIL – `Unresolved reference 'DEFAULT_API'`.
- [ ] **Step 4:** Fill in `app/build.gradle.kts`, the manifest, the resources, `App`, `MainActivity` and `Theme` as listed above.
- [ ] **Step 5: Build everything.** `:app:assembleDebug :app:testDebugUnitTest :app:assembleRelease` → `BUILD SUCCESSFUL`; `app/build/outputs/apk/debug/app-debug.apk` and `app/build/outputs/apk/release/app-release-unsigned.apk` exist, the release one under 20 MB.
- [ ] **Step 6: Check the two things no test sees.** (a) In `app/build/intermediates/merged_manifests/debug/` the merged manifest names neither `ScreenCaptureService` nor `FOREGROUND_SERVICE_MEDIA_PROJECTION`. (b) Put a made-up `google-services.json` (two clients with the two package names, invented values) into `app/`, run `:app:assembleDebug`, and find `google_app_id` with the **debug** client's value under `app/build/generated/res/resValues/debug/`; then delete the made-up file.
- [ ] **Step 7:** `README.md`: the two build commands, where the APKs land, that `local.properties` and `google-services.json` are needed and not committed.
- [ ] **Step 8: Commit.** `feat(parent-app): an Android project that builds (toolchain as DrySpool, LiveKit, FCM, OkHttp)`

### Task 2: Reading a key – typed or scanned

**Files:**
- Create: `…/pairing/PairLink.kt`
- Test: `app/src/test/kotlin/com/noobventure/menkerud/pairing/PairLinkTest.kt`

**Interfaces:**
- Produces:

```kotlin
data class PairLink(val apiBase: String, val code: String)
class PairPolicy(val defaultApi: String, val allowedHosts: Set<String>, val allowAnyServer: Boolean)
sealed interface PairInput {
    data class Ok(val link: PairLink) : PairInput
    data class ForeignServer(val host: String) : PairInput   // a QR code that points at a server this build does not trust
    data object NotAKey : PairInput
}
fun readPairInput(text: String, policy: PairPolicy): PairInput
```

Rules. The text is trimmed. If it starts with `menkerudhome://pair` (any letter case) it is the QR text (C10): `code` and `api` come from its query, URL-decoded; a missing `api` means `policy.defaultApi`. Otherwise the whole text is a typed key and the server is `policy.defaultApi`. A key is valid when, with spaces and dashes taken out, it is exactly 20 characters of `0-9 A-Z a-z`; it is passed on **as typed** (trimmed), because the server does the forgiving. A server address is accepted when `allowAnyServer`, or when it is `https` and its host is in `allowedHosts`; trailing slashes are dropped. An `api` that is not an http or https address at all, and everything else, is `NotAKey`.

- [ ] **Step 1: Write the failing tests**

```kotlin
private val release = PairPolicy("https://call.noobventure.com", setOf("call.noobventure.com"), allowAnyServer = false)
private val debug = PairPolicy("https://call.noobventure.com", setOf("call.noobventure.com"), allowAnyServer = true)
private val key = "0123456789ABCDEFGHJK"
private fun qr(api: String) = "menkerudhome://pair?api=" + java.net.URLEncoder.encode(api, "UTF-8") + "&code=" + key

@Test fun `the QR text gives the server and the key`() =
    assertEquals(PairInput.Ok(PairLink("https://call.noobventure.com", key)), readPairInput(qr("https://call.noobventure.com/"), release))
@Test fun `a key typed with dashes, spaces and small letters is passed on as typed`() =
    assertEquals(PairInput.Ok(PairLink("https://call.noobventure.com", "o123-4567 89ab-cdef ghjk")), readPairInput("  o123-4567 89ab-cdef ghjk \n", release))
@Test fun `a QR code for another server is refused in a release build`() =
    assertEquals(PairInput.ForeignServer("evil.example"), readPairInput(qr("https://evil.example"), release))
@Test fun `plain http is refused in a release build, even for the right host`() =
    assertEquals(PairInput.ForeignServer("call.noobventure.com"), readPairInput(qr("http://call.noobventure.com"), release))
@Test fun `a debug build follows the QR code to the dev copy`() =
    assertEquals(PairInput.Ok(PairLink("http://127.0.0.1:3100", key)), readPairInput(qr("http://127.0.0.1:3100"), debug))
@Test fun `what is not a key is refused`() {
    for (text in listOf("", "hei", key.dropLast(1), key + "X", key.replace('0', '!'), "menkerudhome://pair?api=https%3A%2F%2Fcall.noobventure.com", "MENKERUDHOME://pair?code=kort", qr("ikke-en-adresse")))
        assertEquals(text, PairInput.NotAKey, readPairInput(text, release))
}
@Test fun `the scheme may be in any letter case and the server may be left out`() =
    assertEquals(PairInput.Ok(PairLink("https://call.noobventure.com", key)), readPairInput("MenkerudHome://pair?code=$key", release))
```

- [ ] **Step 2: Run the tests.** Expected: FAIL – `Unresolved reference 'readPairInput'`.
- [ ] **Step 3: Implement** `readPairInput` in `PairLink.kt`. Parse the query by hand or with `java.net.URI`; do not use `android.net.Uri` (it does not exist in a JVM test).
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit.** `feat(parent-app): read a pairing key, typed or from the QR code`

### Task 3: Keeping the phone's secret

**Files:**
- Create: `…/store/Credentials.kt`, `…/store/SecretBox.kt`, `…/store/CredentialStore.kt`
- Test: `app/src/test/kotlin/…/store/CredentialStoreTest.kt`, `app/src/androidTest/kotlin/…/store/KeystoreSecretBoxTest.kt`

**Interfaces:**
- Produces:

```kotlin
@Serializable data class Credentials(val apiBase: String, val deviceId: String, val secret: String, val who: String, val label: String)

interface SecretBox { fun seal(plain: ByteArray): ByteArray; fun open(sealed: ByteArray): ByteArray? }   // open: null when it cannot be opened
class KeystoreSecretBox(alias: String = "menkerud.device") : SecretBox

class CredentialStore(private val file: File, private val box: SecretBox) {
    val current: StateFlow<Credentials?>     // null = this phone is not paired
    fun save(credentials: Credentials)
    fun clear()
}
```

`KeystoreSecretBox`: an AES-256 key in the `AndroidKeyStore` under `alias`, made on first use; `AES/GCM/NoPadding`; the sealed bytes are the 12-byte IV followed by the ciphertext; `open` returns null on **any** failure (wrong length, changed bytes, a key that is gone). `CredentialStore` reads the file once when it is created: no file → null; a file that cannot be opened or is not the expected JSON → null **and the file is deleted**. In the app the file is `File(context.noBackupFilesDir, "device.bin")`.

- [ ] **Step 1: Write the failing tests** (`CredentialStoreTest`, on the JVM, with `FakeBox`: every byte XOR `0x5A`, and `open` returns null when told to)

```kotlin
private val far = Credentials("https://call.noobventure.com", "d_1", "hemmelig-0123456789", "far", "Far")

@Test fun `what is saved is there the next time the app starts`() { CredentialStore(file, box).save(far); assertEquals(far, CredentialStore(file, box).current.value) }
@Test fun `the file does not hold the secret in the clear`() { CredentialStore(file, box).save(far); assertFalse(file.readText(Charsets.ISO_8859_1).contains("hemmelig")) }
@Test fun `a phone that was never paired has nothing`() = assertNull(CredentialStore(file, box).current.value)
@Test fun `a file that cannot be opened means not paired, and the file goes`() {
    CredentialStore(file, box).save(far); box.refuse = true
    assertNull(CredentialStore(file, box).current.value); assertFalse(file.exists())
}
@Test fun `a damaged file means not paired, and the file goes`() { file.writeBytes(byteArrayOf(1, 2, 3)); assertNull(CredentialStore(file, box).current.value); assertFalse(file.exists()) }
@Test fun `clear forgets the pairing`() { val s = CredentialStore(file, box); s.save(far); s.clear(); assertNull(s.current.value); assertFalse(file.exists()) }
```

- [ ] **Step 2: Run the tests.** Expected: FAIL – `Unresolved reference 'CredentialStore'`.
- [ ] **Step 3: Implement** `Credentials`, `SecretBox`, `KeystoreSecretBox` and `CredentialStore`.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Write the on-device test** `KeystoreSecretBoxTest` (it runs in Task 11, on the phone): what is sealed opens again; one changed byte → null; two seals of the same text differ.
- [ ] **Step 6: Commit.** `feat(parent-app): keep the phone's secret sealed with a Keystore key`

### Task 4: Talking to the API

**Files:**
- Create: `…/api/CallApi.kt`, `…/api/HttpCallApi.kt`
- Test: `app/src/test/kotlin/…/api/HttpCallApiTest.kt`

**Interfaces:**
- Consumes: `PairLink` (Task 2), `Credentials` (Task 3).
- Produces:

```kotlin
data class LiveKitTicket(val url: String, val token: String)
enum class Failure { UNREACHABLE, SERVER }            // no answer at all · an answer the app cannot use
enum class Sent { OK, UNPAIRED, FAILED }

sealed interface PairResult {
    data class Paired(val credentials: Credentials) : PairResult
    data object WrongKey : PairResult
    data object Expired : PairResult
    data class Failed(val why: Failure) : PairResult
}
sealed interface AnswerResult {
    data class Joined(val ticket: LiveKitTicket) : AnswerResult
    data object Over : AnswerResult
    data object Unpaired : AnswerResult
    data class Failed(val why: Failure) : AnswerResult
}
sealed interface RingHomeResult {
    data class Ringing(val callId: String, val ticket: LiveKitTicket) : RingHomeResult
    data object Busy : RingHomeResult
    data object KioskOffline : RingHomeResult
    data object TooMany : RingHomeResult
    data object Unpaired : RingHomeResult
    data class Failed(val why: Failure) : RingHomeResult
}
sealed interface StatusResult {
    data class Known(val status: String) : StatusResult
    data object Gone : StatusResult
    data class Failed(val why: Failure) : StatusResult
}

interface CallApi {
    suspend fun pair(link: PairLink, phoneName: String): PairResult
    suspend fun whoAmI(): Sent
    suspend fun sendPushAddress(address: String): Sent
    suspend fun answer(callId: String): AnswerResult
    suspend fun decline(callId: String): Sent
    suspend fun ringHome(): RingHomeResult
    suspend fun status(callId: String): StatusResult
    suspend fun cancel(callId: String): Sent
    suspend fun end(callId: String): Sent
}

class HttpCallApi(http: OkHttpClient, credentials: () -> Credentials?, appVersion: String, io: CoroutineDispatcher = Dispatchers.IO) : CallApi
```

How each answer is read (anything not listed, and a body that cannot be read, is `SERVER`; no answer at all is `UNREACHABLE`):

| Method | Request | Result |
|---|---|---|
| `pair` | `POST {link.apiBase}/api/pair` `{"code","name","appVersion"}`, **no** `Authorization` | 200 → `Paired(Credentials(link.apiBase, deviceId, secret, who, label))` · 400, 429 → `WrongKey` · 410 → `Expired` |
| `whoAmI` | `GET /api/devices/me` | 200 → `OK` · 401 → `UNPAIRED` · else `FAILED` |
| `sendPushAddress` | `PUT /api/devices/me/push-token` `{"token": address}` | 204 → `OK` · 401 → `UNPAIRED` · else `FAILED` |
| `answer` | `POST /api/calls/<id>/answer` `{}` | 200 → `Joined` · 403, 404, 409, 410 → `Over` · 401 → `Unpaired` |
| `decline` | `POST /api/calls/<id>/decline` `{}` | 200, 403, 404, 409, 410 → `OK` · 401 → `UNPAIRED` · else `FAILED` |
| `ringHome` | `POST /api/calls/home` `{}` | 200 → `Ringing` · 409 → `Busy` · 503 → `KioskOffline` · 429 → `TooMany` · 401 → `Unpaired` |
| `status` | `GET /api/calls/<id>`, no `Authorization` | 200 → `Known(status)` · 404 → `Gone` |
| `cancel`, `end` | `POST /api/calls/<id>/cancel` · `/end` `{}`, no `Authorization` | 200, 404 → `OK` · else `FAILED` |

Every other request carries `Authorization: Bearer <deviceId>.<secret>` and `X-App-Version: <appVersion>` and goes to the stored `apiBase`. With nothing stored those methods answer `UNPAIRED` / `Unpaired` **without a request**. The call id is URL-encoded into the path. JSON is read with `ignoreUnknownKeys = true`. The `OkHttpClient` the app builds has a 5 s connect, 10 s read and 15 s whole-call time limit.

- [ ] **Step 1: Write the failing tests** against a `MockWebServer`, with `credentials = { Credentials(server.url("").toString().trimEnd('/'), "d_1", "s3", "far", "Far") }` and `appVersion = "1.2.3"`:

```kotlin
@Test fun `pairing sends the key and gives back who the phone is`() = runTest {
    server.enqueue(MockResponse().setBody("""{"deviceId":"d_9","secret":"abc","who":"mor","label":"Mor"}"""))
    assertEquals(PairResult.Paired(Credentials(base, "d_9", "abc", "mor", "Mor")), api.pair(PairLink(base, "0123-4567"), "Galaxy S26"))
    val sent = server.takeRequest()
    assertEquals("POST /api/pair", sent.method + " " + sent.path)
    assertEquals("""{"code":"0123-4567","name":"Galaxy S26","appVersion":"1.2.3"}""", sent.body.readUtf8())
    assertNull(sent.getHeader("Authorization"))
}
@Test fun `answering carries the phone's secret and brings back the ticket`() = runTest {
    server.enqueue(MockResponse().setBody("""{"callId":"c_1","livekit":{"url":"wss://rtc.test","token":"tok"},"somethingNew":1}"""))
    assertEquals(AnswerResult.Joined(LiveKitTicket("wss://rtc.test", "tok")), api.answer("c_1"))
    val sent = server.takeRequest()
    assertEquals("POST /api/calls/c_1/answer", sent.method + " " + sent.path)
    assertEquals("Bearer d_1.s3", sent.getHeader("Authorization")); assertEquals("1.2.3", sent.getHeader("X-App-Version"))
}
```

and one test per row of the table that walks its statuses, for example `pair`: 400 → `WrongKey`, 429 → `WrongKey`, 410 → `Expired`, 500 → `Failed(SERVER)`, 200 with the body `<html>` → `Failed(SERVER)`; `ringHome`: 409 → `Busy`, 503 → `KioskOffline`, 429 → `TooMany`, 401 → `Unpaired`; `status`: `{"status":"unreachable"}` → `Known("unreachable")`, 404 → `Gone`. Plus: `a server that is not there is UNREACHABLE` (shut the server down first), `nothing is sent when the phone is not paired` (`credentials = { null }`, `server.requestCount == 0`), and `the push address goes out as token` (`PUT /api/devices/me/push-token`, body `{"token":"fid-123"}`, 204 → `OK`).

- [ ] **Step 2: Run the tests.** Expected: FAIL – `Unresolved reference 'HttpCallApi'`.
- [ ] **Step 3: Implement** `CallApi.kt` and `HttpCallApi.kt` (OkHttp calls on `io`, kotlinx-serialization for the bodies).
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit.** `feat(parent-app): the call API as Kotlin – pair, answer, decline, ring home, status`

### Task 5: Push – reading a message, and telling the server where to send them

**Files:**
- Create: `…/push/PushMessage.kt`, `…/push/PushAddressSync.kt`, `…/push/FirebasePushAddress.kt`, `…/AppContainer.kt`
- Modify: `…/App.kt` (build the container; run the sync at every start), `app/build.gradle.kts` (add `com.google.firebase:firebase-installations` from the BoM)
- Test: `app/src/test/kotlin/…/push/PushMessageTest.kt`, `…/push/PushAddressSyncTest.kt`, `app/src/test/kotlin/…/Fakes.kt`

**Interfaces:**
- Consumes: `CallApi`, `Sent` (Task 4); `CredentialStore` (Task 3).
- Produces:

```kotlin
sealed interface PushMessage {
    data class Call(val callId: String, val who: String, val expiresAt: Long) : PushMessage
    data class CallEnd(val callId: String, val reason: String, val at: Long?) : PushMessage   // at: null when the push did not say
    data object Unknown : PushMessage
}
fun readPush(data: Map<String, String>): PushMessage

class PushAddressSync(private val api: CallApi, private val store: CredentialStore, private val address: suspend () -> String?) {
    suspend fun run(known: String? = null): Sent?      // null = nothing to do
}

class FirebasePushAddress(context: Context) { suspend fun get(): String? }

class AppContainer(context: Context) {                 // grows in Tasks 7 and 8
    val scope: CoroutineScope                          // SupervisorJob() + Dispatchers.Main.immediate
    val store: CredentialStore
    val api: CallApi
    val pushSync: PushAddressSync
}

// Fakes.kt – shared by the tests of Tasks 5 and 6
class FakeCallApi : CallApi {
    val calls = mutableListOf<String>()                // "sendPushAddress fid-1", "answer c_1", "status c_1", "end c_1" …
    var sent: Sent = Sent.OK                           // the answer to whoAmI, sendPushAddress, decline, cancel and end
    var pairResult: PairResult = PairResult.WrongKey
    var answerResult: AnswerResult = AnswerResult.Over
    var ringHomeResult: RingHomeResult = RingHomeResult.Busy
    val statuses = ArrayDeque<StatusResult>()          // answers to status(), in order; the last one repeats
}
```

Rules. `readPush`: `v` must be `"1"` and `type` known, else `Unknown`. `call` needs a `callId` and a numeric `expiresAt`. `call_end` needs a `callId`; `reason` is passed on as it is (the controller decides what an unknown reason means); `at` is null when missing or not a number. Extra fields are ignored. `PushAddressSync.run`: not paired → null, and nothing is asked of Firebase. Otherwise the address is `known`, or else `address()`; no address → null. Then `api.sendPushAddress(address)`; `UNPAIRED` → `store.clear()`. It runs at every start of the app, right after a pairing (Task 9), and whenever Firebase hands over an address (Task 7) – changed or not, because a phone whose address the push service reported dead only counts again once it has sent one (C3).

`AppContainer`, as far as it goes in this task: `store = CredentialStore(File(context.noBackupFilesDir, "device.bin"), KeystoreSecretBox())` · `api = HttpCallApi(an OkHttpClient with the three time limits of Task 4, { store.current.value }, BuildConfig.VERSION_NAME)` · `pushSync = PushAddressSync(api, store) { FirebasePushAddress(context).get() }`.

`FirebasePushAddress.get()`: null when `BuildConfig.PUSH_CONFIGURED` is false or `FirebaseApp.getApps(context)` is empty; otherwise `FirebaseMessaging.getInstance().register().await()` and then `FirebaseInstallations.getInstance().id.await()`; any exception → null. The manifest gets `<meta-data android:name="firebase_messaging_installation_id_enabled" android:value="true"/>`. **Named fall-back:** if Task 11 shows that pushes do not reach a phone registered this way, this one class changes to `FirebaseMessaging.getInstance().token.await()` (and the service in Task 7 to `onNewToken`); nothing else moves.

- [ ] **Step 1: Write the failing tests**

```kotlin
// PushMessageTest
@Test fun `a call`() = assertEquals(PushMessage.Call("c_1", "far", 1794700000000),
    readPush(mapOf("type" to "call", "v" to "1", "callId" to "c_1", "from" to "home", "who" to "far", "expiresAt" to "1794700000000")))
@Test fun `the end of a call`() = assertEquals(PushMessage.CallEnd("c_1", "missed", 1794700060000),
    readPush(mapOf("type" to "call_end", "v" to "1", "callId" to "c_1", "reason" to "missed", "at" to "1794700060000")))
@Test fun `a kind of message this version does not know is left alone`() {
    assertEquals(PushMessage.Unknown, readPush(mapOf("type" to "message", "v" to "1", "text" to "hei")))
    assertEquals(PushMessage.Unknown, readPush(mapOf("type" to "call", "v" to "2", "callId" to "c_1", "expiresAt" to "1")))
    assertEquals(PushMessage.Unknown, readPush(mapOf("type" to "call", "v" to "1", "expiresAt" to "1")))
    assertEquals(PushMessage.Unknown, readPush(mapOf("type" to "call", "v" to "1", "callId" to "c_1", "expiresAt" to "snart")))
    assertEquals(PushMessage.Unknown, readPush(emptyMap()))
}
@Test fun `an end without a time is still an end`() = assertEquals(PushMessage.CallEnd("c_1", "cancelled", null),
    readPush(mapOf("type" to "call_end", "v" to "1", "callId" to "c_1", "reason" to "cancelled", "extra" to "x")))

// PushAddressSyncTest – store on a temp file with FakeBox, already paired unless the test says otherwise
@Test fun `the address is sent at every start, changed or not`() = runTest { sync.run(); sync.run(); assertEquals(listOf("sendPushAddress fid-1", "sendPushAddress fid-1"), api.calls) }
@Test fun `nothing is asked or sent while the phone is not paired`() = runTest { store.clear(); assertNull(sync.run()); assertEquals(0, asked); assertTrue(api.calls.isEmpty()) }
@Test fun `a build without push sends nothing`() = runTest { address = null; assertNull(sync.run()); assertTrue(api.calls.isEmpty()) }
@Test fun `an address Firebase hands over is sent as it is`() = runTest { sync.run("fid-9"); assertEquals(listOf("sendPushAddress fid-9"), api.calls); assertEquals(0, asked) }
@Test fun `a refused push address forgets the pairing`() = runTest { api.sent = Sent.UNPAIRED; assertEquals(Sent.UNPAIRED, sync.run()); assertNull(store.current.value) }
```

- [ ] **Step 2: Run the tests.** Expected: FAIL – `Unresolved reference 'readPush'`.
- [ ] **Step 3: Implement** `PushMessage.kt`, `PushAddressSync.kt`, `FirebasePushAddress.kt`, `Fakes.kt` (`FakeCallApi`, and `FakeBox` moved here from Task 3's test), `AppContainer`, and in `App.onCreate`: build the container and `container.scope.launch { container.pushSync.run() }`.
- [ ] **Step 4: Run the tests** and `:app:assembleDebug`. Expected: PASS, `BUILD SUCCESSFUL` (also without a `google-services.json`).
- [ ] **Step 5: Commit.** `feat(parent-app): read pushes; send the phone's push address at every start`

### Task 6: The life of a call

The heart of the app, and plain Kotlin: no Android in it.

**Files:**
- Create: `…/call/CallState.kt`, `…/call/CallSession.kt`, `…/call/RingNotifier.kt`, `…/call/CallController.kt`
- Modify: `app/src/test/kotlin/…/Fakes.kt` (add `FakeSession`, `FakeNotifier`)
- Test: `app/src/test/kotlin/…/call/CallControllerTest.kt`

**Interfaces:**
- Consumes: `CallApi` and its result types, `LiveKitTicket` (Task 4); `PushMessage` (Task 5).
- Produces:

```kotlin
sealed interface CallState {
    data object Idle : CallState
    data class Incoming(val callId: String) : CallState          // ringing on this phone
    data class Answering(val callId: String) : CallState         // «Svar» pressed, getting in
    data class RingingHome(val callId: String?) : CallState      // «Ring hjem» pressed; null until the API has answered
    data class InCall(val callId: String) : CallState
    data class Ended(val why: EndReason) : CallState             // shown for a moment, then Idle
}
enum class EndReason { HUNG_UP, OTHER_SIDE_LEFT, CALL_OVER, NOBODY_HOME, BUSY_HOME, KIOSK_OFFLINE, TOO_MANY, NO_SERVER, COULD_NOT_JOIN, UNPAIRED }
enum class CallKind { FROM_HOME_ANSWERED, FROM_HOME_MISSED, TO_HOME_ANSWERED, TO_HOME_UNANSWERED }
data class LastCall(val at: Long, val kind: CallKind)

enum class SessionEvent { OTHER_JOINED, OTHER_LEFT, CLOSED }     // CLOSED: the room is gone, or the connection is lost for good
interface CallSession {
    val events: Flow<SessionEvent>
    suspend fun connect(ticket: LiveKitTicket)                   // joins with camera and microphone on; throws when it cannot get in
    suspend fun close()                                          // leaves, lets go of camera and microphone; safe to call twice
}
fun interface SessionFactory { fun create(): CallSession }

interface RingNotifier {
    fun ring(callId: String, forMs: Long)                        // show the incoming call and ring
    fun stop(callId: String)                                     // take it away
    fun missed(callId: String, at: Long)                         // leave «Tapt anrop hjemmefra kl. …»
}

fun ringFor(expiresAt: Long, now: Long): Long

class CallController(
    api: CallApi, sessions: SessionFactory, notifier: RingNotifier,
    unpair: () -> Unit,                                          // forget the stored secret
    remember: (LastCall) -> Unit,                                // once per call, when it is over
    now: () -> Long, scope: CoroutineScope
) {
    val state: StateFlow<CallState>
    val session: StateFlow<CallSession?>                         // the live session, for the call screen to draw from
    fun onPush(message: PushMessage)
    fun answer(callId: String)
    fun decline(callId: String)
    fun ringHome()
    fun hangUp()                                                 // «Legg på», and «Avbryt» while it rings home
    fun dismissEnded()
}
```

**Rules** (times in ms: ring at most 60 000, fall-back 45 000, status question every 1 000, ring home at most 65 000, an `Ended` shown 4 000, the other side may be gone 10 000):

1. **A call comes in.** `onPush(Call)` while `Idle` or `Ended` → `Incoming(id)` and `notifier.ring(id, ringFor(expiresAt, now()))`. At the same time ask `api.status(id)`: any answer other than `Known("ringing")` or `Failed` → `notifier.stop(id)`, `Idle` (the push came after the call was over). `Failed` keeps it ringing.
2. `ringFor` = `expiresAt − now` when that is between 5 000 and 60 000, otherwise 45 000.
3. **Nobody answers.** Ring time passed and still `Incoming(id)` → `notifier.stop(id)`, `notifier.missed(id, now())`, `Idle`.
4. **`onPush(CallEnd)`.** If it is the call that rings here: `notifier.stop(id)`, `Idle`. Reason `cancelled` or `missed` → `notifier.missed(id, at ?: now())`, **also when this phone never rang for that call**. Every other reason, known or not, leaves no notice. One call id leaves at most one notice.
5. **`onPush(Call)` in other states.** While `Incoming(other)`: the other call is over (the server has one call at a time) – stop its ringing without a notice and ring the new one. While `Answering`, `RingingHome`, `InCall`: ignored. `Unknown`: ignored, always.
6. **«Svar».** `answer(id)` from `Idle`, `Ended` or `Incoming(any)` → stop all ringing, `Answering(id)`, `api.answer(id)`: `Joined(ticket)` → a new session, `connect(ticket)` → `InCall(id)`; `Over` → `Ended(CALL_OVER)`; `Unpaired` → `unpair()`, `Ended(UNPAIRED)`; `Failed` → `Ended(NO_SERVER)`; `connect` throws → `api.end(id)`, `Ended(COULD_NOT_JOIN)`. In any other state `answer` does nothing.
7. **«Ikke nå».** `decline(id)` from `Idle`, `Ended` or `Incoming(id)` → `notifier.stop(id)`, `api.decline(id)` (its answer does not matter), `Idle`.
8. **«Ring hjem».** `ringHome()` from `Idle` or `Ended` → `RingingHome(null)`, `api.ringHome()`:
   - `Ringing(id, ticket)` → `RingingHome(id)`; a new session connects with the ticket (the parent waits in the room); ask `api.status(id)` every second: `connected` → `InCall(id)` · `declined`, `missed` → `Ended(NOBODY_HOME)` · `unreachable` → `Ended(KIOSK_OFFLINE)` · `cancelled`, `ended`, `Gone` → `Ended(HUNG_UP)` · five `Failed` in a row → `api.cancel(id)`, `Ended(NO_SERVER)` · 65 000 ms without an end → `api.cancel(id)`, `Ended(NOBODY_HOME)`. A session that cannot connect → `api.cancel(id)`, `Ended(COULD_NOT_JOIN)`.
   - `Busy` → `Ended(BUSY_HOME)` · `KioskOffline` → `Ended(KIOSK_OFFLINE)` · `TooMany` → `Ended(TOO_MANY)` · `Unpaired` → `unpair()`, `Ended(UNPAIRED)` · `Failed` → `Ended(NO_SERVER)`.
9. **Hanging up.** `hangUp()` in `InCall(id)` or `Answering(id)` → `api.end(id)`, `Ended(HUNG_UP)`; an answer from the API that only arrives after that is dropped and its room is never entered. In `RingingHome(id)` → `api.cancel(id)`, `Idle`; in `RingingHome(null)` → `Idle` now, and `api.cancel(id)` as soon as the API's answer names the call.
10. **The room speaks**, while `InCall(id)`: `CLOSED` → `api.end(id)`, `Ended(OTHER_SIDE_LEFT)`. `OTHER_LEFT` with no `OTHER_JOINED` within 10 000 ms → the same. While it rings home `CLOSED` is ignored: the outcome comes from the status questions (C6).
11. **Leaving a call, for whatever reason, closes its session exactly once.** `Ended` becomes `Idle` after 4 000 ms or on `dismissEnded()`.
12. **`remember`** is called once per call: an incoming call that was answered → `FROM_HOME_ANSWERED` when it ends; every `notifier.missed` → `FROM_HOME_MISSED` with the same time; a call home that reached `InCall` → `TO_HOME_ANSWERED` when it ends; one that ended `NOBODY_HOME`, `KIOSK_OFFLINE` or `BUSY_HOME` → `TO_HOME_UNANSWERED`.

- [ ] **Step 1: Write the failing tests** in `CallControllerTest` with `runTest` (virtual time), `T0 = 1_794_700_000_000`, `now = { T0 + testScheduler.currentTime }`, and the fakes: `FakeCallApi` (Task 5), `FakeSession` (`events` fed by the test, `failConnect`, `connected: LiveKitTicket?`, `closed: Int`), `FakeNotifier` (`log`: `"ring c_1 60000"`, `"stop c_1"`, `"missed c_1 <at>"`), lists `unpaired` and `remembered`. One test per line; the name is the test's name:

| Test | Given → when | Then |
|---|---|---|
| `a call from home rings` | status `ringing`; push `Call("c_1", expiresAt = T0 + 60 000)` | `Incoming("c_1")`; log `ring c_1 60000` |
| `a push for a call that is already over stops ringing at once` | status `missed`; the same push | `Idle`; log `ring c_1 60000`, `stop c_1`; no `missed` |
| `no network for the check keeps it ringing` | status `Failed(UNREACHABLE)` | `Incoming("c_1")` |
| `a phone clock that is wrong rings for 45 seconds` | `ringFor(T0 − 600 000, T0)`, `ringFor(T0 + 600 000, T0)`, `ringFor(T0 + 4 000, T0)`, `ringFor(T0 + 30 000, T0)` | 45 000, 45 000, 45 000, 30 000 |
| `nobody answers - the ring ends and leaves a missed call` | ringing; 60 001 ms pass | `Idle`; `stop c_1`, `missed c_1 <T0+60000>`; remembered `FROM_HOME_MISSED` |
| `the kids hang up while it rings - a missed call` | ringing; push `CallEnd("c_1", "cancelled", T0 + 5 000)` | `Idle`; `missed c_1 <T0+5000>` |
| `answered on the other phone - it just stops` | ringing; `CallEnd("c_1", "answered_elsewhere")` | `Idle`; `stop c_1`; no `missed` |
| `a missed call the phone never rang for still leaves its notice` | `Idle`; `CallEnd("c_9", "missed", T0 − 3 600 000)` | log is exactly `missed c_9 <T0−3600000>` |
| `the same missed call told twice is remembered once` | that push twice | one `missed`, one `remembered` |
| `Svar gets in` | ringing; `answerResult = Joined(ticket)`; `answer("c_1")` | `InCall("c_1")`; session connected with `ticket`; `stop c_1` |
| `Svar after the call was taken elsewhere says the call is over` | `answerResult = Over` | `Ended(CALL_OVER)`; 4 000 ms later `Idle` |
| `Svar twice starts one call` | `answer` twice | one `answer c_1` in `api.calls`; one session made |
| `Svar from the notification works after a restart` | a new controller, `Idle`; `answer("c_1")` | `InCall("c_1")` |
| `a removed phone that answers is sent back to pairing` | `answerResult = Unpaired` | `unpaired` once; `Ended(UNPAIRED)` |
| `a room that cannot be joined ends the call on the server too` | `Joined`, `failConnect` | `end c_1`; `Ended(COULD_NOT_JOIN)`; session closed once |
| `Ikke nå tells the server and stops` | ringing; `decline("c_1")` | `decline c_1`; `stop c_1`; `Idle` |
| `hanging up ends it for both` | in a call; `hangUp()` | `end c_1`; `Ended(HUNG_UP)`; closed once; remembered `FROM_HOME_ANSWERED` |
| `the room closing ends the call` | in a call; session emits `CLOSED` | `end c_1`; `Ended(OTHER_SIDE_LEFT)`; closed once |
| `the other side gone for ten seconds ends the call` | `OTHER_LEFT`; 9 999 ms → still `InCall`; 2 ms more | `Ended(OTHER_SIDE_LEFT)` |
| `the other side coming back keeps the call` | `OTHER_LEFT`, 5 000 ms, `OTHER_JOINED`, 10 000 ms | `InCall("c_1")` |
| `a call always lets go of its session exactly once` | `hangUp()`, then `CLOSED` arrives | `closed == 1` |
| `ring home - they answer` | `Ringing("c_2", ticket)`; statuses `ringing`, `ringing`, `connected` | `RingingHome("c_2")`, then `InCall("c_2")` after 3 000 ms; connected with `ticket` |
| `ring home - each way it ends without an answer` | `declined` · `missed` · `unreachable` · `cancelled` · `Gone` · `Busy` · `KioskOffline` · `TooMany` · `Failed` · `Unpaired` | `NOBODY_HOME` · `NOBODY_HOME` · `KIOSK_OFFLINE` · `HUNG_UP` · `HUNG_UP` · `BUSY_HOME` · `KIOSK_OFFLINE` · `TOO_MANY` · `NO_SERVER` · `UNPAIRED` (and `unpaired` once) |
| `ring home gives up after 65 seconds` | status `ringing` for ever | at 65 000 ms: `cancel c_2`, `Ended(NOBODY_HOME)` |
| `ring home survives four failed questions but not five` | four `Failed` then `connected` · five `Failed` | `InCall` · `cancel c_2`, `Ended(NO_SERVER)` |
| `Avbryt while it rings home cancels the call` | `RingingHome("c_2")`; `hangUp()` | `cancel c_2`; `Idle`; closed once |
| `Avbryt before the server has answered cancels as soon as it does` | `hangUp()` before `ringHome` returns | `Idle` at once; `cancel c_2` afterwards; no session left open |
| `a call cannot start while another is on` | in a call; `ringHome()`; push `Call("c_3")` | no `ringHome` in `api.calls`; state unchanged; no `ring c_3` |
| `a new call while an old one still rings here replaces it` | `Incoming("c_1")`; push `Call("c_5")` | `stop c_1`, `ring c_5 …`; `Incoming("c_5")`; no `missed` |
| `unknown messages and unknown reasons do no harm` | `Unknown` · `CallEnd("c_1", "something-new")` while ringing | no change · `stop c_1`, `Idle`, no `missed` |

- [ ] **Step 2: Run the tests.** Expected: FAIL – `Unresolved reference 'CallController'`.
- [ ] **Step 3: Implement** the four files. One coroutine per call; every way out of a call goes through one private function that closes the session, stops the ringing and sets the state – that is what makes rule 11 true.
- [ ] **Step 4: Run the tests.** Expected: PASS (30 tests).
- [ ] **Step 5: Commit.** `feat(parent-app): the life of a call – ringing, answering, ringing home, ending`

### Task 7: The call itself – a LiveKit room behind `CallSession`

**Files:**
- Create: `…/call/RoomSignals.kt`, `…/call/LiveKitCallSession.kt`, `…/ui/screens/VideoView.kt`
- Test: `app/src/test/kotlin/…/call/RoomSignalsTest.kt`

**Interfaces:**
- Consumes: `CallSession`, `SessionEvent`, `LiveKitTicket` (Tasks 4 and 6).
- Produces:

```kotlin
internal class RoomSignals {                          // what the room says → what the controller needs to hear
    fun connected(othersInRoom: Int): SessionEvent?   // OTHER_JOINED when somebody was there first, else null
    fun joined(): SessionEvent                        // OTHER_JOINED
    fun left(othersInRoom: Int): SessionEvent?        // OTHER_LEFT when nobody else is left, else null
    fun disconnected(): SessionEvent?                 // CLOSED the first time, null after that
}

class LiveKitCallSession(context: Context) : CallSession {
    val room: Room
    val remoteVideo: StateFlow<VideoTrack?>           // the other side's picture, when there is one
    val localVideo: StateFlow<VideoTrack?>            // one's own
    val microphoneOn: StateFlow<Boolean>
    val cameraOn: StateFlow<Boolean>
    suspend fun setMicrophone(on: Boolean)
    suspend fun setCamera(on: Boolean)
    fun switchCamera()
}

@Composable fun VideoView(room: Room, track: VideoTrack?, mirror: Boolean, modifier: Modifier)
```

How it is built (API of `io.livekit:livekit-android:2.30.0`, read from its sources on 2026-11-15):

- `room = LiveKit.create(context.applicationContext)`. `connect(ticket)`: `room.connect(ticket.url, ticket.token)`, then `room.localParticipant.setCameraEnabled(true)` and `setMicrophoneEnabled(true)`; anything thrown goes to the caller (the controller turns it into `COULD_NOT_JOIN`).
- Events: collect `room.events` (`import io.livekit.android.events.collect`) in a coroutine of the session's own: `RoomEvent.ParticipantConnected` → `signals.joined()`; `RoomEvent.ParticipantDisconnected` → `signals.left(room.remoteParticipants.size)`; `RoomEvent.Disconnected` → `signals.disconnected()`; right after `connect` returns → `signals.connected(room.remoteParticipants.size)`, because LiveKit does not announce those who were in the room first. `RoomEvent.TrackSubscribed` / `TrackUnsubscribed` with a `VideoTrack` set and clear `remoteVideo`; `localVideo` is the camera publication's track once the camera is on.
- `switchCamera()`: `(room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack)?.switchCamera()`.
- `close()`: `room.disconnect()`, `room.release()`, cancel the session's coroutine; a second call does nothing.
- Sound: nothing to set. LiveKit's default order is Bluetooth headset, wired headset, **loudspeaker**, earpiece – the loudspeaker when nothing is plugged in, as the design asks.
- `VideoView`: an `AndroidView` holding a `TextureViewRenderer`; `room.initVideoRenderer(view)` once; `track.addRenderer(view)` when the track changes and `removeRenderer` for the old one; on dispose `removeRenderer` and `view.release()`. LiveKit's separate Compose artifact is **not** used (it pins an older SDK and brings its own Compose versions).

- [ ] **Step 1: Write the failing tests** (`RoomSignalsTest`)

```kotlin
@Test fun `somebody who was in the room first is announced when we get in`() { assertEquals(SessionEvent.OTHER_JOINED, RoomSignals().connected(1)); assertNull(RoomSignals().connected(0)) }
@Test fun `the other side leaving counts only when nobody is left`() { assertEquals(SessionEvent.OTHER_LEFT, RoomSignals().left(0)); assertNull(RoomSignals().left(1)) }
@Test fun `the room closing is said once`() { val s = RoomSignals(); assertEquals(SessionEvent.CLOSED, s.disconnected()); assertNull(s.disconnected()) }
```

- [ ] **Step 2: Run the tests.** Expected: FAIL – `Unresolved reference 'RoomSignals'`.
- [ ] **Step 3: Implement** the three files.
- [ ] **Step 4: Run the tests, then `:app:assembleDebug :app:assembleRelease`.** Expected: PASS and `BUILD SUCCESSFUL` – the release build is the first time R8 meets code that really uses LiveKit. No test on a PC can stand in for a camera, a microphone and a LiveKit server: this task is proven in Task 11, steps 4, 8, 9 and 10.
- [ ] **Step 5: Commit.** `feat(parent-app): the call itself – a LiveKit room behind CallSession`

### Task 8: Ringing – and everything wired together

**Files:**
- Create: `…/call/AndroidRingNotifier.kt`, `…/call/CallActionReceiver.kt`, `…/call/CallForegroundService.kt`, `…/push/MenkerudMessagingService.kt`, `…/store/AppPrefs.kt`, `…/ui/Texts.kt`, `…/ui/CallActivity.kt`, `…/ui/screens/IncomingCallScreen.kt`, `…/ui/screens/RingingHomeScreen.kt`, `…/ui/screens/InCallScreen.kt`
- Modify: `…/AppContainer.kt`, `app/src/main/AndroidManifest.xml` (the activity, the receiver, the two services)
- Test: `app/src/test/kotlin/…/ui/TextsTest.kt`; `app/src/androidTest/kotlin/…/call/AndroidRingNotifierTest.kt` (runs in Task 11)

**Interfaces:**
- Consumes: `CallController`, `CallState`, `EndReason`, `LastCall`, `RingNotifier`, `SessionFactory` (Task 6); `LiveKitCallSession`, `VideoView` (Task 7); `readPush`, `PushAddressSync`, `AppContainer` (Task 5).
- Produces:

```kotlin
object Texts { /* every text of the app except app_name: the fixed ones and the table «Texts», by the names given there */ }
fun endText(reason: EndReason): String
fun missedText(at: Long, zone: ZoneId): String            // «Tapt anrop hjemmefra kl. 14:32»

class AppPrefs(context: Context) { val lastCall: Flow<LastCall?>; suspend fun setLastCall(call: LastCall) }   // DataStore preferences

class AndroidRingNotifier(context: Context) : RingNotifier   // also makes the three channels
class CallActionReceiver : BroadcastReceiver()               // ACTION_DECLINE (extra "callId"), ACTION_HANG_UP
class CallForegroundService : Service()                      // companion: fun start(context: Context)
class MenkerudMessagingService : FirebaseMessagingService()
class CallActivity : ComponentActivity()                     // ACTION_RING, ACTION_ANSWER, ACTION_SHOW; extra "callId"

// AppContainer gains:
val prefs: AppPrefs
val notifier: RingNotifier
val sessions: SessionFactory        // SessionFactory { LiveKitCallSession(appContext) }
val calls: CallController           // unpair = store::clear · remember = { scope.launch { prefs.setLastCall(it) } } · now = System::currentTimeMillis
```

`endText`: `HUNG_UP` and `OTHER_SIDE_LEFT` → `call_ended` · `CALL_OVER` → `call_over` · `NOBODY_HOME` → «Ingen svarte hjemme» · `BUSY_HOME` → «Opptatt hjemme» · `KIOSK_OFFLINE` → «Skjermen hjemme er ikke tilkoblet» · `TOO_MANY` → `try_later` · `NO_SERVER` → `no_server` · `COULD_NOT_JOIN` → `could_not_join` · `UNPAIRED` → `unpaired`.

**The notification that rings** (`AndroidRingNotifier`), as Android 14–17 demand it:

- Channels, made once: `calls` «Anrop hjemmefra», importance HIGH, sound = `RingtoneManager.getDefaultUri(TYPE_RINGTONE)` with `AudioAttributes` usage `USAGE_NOTIFICATION_RINGTONE`, vibration on · `missed` «Tapte anrop», DEFAULT · `ongoing` «Pågående samtale», LOW, silent. A channel's sound and importance cannot be changed once it exists on a phone: if they ever must change, the channel gets a new id.
- `ring(callId, forMs)`: `NotificationCompat.CallStyle.forIncomingCall(Person named «Hjemme», declineIntent, answerIntent)`, title «Det ringer hjemmefra», `setCategory(CATEGORY_CALL)`, `setOngoing(true)`, `setTimeoutAfter(forMs)`, `setFullScreenIntent(ringIntent, true)`, and `flags = flags or Notification.FLAG_INSISTENT` so that the ringtone goes on until it is answered, declined or timed out. A call-style notification **without** a full-screen intent makes `notify` throw.
  - `answerIntent` and `ringIntent` are **activity** intents to `CallActivity` (`ACTION_ANSWER`, `ACTION_RING`): Android forbids opening an activity from a receiver that a notification button started. `declineIntent` is a broadcast to `CallActionReceiver` (`ACTION_DECLINE`) → `container.calls.decline(callId)`. All `FLAG_IMMUTABLE`.
  - Notification tag `"ring"`, id `callId.hashCode()`. `stop(callId)` cancels exactly that one.
- `missed(callId, at)`: on `missed`, tag `"missed"`, id `callId.hashCode()`, text `missedText(at, ZoneId.systemDefault())`, tapping it opens `MainActivity`, auto-cancel.
- Nothing here starts an activity or a service by itself: from a push in the background Android allows neither. The banner's two buttons carry Android's own words (decision 4).

**`MenkerudMessagingService`**: `onMessageReceived(message)` → `container.calls.onPush(readPush(message.data))`; `onRegistered(installationId)` → `container.scope.launch { container.pushSync.run(installationId) }`. Manifest: the service with the action `com.google.firebase.MESSAGING_EVENT`, not exported.

**`CallForegroundService`** keeps an answered call alive when the parent leaves the screen: `android:foregroundServiceType="camera|microphone"`, not exported. `onStartCommand` calls `startForeground` with a notification on `ongoing`: title `ongoing_title`, one action «Legg på» → a broadcast to `CallActionReceiver` (`ACTION_HANG_UP`) → `container.calls.hangUp()`; tapping the notification opens `CallActivity` (`ACTION_SHOW`). It watches `container.calls.state` and stops itself on `Idle` and `Ended`. It is only ever started from `CallActivity` while that is on screen and camera and microphone are granted: Android refuses a camera or microphone service started from the background.

**`CallActivity`** – the one screen for a call from its first ring to its last word:

- Manifest: `showWhenLocked="true"`, `turnScreenOn="true"`, `launchMode="singleTask"`, `excludeFromRecents="true"`, portrait, not exported; in `onCreate` also the two window flags for API 26, and `FLAG_KEEP_SCREEN_ON`.
- It draws `container.calls.state`: `Incoming` → `IncomingCallScreen` (the avatar from `assets/`, «Det ringer hjemmefra», a green «Svar», a red «Ikke nå») · `Answering` → `connecting` · `RingingHome` → `RingingHomeScreen` (one's own camera picture, «Ringer hjem…», «Avbryt» → `hangUp()`) · `InCall` → `InCallScreen` (their picture filling the screen, one's own small and mirrored in a corner, «Legg på», `mic_off`/`mic_on`, `cam_off`/`cam_on`, `cam_flip`; `connecting` over it until their picture is there) · `Ended(reason)` → `endText(reason)` · `Idle` → `finish()`.
- `ACTION_ANSWER` (the banner's button) and the «Svar» button both go through one function: ask for `CAMERA` and `RECORD_AUDIO` if they are not granted (and `BLUETOOTH_CONNECT` on API 31+, whose refusal changes nothing); granted → `container.calls.answer(callId)`; refused → `need_cam_mic` with a button to the app's settings page, and the call keeps ringing.
- Whenever the state is `Answering`, `RingingHome` or `InCall` and both permissions are granted: `CallForegroundService.start(this)`.
- The pictures come from `container.calls.session` cast to `LiveKitCallSession`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// TextsTest (JVM)
@Test fun `a missed call says when`() =
    assertEquals("Tapt anrop hjemmefra kl. 14:32", missedText(Instant.parse("2026-11-15T13:32:10Z").toEpochMilli(), ZoneId.of("Europe/Oslo")))
@Test fun `every way a call can end has its words`() {
    assertEquals("Ingen svarte hjemme", endText(EndReason.NOBODY_HOME))
    assertEquals("Opptatt hjemme", endText(EndReason.BUSY_HOME))
    assertEquals("Skjermen hjemme er ikke tilkoblet", endText(EndReason.KIOSK_OFFLINE))
    assertEquals("Samtalen er avsluttet", endText(EndReason.HUNG_UP))
    assertEquals("Samtalen er avsluttet", endText(EndReason.OTHER_SIDE_LEFT))
    assertEquals("Samtalen er over", endText(EndReason.CALL_OVER))
    assertEquals("For mange forsøk – prøv igjen om litt", endText(EndReason.TOO_MANY))
    assertEquals("Får ikke kontakt med serveren", endText(EndReason.NO_SERVER))
    assertEquals("Klarte ikke å koble til samtalen", endText(EndReason.COULD_NOT_JOIN))
    assertEquals("Telefonen er koblet fra. Koble til på nytt med en ny nøkkel.", endText(EndReason.UNPAIRED))
}
```

and the on-device test `AndroidRingNotifierTest` (written now, run in Task 11, with the notification permission granted by the test rule): after `ring("c_1", 30_000)` the active notifications hold exactly one with tag `ring` on the channel `calls`, category `call`, a full-screen intent, and the flags ongoing and insistent; `stop("c_1")` removes it; `missed("c_1", at)` leaves one on `missed` whose text starts with «Tapt anrop hjemmefra kl.»; the channel `calls` has importance HIGH and a sound with the ringtone usage, `missed` DEFAULT, `ongoing` LOW.

- [ ] **Step 2: Run the tests.** Expected: FAIL – `Unresolved reference 'missedText'`.
- [ ] **Step 3: Implement** `Texts.kt`, `AppPrefs`, `AndroidRingNotifier`, `CallActionReceiver`, `CallForegroundService`, `MenkerudMessagingService`, `CallActivity` with its three screens, and the container's new members; add the components to the manifest.
- [ ] **Step 4: Run the tests, then `:app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest`.** Expected: PASS and `BUILD SUCCESSFUL`. What rings and what shows is proven on the phone: Task 11, steps 2 and 4–7.
- [ ] **Step 5: Commit.** `feat(parent-app): the phone rings – call-style notification, the call screen, push wired to the controller`

### Task 9: Pairing, the home screen, and «is this phone ready to ring?»

**Files:**
- Create: `…/setup/PhoneSetup.kt`, `…/setup/AndroidPhoneSetup.kt`, `…/ui/screens/PairingScreen.kt`, `…/ui/screens/HomeScreen.kt`, `app/src/main/res/drawable-nodpi/bg_night.webp`, `avatar_mor.png`, `avatar_far.png`, `badge_home.png`, `app/src/main/res/font/nunito.ttf`, the launcher icon resources
- Modify: `…/ui/MainActivity.kt`, `…/ui/Texts.kt` (`lastCallText`), `…/ui/theme/Theme.kt` (the font), `app/src/main/AndroidManifest.xml` (the `menkerudhome://pair` link)
- Test: `app/src/test/kotlin/…/setup/PhoneSetupTest.kt`, `…/ui/TextsTest.kt` (one more test)

**Interfaces:**
- Consumes: `readPairInput`, `PairPolicy`, `PairInput` (Task 2); `CredentialStore` (Task 3); `CallApi`, `PairResult`, `Sent` (Task 4); `PushAddressSync` (Task 5); `CallController`, `LastCall`, `CallKind` (Task 6); `AppPrefs`, `Texts`, `CallActivity` (Task 8).
- Produces:

```kotlin
data class PhoneSetup(val pushConfigured: Boolean, val notifications: Boolean, val fullScreen: Boolean,
                      val cameraAndMic: Boolean, val batteryUnrestricted: Boolean, val notPausedWhenUnused: Boolean)
enum class SetupItem { PUSH_MISSING, NOTIFICATIONS, FULL_SCREEN, CAMERA_MIC, BATTERY, UNUSED_PAUSE }
fun missing(setup: PhoneSetup): List<SetupItem>      // in the order of the enum; empty = the phone is ready
fun canRing(setup: PhoneSetup): Boolean              // pushConfigured && notifications – without these it will not ring at all

class AndroidPhoneSetup(context: Context) {
    fun read(): PhoneSetup
    fun fix(item: SetupItem, activity: ComponentActivity)   // ask for the permission, or open the setting that decides it
}

fun lastCallText(last: LastCall, now: Long, zone: ZoneId): String
```

`lastCallText` = `"<day> kl. <HH:mm> – <what>"`. Day: «i dag», «i går», otherwise `dd.MM.`. What: `FROM_HOME_ANSWERED` «de ringte, du svarte» · `FROM_HOME_MISSED` «tapt anrop» · `TO_HOME_ANSWERED` «du ringte hjem» · `TO_HOME_UNANSWERED` «du ringte hjem, ingen svarte».

`AndroidPhoneSetup.read()`: `pushConfigured` = `BuildConfig.PUSH_CONFIGURED` · `notifications` = `NotificationManagerCompat.areNotificationsEnabled()` and the channel `calls` not switched off · `fullScreen` = true below API 34, else `NotificationManager.canUseFullScreenIntent()` · `cameraAndMic` = both permissions granted · `batteryUnrestricted` = `PowerManager.isIgnoringBatteryOptimizations(packageName)` · `notPausedWhenUnused` = true below API 30, else `PackageManager.isAutoRevokeWhitelisted()`.

`fix`: `NOTIFICATIONS` → ask for `POST_NOTIFICATIONS` (API 33+), or open the app's notification settings · `FULL_SCREEN` → `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` · `CAMERA_MIC` → ask, or the app's settings page once Android no longer asks · `BATTERY` → `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` · `UNUSED_PAUSE` → `IntentCompat.createManageUnusedAppRestrictionsIntent` (Android stops pushes to an app nobody has opened for months) · `PUSH_MISSING` → nothing to tap. Samsung's own list cannot be read by an app: under the battery row a line of help – «På Samsung: Innstillinger → Batteri → Grenser for bakgrunnsbruk → Aldri sovende apper → legg til Menkerud Home».

**Pictures and font:** `assets/backgrounds/home-night.png` → `bg_night.webp` at 1920×1080, quality 80, with Pillow (from the repo root: `python -c "from PIL import Image; Image.open(r'assets/backgrounds/home-night.png').save(r'parent-app/app/src/main/res/drawable-nodpi/bg_night.webp', quality=80)"`), drawn with `ContentScale.Crop` under a dark veil; `assets/avatars/mor.png` and `far.png` as they are (the one matching `who` is shown); `assets/icons/home-arrival-glass.png` → `badge_home.png`, also the launcher icon's foreground on `#07162F`. Nunito (OFL) from `https://github.com/google/fonts/raw/main/ofl/nunito/Nunito%5Bwght%5D.ttf` as `res/font/nunito.ttf`; if it cannot be fetched, the system font stays.

**`MainActivity`:**

- Not paired (`store.current` is null) → `PairingScreen`: `pair_title`, `pair_hint`, a text field, `pair_scan`, `pair_go`. What is typed or scanned goes through `readPairInput(text, PairPolicy(BuildConfig.DEFAULT_API, setOf(BuildConfig.ALLOWED_API_HOSTS), BuildConfig.ALLOW_ANY_API))`: `NotAKey` → `pair_not_key` · `ForeignServer` → `pair_foreign` · `Ok(link)` → `api.pair(link, Build.MODEL)`: `Paired` → `store.save(credentials)`, then `pushSync.run()` · `WrongKey` → `pair_wrong` · `Expired` → `pair_expired` · `Failed` → `no_server`.
- Scanning: `GmsBarcodeScanning.getClient(this, GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()).startScan()`; its `rawValue` takes the same road as typed text; any failure (the scanner module could not be fetched, the scan was cancelled) → `scan_failed`. A `menkerudhome://pair?…` link opened from outside fills the field in; the parent still presses `pair_go`.
- Paired → `HomeScreen` over the night background: `home_as`; the headline `home_ready` when `missing(setup)` is empty, else `home_not_ready` – red when `canRing` is false, amber otherwise – with one row per missing item (tap → `fix`); a large «Ring hjem»; `home_last` with `lastCallText`; the version. The column leaves room for a second section later (spec §9). The setup is read again every time the screen comes back to the front.
- Every time the screen comes to the front while paired: `api.whoAmI()`; `UNPAIRED` → `store.clear()` and the pairing screen shows `unpaired`; `FAILED` → one line `no_server` on the home screen, nothing else changes.
- «Ring hjem»: ask for camera and microphone if needed (refused → `need_cam_mic`), then `calls.ringHome()` and open `CallActivity`. Whenever `calls.state` is not `Idle`, `CallActivity` is opened.
- Debug builds only: a small «Test forbindelsen ut» that fetches `https://call.noobventure.com/healthz` and `https://rtc.noobventure.com/rtc/validate` and shows the two status codes (200 and 401 are the good answers). It answers the design's early question – does an app get through Cloudflare from a mobile network – before anything is paired with production.

- [ ] **Step 1: Write the failing tests**

```kotlin
// PhoneSetupTest
private val ok = PhoneSetup(pushConfigured = true, notifications = true, fullScreen = true, cameraAndMic = true, batteryUnrestricted = true, notPausedWhenUnused = true)
@Test fun `a phone that will not ring never looks ready`() {
    assertTrue(missing(ok).isEmpty()); assertTrue(canRing(ok))
    assertFalse(canRing(ok.copy(notifications = false))); assertFalse(canRing(ok.copy(pushConfigured = false)))
    assertTrue(canRing(ok.copy(batteryUnrestricted = false)))            // it rings today – and is still named as missing
    assertEquals(listOf(SetupItem.BATTERY), missing(ok.copy(batteryUnrestricted = false)))
}
@Test fun `each missing piece is named in order`() =
    assertEquals(SetupItem.entries.toList(), missing(PhoneSetup(false, false, false, false, false, false)))

// TextsTest
@Test fun `the last call in plain words`() {
    val oslo = ZoneId.of("Europe/Oslo"); fun at(s: String) = Instant.parse(s).toEpochMilli(); val now = at("2026-11-15T19:00:00Z")
    assertEquals("i dag kl. 14:32 – tapt anrop", lastCallText(LastCall(at("2026-11-15T13:32:00Z"), CallKind.FROM_HOME_MISSED), now, oslo))
    assertEquals("i går kl. 09:05 – de ringte, du svarte", lastCallText(LastCall(at("2026-11-14T08:05:00Z"), CallKind.FROM_HOME_ANSWERED), now, oslo))
    assertEquals("03.11. kl. 18:20 – du ringte hjem, ingen svarte", lastCallText(LastCall(at("2026-11-03T17:20:00Z"), CallKind.TO_HOME_UNANSWERED), now, oslo))
    assertEquals("i dag kl. 20:00 – du ringte hjem", lastCallText(LastCall(now, CallKind.TO_HOME_ANSWERED), now, oslo))
}
```

- [ ] **Step 2: Run the tests.** Expected: FAIL – `Unresolved reference 'PhoneSetup'`.
- [ ] **Step 3: Implement** `PhoneSetup.kt`, `AndroidPhoneSetup.kt`, `lastCallText`, the two screens, `MainActivity`, the pictures, the font, the launcher icon and the link in the manifest.
- [ ] **Step 4: Run the tests, then `:app:assembleDebug :app:assembleRelease`.** Expected: PASS and `BUILD SUCCESSFUL`. The screens are looked at on the phone in Task 11, step 3.
- [ ] **Step 5: Commit.** `feat(parent-app): pairing by key or QR code, the home screen, and what the phone still needs to ring`

### Task 10: A build that can be handed over – shrunk, signed, and written down

**Files:**
- Modify: `parent-app/app/build.gradle.kts` (the build type `minified`), `parent-app/app/proguard-rules.pro`, `parent-app/README.md`, `parent-app/keystore.properties.template`, `DESIGN.md` (§1 Files, §11), `CLAUDE.md` (two lines: the app exists, how it is built and tested)
- Create, never commit: `parent-app/keystore.properties` and the keystore itself (the operator's)

**Interfaces:**
- Consumes: the whole app.
- Produces: three installable builds – `debug` (as before), **`minified`** and `release`.

Why a third build type: the release build is shrunk by R8, and R8 is what breaks apps like this one (LiveKit's native bridge, serialization, Firebase). But a release build only accepts the production server, and production is not to be paired in part 2. `minified` is the release build in every respect that R8 cares about, pointed at the dev copy:

- `create("minified") { initWith(getByName("release")) }` with `applicationIdSuffix ".debug"`, `versionNameSuffix "-minified"`, the debug signing key, `ALLOW_ANY_API = true`, app name «Menkerud Home (test)», `matchingFallbacks += "release"`, and the debug source set's manifest overlay and `res` (plain http for the dev copy). Its Firebase values are those of the `.debug` client.
- `proguard-rules.pro`: the kotlinx-serialization rules for the app's own `@Serializable` classes (as in DrySpool's file). LiveKit and Firebase bring their own rules. Add a rule only when Task 11, step 15 shows something missing – and say in a comment what broke without it.

The release keystore is the app's identity on the two phones: an installed app can only be replaced by a build signed with the same key – otherwise it must be uninstalled first, which unpairs the phone.

- [ ] **Step 1 (operator, or the executor on the operator's word):** make the keystore **outside the repo** and back it up:
  `"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore <folder outside the repo>\menkerud-release.jks -alias menkerud -keyalg RSA -keysize 2048 -validity 10000`, then copy `keystore.properties.template` to `keystore.properties` and fill in `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Without it everything below still works; the release APK is just unsigned.
- [ ] **Step 2:** Add the build type `minified` and the serialization rules.
- [ ] **Step 3: Build all three.** `:app:testDebugUnitTest :app:assembleDebug :app:assembleMinified :app:assembleRelease` → `BUILD SUCCESSFUL`; `app-minified.apk` is installable (signed with the debug key) and about the size of the release one; with a keystore, `app-release.apk` is signed: `apksigner verify --print-certs` (in `Sdk\build-tools\36.0.0`) shows the certificate of the alias `menkerud`.
- [ ] **Step 4: Write `parent-app/README.md` in full:** what the app is; the three builds and their commands; where Firebase's file goes and what happens without it; installing (`adb install -r <apk>`, or copying the file to the phone; on a Samsung, Auto Blocker must be off while installing); first start – the key from the local page, typed or scanned; **the phone's settings, one by one, as the home screen names them**, plus Samsung's «Aldri sovende apper»; what the app cannot do (it does not ring when it was force-stopped; an app nobody opens for months is put to sleep by Android unless the setting is off; silent mode and Do Not Disturb are respected); updating by installing over the old one with the same key; the dev copy for testing (pointer to `call-backend/README.md`). A note for 2027: Google is bringing developer verification to sideloaded apps; installs over adb stay exempt.
- [ ] **Step 5: `DESIGN.md`:** a row for `parent-app/` in §1; in §11 a paragraph «Parent app, part 2 – the Android app»: what was built, the decisions of this plan in two or three sentences each (ringing by notification, no Telecom, installation ids, target 36), and that nothing is paired with production until part 3. `CLAUDE.md`: `parent-app/` is a separate Gradle project; build and test it with Android Studio's Java (`JAVA_HOME`), `:app:testDebugUnitTest`; UI text Norwegian, in `Texts.kt`.
- [ ] **Step 6: Commit.** `feat(parent-app): a shrunk test build next to the release build; how to build, install and set up the phone`

### Task 11: Everything together on a real phone, against the dev copy

No new code unless a step fails. This is where the app meets a camera, a ringtone, a lock screen and Samsung's battery saving – and it needs the operator: the phone on a USB cable, the three things of Task 0, and about an hour.

**Set-up for every step:** the dev copy running with real pushes (`npm run dev` in `call-backend/`; its start line must say `push fcm`); `adb devices` shows the phone; `adb reverse tcp:3100 tcp:3100` (the phone then reaches the dev copy as `http://127.0.0.1:3100`, which is also what the dev copy's QR code says); the kiosk page open in Chrome on the PC, pointed at the dev copy as `call-backend/README.md` describes; the pairing page `http://127.0.0.1:3101` open on the PC.

A step that fails: find the cause before changing anything (the phone's log: `adb logcat -s MenkerudHome:* AndroidRuntime:E`; the dev copy's log). Where a JVM test can show the fault, write that test first. Record each step's result in the ledger.

- [ ] **Step 1: The on-device tests.** `:app:connectedDebugAndroidTest` → `KeystoreSecretBoxTest` and `AndroidRingNotifierTest` pass. (This task uninstalls the test build afterwards – run it before pairing.)
- [ ] **Step 2: Install and start.** `adb install -r app/build/outputs/apk/debug/app-debug.apk`; the app opens on the pairing screen; the home network's Chrome is not involved.
- [ ] **Step 3: Pair, and make the phone ready** (spec §11.1). On the pairing page: «Ny telefon for Far»; in the app `pair_scan`, scan the QR code → the home screen says «Denne telefonen er Far», and the phone appears in the page's list with a push address. The home screen names every missing piece; switch each on from its row until it says `home_ready`. Then once more with a key typed by hand, dashes and small letters included (remove the phone on the page first).
- [ ] **Step 4: The kids call, the phone is locked** (spec §11.2). «Ring far» on the kiosk page: the phone rings within about 5 s – ringtone, vibration, the ringing screen over the lock screen with «Svar» and «Ikke nå». «Svar»: picture and sound both ways within about 5 s more. «Legg på» on the phone → the kiosk page is back on its front page. Again, and hang up on the kiosk → the phone says `call_ended` and lets the camera go. **If no push arrives** and the dev copy logs `push failed … INVALID_ARGUMENT`: Google wants an installation id in the field `fid`, not `token` – change `call-backend/src/push.js` (test first, in `push.test.js`: an address of 22 characters goes out as `fid`); if that is not it either, take the fall-back of Task 5.
- [ ] **Step 5: The phone is in use.** The same call while the phone is unlocked: the banner with Android's own two buttons; its «Svar» opens the call.
- [ ] **Step 6: «Ikke nå»** (spec §11.3) → the kiosk page says «Far kunne ikke svare nå 😢» within about 3 s.
- [ ] **Step 7: Nobody answers** (spec §11.4) → after the ring time the kiosk page shows the same message and the phone shows «Tapt anrop hjemmefra kl. …». Then: «Ring far» and «Legg på» on the kiosk while it rings → the phone stops and shows the missed call.
- [ ] **Step 8: «Ring hjem»** (spec §11.5). The kiosk page shows «Far ringer!»; «Svar» connects, both ways. Again with «Ikke nå» → «Ingen svarte hjemme». Again without touching the kiosk page for 45 s → «Ingen svarte hjemme». With the kiosk page closed → «Skjermen hjemme er ikke tilkoblet». While the kiosk page is itself ringing far → «Opptatt hjemme».
- [ ] **Step 9: The connection is lost in the middle of a call.** Flight mode on the phone during a call: the phone ends with `call_ended`, the camera light goes out, the call notification disappears; the kiosk page ends too. Note how long each side takes.
- [ ] **Step 10: The kiosk vanishes in the middle of a call.** Close the kiosk page's tab during a call: the phone ends within about 10 s.
- [ ] **Step 11: Android has put the app away.** Swipe the app out of the recent apps, then «Ring far»: it rings, and «Svar» from the notification opens the call. Then `adb shell dumpsys deviceidle force-idle` (deep sleep) and «Ring far»: it rings (`adb shell dumpsys deviceidle unforce` afterwards). Then `adb shell am force-stop com.noobventure.menkerud.debug` and «Ring far»: it does **not** ring – that is Android's rule for an app the user has stopped by force; it goes into the README as it is.
- [ ] **Step 12: Each missing piece is named.** Switch off, one at a time: notifications, the full-screen permission, the camera permission, the battery exemption. Each time the home screen names exactly that row. With the full-screen permission off a call still shows as a banner and rings.
- [ ] **Step 13: A removed phone** (spec §11.6). «Fjern» on the pairing page. «Ring far» no longer rings this phone (the kiosk page says «Fikk ikke ringt far» – the dev copy has no Discord to fall back on). Bringing the app to the front shows the pairing screen with `unpaired`; «Ring hjem» cannot be reached.
- [ ] **Step 14: From outside the house.** Pair again. Wi-Fi off on the phone, mobile data on, the USB cable still in (the API goes through the cable, the picture and sound through the mobile network). «Test forbindelsen ut» shows 200 and 401. «Ring far» and «Svar»: picture and sound both ways. **This is the call from outside that part 1's Task 0 still owes.** If there is no picture or sound: the design's own list – UDP 7882 forwarded on both routers, then TCP 7881, then LiveKit's relay (spec §8, «First step»).
- [ ] **Step 15: The shrunk build.** Uninstall the test build, install `app-minified.apk`, pair, and do steps 4 and 8 once each. Anything that works in `debug` and not here is a missing keep-rule: add it to `proguard-rules.pro` with a comment saying what broke.
- [ ] **Step 16: Both suites and the books.** `:app:testDebugUnitTest` passes; `npm test` in the repo root and in `call-backend/` still pass (they must be untouched unless step 4 changed `push.js`). Write into `DESIGN.md` §11 what this run showed – the times of steps 4, 6 and 9, anything that did not behave as written, and what the phones need set (for part 3). Commit: `test(parent-app): the app against the dev copy on a real phone – what was seen`.

---

## After this plan

Part 3 – the rollout – gets its own plan: the new backend on Unraid (the list in `DESIGN.md` §11), the Firebase key on the server, the release build on both phones, pairing them with production one parent at a time (that parent leaves the Discord link at that moment), the kiosk's Chrome allowed to make sound untouched, and – once both parents are on the app and the operator says so – removing the answer page and the Discord call message. `SETUP.md` and `DEPLOYMENT.md` get the operator's steps for the app then, when they are true of the house; until then they live in `parent-app/README.md`.
