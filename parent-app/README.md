# Menkerud Home – the parent app

The Android app on mor's and far's phones. It rings when the kids call from the screen at home, joins the call on
«Svar», and can ring home. It is installed by hand as an `.apk`, not from the Play Store.

Design: `docs/superpowers/specs/2026-11-14-parent-app-design.md`. The API it talks to: `call-backend/`.

## Building

Android Studio must be installed (the build uses its Java and the Android SDK). From this folder, in Git Bash:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleRelease --console=plain
```

or in PowerShell:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:assembleRelease --console=plain
```

`JAVA_HOME` is needed because the `java` on this PC's PATH is a 32-bit Java 8, which cannot run the build.

The APKs land in `app/build/outputs/apk/debug/app-debug.apk` and `app/build/outputs/apk/release/` –
`app-release.apk` once a keystore is set up (`keystore.properties.template` says how), `app-release-unsigned.apk`
until then.

## Two files the build wants that are never committed

- **`local.properties`** – one line saying where the Android SDK is, for example
  `sdk.dir=C:/Users/Kjetil/AppData/Local/Android/Sdk`. Android Studio writes it when it opens the project.
- **`app/google-services.json`** – from the Firebase project, which holds two Android apps:
  `com.noobventure.menkerud` and `com.noobventure.menkerud.debug`. The build reads the four values Firebase needs
  out of this file itself (the google-services Gradle plugin is not used). Without the file the app still builds
  and runs, but it cannot be rung, and its home screen says so.

## The two builds

- **debug** installs as a second app, «Menkerud Home (test)» (`com.noobventure.menkerud.debug`), beside the real
  one. It accepts any server address from a QR code, and plain http, so it can be paired with the dev copy of the
  backend (`call-backend/README.md`, «Developing on a separate copy»).
- **release** is «Menkerud Home» (`com.noobventure.menkerud`): shrunk, arm64 only, https only, and it accepts a QR
  code only for `https://call.noobventure.com`.

## Why the app targets Android 16

`targetSdk` is 36 on purpose, not 37. An app that targets 36 keeps its access to the home network without a new
permission prompt (at home the call's picture and sound go straight to the server on the LAN), and it stays clear
of Android 17's stricter rule for sound started from the background. Raise it only together with a test of both.
