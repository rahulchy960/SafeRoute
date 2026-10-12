# P014c1a: the tile and the notification start the SOS through one use case

| Field | Value |
| --- | --- |
| Prompt | P014c, part 1 (entry points), first half. Part 1 was split: P014c1a (this log) and P014c1b (the home-screen widget) |
| Milestone | M5 (follows P014b7, merged as `3225e12`) |
| Branch | `feat/014c1a-sos-entry-points` |
| PR title | `feat(sos): tile and notification start the SOS through one use case with the consent gate [P014c1a]` |
| Notion | [P014c1a row in the Prompt Log](https://app.notion.com/p/3f707370772081c4933add1617c4a69d) |
| Date | 2026-10-12 |
| Plan refs | Plan v7 §3.2 F-08, §5.3, §7.1–7.5, §12.3; addendum v7.1, section B (entry points); ADR 0027 (note of 2026-10-12, P014c1a), ADR 0024, ADR 0008, ADR 0015 |

> **Nothing was run on a phone.** Every test runs on the JVM (Robolectric). Whether a phone
> shows the countdown over its lock screen, lets the tile be tapped there, and starts the
> location service from there is not known until script M17 is run.
>
> **From this change one tap outside the app starts the five-second countdown**, also on a
> locked phone. If nobody cancels, the emergency contacts are texted. Read section 9.
>
> **Why part 1 was split.** With the widget, part 1 was about 1,160 changed lines in
> `android/` (443 of app code and resources, 613 of tests), above the ~800 the prompt
> allows. The widget is the cleanest piece to take out: its own provider, layout, manifest
> entry and test file, about 360 lines, and nothing else depends on it. It is written and
> passes its tests on a local branch; it ships as P014c1b.

## 1. Objective

Make every entry point start the same five-second, cancellable countdown through one use
case and the same consent gate. Before this change only the hold in the app started an
SOS; the Quick Settings tile and the pinned notification opened the emergency options.
This half covers the use case, the hold, the tile, the notification and the consent
notice. The widget is P014c1b; the readiness screen and the notice after a restart are
P014c2 and P014c3.

## 2. Context & prerequisites

- Preflight: `main` synced at `3225e12`; PR #62 (P014b7) merged; no open pull requests; hook
  path `.githooks`. Notion: P014b7 set to Merged, a new row for this prompt.
- `android-ci` on `main` for `3225e12`: success (run 38285365927). This is the evidence
  that lets rows 2 and A8 count the tests of P014b7.
- **Manual results so far:** the prompt's line was a placeholder. **Not reported.**

**What was found (discovery), by its real name:**

| Asked for | Found |
| --- | --- |
| The engine, the runner | `core/emergency/SosEngine` (`startCountdown(entryPoint)`), `SosRunner` (`start(entryPoint)`, `resume`, `cancel`, `markSafe`) |
| The entry point on the record | `SosEntryPoint { IN_APP, TILE, NOTIFICATION, WIDGET }` in `SosModel.kt`, stored as plain text in `sos_records.entryPoint` since P014a1. All four values existed and the column has no constraint: **no migration** |
| The consent gate | `SosAlertPolicy` (`alertsAllowed()`), on a phone `ConsentSosAlertPolicy` in `SosAlertsDevice.kt`: the notice version noted in `ContactsPreferences` equals `SOS_ALERTS_NOTICE_VERSION` |
| The emergency activity | `feature/emergency/EmergencyActivity`, shown over the lock screen, with `SosViewModel` and `SosOpenMode { OPEN, START, SAFE, PRACTICE }` |
| The in-app flow | `EmergencyDialog` with `EmergencyArm` (in `feature/home`), used by Home and Search; the hold opened the activity with `START` |
| The tile | `SosTileService`, which started `EmergencyShortcut.tileLaunch` (mode `OPEN`: the options) |
| The pinned notification and its receiver | `EmergencyNotification.kt` (`buildEmergencyNotification`, buttons "Call 112" and "Open SOS"), `EmergencyNotificationController`, `EmergencyShortcutReceiver` (boot and app update) |
| The service | `SosForegroundService` (type `location`), started by `AndroidSosHost` when the runner begins |
| Dispatch and alerts | `SosDispatch`, `SosAlerts` (unchanged here) |
| Contacts | `ContactsRepository`, `ActiveSosContacts` (unchanged here) |
| The notice | `contacts_notice_p1` to `p7`, mirrored in `docs/legal/sos-alerts-notice-v2.md` |
| Settings | `SettingsScreen.kt`: "Add the SOS tile", the notification switch |
| The manifest | One receiver, two services, `EmergencyActivity` not exported; no widget |

## 3. Workflow executed

1. `/start-prompt`: sync, PR #62 merged, Notion, branch.
2. Discovery (the table above) and the spike (section 7).
3. The use case, the intents, the tile, the notification, the screen for "not set up", the
   way to the setup; the widget; the notice sentence and version 3.
4. Tests; the whole Android gate once: 1,161 tests passed.
5. **Measured the size and split.** The full work was kept on a local branch
   (`wip/014c1-with-widget`, never pushed); this branch was made from `main` with
   everything but the widget.
6. Because of the split the notice sentence was reworded: it first named "tile, widget or
   notification", which would have described a widget that does not exist in this merge.
   It now says "a SafeRoute shortcut outside the app, such as its Quick Settings tile or
   its pinned notification", which is true now and still true with the widget.
7. The matrix, the manual scripts, ADR 0027, the Play draft, CLAUDE.md, the diagram.
8. The Android gate three times in a row (section 6 says what came before them);
   `/ship-prompt`.

Mistakes on the way, none of which reached a commit: a `git checkout` of one file threw
away my own uncommitted edit to it (re-applied); a gate run that I stopped left a file
lock, and the next run failed on it (section 6).

## 4. Changes

**`core/emergency/StartSos.kt`** (new): `StartSos(entryPoint)` asks `SosAlertPolicy`, then
`SosRunner.start`. Answers: `NotSetUp`, `Started`, `AlreadyRunning`.

**`feature/emergency`**

- `EmergencyShortcut`: `startIntent(entryPoint)` and `startPendingIntent(entryPoint)` (mode
  `START` plus the entry point), `openPendingIntent` (a notification's body),
  `entryPointOf(intent)`. `EmergencyEntry` is gone; `SosEntryPoint` is used throughout.
  Each PendingIntent has its own request code.
- `SosViewModel`: takes `StartSos` instead of the policy; `onShown(mode, fresh,
  entryPoint)`; the new state `SosUi.NotSetUp`; `onPractice()`.
- `EmergencyActivity`: reads the entry point from its intent; for `NotSetUp` shows the
  emergency dialog with Call 112, "SOS alerts are not set up yet", "Practice SOS" and "Set
  up SOS alerts", which asks for the unlock on a locked phone and then opens the app.
- `SosTileService`: unchanged code; through `tileLaunch` a tap now asks for a start from
  `TILE`.
- `EmergencyNotification`: the second button is "Start SOS" (`NOTIFICATION`); a tap on the
  body opens the emergency screen and starts nothing.

**`navigation/OpenRequests.kt`** (new), `MainActivity`, `SafeRouteNavHost`: "Set up SOS
alerts" opens the app at Emergency contacts. The intent carries one fixed word.

**`feature/home`, `feature/search`**: the completed hold uses `startIntent(IN_APP)`.

**Strings** (English and Bengali): 8 changed, none added. `sos_tile_subtitle`,
`settings_tile_add_supporting`, `settings_tile_help`, `settings_notification_off`,
`settings_notification_explain_what`, `shortcut_offer_body` (they said that the shortcuts
open the emergency options); `emergency_notification_open` renamed to
`emergency_notification_start`; `contacts_notice_p4` (one sentence added).

**Notice**: `SOS_ALERTS_NOTICE_VERSION` is `2026-10-alerts-draft3`;
`docs/legal/sos-alerts-notice-v3.md` (generated from the strings); version 2 is kept with
a banner.

**Docs**: ADR 0027 (note "Entry points"), the failure matrix, the manual scripts, the Play
foreground-service draft, CLAUDE.md, `android/README.md`, the diagram.

**No change**: the manifest, permissions, the database and its schema, build files, the
API contract, the backend, the SMS texts, the notification's title, text, channel and
importance, when it is posted, the countdown, the runner, the dispatcher.

## 5. Diagram

[`docs/diagrams/014c-sos-entry-points.svg`](../diagrams/014c-sos-entry-points.svg) (source
`.json`, also `.excalidraw` and `.png`): the four entry points, the one screen and use
case, the consent gate and its three outcomes. It covers both halves of part 1 and marks
the widget as P014c1b.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug --rerun-tasks`, run 1 | Pass: 1155 tests, 0 failures, 0 skipped (5 min 41 s) |
| The same, run 2 | Pass: 1155 tests, 0 failures, 0 skipped (4 min 32 s) |
| The same, run 3 | Pass: 1155 tests, 0 failures, 0 skipped (4 min 42 s) |
| `pnpm generate` (diagrams) | Pass |
| Every test named in the failure matrix exists | Pass: 294 names |
| markdownlint, link check, gitleaks | Pass (137 Markdown files; 200 links and anchors in the changed documents, 0 broken; no leaks) |

Before these three: one pass of the whole gate with the widget still in (1,161 tests), and
on this branch one run that failed with "Couldn't delete ... R.jar" (a file lock left by a
gate run I had stopped; no test ran), one pass, and one run that was stopped because the
machine ran low on memory. The three runs in the table were made afterwards, one after the
other, after `./gradlew --stop`.

`assembleRelease` was not run: no build file, manifest, `src/release` or `src/debug`
changed.

18 new tests (1137 before, 1155 now); 4 older tests rewritten for the new functions.

| Requirement (1.7) | Tests |
| --- | --- |
| Every entry point calls the same use case with the right entry point | `StartSosTest` "every entry point starts the same five-second countdown, and the record says which"; `SosViewModelTest` "the tile, the widget and the notification start the same countdown as the hold"; `SosEmergencyScreenTest` "the tile, the widget and the notification each open the countdown, and the record says which"; `SosTileServiceTest` "a tap asks the emergency destination to start an SOS from the tile, and nothing else"; `EmergencyNotificationTest` "call 112 opens the dialer and start SOS asks the emergency screen to start, both immutable"; `SosFlowTest` (the hold, `IN_APP`) |
| Nothing else can start an SOS | `StartSosTest` "nothing in the app starts the runner except the use case" (reads the sources) |
| Gate closed: the options and no countdown, for each entry | `StartSosTest` "with the gate closed no entry point starts a countdown or writes anything down", "the gate is asked at every start, not once"; `SosViewModelTest` "with alerts not set up every entry shows the options and the way to the setup, and no countdown"; `SosEmergencyScreenTest` "with alerts not set up a shortcut shows Call 112 and the way to the setup, and no countdown", "set up SOS alerts opens the app at emergency contacts and closes the emergency screen" |
| Gate open: the countdown, for each entry | The first row |
| Duplicate starts from two entries show the same SOS | `StartSosTest` "a second start from another entry point shows the same emergency", "a start in a new process picks up the emergency the old process left"; `SosViewModelTest` "a start from a second entry while an SOS runs shows the one that is running", "a re-created screen never starts an SOS from a shortcut's intent a second time" |
| Immutable PendingIntents, no trampoline | `EmergencyShortcutTest` "the pending intent starts an activity, is immutable, and differs per entry"; `EmergencyNotificationTest` (above) |
| The tile on a locked and an unlocked phone | `SosTileServiceTest` (above) and "on a locked phone the tap does the same, and never waits for the unlock"; `SosEmergencyScreenTest` "on a locked phone set up SOS alerts asks for the unlock first" |
| Notification actions | `EmergencyNotificationTest` (above) |
| The manifest list, no new permission | `MainActivityTest` and `EmergencyShortcutsManifestTest` (unchanged, pass: the manifest did not change) |
| Logging | `SosTileServiceTest` (nothing in Logcat); `SosCoreBoundaryTest` (unchanged; it covers `StartSos.kt`) |
| String parity EN/BN | `StringResourceParityTest` (unchanged, passes) |
| Font scale 2.0, Bengali | `SosEmergencyScreenTest` "in Bengali at double font size the not-set-up dialog still reaches Call 112 and the setup" |
| The notice | `ContactsNoticeDocumentTest` (now reads version 3); `SosAlertsDeviceTest` "the gate opens only for consent to the notice the app shows today" (version 2 no longer opens it), "the notice version is the third one, and differs from the first two" |
| The SOS control never overlapping | Not touched: no Home or Search layout changed, and their existing tests pass |
| Widget provider and clicks | **P014c1b** |

**Failure matrix** (`docs/sos/failure-matrix.md`)

| Row | What changed | Status now |
| --- | --- | --- |
| A1, SOS from the tile while locked | Built: the tile starts the countdown through the use case; tests added | **Manual pending · Waiting on P014c** (the widget). "Automated pass" once `android-ci` is green on `main` with the new tests |
| A2, pinned notification | Its button starts an SOS; tests added | Automated pass · Manual pending (posting again, unchanged); the new tests count after the merge |
| A3, tile unavailable | No behaviour change; script M19 updated for the new texts | Automated pass · Manual pending (unchanged) |
| A4, notification permission denied | Nothing changed | Automated pass · Manual pending (unchanged) |
| 6, duplicate trigger | A second start from another entry point; tests added | Automated pass · Manual pending (unchanged); the new tests count after the merge |
| 2, no signal | No code change | Automated pass · Manual pending; its retry tests of P014b7 count now (run 38285365927) |
| A8, SMS fails for every contact | No code change | **Automated pass · Manual pending**: the tests of P014b7 are on `main` and the run was green |
| All others | Unchanged | Unchanged |

"Accepted risks" gained item 7 (a start nobody meant). Script M17 was written (it was a
draft) and moved to the scripts for what is built; its widget part stays a draft. M18 and
M19 were updated, and the safety rules gained a paragraph about the tile.

## 7. Decisions & ADRs

ADR 0027, note of 2026-10-12 (P014c1a), "Entry points".

**The spike (1.0).** Sources: the Android guide "Restrictions on starting activities from
the background" and the AndroidX release page of Glance (both read on 2026-10-12), and the
platform sources of API 37.

| Topic | Finding | Certainty |
| --- | --- | --- |
| `startActivityAndCollapse` | The PendingIntent form from Android 14; the Intent form throws there and is the only one before. Already handled since P012f1 | Certain |
| `isLocked`, `isSecure`, `unlockAndRun` | A tile whose action is safe while locked starts an activity over the lock screen; otherwise `unlockAndRun` | Certain. What each phone maker allows on its lock screen: not recorded |
| The emergency activity over the lock screen | `showWhenLocked`, `setTurnScreenOn` (P014a3), unchanged | Certain |
| Starting the `location` service | While an activity is visible: the emergency screen, for every entry point | Certain for a visible activity; over the lock screen on each phone: a phone check |
| Notification actions | An activity PendingIntent sent by the system is allowed; a receiver or service in between is not (Android 12) | Certain |
| Widget taps | Allowed: a launch started by the launcher | Certain |
| PendingIntent rules of Android 14 and 15 | The sender (system, launcher) has the right to start activities itself | Certain for notifications and widgets; the guide is silent on tiles |
| Glance or RemoteViews | Glance is stable (1.2.0). RemoteViews chosen: no new library for one static button | Certain |
| A widget on the lock screen | Home screen only unless declared otherwise; some lock-screen features may ignore the declaration | Uncertain per phone |

**Decided.**

- One use case, called from one place: the emergency screen's ViewModel, in the app's
  scope. The shortcuts only open that screen. So the service starts while a screen is
  visible, the same for all of them.
- Gate closed means no countdown and no record: Call 112, practice and the way to the
  setup. A practice run is kept there because it touches nothing, as in the app.
- The tile never asks for the unlock before the countdown.
- A tap on the notification's body does not start an SOS: its text says "Tap for the
  emergency options", and the prompt asks to leave the text. Only the button starts.
- **1.5, no confirmation step**: the countdown with its vibration and the large Cancel is
  the safeguard, as the prompt decided. The risk is in the ADR and a follow-up.
- **1.6, the notice**: version 2 says "Nothing is sent unless you start an SOS" and
  describes no way of starting one. A single tap on a locked phone is a different thing
  from the two-second hold a user knows from the app, so one sentence was added and the
  version is 3. A consent for version 2 no longer opens the gate.
- "Set up SOS alerts" needs a way from the emergency activity into the main one:
  `OpenRequests`, a yes-or-no kept in memory, read by the navigation host.

## 8. Security & privacy notes

- **No new permission**; the manifest is unchanged.
- The intents to the emergency screen carry a mode and an entry point; the one to the main
  screen carries a fixed word. Nothing about the user.
- Every PendingIntent is immutable and starts an activity directly.
- On the lock screen: the countdown, fixed text, counts. The not-set-up dialog says that
  SOS alerts are not set up, which is a status. "I'm safe" and "Set up SOS alerts" ask for
  the unlock.
- `MainActivity` is exported (the launcher needs that), so another app could send the
  "open contacts" word. It only navigates inside a signed-in app.
- Nothing is logged by the new code. No network code was added.
- The notice text is a draft for a lawyer; the Bengali for a native speaker.

## 9. Known issues & risks

- **Unverified on a phone**, in particular the lock screen.
- **A start nobody meant.** One tap on the tile or on the notification's button starts
  the countdown, on a locked phone too, by anyone who holds it. Five seconds later the
  contacts are texted (in a debug build: only the numbers on its list). Not measured.
- **A tester who agreed to notice version 2 cannot start an SOS** after installing this
  build until they agree to version 3 (Emergency contacts → "Read how SOS alerts work").
  Nothing tells them beforehand; the readiness screen is P014c2.
- **The notice says "shortcut outside the app" and names two.** The widget (P014c1b) is a
  third; the sentence covers it, and the lawyer should say whether it must be named.
- Some phones hide Quick Settings on the lock screen or ask for the unlock themselves; the
  app cannot change that.
- The widget does not exist yet. Row A1 keeps "Waiting on P014c".
- 8 Bengali strings changed and are drafts: **they need human review before release**
  (`sos_tile_subtitle`, `settings_tile_add_supporting`, `settings_tile_help`,
  `emergency_notification_start`, `settings_notification_off`,
  `settings_notification_explain_what`, `shortcut_offer_body`, `contacts_notice_p4`).
- About 815 changed lines in `android/` (344 of app code and strings, 471 of tests).

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P014c1a):

- Measure starts nobody meant in the closed test, by entry point (ADR 0027).
- Phone check with script M17 on the launch set of phones; Bengali review of 8 strings.
- Matrix rows A1, A2 and 6: count the new tests after `android-ci` is green on `main`.
- Lawyer: notice version 3, and whether every shortcut must be named in it.
- A way to add the widget from Settings (P014c1b decides).

**For P014c1b (the widget).** It is written: on the local branch `wip/014c1-with-widget`
are `SosWidgetProvider.kt`, `res/layout/emergency_widget.xml`, two drawables,
`res/xml/emergency_widget_info.xml`, four colours, the manifest entry, three strings and
`SosWidgetTest.kt` (7 tests). It needs: a branch from `main` after this merge, the
manifest test for two receivers, `assembleRelease` (the manifest and resources change),
row A1, part 7 of script M17, the Play draft's flow, and the ADR's widget lines confirmed.
If that local branch is lost, the widget is rebuilt from this description.

**Then** P014c2 (readiness) and P014c3 (the notice after a restart, sign-out safety), each
on Rahul's word.

## 11. How Rahul can verify

1. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug`.
2. Read `core/emergency/StartSos.kt` (47 lines) and the note "Entry points" in ADR 0027.
3. On the phone, **with an account whose only contact is your second phone, and the debug
   list set** (or an account with no contacts), run script M17, parts 1 to 6:
   - agree to the new notice first (Emergency contacts → "Read how SOS alerts work");
   - tile, unlocked: the countdown at once; Cancel;
   - tile, locked: the countdown over the lock screen; nothing of the account on it;
   - the notification's "Start SOS"; then a tap on its body (options only);
   - two entries during one countdown: one SOS;
   - without the consent: Call 112 and "Set up SOS alerts", no countdown.
4. Report: the phone, whether the tile can be tapped on the lock screen, whether the
   location line appeared when started from the lock screen, and any start you did not mean.

## 12. Learning notes

- **Use case.** One class for one thing the app does ("start an SOS"), so that four
  buttons cannot do it in four slightly different ways.
- **PendingIntent.** A permission slip handed to the system: "start exactly this for me,
  later". Immutable means the holder cannot change it. Android tells two of them apart by
  the request code and the target, not by their extras.
  [Docs](https://developer.android.com/reference/android/app/PendingIntent)
- **Trampoline.** A receiver or service that only starts an activity. Android 12 forbids
  it for notification taps, so a button must name the activity itself.
- **Quick Settings tile.** A `TileService` that Android binds for a moment when the tile is
  tapped. It may start an activity over the lock screen when that is safe.
  [Docs](https://developer.android.com/develop/ui/views/quicksettings-tiles)
- **Foreground services and visibility.** A service of the type `location` may be started
  only while a screen of the app is visible. That is why every shortcut opens the
  emergency screen first and the screen starts the SOS.
  [Docs](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- **Starting activities from the background.** An app may not open a screen by itself;
  a tap on its notification, tile or widget is the user's doing, so the system does it.
  [Docs](https://developer.android.com/guide/components/activities/background-starts)
- **`showWhenLocked` and the keyguard.** An activity can ask to be drawn on top of the lock
  screen. It unlocks nothing; `requestDismissKeyguard` asks the user for the unlock.
- **`onNewIntent`.** When an activity that is already open is started again, Android hands
  it the new intent instead of making a second copy.
