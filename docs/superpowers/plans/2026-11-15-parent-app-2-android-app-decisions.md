# Parent app, part 2 – the decisions made while building it

Plan: `2026-11-15-parent-app-2-android-app.md`. Spec: `../specs/2026-11-14-parent-app-design.md`.

The plan was carried out by Claude in one session on 2026-11-15/16 (Tasks 1–10, a whole-branch review by a fresh reviewer, one fix pass). Wherever the plan was silent, wrong, or overtaken by something found on the way, a decision was made and written down at once – what was decided, why, and what it costs if it is wrong. This is that list, complete and in the order the decisions were made. Nothing here has been run on a phone yet: that is Task 11 of the plan.

The smaller findings of the review that were **not** fixed are in `DESIGN.md` §10.

## Decisions

### Before Task 1

- **Work in the existing checkout on branch `experimental`, no separate worktree.**
  Why: The plan's Global Constraints name that branch and it is not main.
  If that is wrong: Work in progress shares the folder the operator opens; per-task commits keep it recoverable.

- **Task 8's Files list is right; read "Task 7" in T5's text as "Task 8".**
  Why: The plan's own self-review moved the services to Task 8 and missed these mentions.
  If that is wrong: None (no code depends on which task creates the file).

### Task 1

- **The debug app name is an overlay file app/src/debug/res/values/strings.xml (not in the brief's file list).**
  Why: A resValue named app_name beside strings.xml's app_name is a duplicate resource.
  If that is wrong: One small file.

- **BuildFeatures.resValues = true added to the brief's two features.**
  Why: The four Firebase strings are res values and AGP 9 has the feature off unless asked.
  If that is wrong: None (a flag).

### Task 2

- **The server address of a QR code is rebuilt from its parsed parts (scheme, host, port, path; query and fragment dropped) and an address with a user name in it is refused; three tests added to the brief's seven.**
  Why: The brief only said "accepted when https and its host is in allowedHosts", but a text checked by one parser (java.net.URI) and later connected to by another (OkHttp) is the classic way such a check is got round, and the spec's anti-goal is a phone pairing with a foreign server.
  If that is wrong: A server whose address needs a query string or a user name could not be paired from a QR code (none exists).

### Task 3

- **Credentials.toString() leaves the secret out, and three tests were added to the brief's six (printed credentials, saving shows at once in `current`, saving again replaces).**
  Why: The Global Constraints say the secret is never logged or shown, and a data class prints every field by default.
  If that is wrong: None.

### Task 4

- **With nothing stored, status() answers Gone and cancel()/end() answer UNPAIRED, without a request.**
  Why: The brief names UNPAIRED/Unpaired only for the methods that carry the secret, but these three also need the stored server address, and StatusResult has no Unpaired.
  If that is wrong: None seen (the controller treats Gone as «the call is over»).

- **Two guards beyond the brief, each seen failing first: (a) a call id that is empty, "." or ".." is answered as over (Over / Gone / OK) without a request.**
  Why: OkHttp reads ".." as «one level up» when it builds the address, so the brief's "the call id is URL-encoded into the path" does not hold for it; (b) pair() answers Failed(SERVER) when the server's deviceId or secret holds a character that cannot stand in a header — such a pairing would be saved and then make every request throw.
  If that is wrong: (b) would refuse a server that hands out secrets outside [A-Za-z0-9._~+/=-]; ours hands out hex.

### Task 5

- **A `call` push without `who` is still a Call (who = ""), a `call_end` without `reason` is a CallEnd with reason "".**
  Why: The brief names only callId and expiresAt as needed; nothing rings differently by `who`.
  If that is wrong: None.

### Task 6

- **A call in which the other side never shows up ends after 30 s (api.end, Ended(OTHER_SIDE_LEFT)).**
  Why: The brief's rule 10 only covers «left and did not come back within 10 s», so a phone that got into a room the kiosk had already vanished from would sit there with its camera on until the parent hung up; the plan's Review Focus 3 asks that such a call ends.
  If that is wrong: A kiosk that needs more than 30 s to join after answering a parent's call is cut off (its own limit for that is 20 s).

- **The same `call` push twice while it rings is ignored (one ring, one status question).**
  Why: The brief's rule 5 only names «another call»; Review Focus 1 names «twice».
  If that is wrong: None.

- **Decline(id) while a DIFFERENT call rings here takes that id's notification away and tells the server, and leaves the ringing call alone; decline in Answering / RingingHome / InCall does nothing.**
  Why: The brief only names Idle, Ended and Incoming(id).
  If that is wrong: None seen.

- **LastCall.at is when the phone got into the call (answered calls from home), when «Ring hjem» was pressed (calls home, answered or not), and the notice's own time (missed).**
  Why: The brief says when remember is called but not which time it carries.
  If that is wrong: The home screen's «Siste anrop» shows the start of a call where someone expected its end.

- **While ringing home, a status word this build does not know keeps the questions going (until the 65 s limit); a room that cannot be joined after the kiosk has already answered is ended with /end, before that with /cancel.**
  Why: Both unspecified; the server treats /cancel and /end alike.
  If that is wrong: None.

### Task 7

- **A camera or a microphone that will not start does not fail connect(): the call goes on with what there is (cameraOn / microphoneOn stay false, one log line without details).**
  Why: The brief says «anything thrown goes to the caller», which would turn «another app holds the camera» into «Klarte ikke å koble til samtalen» although sound would work.
  If that is wrong: A parent can be in a call the kids cannot see or hear them in, and has to notice it from the buttons.

- **Two small additions to the brief's surface: VideoView has `fill: Boolean = false` (the whole picture by default, cut-to-fill for one's own small picture), and LiveKitCallSession has `frontCamera: StateFlow<Boolean>` (the back camera's picture must not be mirrored).**
  Why: The picture from home is 16:9 and a phone held upright is not; filling the screen with it would cut away three quarters.
  If that is wrong: None.

### Task 8

- **CallController.onPush and .decline return their Job; the push service blocks (at most 5 s) until the notification is up, the decline button's receiver holds its broadcast (at most 8 s) until the server has been told.**
  Why: Android may put a background process to sleep the moment a push service or a receiver returns: the phone might not ring until the next wake-up, and a decline might never reach the server (the kiosk would ring on for a minute, and the phone would then show «Tapt anrop»).
  If that is wrong: None seen; the two functions' callers may ignore the result.

- **A call_end push also takes away the ringing notification of a call the controller knows nothing of (state Idle, Ended, or another call ringing).**
  Why: Android may end the app while the notification rings on; the push that ends the call then starts a new process, and without this the phone would ring on until the notification's own time-out. The brief's test `a missed call the phone never rang for still leaves its notice` now expects `stop c_9` before `missed c_9` instead of the single line.
  If that is wrong: One needless cancel of a notification that is not there.

- **CallActivity keeps the ringing screen up when the controller is Idle but the notification that opened it still rings (same case: the app was restarted in between); it looks at the notification twice a second and closes when it is gone. «Svar» and «Ikke nå» work from Idle (Task 6).**
  Why: The brief says «Idle -> finish()», which would close the ringing screen at once in that case.
  If that is wrong: None seen.

- **While a call is on, the call screen turns with the phone (requestedOrientation SENSOR in InCall, portrait otherwise; the manifest's configChanges keep a turn from restarting it). The brief says portrait.**
  Why: The picture from home is 16:9; upright it is a band a quarter of the screen high, and the spec's §8 says «their picture full screen». Upright the whole picture is shown with the line «Snu telefonen for større bilde».
  If that is wrong: A parent whose phone is locked to upright sees the screen turn anyway during a call (SENSOR ignores that lock, on purpose).

- **`androidx.fragment:fragment` 1.8.5 is named as a dependency although the app uses no fragments.**
  Why: Play services bring Fragment 1.1.0, and lint refuses to build a release that asks for permissions the modern way with a Fragment older than 1.3.0 (lintVitalRelease: InvalidFragmentVersionForActivityResult).
  If that is wrong: None (it replaces the old version already in the APK).

- files and texts the brief does not list: ui/screens/Parts.kt (NightBackground, BigButton, QuietButton, shared by Task 9's screens), res/drawable/ic_stat_call.xml (the notifications' small icon), the art Task 9 was to bring (bg_night.webp, the two avatars, badge_home.png – the incoming-call screen needs the badge now), colours CallGreen/CallRed/Amber/Veil from the kiosk's CSS, and the texts «Hjemme», «Åpne innstillinger», «Snu telefonen for større bilde» — cost if wrong: none.

- **The microphone and camera buttons say how things ARE («Lyd på» / «Lyd av», red when off), not what a tap would do.**
  Why: The brief only names the four texts.
  If that is wrong: A parent reads «Lyd av» as an offer rather than a state; the red colour is meant to settle it.

- lint is told not to report the pinned versions, targetSdk 36 and the arm64-only release (all decided in the plan), so that `:app:lintDebug` only shows real findings; it is run as an extra gate from here on. The on-device notifier test grants the notification permission through UiAutomation (no extra test library) and accepts Android 14's «full-screen intent asked for but not allowed» mark — cost if wrong: none.

### Task 9

- **What happens on «Koble til» lives in a class of its own, pairing/Pairing.kt, with PairingTest – the brief wrote it into MainActivity.**
  Why: The plan's own rule is that what decides something is plain Kotlin with a JVM test, and pairing is the app's front door. It also catches a secret that cannot be saved (text «Fikk ikke lagret koblingen på telefonen. Lag en ny nøkkel og prøv igjen.») instead of crashing.
  If that is wrong: None.

- **The pairing screen says «Telefonen er koblet fra …» whenever a pairing was lost – also when the app was not open when it happened, and also when the stored secret could no longer be read. For that: CredentialStore.unreadable, AppPrefs.unpairedNotice, and a watcher in AppContainer.**
  Why: The brief only covers «whoAmI says UNPAIRED while the home screen shows»; the plan's Review Focus 4 asks that the app «goes back to the pairing screen and says why» in both cases.
  If that is wrong: A phone whose pairing file was damaged shows that line on its next start although nobody removed it.

- **AndroidPhoneSetup offers read(), permissions(item), settings(item), granted() and appSettings() instead of the brief's fix(item, activity); MainActivity.fix does the asking.**
  Why: Android only lets an activity ask for permissions through launchers it registered itself at start-up. A permission Android refuses without asking (answer within half a second) opens the setting that decides it.
  If that is wrong: None.

- **«Whenever calls.state is not Idle, CallActivity is opened» is done as: opened when a call begins while the home screen shows, or when the app is opened while a call is on. Coming BACK to the home screen during a call does not bounce the parent to the call screen again; the big button then reads «Tilbake til samtalen».**
  Why: The brief's wording would make the back button on the call screen do nothing but flicker.
  If that is wrong: A parent on the home screen during a call has to press that button to return.

- the connection test («Test forbindelsen ut») shows in every build that accepts any server (debug, and Task 10's minified), not only in debug; the Samsung help line shows on Samsung phones only; a scanned QR code pairs at once, a link opened from outside only fills the field in (as the brief says) — cost if wrong: none.

### Task 11

- **While waiting, a test the plan does not have was added and run – DevCopyTest: the app's real Pairing, HttpCallApi, readPush and CallController against the RUNNING dev copy (PUSH_TRANSPORT=file), with stand-ins only for the room, the notification and the screen at home's event stream. 7 of 7 passed on the first run against the dev copy on this PC (pairing with a real key; a `call` push as the server writes it; «Svar» returns a real LiveKit ticket and the server says connected; «Ikke nå» -> declined; the kids cancel -> call_end cancelled -> «missed»; ring home with no kiosk -> KIOSK_OFFLINE; ring home answered, then declined; a removed phone -> UNPAIRED). The dev copy's log shows no error. It is skipped unless MENKERUD_DEV_COPY is set.**
  Why: The phone run costs the operator an hour; a mismatch between app and server that a PC can find should not be found there.
  If that is wrong: One more test file to keep true when the contract changes (that is its purpose).

### Review and fix pass

- **The whole-branch review is run NOW, before Task 11 – the skill puts it after the last task.**
  Why: Task 11 is a run on the operator's phone that adds no code unless a step fails, and it waits for the operator's Firebase project and phone; reviewing first means the phone run tries reviewed code, and the review is the main safeguard for the Android-only code that nothing on this PC can execute.
  If that is wrong: Changes that the phone run forces later are not covered by this review; if they are more than small fixes, a second review is owed.

- **Two of the reviewer's Minors were re-graded Important and fixed in the pass – (Minor 1) the banner did not carry «Det ringer hjemmefra», a sentence the spec fixes (§7) and the place a parent sees most; (Minor 3) a room that cannot be made or will not close crashed the app in the middle of an answer, leaving the kids waiting for the server's watchdog.**
  Why: Graded by what the person gets, not by the label.
  If that is wrong: Two small fixes more than the reviewer asked for.

- **Log lines were added although they were a recommendation, not a finding (Notes.kt; every push, every turn of a call, the ring up and down, the service, the push registration; a call named by its last four characters).**
  Why: Task 11 tells the operator to read `adb logcat -s MenkerudHome` when a step fails, and before this the tag carried five lines that could not tell «no push» from «no notification».
  If that is wrong: None; no secret, ticket or push address is written.

## What the reviewer set aside, and what was decided about each

- **Ringing through silent mode or Do Not Disturb.** A spec non-goal (§3); the phone follows its own sound setting. If that is wrong: A parent with the phone on silent misses a call, as designed.
- **The server (call-backend), including whether Google takes an installation id in the push's `token` field.** Reviewed in part 1; the plan's Task 11 step 4 names that risk and its two ways out. If that is wrong: No push arrives on the first try at the phone, and step 4 says what to change.
- **The kiosk page.** Outside this plan's range; nothing in it was touched. If that is wrong: None.
- **Google's code scanner and Firebase's own transport may report usage to Google although the app has «no analytics».** The app itself sends none; both libraries are what the operator agreed to (Google's push service) or what plan decision 12 chose (scanning without a camera permission). The README says the app talks to Google's push service. If that is wrong: Google learns that this app scanned a code or received a push; if that is unwanted, typing the key avoids the scanner, and nothing avoids the push service.
- **TargetSdk 36 and what Android 17 changes.** Plan decision 2, deliberate; nobody could run Android 17 here. If that is wrong: On a phone with Android 17 something behaves differently than on 16; Task 11 runs on the operator's real phone and will show it.
- **No Telecom integration: the volume keys do not silence the ring, and an ordinary phone call during a Menkerud call is undefined.** Plan decision 5; one try on the phone was added to Task 11 (step 15b). If that is wrong: An ordinary call arriving in the middle of a call home fights it for the sound.
- **Back or Home during a call leaves camera and microphone on behind a quiet notification.** That is what the plan's Task 8 asks for (the call lives on; the notification has «Legg på»). If that is wrong: A parent who leaves the call screen without hanging up is still seen and heard at home until they hang up or the kids do.
- **Android older than 12 (the older sound routing, service types on Android 10).** The two phones run Android 15-17; minSdk 26 is the spec's. If that is wrong: An old spare phone would be the first to run those paths.
- **Accessibility, very large font sizes, battery use in long calls, picture quality.** Not in the plan or the spec; the screens scroll, so large text does not cut buttons off. If that is wrong: Cramped screens for a parent with a very large font; look at step 3.
- **The release keystore and signing.** The operator's; left undone on purpose (Task 10). If that is wrong: None; the release build stays unsigned until the operator makes the key.
- **Two phones for one parent ringing at once.** The logic is tested (the same push twice, answered elsewhere) but it cannot be tried with one phone; it is on DESIGN.md's list for the rollout. If that is wrong: The second phone rings on after the first has answered, until the server's `call_end` reaches it.
