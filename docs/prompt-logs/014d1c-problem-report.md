# P014d1c: "Report a problem", and the existing failures as typed events

| Field | Value |
| --- | --- |
| Prompt | P014d (diagnostics), part 1, third and last piece: the problem report, the event sources, the Play data-safety draft |
| Milestone | M5 (follows P014d1b, merged as `3da0843`) |
| Branch | `feat/014d1c-problem-report` |
| PR title | `feat(android): a redacted problem report the tester sends by hand [P014d1c]` |
| Notion | [P014d1c row in the Prompt Log](https://app.notion.com/p/3f807370772081b98e24ebfc62b523b3) |
| Date | 2026-10-13 |
| Plan refs | Plan v7 §12, §13; ADR 0028 (note of 2026-10-13, P014d1c), ADR 0027 |

> **Nothing was run on a phone.** Nobody has seen the mail app or the share sheet open
> with the text, and no event has been produced by a real failure.
>
> **With this piece part 1 of P014d (Android) is complete.** Part 2, the server alerts
> runbook, is P014d2.

## 1. Objective

Give a tester a way to tell the developer what went wrong without the app sending
anything: a short text they read first and send themselves. And let the failures the app
already knows about appear in it, and in crash reports, as fixed words.

## 2. Context & prerequisites

- P014d1b (PR #70) is merged as `3da0843`; its Notion row was set to Merged.
- This piece is requirement 4 of the prompt's part 1, the event sources named in its
  discovery list, and the rest of requirement 7.
- Phone script D1 to D4 (P014d1b) has not been reported.
- No data-safety draft existed in `docs/play`; the prompt said "update". One was created.

## 3. Workflow executed

1. Preflight: PR #70 confirmed merged with `gh pr view`, main synced, branch created.
2. Read the places where the existing failures happen (the SMS gateway, the dispatcher's
   and the trail's published states, the SOS view model, the HTTP client).
3. The report as a pure function; the screen; the two intents; the ways in.
4. The event sources, each as a listener or a wrapper.
5. Tests, then the whole unit-test task.
6. The Play draft (the help page was read on 2026-10-13), the ADR note, the other docs.
7. The Android gate three times with `--rerun-tasks`; one lint finding fixed; the gate
   once more; the release build.

## 4. Changes

The report:

- `core/diagnostics/ProblemReport.kt`: `ProblemReportInput` and `problemReportText`, a
  pure function. Relative times only.
- `feature/diagnostics/ProblemReportScreen.kt`: the view model, the screen (the text, an
  optional note, "Send by email", "Share", "Back") and the two intents.
- `feature/diagnostics/DiagnosticsFacts.kt`: the allowlisted facts read from the phone
  (phone-maker family, language, kind of network, SMS mode, three permission states).
- Ways in: a row in Settings; a link on the onboarding problem screen.
- `app/build.gradle.kts`: `BuildConfig.SUPPORT_EMAIL` from the Gradle property
  `saferoute.supportEmail`; empty hides the e-mail button.

The event sources:

- `feature/diagnostics/SosDiagnostics.kt`: `ReportingSmsGateway` (wraps the real gateway;
  `SMS_SEND_FAILED`, once per reason) and `DiagnosticsObservers` (`ALERTS_GIVEN_UP`,
  `LOCATION_UNAVAILABLE`, and the in-memory list emptied at sign-out).
- `SosViewModel`: `RECOVERY_SHOWN`, once per screen.
- `core/network/interceptor/DiagnosticsInterceptor.kt`: `API_ERROR` for 429, 503 and other
  5xx answers, after the retries.
- `CrashReporting` reads the facts once at start and sets them as keys.
- `SERVICE_KILLED` stays defined and unreported: nothing in the app detects it.

Strings: 9 in `values` and `values-bn`. Docs: `docs/play/data-safety-draft.md` (new), a
note in ADR 0028, `CLAUDE.md`, `android/README.md`, a history line in the failure matrix,
the diagram, this log.

Size: about 1,120 changed lines in `android/`, of which about 480 are tests. Above ~800;
the report and its event sources were kept together because the report is empty without
them.

**Deployment impact:** none.

## 5. Diagram

[`docs/diagrams/014d1-diagnostics-flow.svg`](../diagrams/014d1-diagnostics-flow.svg),
updated: the report node and the event sources.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug --rerun-tasks` | Pass, three times in a row |
| The same, once more after a one-line lint fix (`toUri`) | Pass |
| Unit tests | 1,328 tests, 0 failures; 17 new |
| First `android-ci` run on the pull request | **Failed**: one test, a race this change introduced (below). Fixed; the gate then passed three more times with `--rerun-tasks` |
| Lint | 0 errors, 34 warnings (as before) |
| `./gradlew assembleRelease -Psaferoute.apiBaseUrl=https://example.invalid/` | Pass |
| Tracked-secrets check | Prints nothing |
| Diagram generator | Pass |
| Markdown lint (changed documents) | Pass, 0 findings |
| gitleaks on `main..HEAD` | Pass, no leaks |

New tests:

- `ProblemReportTest` (15): the exact text; no events and a note; relative times; personal
  text reported elsewhere reaches the list as a category only; phone-maker families; every
  fact read from the phone passes its key; a failed text noted once per reason and a sent
  one never; given-up alerts and a missing position noted when they begin, and sign-out
  empties the list; server failures by kind (with a local test server); the screen shows
  what both buttons hand over; no address, no e-mail button; no e-mail app; the two
  intents; the two ways in.
- `CrashReportsFlowTest` (+1, Hilt): Settings leads to the report, and opening it records
  and starts nothing.
- `SosViewModelTest` (one test extended): a start and a sent alert note nothing; a found
  emergency notes `RECOVERY_SHOWN` once.
- `NetworkModuleTest` (existing, updated): the interceptor order now includes the new one.

**The CI failure and its fix.** `DefaultLocationRepositoryTest` failed in CI with
"uncaught exceptions before the test started". The cause was new code, not that test:
`CrashReporting.start()` read the facts about the phone on a background thread at app
start, and tests that run the real application class can take the test environment away
while that read is in flight. It passed four times locally and failed once in CI. The read
now cannot throw (a failure contributes no facts), and a test covers it. In production the
same guard means a phone that cannot answer one of the questions breaks nothing.

**Failure matrix:** no row changed and no SOS behaviour changed; a history line records
the wrapper around the SMS gateway.

What the tests do not show: an event produced by a real failure on a phone; the mail app
and the share sheet; what `ACCESS_NETWORK_STATE` answers on a real phone.

## 7. Decisions & ADRs

Recorded as a note in [ADR 0028](../adr/0028-diagnostics-and-crash-reporting.md):

- **The report's labels are fixed English words**, not translated: it is technical text
  for the developer. The screen around it is translated. Say so if testers should read it
  in Bengali.
- **Events are noted by listening**, not by calls from SOS code: a wrapper around the SMS
  gateway and observers of states the app already publishes. One line was added to the
  SOS view model.
- **A missing connection is not an event.** It is ordinary on a phone and would fill the
  list of 20 with one line. Only 429, 503 and other 5xx answers are noted.
- **The same SMS failure reason is noted once**, not at every retry.
- **`SERVICE_KILLED` is not wired.** The prompt lists it; the app has nothing that
  detects a killed service. A found emergency shows as `RECOVERY_SHOWN`.
- **What the events give away** (see section 8).

## 8. Security & privacy notes

- **The app sends nothing and stores nothing** for the report. Both buttons hand a text
  to another app; the person sends it there.
- The report has no field for a place, a contact, a search or an account, and no time of
  day. A test feeds personal text into the reporter and checks that none of it arrives.
- **The note is the person's own text** and is not filtered. The field says "Do not
  include names, numbers or places".
- **Four of the six events can only happen during an SOS.** A crash report or a problem
  report that carries one says that this installation had an SOS in which something
  failed. That is their purpose, and only people who opted in or who send the report
  themselves reveal it. The crash-report notice speaks of "crash details" and does not
  say this: **a point for the lawyer**, recorded in the ADR and as a follow-up.
- The support address is a Gradle property in the user-level file. It is not in the
  repository and is never printed.
- No new permission. The kind of network is read with `ACCESS_NETWORK_STATE`, which the
  libraries already bring into the manifest.

## 9. Known issues & risks

- **Unverified on a phone.**
- The race found by CI was timing-dependent; three local passes after the fix do not
  prove its absence. Watch `android-ci` on `main` after the merge.
- The report is in English also for a person who uses the app in Bengali.
- `SERVICE_KILLED` is never reported.
- A server failure during sign-in is noted even before a person was asked about crash
  reports; it goes only to the in-memory list, because collection is off.
- The Play draft covers only what P014d1 added; whether a share sheet counts as
  "sharing" in the form is open.
- 9 Bengali strings are new drafts: **they need human review before release**.
- Above the ~800-line limit (section 4).

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P014d1c):

- Phone script D5 and D6 (section 11).
- Lawyer: the failure events reveal that an SOS happened; does the notice need to say so.
- A privacy policy does not exist yet. It must cover crash reports (Google, 90 days, the
  ids), what arrives in the developer's mailbox from "Report a problem", and deletion.
- Complete the Play data-safety draft for the rest of the app; settle the share-sheet
  question.
- Detect a killed SOS service, so that `SERVICE_KILLED` means something.
- Bengali review of 9 strings; decide whether the report should be translated.

**Next: P014d2** (say "P014d2"): the server's error reporting and the alerts runbook.
Claude Code runs no cloud command; the runbook is for Rahul to run. P015 is not started.

## 11. How Rahul can verify

On the computer: `cd android` and `./gradlew lint testDebugUnitTest assembleDebug`.

To see the e-mail button, add `saferoute.supportEmail=<your address>` to your user-level
`gradle.properties` and install the debug build again. Write down only "worked" or what
you saw.

**D5. Report a problem.**

1. Open Settings, "Report a problem".
2. Read the text. It holds the app version, the phone's make and model, the Android
   version, the language, the kind of network, permission states as words, the SOS setup
   words and "Recent events". It holds no name, number, place or time of day.
3. Type a short note. It appears at the end of the text.
4. Tap "Send by email": the mail app opens, addressed to you, with the text. Send it to
   yourself and compare it with the screen.
5. Go back, tap "Share": Android's share sheet opens. Close it without choosing.
6. Remove `saferoute.supportEmail`, rebuild: the e-mail button is gone, "Share" remains.

**D6. Events show up.** Only with a test contact on the debug SMS allowlist, and only if
you want to try: start an SOS in airplane mode, wait for the first failed attempt, end it
with "I'm safe", then open "Report a problem". "Recent events" should show
`SMS_SEND_FAILED(...)` with "N min ago", and nothing about the SOS having started.

D1 to D4 of P014d1b are still owed.

## 12. Learning notes

- **A pure function** gets everything it needs as parameters and returns a value; it
  reads no clock, file or sensor. `problemReportText` is one, which is why its exact
  output can be written into a test.
- **An intent** is a message to Android: "someone, please do this". `ACTION_SENDTO` with
  a `mailto:` address asks for a mail app; `ACTION_SEND` asks for anything that accepts
  text, and `createChooser` shows the list (the share sheet). The app does not send: it
  hands over. Guide: <https://developer.android.com/training/sharing/send>.
- **A decorator** (here `ReportingSmsGateway`) is an object with the same interface as
  the one it wraps. It passes every call on and adds one thing. The code that uses the
  gateway cannot tell the difference, which is how the SOS code stayed untouched.
- **An OkHttp interceptor** sees every request and its answer. The order matters: this
  one is placed outside the retry interceptor, so it sees the final answer once.
- **`distinctUntilChanged`** lets a value through only when it differs from the one
  before. A state that stays "unavailable" for an hour is noted once.
- **`ConnectivityManager`** tells what kind of connection the phone has (Wi-Fi, mobile)
  without naming the network.
