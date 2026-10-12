# P014b7: keep retrying unsent SOS alerts while the SOS is active

| Field | Value |
| --- | --- |
| Prompt | P014b7 · a fix between part b and part c of P014 |
| Milestone | M5 (follows P014b6, merged as `0298557`) |
| Branch | `fix/014b7-sos-retry-while-active` |
| PR title | `fix(sos): keep retrying unsent alerts while the SOS is active, per Plan §7.5 [P014b7]` |
| Notion | [P014b7 row in the Prompt Log](https://app.notion.com/p/3f7073707720812995d5cda241c6e3e7) |
| Date | 2026-10-12 |
| Plan refs | Plan v7 §7.5 (row "No signal at all"), §7.1; ADR 0027 (note of 2026-10-12, P014b7) |

> **Nothing was run on a phone.** Every test runs on the JVM, in virtual time. What a real
> phone does while it sleeps, and what its radio answers without signal, is not known.
>
> **The numbers 2 hours and 24 hours are choices of this prompt.** The plan says "keeps
> retrying" and names no end.

## 1. Objective

An alert that could not be sent was given up after 20 tries, about ten minutes. Plan v7
§7.5 says that with no signal the device keeps retrying and that the SMS is retried when
signal returns. Make the phone keep trying for as long as the SOS is active, tell the user
when alerts stay unsent, and do it without a new permission, service or wake lock.

## 2. Context & prerequisites

- Preflight: `main` synced at `0298557`; PR #61 (P014b6) merged; no open pull requests; hook
  path `.githooks`. Notion: P014b6 set to Merged, a new row for P014b7.
- `android-ci` on `main` for `0298557`: success (run 38247544266).
- Evidence named by the prompt: failure matrix row A8 and "Not specified", item 2.
- From P014b1 and P014b2: `SosAlerts.sendUntilDone` (a round every 30 seconds, 20 tries),
  `SosDispatch` (started by the runner once the SOS is active, in the app scope).
- The notice the user agrees to (`docs/legal/sos-alerts-notice-v2.md`) names no duration
  for retries, so its version does not change.

## 3. Workflow executed

1. `/start-prompt`: sync, PR #61 merged, Notion, branch.
2. Read the sender, the dispatcher, the runner, the action table, the manifest and the
   test that pins the merged permission list.
3. **Verified the signals in the Android SDK sources (API 37)**: the airplane-mode
   broadcast needs no permission; the default network callback needs
   `ACCESS_NETWORK_STATE`, which is already in the merged manifest; the service-state
   broadcast needs `READ_PHONE_STATE` (section 7).
4. The engine (`SosAlerts`), the dispatcher, the phone's side, the screen.
5. Tests. On the way:
   - three older tests failed because a restarted app no longer tried at once. That was a
     gap in my change, not in the tests: opening the app again **is** "the app comes to the
     front". The dispatcher now makes one try at once when it finds the emergency in a new
     process, and keeps the number of tries;
   - one older test retried twice in a row without letting the wait pass; it now advances
     the clock by 30 seconds;
   - the test framework grants no permission by itself, so the network signal was not
     registered in my first device test. That showed that the guard works; the test now
     grants the permission where it needs it, and a second test keeps the guarded case.
6. The matrix, the manual scripts, ADR 0027, CLAUDE.md, the diagram (redrawn once: the
   first version had labels on top of boxes).
7. The Android gate three times in a row; `/ship-prompt`.

## 4. Changes

**`core/emergency/SosAlerts.kt`**

- `alertRetryDelayMillis`: 30 seconds after each of the first ten tries, then 1, 2 and 4
  minutes, then 5 minutes.
- `RetryRule`: how a kind of message is retried. `alertRetryRule(triggeredAt)`: no limit on
  tries, an end 24 hours after the trigger. `FOLLOW_UP_RETRY_RULE`: 30 seconds, 20 tries,
  as before, for the "safe" message and the location update.
- `sendDue` tries only what is **due**: a failed message is due when its wait is over,
  worked out from its stored number of tries and the time of the last one. It returns the
  time until the next one is due.
- `sendNow`: a try at once for everything unsent; does nothing while a round is in flight.
- `markUnsentNoticeOnce`: writes a row of the new type `UNSENT_NOTICE` (no contact, number
  or name), once per emergency.
- A message still unsent at the end of the rule becomes FAILED_FINAL with the category
  `expired`.
- `MAX_ALERT_ATTEMPTS` is now `MAX_FOLLOW_UP_ATTEMPTS`.

**`core/emergency/SosDispatch.kt`, `SosRunner.kt`**

- The alert loop uses `alertRetryRule` with the trigger time from the record.
- `retryNow()`; signals from the phone (`RetrySignals`) ask for the same, at most every 10
  seconds; a new process that finds the emergency makes one try at once.
- A timer for the two-hour notice (`UnsentAlertNotifier`), also worked out from the
  record's trigger time.
- `SosAlertStatus.Automatic` gained `unsentForLong` and `retryingStopped`.

**`feature/emergency`**

- `SosRetryDevice.kt` (new): the notification "Some alerts have not been sent" (fixed
  text, a tap opens the emergency screen, one button opens the dialer with 112), and
  `AndroidRetrySignals` (airplane mode off; a network becoming available).
- The active screen: "SafeRoute keeps trying while this SOS is active.", the button "Send
  again now", the two-hour sentence, the 24-hour sentence.
- `SosViewModel`: `onRetryNow()`; a try when the screen is shown again.

**Strings**: 8 new, English and Bengali (`sos_alerts_retry_explain`, `sos_alerts_retry_now`,
`sos_alerts_unsent_long`, `sos_alerts_stopped`, `sos_unsent_channel`,
`sos_unsent_channel_description`, `sos_unsent_title`, `sos_unsent_text`).

**Docs**: the failure matrix, the manual scripts, ADR 0027, CLAUDE.md, the diagram.

**No change**: manifest, permissions, database schema, build files, API contract, backend,
the consent notice and its version, the SMS texts.

## 5. Diagram

[`docs/diagrams/014b7-sos-alert-retries.svg`](../diagrams/014b7-sos-alert-retries.svg)
(source `.json`, also `.excalidraw` and `.png`): what starts a try, one try, and the ways
it ends.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug --rerun-tasks`, run 1 | Pass: 1137 tests, 0 failures, 0 skipped (4 min 33 s) |
| `./gradlew lint testDebugUnitTest assembleDebug --rerun-tasks`, run 2 | Pass: 1137 tests, 0 failures, 0 skipped (4 min 28 s) |
| `./gradlew lint testDebugUnitTest assembleDebug --rerun-tasks`, run 3 | Pass: 1137 tests, 0 failures, 0 skipped (4 min 6 s) |
| `pnpm generate` (diagrams) | Pass |
| Every test named in the failure matrix exists | Pass: 278 names |
| markdownlint, link check, gitleaks | Pass (135 Markdown files; links and anchors 0 broken; no leaks) |

`assembleRelease` was not run: no build file, `src/release` or `src/debug` changed.

30 new tests (1107 before, 1137 now), all in virtual time:

| Requirement | Tests |
| --- | --- |
| Retries continue past 20 tries | `SosAlertsTest` "an alert is tried again past 20 tries, for as long as the emergency is active"; `SosDispatchTest` "an alert that cannot be sent is still tried an hour later, and leaves when the signal returns" |
| Backoff sequence and cap | `SosAlertsTest` "the wait is 30 seconds for the first ten tries, then 1, 2 and 4 minutes, then 5 minutes" (the function, and the seconds at which tries 2 to 17 happen) |
| Stop when the SOS ends | `SosAlertsTest` "retries stop the moment the emergency is over" (unchanged); `SosDispatchTest` "a signal from the phone asks for one more try, at most every ten seconds, and none after the end" |
| Never resend SENT | Asserted in the two "past 20 tries" tests, in "a try asked for now is made at once ..." and in the 24-hour test |
| Final failures stay final | `SosAlertsTest` "a final failure stays final, also when a try is asked for now" and "a failure that cannot pass is not tried again" (unchanged) |
| The 2-hour notice appears once | `SosAlertsTest` (2); `SosDispatchTest` "two hours after the trigger with an alert unsent the user is told once, also across a process death", "a process that starts after the two hours tells the user, if the old one never did", "with every alert sent nobody is told anything after two hours"; `SosRetryDeviceTest` (the notification) |
| The 24-hour stop | `SosAlertsTest` "24 hours after the trigger what is still unsent is given up, on time, and says why"; `SosDispatchTest` "24 hours after the trigger trying stops, the screen is told, and the emergency goes on" |
| "Send again now": one attempt, ignored while one is in flight | `SosAlertsTest` (2); `SosDispatchTest` "send again now makes one try at once, and a second tap during that try is ignored"; `SosViewModelTest`; `SosAlertLinesTest` (the button) |
| Process death keeps the schedule | `SosAlertsTest` "a new process continues the same schedule instead of starting it again"; the two older process-death tests of `SosDispatchTest` |
| No logging of numbers or text | `SosCoreBoundaryTest` (the core, unchanged, covers the new code); `SosRetryDeviceTest` "the retry code of the phone never logs, sends nothing and asks for no permission"; the notification tests (fixed text only) |
| No new permission | `SosRetryDeviceTest` "the main manifest gained no permission for the retries"; `MainActivityTest` (the merged list, unchanged) |
| String parity EN/BN | `StringResourceParityTest` (unchanged, passes with the 8 new keys) |

Older tests changed on purpose: "after 20 tries a message is given up, about ten minutes
in" is now "a follow-up is given up after 20 tries, about ten minutes in" (the rule that
still has that limit); one test waits 30 seconds before its second round; two assertions
read the new return value of `sendDue`.

**Failure matrix** (`docs/sos/failure-matrix.md`)

| Row | What changed | Status now |
| --- | --- | --- |
| 2, no signal at all | Expected behaviour rewritten; the retry test replaced | Automated pass · Manual pending (unchanged; its retry tests count once `android-ci` is green on `main` with them) |
| A8, SMS fails for every contact | Expected behaviour rewritten; the new tests listed under its Evidence | **Manual pending**: the tests of the new behaviour are on `main` only after this merge |
| A13, debug build with real contacts | No code change | **Automated pass · Manual pending**: its tests are on `main` since `0298557` and the run was green |
| All others | Unchanged | Unchanged |

"Accepted risks" gained four items (an alert can arrive hours later; no wake lock; every
try counts; the service-state signal is not used). "Not specified", item 2, now records
that 2 and 24 hours are choices. Script M22 was rewritten.

## 7. Decisions & ADRs

ADR 0027, note of 2026-10-12 (P014b7). It replaces point 5 of the note of P014b1 for the
alert.

- **The schedule lives in the record.** The due time follows from the stored tries and the
  time of the last one. Nothing new is stored, no migration, and a new process cannot
  forget it.
- **The two-hour notice is marked in the action table**, as a row of its own type. "Once"
  then holds across a process death, and the mark is deleted with the emergency. The
  alternative, a flag in DataStore, would have kept a piece of an emergency's state
  outside its record.
- **Follow-ups keep 20 tries.** The "safe" message is sent after the SOS has ended; without
  a limit it would be tried for ever.
- **Signals.** Verified in the SDK sources:

  | Signal | Needs | Used |
  | --- | --- | --- |
  | Airplane mode switched off (`ACTION_AIRPLANE_MODE_CHANGED`) | Nothing | Yes |
  | A network became available (`registerDefaultNetworkCallback`) | `ACCESS_NETWORK_STATE`, already in the merged manifest through the sign-in libraries; checked at run time | Yes |
  | The mobile network's service state, as a broadcast | `READ_PHONE_STATE` | **No: skipped, as the prompt asks** |
  | The same as a `TelephonyCallback` listener | Names no permission, but could not be checked without a phone | No (a follow-up) |

- **"The app comes to the front"** is the emergency screen being shown again, and a new
  process finding the emergency. A rotation of the screen also counts; the cost is one try.
- **A try asked for counts as a try.** A separate counter would have needed a new column.
  The effect is bounded: the wait never exceeds five minutes.
- **24 hours ends the retries, not the SOS.** The emergency stays active until the user
  says they are safe.
- **`SosAlertStatus.Automatic` carries two flags** instead of new states, so that every
  existing use of the counts keeps working.

## 8. Security & privacy notes

- **No new permission.** `READ_PHONE_STATE` is not requested. The app's own manifest is
  unchanged; `ACCESS_NETWORK_STATE` was already granted through a library, and the code
  works without it.
- The notification and the new screen lines are fixed text: no name, number or place, also
  on the lock screen.
- The `UNSENT_NOTICE` row holds no personal data.
- **A contact can now get an alert hours after the trigger** (up to 24). Each try writes
  the message anew, with the newest position and the clock time of that position.
- Nothing is logged by the new code.
- The notice wording was not changed. Whether it should mention late alerts is a question
  for the lawyer (a follow-up).

## 9. Known issues & risks

- **Unverified on a phone.**
- **Battery: an estimate, not a measurement.** No new service, alarm or wake lock; the timer
  is a coroutine kept alive by the foreground service that already runs for the location
  trail. An unsent alert costs 11 tries in the first five minutes, 15 in the first
  seventeen, then 12 an hour: about 36 in two hours and about 300 in 24 hours per message,
  each a short use of the radio. This is small beside the location trail (a position every
  5 seconds, every 15 below 10% battery), which is what an SOS of many hours mainly costs.
  Script M22, part 5, asks for the battery level before and after.
- **A try can be late while the phone sleeps deeply**: no wake lock is held, on purpose. It
  is made when the phone wakes, and at once when the screen is opened.
- A data network coming back is a hint, not proof that an SMS will pass.
- A forgotten SOS can alert a contact up to 24 hours later.
- A tap on "Send again now" shows nothing when the try fails again; the line stays "Still
  trying".
- 8 Bengali strings are drafts and **need human review before release**.
- About 1,260 added lines in `android/` (about 530 of app code and strings, about 730 of
  tests), above the usual size: the engine, its triggers and the two limits were kept in
  one change because each is tested against the others.

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P014b7):

- Phone check of the retries (script M22) and Bengali review of 8 strings.
- Matrix row A8 to "Automated pass" after `android-ci` is green on `main` with these tests.
- Can the service state be used as a signal without `READ_PHONE_STATE`?
- Lawyer: an alert can now leave up to 24 hours after the trigger.

Updated: the P014b6 follow-up about row A13 (done here); its second half, the card that
says "On" without a list, stays open.

**For P014c:** the tile, the widget and the notification start an SOS through the same
runner, so the retries hold for them; the notice after a restart should lead to the
emergency screen, which makes a try at once. P014c also updates rows 2 and A8.

## 11. How Rahul can verify

1. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug`.
2. Read `alertRetryDelayMillis` and `RetryRule` at the top of `SosAlerts.kt`, and the ADR
   note.
3. On a phone, with the debug list set (P014b6) and only your second phone as contact, run
   script **M22** in `docs/sos/manual-test-scripts.md`: parts 1 and 2 take twenty minutes;
   part 5 takes two hours on a charger.
4. Tell me: how long after airplane mode was switched off the alert arrived, whether the
   screen was off, and the battery level before and after part 5.

## 12. Learning notes

- **Backoff.** Waiting longer after each failure. Trying every 30 seconds for a day would
  cost battery for nothing; trying every 5 minutes loses little.
- **Derive, don't remember.** The next try is worked out from what is written down (how
  many tries, when the last one was). Code that keeps "the next time" only in a variable
  loses it when Android ends the process.
- **Broadcast receiver.** A way to be told about system events. Registered in code, it
  lives only while the app wants it; some broadcasts need a permission to receive, the
  airplane-mode one does not.
  [Docs](https://developer.android.com/develop/background-work/background-tasks/broadcasts).
- **Network callback.** `ConnectivityManager` calls back when a network becomes available.
  It needs `ACCESS_NETWORK_STATE`, a "normal" permission granted at install.
  [Docs](https://developer.android.com/develop/connectivity/network-ops/reading-network-state).
- **Normal and dangerous permissions.** Normal ones are granted at install without a
  dialog; dangerous ones (location, SMS, phone state) need the user's answer.
- **Merged manifest.** Libraries bring their own manifest entries; the app that is built
  contains the sum. A permission can be in the app although the app's own file never names
  it.
- **Doze and wake locks.** A sleeping phone delays timers to save battery. A wake lock
  keeps the processor awake and is an easy way to drain a battery, so this change does
  without one.
  [Docs](https://developer.android.com/training/monitoring-device-state/doze-standby).
- **Virtual time in tests.** The tests move a fake clock forward by hours in milliseconds
  of real time, so "24 hours later" can be checked on every run.
