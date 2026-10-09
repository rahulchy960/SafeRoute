# P014a3: SOS on the device: arm step, countdown, active and recovery screens, practice mode

| Field | Value |
| --- | --- |
| Prompt | P014 · part a, third and last piece (P014a1 records and logic; P014a2 what Android runs; **P014a3 what the user sees**; then P014b SMS, P014c entry points) |
| Milestone | M5 (depends on P014a2, merged as `df993f1`) |
| Branch | `feat/014a3-sos-screens` |
| PR title | `feat(sos): hold-to-arm, countdown, active and recovery screens, practice mode [P014a3]` |
| Notion | [P014a3 row in the Prompt Log](https://app.notion.com/p/3f4073707720813db032da28a8dfde27) |
| Date | 2026-10-09 |
| Plan refs | Plan v7 §3.2 F-08, §7.1–7.5, §12.2, §12.3; addendum v7.1 section B; ADRs 0008, 0015, 0027 |

> **Part a of P014 is complete with this piece.** From here an SOS can be started in the app
> (SOS control → hold for 2 seconds). It runs the countdown, the location trail on the phone
> and the active screen. **It messages nobody** (P014b brings the SMS), and the tile, the
> pinned notification and the widget do not start it yet (P014c).
>
> **Not verified here:** anything on a phone. All tests run on the JVM (Robolectric). The
> manual scripts M1 to M10 in `docs/sos/failure-matrix.md` are the phone checks of part a.
>
> **Not built, moved to P014c:** the sound setting for the countdown (vibration only is the
> default and, for now, the only mode). It belongs to the readiness screen.
>
> **Size:** about 2,300 added lines outside the generated diagram files (source 890, strings
> 80, tests 820, documents 510). Over the ~800-line guide; the screens could not be cut further
> without leaving an SOS that starts but cannot be cancelled or ended.

## 1. Objective

Give the SOS its screens: the arm step in the app, the countdown, the active emergency, the
questions after the app was closed or the phone restarted, "I'm safe", and a practice mode,
in English and Bengali, usable with TalkBack, at 200% font size and in landscape.

## 2. Context & prerequisites

- Preflight: `main` synced at `df993f1`; PR #54 (P014a2) merged; no open pull requests; hook
  path `.githooks`. Notion: P014a2 set to Merged, a new row for P014a3.
- From P014a2: `SosRunner` (start, cancel, sendNow, markSafe, resume), `SosTrail.report()`,
  the running-SOS notification, `FakeSosDeviceModule`.
- Before this part the SOS control opened a dialog with Call 112 on Home and Search, and the
  tile and the pinned notification opened `EmergencyActivity` with the same dialog.

## 3. Workflow executed

1. `/start-prompt`: sync, PR #54 merged, Notion, branch.
2. Read how Home, Search and `EmergencyActivity` open the dialog, and the tests around them.
3. `SosViewModel`, `SosScreens.kt`, `EmergencyActivity` rewritten as the emergency screen,
   `EmergencyDialog` extended with the arm part, wiring in Home, Search and `MainActivity`,
   "I'm safe" on the notification, strings in both languages.
4. Tests. Two failures on the way, both fixed in the product and not in the test:
   - a string test crashed on a `%1$d` text (the test read it through a formatter);
   - `AppNavigationTest` failed because, with the arm part, the "no phone app" sentence had
     moved below the visible part of the dialog. That was a real fault: the sentence is now
     the first thing in the dialog.
5. Docs: notes in ADR 0027 and ADR 0015, CLAUDE.md, the failure matrix with the manual
   scripts, a flow diagram. `/ship-prompt`.

## 4. Changes

**`feature/emergency`**

- `SosScreens.kt` (new): `HoldToArmButton`, `SosCountdownScreen`, `SosActiveScreen`,
  `SosAskScreen`, `SosPracticeFinishedScreen`, and the frame they share (PRACTICE banner,
  "SafeRoute is not an emergency service. Call 112.", the Call 112 button).
- `SosViewModel.kt` (new): `SosUi`, `SosOpenMode`; commands go to `SosRunner` in the app's
  scope; a practice run lives here in memory.
- `EmergencyActivity`: now the emergency screen (Hilt entry point). Over the lock screen,
  screen switched on, kept on during a countdown and an active SOS; the unlock before
  "I'm safe". With nothing running it shows the dialog as before.
- `EmergencyShortcut`: `modeIntent`, `safePendingIntent`.
- The running-SOS notification gains "I'm safe" once active (it only opens the screen).

**`feature/home`, `feature/search`**: `EmergencyDialog` takes an optional `EmergencyArm`
(hold, practice, the location note); Home and Search pass one and open the emergency screen.
The "no phone app" sentence is now first in the dialog.

**`MainActivity`**: on start, opens the emergency screen when the phone remembers an
emergency that the runner is not running.

**Strings**: 36 new in English and Bengali; `emergency_dialog_title` and
`emergency_dialog_body` changed ("SOS is not available yet" is no longer true).

**No change**: manifest, permissions, database, API contract, backend, the tile, the pinned
notification.

## 5. Diagram

[`docs/diagrams/014a3-sos-screens-flow.svg`](../diagrams/014a3-sos-screens-flow.svg)
(source `.json`, also `.excalidraw` and `.png`): from the SOS control to the emergency
screen and back, and the three recovery cases.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` | Pass: 971 tests, 0 failures, 0 skipped; lint without errors |
| `./gradlew assembleRelease` | Not run: no build file, `src/release` or `src/debug` change |
| `cd tools/diagrams && pnpm generate` | Pass; only the new diagram's files changed |
| markdownlint, gitleaks, relative links | See the pull request |

New tests (40): `SosViewModelTest` (14), `SosScreensTest` (19), `SosFlowTest` (4),
`SosEmergencyScreenTest` (2), one in `SosDeviceTest`. The hold is tested with the Compose
test clock (1.8 s arms nothing, 2.3 s arms once); the ViewModel with the real runner on
fakes in virtual time; the two flow tests with the real app graph.

**Failure matrix** (full table, test names and manual scripts: `docs/sos/failure-matrix.md`):

| Row | Covered here | Status |
| --- | --- | --- |
| 1 No mobile data | No | P014b |
| 2 No signal | Call 112 on every SOS screen, also without a phone app | Automated pass at logic, component and screen level; manual pending (M5) |
| 3 API down | No | P015 |
| 4 App killed | The three recovery screens; the app opens them when it comes to the front; a re-created screen starts nothing | Automated pass at data, logic, component and screen level; manual pending (M4, M6) |
| 5 Phone rebooted | The same on app open | Automated pass at data, logic and screen level; boot notice P014c; manual pending (M6) |
| 6 Duplicate trigger | The hold arms once; a second screen starts nothing; practice never replaces a real SOS | Automated pass at data, logic and screen level; manual pending (M2) |
| 7 SEND_SMS denied | No | P014b |
| 8 No GPS fix | A sentence for every location state | Automated pass at logic and screen level; manual pending (M5) |
| 9, 10 | No | P015 |
| 11 Accidental trigger | Early release, Cancel (72 dp), back is not Cancel, "I'm safe" with unlock and confirmation | Automated pass at data, logic, component and screen level; manual pending (M1, M2, M3) |
| Tile while locked | The emergency screen holds statuses only | Automated pass for the screen; countdown from the tile P014c |
| Notification permission denied | The active screen says so | Automated pass at logic and screen level; manual pending (M7) |

**Part a against the prompt's A10 list:** state machine, persistence and restore in every
state, hold-to-arm timing, countdown, cancel is local-only, double trigger, location
fallbacks, recovery cases, practice mode, UUID v7, retention purge and sign-out wipe, labels,
72 dp cancel, font 2.0, string parity, no MapLibre outside `core/map`: covered across a1 to
a3. **Weaker than asked:** "lock-screen redaction" is tested as "the screen holds no name,
number or place and the way out asks for the unlock", because part a has no contact line to
redact; the per-contact lines arrive with P014b and need their own test. The "capture test"
for logs is a source test (no log call exists in the SOS code), not a capture of Logcat.

## 7. Decisions & ADRs

Note of 2026-10-09 (P014a3) in [ADR 0027](../adr/0027-sos-device-flow.md). The ones that
differ from, or add to, the prompt:

- **A dialog, not a sheet**, for the arm step: the existing emergency dialog with the hold,
  the practice button and the location note added.
- **"Hold to start SOS" and "Start SOS now"**, not "send": nobody is messaged until P014b,
  and CLAUDE.md forbids a text that says otherwise. A test enforces it.
- **The hold has an accessibility action** that starts the countdown without holding.
- **Back is not Cancel** during the countdown and on the question.
- **The question after a late countdown cannot be dismissed**; it comes back until answered.
- **The stale age does not tick** on the screen; it is read when the screen appears.
- **The sound setting is not built** (P014c).
- **"SOS is still active" is a dialog on the active screen**, after the runner has picked the
  emergency up again (the trail restarts at once), not a gate before it.

## 8. Security & privacy notes

- No new permission, no manifest change, no network call, nothing sent.
- The location disclosure for the trail is `sos_arm_location_note`, shown in the dialog
  before anything can start. A draft for the lawyer.
- The emergency screen is shown over the lock screen: it holds fixed texts, a number that
  counts down and statuses in words. Ending an SOS needs the unlock and a confirmation; the
  notification's "I'm safe" only opens the screen.
- A practice run reads no contact, starts no service and stores nothing.
- No text says that a contact is or will be messaged (tested).
- `EmergencyActivity` is still not exported.

## 9. Known issues & risks

- Unverified on a phone: the hold and its ticks, the screen over the lock screen, the
  unlock, the vibration, what a phone maker does to the service.
- The SOS can be started by a signed-in user with no contact and no location permission; it
  then records nothing useful and messages nobody. The readiness checklist (P014c) and the
  SMS (P014b) change that. Until then the texts say what happens.
- The age of a stale position is not updated while the screen stays open.
- Signing out during an SOS still wipes the records under the runner (follow-up).
- If the user leaves the question unanswered, it returns each time the app comes forward.
- 38 Bengali strings are drafts (36 new, 2 changed): every `sos_arm_*`, `sos_practice_*`,
  `sos_countdown_*`, `sos_active_*`, `sos_location_*`, `sos_safe_*`, `sos_recovered_*`,
  `sos_ask_*`, `sos_not_emergency_service_call`, `emergency_dialog_title`,
  `emergency_dialog_body`. **Needs human review before release.**

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P014a3):

- Run the phone scripts M1 to M10 and record the results (make, model, Android version).
- Lawyer review: the location note of the arm dialog, with the other SOS texts.
- Bengali review of 38 SOS strings.
- Hold-duration tuning after phone tests (2 seconds today).
- A ticking age for a stale position on the active screen.

**For P014b:** send at `SosRunner.onTriggered` (after the engine said yes), from
`ActiveSosContacts.current()`; fill `sos_actions`; put the per-contact lines on the active
screen with counts only when `locked`, and test that; replace `sos_active_contacts_none`,
`sos_arm_location_note` and the "start" wordings when alerts are real, together with notice
version 2; "I'm safe" gains the "Tell my contacts" checkbox in the confirmation dialog.

## 11. How Rahul can verify

Use a debug build on the phone. Nobody is messaged, so no one needs a warning.

1. Home → SOS control: the dialog shows "Hold to start SOS", a note about location,
   "Practice SOS" and Call 112.
2. Run the scripts **M1, M2, M3 and M8** in `docs/sos/failure-matrix.md` (hold and early
   release, cancel, the full countdown with a lock, practice). They take about ten minutes.
3. When there is time: M4 to M7 and M9 (swipe away, airplane mode and location off, force
   stop and reboot, notifications refused, Do Not Disturb, Bengali, large font, TalkBack).
4. Tell me the phone and Android version and what differed. Add screenshots of the dialog,
   the countdown and the active screen (light, dark, Bengali) to the pull request.
5. Say "P014b" for the SMS alerts.

## 12. Learning notes

- **A second activity.** Most of the app is one activity with Compose screens. The
  emergency screen is its own activity because it has its own rules: it may appear over the
  lock screen (`showWhenLocked`), and it must not carry the map or the account with it.
- **Keyguard.** Android's name for the lock screen. `requestDismissKeyguard` asks the system
  to show the unlock; the app never sees the PIN and only learns whether it worked.
- **`BackHandler`.** Lets a Compose screen decide what the back gesture does; here: nothing,
  so that leaving cannot be mistaken for cancelling.
- **Pointer input and `Animatable`.** `detectTapGestures(onPress = ...)` tells the button
  when a finger goes down and waits for it to lift; an `Animatable` moves a value from 0 to 1
  in 2 seconds, and lifting the finger cancels that animation.
- **Semantics.** What TalkBack reads and can do. The hold button has a name and an "on
  click" action of its own, because a held finger is not something every person can do.
- **`rememberSaveable` and "fresh".** Android may destroy and re-create a screen at any time
  and hands it the saved state. `savedInstanceState == null` means "created for a new
  reason"; only then may the screen start something.
- **ViewModel scope against app scope, again.** The practice countdown runs in the
  ViewModel's scope because it should die with the screen; a real command runs in the app's
  scope because it must not.
- **The Compose test clock.** `mainClock.advanceTimeBy(1800)` moves animations forward
  without waiting, which is how "released after 1.8 seconds" is tested exactly.
