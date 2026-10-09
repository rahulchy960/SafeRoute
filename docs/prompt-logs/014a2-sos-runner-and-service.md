# P014a2: SOS on the device: the runner, the foreground service and the location trail

| Field | Value |
| --- | --- |
| Prompt | P014 · part a, second of three (P014a1 records and logic; **P014a2 what Android runs**; P014a3 what the user sees; then P014b SMS, P014c entry points) |
| Milestone | M5 (depends on P014a1, merged as `548e722`) |
| Branch | `feat/014a2-sos-runner-and-service` |
| PR title | `feat(sos): SOS runner, location foreground service, location trail and daily purge [P014a2]` |
| Notion | [P014a2 row in the Prompt Log](https://app.notion.com/p/3f4073707720816c8c0ae39f8a8d39d1) |
| Date | 2026-10-09 |
| Plan refs | Plan v7 §3.2 F-08, §5.3, §7.1–7.5, §12.2, §12.3; ADRs 0008, 0015, 0027 |

> **P014a is split a second time.** The P014a1 log announced the service and the screens for
> this part. Together they are about 2,800 lines, so the cut is: **this part is what Android
> runs** (the countdown runner, the foreground service and its notification, the location
> trail and its fallbacks, the vibration, the daily clean-up job, the manifest); **P014a3 is
> what the user sees** (the arm sheet with hold-to-arm, the countdown, active and recovery
> screens, practice mode, 112 on every SOS screen, lock-screen redaction, accessibility, the
> location disclosure for this use, the manual phone scripts).
>
> **Nothing can start an SOS from a screen yet.** So this part ships without harm and without
> anything new to try on a phone, except that the app installs and opens as before.
>
> **Size:** see section 6. Over the ~800-line guide again; half is tests.
>
> **Not verified here:** anything on a phone: that the service starts, survives and gets
> positions, that the phone vibrates, that the daily job runs. All tests run on the JVM.

## 1. Objective

Make a stored emergency run: a 5-second countdown that belongs to the app's process and not
to a screen, a trigger at its end that happens once, a foreground service that keeps the
process alive, a location trail that never blocks the SOS, and a daily clean-up job. No
screen, no message and no server call.

## 2. Context & prerequisites

- Preflight: `main` synced at `548e722`; PR #53 (P014a1) merged; no open pull requests; hook
  path `.githooks`. Notion: P014a1 set to Merged, a new row for P014a2.
- From P014a1: `SosEngine`, `SosStore`, the three tables, `recoveryFor`, `SosHousekeeping`.
- From the A0 spike (ADR 0027): a `location` foreground service cannot be created without a
  location permission or from the background. That finding shaped this part.
- WorkManager 2.12.0 is the current stable release on Google Maven (read on 2026-10-09).

## 3. Workflow executed

1. `/start-prompt`: sync, PR #53 merged, Notion, branch (renamed to match the split).
2. Read the emergency, notification and location code; decided the split (above).
3. `core/location`: a second location source for the trail.
4. `core/emergency`: `SosTrail`, `SosRunner`, the scheduler interface.
5. `feature/emergency`: the service and its notification, the host, vibration, battery, the
   purge worker, the Hilt module. Manifest and strings.
6. Tests. Three things failed on the way and were fixed, not hidden:
   - every Robolectric test failed with "WorkManager is not initialized": a JVM test has no
     app start-up that sets WorkManager up. The scheduler now returns quietly when
     WorkManager is not there (the clean-up at app start has already run);
   - `SosColourUsageTest` failed on the job's name, which contained the three letters as a
     word: the job was renamed;
   - `EmergencyShortcutsTest` and `MainActivityTest` pinned "no foreground service" and the
     old permission list. Both were **changed on purpose**: this prompt asks for the service
     and the three permissions. They now pin the new exact list and that the one foreground
     service is this one.
7. Docs: notes in ADR 0027 and ADR 0015, CLAUDE.md, the failure matrix, a second diagram.
8. `/ship-prompt`.

## 4. Changes

**`core/location`**: `TrailLocationSource` (interface) and `FusedTrailLocationSource`
(in `FusedLocation.kt`, still the only file that uses Play services location). A second
source because the map's source stops when the map leaves the screen.

**`core/emergency`**

- `SosTrail.kt`: the trail and its modes; `locationReport` (precise, approximate, stale with
  age, searching, no permission, unavailable); `trailIntervalMillis`; `BatteryLevel`.
- `SosRunner.kt`: `start`, `cancel`, `sendNow`, `markSafe`, `resume`; `SosRunState`
  (Idle, Countdown with seconds left, Active); the interfaces `SosHost` and `SosHaptics`.
- `SosHousekeeping`: also plans the daily job (`PurgeScheduler`).

**`core/data`**: `RoomSosStore.addPoint` drops a point whose record was just deleted instead
of failing.

**`feature/emergency`**

- `SosForegroundService.kt`: the service (type `location`, `START_NOT_STICKY`), the channel
  and notification, `AndroidSosHost` (service, or a plain notification as the fallback).
- `SosDevice.kt`: `AndroidSosHaptics`, `AndroidBatteryLevel`, `SosPurgeWorker`,
  `WorkManagerPurgeScheduler`, `SosDeviceModule`.

**Manifest**: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `VIBRATE`; the service
declaration. Merged in from WorkManager: `WAKE_LOCK` (and its own components).

**Build**: `androidx.work:work-runtime` 2.12.0, `work-testing` in tests. Apache-2.0.

**Strings** (EN and BN): `sos_running_channel`, `sos_running_channel_description`,
`sos_running_title_countdown`, `sos_running_title_active`, `sos_running_text`.

**No change**: screens, navigation, the tile, the pinned notification, API contract, backend,
database tables.

## 5. Diagram

[`docs/diagrams/014a2-sos-runner-and-service.svg`](../diagrams/014a2-sos-runner-and-service.svg)
(source `.json`, also `.excalidraw` and `.png`): who starts an emergency, what runs it, the
phone's parts and where the data goes. The state diagram of P014a1 is unchanged.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` | Pass: 931 tests, 0 failures, 0 skipped; lint without errors |
| `./gradlew assembleRelease -Psaferoute.apiBaseUrl=https://example.invalid/` | Pass (run because build files and the manifest changed) |
| `cd tools/diagrams && pnpm generate` | Pass; only the new diagram's files changed |
| markdownlint, gitleaks, relative links | See the pull request |

Size: about 2,360 added lines outside the generated diagram files (source 860, tests 970, documents and build files 530).

New tests: `SosRunnerTest` (14), `SosTrailTest` (8), `SosDeviceTest` (8),
`AndroidSosHostTest` (2), `SosForegroundServiceTest` (3). Changed: `MainActivityTest`
(permission list), `EmergencyShortcutsTest` (the one foreground service),
`SosHousekeepingTest` (the daily job is planned once).

The runner tests use virtual time and a clock that follows it (`SchedulerClock`). "Process
death" is acted out by cancelling everything the old objects were running and building new
ones on the same store.

**Failure matrix** (full table with test names: `docs/sos/failure-matrix.md`, updated):

| Row | Covered here | Status |
| --- | --- | --- |
| 1 No mobile data | No | P014b |
| 2 No signal | The run needs no network; Call 112 on the notification | Automated pass at logic and component level; screen P014a3; manual pending |
| 3 API down | No | P015 |
| 4 App killed | A new process finishes the same countdown, asks about a late one, picks up an active one; the service follows the runner | Automated pass at data, logic and component level; survival on a phone manual pending |
| 5 Phone rebooted | Same logic with a later clock | Automated pass at data and logic level; boot notice P014c |
| 6 Duplicate trigger | A second start starts nothing; one trigger | Automated pass at data and logic level |
| 7 SEND_SMS denied | No | P014b |
| 8 No GPS fix | Last known with its age, stale after 30 s, approximate, mock flag, never blocks | Automated pass at logic level; manual pending |
| 9, 10 | No | P015 |
| 11 Accidental trigger | Cancel stops everything and leaves nothing; afterwards only "I'm safe" | Automated pass at data, logic and component level |
| Notification permission denied | The run does not depend on the notification | Automated pass at logic level; the screen P014a3 |

**Requirements of part a still open** (all P014a3): A4 screens, A5 practice mode, A6 112 on
every SOS screen, the screens of A7, "I'm safe" on the notification, the Active screen's
notice when notifications are off, lock-screen redaction, accessibility, the sound setting,
the manual phone checks.

## 7. Decisions & ADRs

Note of 2026-10-09 in [ADR 0027](../adr/0027-sos-device-flow.md) and in
[ADR 0015](../adr/0015-map-stack-and-location-policy.md), "Location policy". The ones that
differ from, or add to, the prompt:

- **The runner owns the countdown, the service hosts it.** The prompt says the service owns
  the timer. A `location` service cannot start without a location permission, and location
  must never block an SOS (A8), so the timer cannot live inside the service class.
- **No location permission → no service**, a plain notification instead (known limit: the
  process may be ended sooner).
- **Not sticky**, as the prompt asks; recovery from the record is the way back.
- **Nothing resumes in the background**; `resume()` is for a visible screen.
- **The notification has one button, Call 112.** "I'm safe" comes with the screen that can
  ask for the unlock.
- **The trail starts with the countdown**, so a position is ready at the trigger; a cancel
  deletes it with the record.
- **The battery is checked at every position**, not only at the start.
- **`WAKE_LOCK`** arrives through WorkManager; the prompt's list did not have it.
- The scheduler swallows "WorkManager is not initialized" (tests); on a phone WorkManager
  sets itself up at app start.

## 8. Security & privacy notes

- **Location use changes** (ADR 0015 note): during an SOS the user started, positions are
  collected while the app is not in front and are **stored on the phone** for up to 30 days.
  Nothing is sent anywhere. No `ACCESS_BACKGROUND_LOCATION`. The SOS never asks for a
  permission. The disclosure wording for this use is owed with the screens (P014a3), before
  anything can start an SOS; it is a draft for the lawyer.
- New permissions, all granted at install without a dialog: `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_LOCATION`, `VIBRATE`, and `WAKE_LOCK` from WorkManager.
  `MainActivityTest` pins the exact list and that `SEND_SMS`, `READ_SMS`, `READ_CONTACTS`,
  `CALL_PHONE`, `ACCESS_BACKGROUND_LOCATION` and the battery-optimisation permission are
  absent.
- Google Play will ask for a declaration of the `location` foreground service (drafts are
  part of P014b, B7).
- The notification holds fixed text only (tested): no name, number, place or count.
- No log line and no network code in the new files (source tests); the types that hold a
  position hide it in `toString()` (tested).
- The service is not exported.

## 9. Known issues & risks

- Unverified on a phone (see the top). The foreground-service start, the trail in the
  background and the vibration are exactly the things JVM tests cannot show.
- Without a location permission there is no foreground service.
- A sign-out during a running emergency wipes the records, but the runner in memory still
  shows it until the process ends (follow-up from P014a1, now with this detail).
- `SosForegroundServiceTest` uses the real clock and real threads for two short checks; it
  waits up to 5 seconds for the service to stop. If it ever flakes, look there.
- Vibration under Do Not Disturb is the phone's decision (not verified).
- 5 Bengali strings are drafts: `sos_running_channel`, `sos_running_channel_description`,
  `sos_running_title_countdown`, `sos_running_title_active`, `sos_running_text`. **Needs
  human review before release.**

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P014a2):

- Play Console declaration for the `location` foreground service (with B7).
- An SOS without a location permission has no foreground service: accept, or find a type
  Play allows.
- Bengali review of the five notification strings.
- Sign-out while the runner is active (adds to the P014a1 follow-up).

**For P014a3:** inject `SosRunner`; `start(SosEntryPoint.IN_APP)` after the hold;
collect `runner.state` for the countdown and active screens; `cancel()`, `markSafe()`,
`sendNow()`; call `resume()` in `onStart` of the screens and show its three answers;
`SosTrail.report()` for the location line; `NotificationGate` for "notifications are off";
change `EmergencyActivity` (the destination of the notification) into the SOS screen and
add "I'm safe" to the notification; write the location disclosure for the trail; add the
new red surfaces to `SosColourUsageTest`; write the manual scripts into the failure matrix.

## 11. How Rahul can verify

1. Install this branch's debug build over the current one. The app opens as before; no new
   permission dialog, no notification, no vibration.
2. Settings → Apps → SafeRoute → Permissions: nothing new is listed.
3. Read the note of 2026-10-09 in ADR 0027 and the new diagram.
4. Nothing else can be tried until P014a3. Say "P014a3".

## 12. Learning notes

- **Foreground service.** A part of the app that Android keeps running with no screen open,
  as long as it shows a notification. It must say what it is for (`foregroundServiceType`);
  `location` is the only type that keeps positions arriving in the background. See
  developer.android.com, "Foreground services".
- **Why the service is a shell.** Android creates and destroys a service; code inside it is
  hard to test and cannot run when Android refuses the service. The logic lives in a plain
  object (`SosRunner`) and the service only follows it.
- **`START_NOT_STICKY`.** What a service returns to say "if you kill me, do not start me
  again by yourself".
- **Notification channel.** The category the user sees under Settings → Notifications;
  importance "low" means silent and no pop-up.
- **PendingIntent.** A ticket that lets the system start something of the app later (a tap
  on a notification). Immutable: whoever holds it cannot change what it starts.
- **WorkManager.** Runs a job later, also after the app was closed or the phone restarted,
  when Android finds it convenient. "Unique periodic work" with KEEP means: one such job, and
  asking again changes nothing. See "Schedule tasks with WorkManager".
- **Hilt entry point.** A worker is created by Android, not by Hilt, so it fetches what it
  needs from Hilt through a small interface.
- **App-lifetime scope against a screen's scope.** Work started in a screen's scope is
  cancelled when the screen goes; a countdown must not be. (The same lesson as P009d.)
- **Virtual time in tests.** `delay(5000)` takes no real time in `runTest`; the test moves
  the clock forward and checks what happened at each millisecond.
