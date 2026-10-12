# P014c3: the notice after a restart, and safe sign-out during an active SOS

| Field | Value |
| --- | --- |
| Prompt | P014c, part 3 (restart notice and sign-out safety): the last part of P014c and of the device side of SOS |
| Milestone | M5 (follows P014c2c, merged as `5bd7092`) |
| Branch | `feat/014c3-sos-boot-recovery` |
| PR title | `feat(sos): notice after a restart and safe sign-out during an active SOS [P014c3]` |
| Notion | [P014c3 row in the Prompt Log](https://app.notion.com/p/3f707370772081c1bca1e3ed2a872fe3) |
| Date | 2026-10-13 |
| Plan refs | Plan v7 §3.2 F-08, §5.3, §7.1–7.5 (row "Phone rebooted during SOS"), §12.3; ADR 0027 (note of 2026-10-13, P014c3), ADR 0024, ADR 0008 |

> **Nothing was run on a phone.** Every test runs on the JVM (Robolectric). In particular
> nobody has seen whether a phone delivers the restart broadcast to the app, or what its
> lock screen does with the notification's buttons.
>
> **With this part the device side of SOS is complete.** No script of the failure matrix
> has been run in full on a phone, so no row is "Manual pass".

## 1. Objective

Surface an SOS that was active when the phone restarted, without the app being opened
(3.1); make a sign-out, a block or a deletion of the account safe while an SOS is going on
(3.2); close part 3 of P014c with the tests, the matrix rows and scripts, a flaky-test
check and the statement of what remains (3.3 to 3.5).

## 2. Context & prerequisites

- Preflight: `main` synced at `5bd7092`; PR #67 (P014c2c) merged; no open pull requests;
  hook path `.githooks`. Notion: P014c2c set to Merged, a new row for this prompt.
- `android-ci` on `main` for `5bd7092`: success (run 38321661199). That lets rows A6 and
  A7 count the tests of P014c2c.
- **Manual results:** none reported since P014c2a.

**What was found (discovery):**

| Asked for | Found |
| --- | --- |
| The boot receiver | `EmergencyShortcutReceiver` (P012f2): `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`, not exported, puts the pinned notification back. `RECEIVE_BOOT_COMPLETED` is already declared |
| The record after a restart | `SosEngine.current()`; `recoveryFor`: triggered or active → "still active"; a countdown past its end → asked about. The emergency screen shows both since P014a3, when the app is opened |
| Sign-out | `AccountViewModel.signOut()` → `Session.signOut()`; a confirmation dialog in `SettingsScreen` |
| What a sign-out or block deletes | `SosHousekeeping` wiped the records, and `ContactsSessionSync` emptied the contacts, the note of the consent and (since P014c2b) the name for alerts, **both at once**, also during an SOS |
| What a running SOS needs of that | `SosDispatch` asks the consent gate before it sends, also for the message "I am safe". With the note of the consent gone, that message was not sent |
| "I'm safe" from a notification | `SosOpenMode.SAFE`: the ViewModel opened the confirmation at once, **without the unlock** that the screen's own button goes through |

## 3. Workflow executed

1. `/start-prompt`: sync, PR #67 merged, Notion, branch.
2. Read the receiver, the two clean-ups, the session, sign-out, the recovery screens; read
   the Android guide on direct boot.
3. The notice (`SosRestartNotice`), the receiver, the host removing it.
4. `SosEngine.awaitNoEmergency`; the two clean-ups wait for it; Settings refuses sign-out.
5. The unlock path for "I'm safe" from a notification (section 7, a finding).
6. Tests; the matrix, the scripts, ADR 0027, the Play draft, CLAUDE.md.
7. The Android gate three times in a row (3.5); `/ship-prompt`.

One thing I changed back: my ADR edit first rewrote two sentences of the ADR's original
"Consequences". Earlier prompts only ever added notes, so the two sentences stand as
written and the note says that it supersedes them.

## 4. Changes

**`feature/emergency/SosRestartNotice.kt`** (new): `SosRestartNotice.check(reason)` (true
and a notification only for a triggered or active SOS), `RestartNotifier`, the channel
"SOS after a restart" (high importance) and the notification (fixed text; Continue, I'm
safe, Call 112).

**`EmergencyShortcutReceiver`**: after putting the pinned notification back it calls the
check. Its reaction is now a plain function (`react`) that tests call.

**`core/emergency/SosEngine.kt`**: `awaitNoEmergency()`, and a tick after every change of
state that lets a waiter hear of it.

**`core/emergency/SosHousekeeping.kt`, `feature/contacts/ContactsSessionSync.kt`**: for
"signed out", "back at the start" and "blocked" they wait for `awaitNoEmergency()` before
they delete; a session that changes again drops the waiting clean-up.

**`feature/settings`**: `AccountViewModel.sosActive`, read at each resume and again inside
`signOut()`; `SettingsScreen` shows "An SOS is active" with "Open the SOS" instead of the
confirmation.

**`SosViewModel`, `EmergencyActivity`**: `SAFE` sets `safeRequested`; the activity answers
with its own unlock-then-confirm.

**`AndroidSosHost`**: removes the restart notice when the SOS is run again or ends.

**Strings**: 8 new, English and Bengali (`sos_restart_*`, `settings_sign_out_sos_*`).

**Docs**: ADR 0027 (note of P014c3), the failure matrix, scripts M27, M31, M32, the Play
foreground-service draft, CLAUDE.md.

**No change**: the manifest (the receiver and its permission existed), permissions, the
database, build files, the API contract, the backend, the notice, the SMS texts.

## 5. Diagram

No new diagram. The states and the recovery are in
[`014a-sos-device-state-machine.svg`](../diagrams/014a-sos-device-state-machine.svg); this
part adds one notification in front of an existing recovery screen and a wait in front of
two existing clean-ups, which the ADR note describes in eleven sentences.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug --rerun-tasks`, run 1 | Pass: 1252 tests, 0 failures, 0 skipped (6 min 15 s) |
| The same, run 2 | Pass: 1252 tests, 0 failures, 0 skipped (5 min 22 s) |
| The same, run 3 | Pass: 1252 tests, 0 failures, 0 skipped (5 min 50 s) |
| Every test named in the failure matrix exists | Pass: 332 names |
| markdownlint, link check, gitleaks | Pass (143 Markdown files; links and anchors in the changed documents, 0 broken; no leaks) |

`assembleRelease` was not run: no build file, manifest, `src/release` or `src/debug`
changed.

21 new tests (1231 before, 1252 now); one older test rewritten (the `SAFE` path).

| Requirement (3.3) | Tests |
| --- | --- |
| The receiver with and without an active record | `SosRestartNoticeTest` "with no emergency on the phone a restart shows nothing", "an SOS whose alert went out is announced once, and the record is left exactly as it was", "a countdown that never finished, and an SOS that is over, are not announced" |
| Only boot and package replacement | `SosRestartNoticeTest` "the receiver reacts to a restart and to an update of the app, and to nothing else"; `EmergencyNotificationTest` "the receiver answers to a restart and to an update of this app, and to nothing else"; `EmergencyShortcutsManifestTest` "one receiver, not exported, for a restart and an update of this app only" (both unchanged) |
| Notification actions and immutable PendingIntents | `SosRestartNoticeTest` "the notification is urgent, public, fixed text, and offers continue, I am safe and Call 112", "showing posts one notification, and the running SOS or its end takes it away" |
| No service start from boot | `SosRestartNoticeTest` "nothing at boot continues the SOS, starts a service or asks for a position" (reads the sources and the manifest) |
| The record untouched | The second test of the first row |
| The sign-out dialog and the wipe rules | `OnboardingViewModelTest` "while an SOS is going on sign out is refused, and nothing is signed out or deleted", "an SOS that starts after Settings was opened is still seen at the moment of signing out"; `ScreensTest` "while an SOS is active sign out says so, offers the SOS, and never asks to confirm" |
| The account-block rule | `SosWipeAfterEmergencyTest` (7): waits through the countdown and the active SOS; a block wipes nothing until "I'm safe"; a forced sign-out during the countdown wipes after the cancel; a process that dies in between waits again; signed in again, the waiting wipe is dropped; without an emergency the wipe is at once. `ContactsSessionSyncTest` (2 new) for the contacts, the consent note and the name |
| "I'm safe" needs the unlock | `SosViewModelTest` "I am safe on a notification only asks the screen, which takes it through the unlock"; `SosSafeFromNotificationTest` "on a locked phone I am safe from a notification asks for the unlock before the confirmation", "on an unlocked phone it leads straight to the confirmation" |
| Logging | The source test above; `SosCoreBoundaryTest` (unchanged, covers the engine) |
| String parity | `StringResourceParityTest` (unchanged, passes) |

**3.5, the flaky-test check.** The three runs above are the check: 1252 of 1252 each time,
no test failed or was retried. What still depends on real time or leaves a database open,
found by reading the test sources (follow-ups, section 10):

- **Real time.** Eight test files call `Thread.sleep` or read the real clock for a
  deadline: `SosDeviceTest`, `SosScreensTest` (its `waitUntil`), `SosSmsTest`,
  `ApiContactsRepositoryTest`, `ContactsFlowTest`, `FollowFlowTest`, `SearchFlowTest`,
  `StartPointFlowTest`. Ten files use the Compose rule's `waitUntil`, which also polls in
  real time with a five-second limit. None failed here; on a slow CI runner a limit can.
- **Databases.** Every Hilt test builds an in-memory database through
  `TestDatabaseModule` and none closes it (the second of the three runs printed "A
  resource failed to call close" 250 times, the third not once: it depends on when the
  test JVM collects garbage). `ApiContactsRepositoryTest`, `ContactsDatabaseTest`, `SosSettingsDatabaseTest`
  and the Room tests in `SosAlertsTest` close theirs.
- **Memory.** One hung test can exhaust the test JVM and fail everything after it
  (P014c2b saw 108 such failures). The first failure of a run is the one to read.

**Failure matrix** (`docs/sos/failure-matrix.md`)

| Row | What changed | Status now |
| --- | --- | --- |
| 5, phone rebooted during SOS | The notice after a restart is built; tests added; "Waiting on P014c" removed | **Automated pass · Manual pending** (the new tests count after the merge) |
| A11, process death in the countdown | A countdown is not announced after a restart; one test added | Automated pass · Manual pending (unchanged) |
| **A14 (new)**, sign-out or account block while an SOS is active | Built here | **Manual pending**; "Automated pass" once `android-ci` is green on `main` with the tests |
| **A15 (new)**, a shortcut used while SOS alerts are not set up | A row for behaviour and tests of P014c1a | **Automated pass · Manual pending** (those tests are on `main` since `5e57495`) |
| A6, A7 | No code change. The tests of P014c2c are on `main` and ran green (run 38321661199) | Unchanged |
| All others | Unchanged | Unchanged |

"State of the build" now begins with: the device side is complete, and what remains
(P015, P016, the manual results, the drafts). "Not built" lists only the server half and
the live link. Two items were added to "Built, but no test can show it" and to "Accepted
risks". Script M27 was written (it was a draft); M31 and M32 are new.

## 7. Decisions & ADRs

ADR 0027, note of 2026-10-13 (P014c3). The ADR's own text was not edited.

- **The existing receiver is extended**, as the prompt asks. No direct boot: the database
  is in credential-protected storage, readable after the first unlock, and that is when
  `BOOT_COMPLETED` reaches an app that is not direct-boot aware (the Android guide; its
  two statements together, it has no single sentence for it).
- **Only a triggered or active SOS is announced**, as the prompt says. A countdown sent
  nothing; announcing it would alarm for nothing.
- **An update of the app is announced too**, with its own title: it ends the process in
  the same way, and "when the phone restarted" would be untrue.
- **The notice continues nothing.** Its text says that nothing is running.
- **A finding: "I'm safe" from a notification skipped the unlock.** The emergency screen
  may be drawn over the lock screen, and its ViewModel opened the confirmation at once for
  `SAFE`. CLAUDE.md says that "I'm safe" always goes through the unlock. The new notice
  adds a second such button, so both now go through the screen's own path. Whether Android
  would really have started that screen over the lock screen from a notification button
  was not tested on a phone; the rule no longer depends on the answer.
- **Sign-out is refused, not deferred.** The user is there and can end the SOS first.
- **A block or a forced sign-out is deferred, not refused**: nobody is there to ask, and
  the person may be in danger whatever the account's state.
- **`awaitNoEmergency` in the engine**, so that every clean-up of account data waits in
  the same way. A tick after each change of state, with the last one kept, so that a
  waiter cannot miss the end.

## 8. Security & privacy notes

- **No new permission**, and no new stored data.
- The restart notification is fixed text and public on the lock screen: no name, number
  or place. Its PendingIntents are immutable and start the emergency activity or the
  dialer directly.
- **Ending an SOS needs the unlock on every path now.**
- **Between a block and the end of the SOS** the phone keeps the contacts, the note of the
  consent and the name of an account that no longer exists, and uses them for that SOS
  only. They are deleted when it ends. A point for the lawyer.
- Nothing is logged by the new code; the receiver reads the record's state and nothing
  about a person.

## 9. Known issues & risks

- **Unverified on a phone.**
- **A phone may not deliver the restart broadcast** to an app its battery manager has
  restricted, and never to an app the user force-stopped. Then nothing appears until the
  app is opened, as before this change.
- **Between the restart and "Continue" nothing runs**: no location, no retry. The notice
  says so. It cannot be otherwise without background location or a service at boot.
- **A blocked account's SOS that is never ended** keeps its data until the 30-day purge.
- **A block from the server cannot be acted out without staging** (script M31, part 3).
- The test suite still has real-time waits and unclosed databases (section 6).
- 8 Bengali strings are new drafts and **need human review before release**.
- About 955 added lines in `android/` (about 350 of app code and strings, about 600 of
  tests), above the ~800 of the prompt. The two halves of this part (the notice; the
  account) share `awaitNoEmergency` and the unlock fix and were kept together; say so
  before merging if you want them apart.

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P014c3):

- Phone checks: M27 (the notice after a restart), M31 (sign-out during an SOS), M32 (a
  shortcut with the gate closed); Bengali review of 8 strings.
- Matrix rows 5 and A14: count the new tests after `android-ci` is green on `main`.
- Tests: replace the real-time waits (eight files) and close the Hilt tests' database.
- Lawyer: contacts and the name of a blocked account are kept until its running SOS ends.
- A block or deletion from the server during an SOS: act it out on staging (M31, part 3).

Updated: the P014c2c follow-up about rows A6 and A7 (done here).

**Owed by Rahul for all of P014c** (nothing was reported except the widget's listing):
M17 (tile, notification, widget), M30 (the checklist, its settings and its two cards),
M27, M31, M32, and the older M0 to M16, M18 to M23 and M29.

**Inputs for P015 (SOS server orchestration). Not started.** What Rahul needs to decide
or provide before that prompt:

1. **Firebase Cloud Messaging.** The Firebase project the app already signs in with;
   Cloud Messaging switched on in it; how the server authenticates to FCM (through
   workload identity as for the rest of staging, with no key file, ADR 0007); the consent
   purpose and notice for push (a new purpose in `purposes.ts`); whether the Android app
   adds the messaging SDK (CLAUDE.md: no other Firebase product without a prompt that asks
   for it) and the notification permission flow for it. P015 states who receives a push:
   today a contact is a person who is **not** a user, and the server sends no message to a
   contact without a lawyer's review (ADR 0024).
2. **The worker.** pg-boss runs in the API's codebase (Plan v7 §4); decide whether the
   worker is a second Cloud Run service from the same image or a job, its minimum
   instances (a queue that sleeps delays an SOS), and the dedicated SOS queue. The setup
   is a runbook Rahul runs; Claude Code runs no cloud command.
3. **pg-boss and the database.** The outbox and the enqueue in one transaction; the
   tables for SOS sessions and actions (expand-only migrations, ADR 0003); idempotency by
   `clientSosId` and by action; what is stored of a position, for how long, and under
   which consent (today the server receives neither the alerts nor the position, and the
   notice says so: **that sentence changes with P015 and needs a new notice version and a
   lawyer**).
4. **The contract.** New `/v1` routes for an SOS session (no coordinates in a URL, ADR
   0019); `info.version`; the Android client generated from it.
5. **Costs.** FCM is free of charge per message as far as is known, to be confirmed on
   Google's page at the time; the worker's always-on instance and the database's load are
   the real costs. SMS from the server is **not** part of the plan's MVP path (the phone
   sends the SMS); if a server SMS is wanted, it needs a provider, its terms checked in an
   ADR, a price and a lawyer's review. No figure is written here: none was measured.
6. **From the device side, ready for P015:** `clientSosId` (a UUID version 7) as the
   idempotency key; `syncState` (`NOT_SYNCED` only); the state `SYNCING` (unused); the
   rule that no device-side SOS action ever waits for the server; rows 1, 3, 9 and 10 of
   the matrix and the draft scripts M24 to M26 and M28.

## 11. How Rahul can verify

1. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug`.
2. Read the note of P014c3 in ADR 0027 and `SosRestartNotice.kt`.
3. On the phone, with an account whose only contact is your second phone (or none):
   - **M27:** start an SOS, let it become active, restart the phone, unlock it and do not
     open SafeRoute: the notification "SOS was active when the phone restarted". Continue.
     Then again, and "I'm safe" from the lock screen: the unlock comes first.
   - **M31:** with an SOS active, Settings → Sign out: "An SOS is active. End it first".
   - **M32:** with an account that has not agreed to the current notice, tap the tile, the
     widget and the notification's "Start SOS": no countdown.
4. The rest of the checklist for P014c: add the tile and the widget; start from each
   entry, including the lock screen (M17); the readiness screen (M30).
5. Report: whether the restart notification came and how long after the unlock; whether
   the unlock was asked for before "I'm safe"; the words of the sign-out refusal.

## 12. Learning notes

- **Boot receivers.** A broadcast receiver declared in the manifest for `BOOT_COMPLETED`
  lets Android start the app's process for a moment after a restart. It has seconds, not
  minutes, and may not start most services.
  [Docs](https://developer.android.com/develop/background-work/background-tasks/broadcasts)
- **Credential-protected storage and direct boot.** After a restart the app's files stay
  encrypted until the user unlocks the phone once. An app that wants to run before that
  must opt in to "direct boot" and keep its data elsewhere; this app does not.
  [Docs](https://developer.android.com/privacy-and-security/direct-boot)
- **Foreground services and visibility.** A `location` service may start only while the
  app is visible, so a restart can announce an SOS but cannot continue it.
- **Notification importance.** A channel's importance decides whether a notification
  makes a sound and pops up. The running SOS is silent; "it stopped" is not.
- **PendingIntents on a notification.** Each button carries one; immutable, and straight
  to an activity.
- **`requestDismissKeyguard`.** An activity shown over the lock screen can ask Android for
  the unlock and be told when it succeeded.
- **`collectLatest`.** When a new value arrives, the work for the previous one is
  cancelled: a clean-up that waits for an SOS to end is dropped if the session changes.
- **`SharedFlow` with replay.** A stream of events that keeps the last one for a
  subscriber who arrives a moment late.
