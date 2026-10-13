# P014d1b: the choice to send crash reports

| Field | Value |
| --- | --- |
| Prompt | P014d (diagnostics), part 1, second of three pieces: the choice on screen and the debug test buttons |
| Milestone | M5 (follows P014d1a, merged as `0fc4a55`) |
| Branch | `feat/014d1b-crash-reports-choice` |
| PR title | `feat(android): the choice to send crash reports in onboarding, Settings and once on Home [P014d1b]` |
| Notion | [P014d1b row in the Prompt Log](https://app.notion.com/p/3f8073707720811ab133d552241e88e0) |
| Date | 2026-10-13 |
| Plan refs | Plan v7 §12, §13; ADR 0028 (note of 2026-10-13, P014d1b), ADR 0010 |

> **Nothing was run on a phone, and nothing was sent to Firebase.** Whether a report
> arrives in the console is the first thing the script in section 11 finds out.
>
> **From this part on a person can switch crash reports on.** In a release build the
> switch decides alone. In a debug build nothing is sent unless it was built with
> `saferoute.debugCrashReports=true`.

## 1. Objective

Put the choice "Send crash reports" in front of the person: once in onboarding, always in
Settings, and once on Home for someone who was onboarded before the choice existed. Give
the developer two buttons to try it in a debug build.

## 2. Context & prerequisites

- P014d1a (PR #69) is merged as `0fc4a55`; `android-ci` passed there with the dummy
  Firebase file. Its Notion row was set to Merged in the preflight.
- This piece is requirements 2 and 5 of the prompt's part 1. Requirement 4 ("Report a
  problem"), the wiring of the existing events and the Play data-safety draft are P014d1c.
- Discovery was done once in P014d1a (its log, section 2) and not repeated.

## 3. Workflow executed

1. Preflight: PR #69 confirmed merged with `gh pr view`, main synced, branch created.
2. Strings in English and Bengali, and the notice document generated from them.
3. The three screens, the onboarding step in the navigation, the Developer section.
4. Tests, then the whole unit-test task.
5. The note in ADR 0028, the known gap in the failure matrix, the rule in `CLAUDE.md`,
   the diagram.
6. The Android gate three times with `--rerun-tasks`, the release build, the checks of
   section 6.

## 4. Changes

- `feature/diagnostics/CrashReportsChoice.kt` (new package):
  - `CrashReportsChoiceScreen`: the onboarding step. The notice, an **unticked** box and
    "Continue", which works either way.
  - `CrashReportsSettingsSection`: the switch and the notice in Settings; in a debug build
    that sends nothing, a line that says so.
  - `CrashReportsHomeCard`: a card in the Home sheet for a person never asked, with two
    buttons of equal weight. It disappears for good with either answer.
  - `CrashReportsViewModel`.
- `navigation/OnboardingNavHost.kt`: the step `OnboardingCrashReportsDestination`, shown
  when the state is "signed out" and the person has not been asked. Order: age, account
  notice, crash reports, phone.
- `OnboardingViewModel`, `SafeRouteApp`, `SettingsScreen` (a slot), `HomeScreen` (the card).
- `src/debug/.../DiagnosticsDeveloperSection.kt`: "Test crash", "Test non-fatal" and a
  line that says whether this build is collecting and, if not, why.
- Strings: 16 in `values` and `values-bn`, 6 in the debug resources of each.
- `docs/legal/diagnostics-notice-v1.md`, generated from the strings.
- Docs: note in ADR 0028; known gap 9 in `docs/sos/failure-matrix.md`; `CLAUDE.md`;
  the diagram; this log.

Size: about 930 changed lines in `android/`, of which about 510 are tests.

**Deployment impact:** none.

## 5. Diagram

[`docs/diagrams/014d1-diagnostics-flow.svg`](../diagrams/014d1-diagnostics-flow.svg),
updated: the choice node now says where it is asked.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug --rerun-tasks` | Pass, three times in a row |
| Unit tests | 1,311 tests, 0 failures; 23 new |
| Lint | 0 errors, 34 warnings (as before) |
| `./gradlew assembleRelease -Psaferoute.apiBaseUrl=https://example.invalid/` | Pass; the release APK holds no Developer class |
| Tracked-secrets check | Prints nothing |
| Diagram generator | Pass |
| Markdown lint (changed documents) | Pass, 0 findings |
| gitleaks on `main..HEAD` | Pass, no leaks |

New tests:

- `CrashReportsChoiceTest` (14): where the step sits; unticked by default; continuing
  without and with the tick; the five texts; font scale 2.0; the stored answer; the
  Settings switch both ways; the debug line; the Home card shown once, each button, and
  never again; the notice document word for word in both languages; what the text must
  not promise.
- `CrashReportsFlowTest` (5, Hilt, the real `MainActivity`): a new person is asked before
  the phone number; a tick stores a yes; a person asked before goes straight to sign-in;
  a person onboarded earlier sees the card on Home beside a working SOS control; no card
  by default.
- `DiagnosticsDeveloperSectionTest` (4, debug): the state line, the two buttons.
- `StringResourceParityTest` (existing) covers the new keys in both languages.

**Failure matrix:** no row changed. Known gap 9 added: field failures are visible only
for testers who opt in, and no row's status may be taken from crash reports.

## 7. Decisions & ADRs

Recorded as a note in [ADR 0028](../adr/0028-diagnostics-and-crash-reporting.md):

- **Where the step sits.** After the account notice and before the phone number. The
  session has no state for it; the step is shown when the state is "signed out" and the
  flag "asked" is false.
- **A box, not a switch, in onboarding**, and "Continue" as the only button: there is no
  "accept" to tap by habit.
- **Two equal buttons on the Home card.** Neither answer is the highlighted one.
- **The answer stays on the phone across sign-out.** It is a setting of the device. A
  second person on the same phone finds it in Settings. For the lawyer.
- **"Test crash" throws from the button** and so takes the same road as a real crash,
  through the handler that replaces the message.
- **Hilt tests start with the question answered**, so that the 1,288 older tests do not
  meet a new screen; `clearAsked()` makes it due.

## 8. Security & privacy notes

- Default off, never pre-ticked, asked once, changeable at any time.
- The text names Google and says that sent reports stay until the provider deletes them.
- The answer is two flags on the phone; the server is not told.
- The card is never a dialog and never on an SOS screen.
- 16 Bengali strings (and 6 debug ones) are drafts: **they need human review before
  release**. The English is a draft for the lawyer.

## 9. Known issues & risks

- **Unverified on a phone and against Firebase** (section 11).
- The consent is not recorded anywhere but on the phone; whether that is enough is a
  question for the lawyer.
- "Google states that it keeps them for 90 days" repeats Google's published figure.
- A person who signs out and in again is not asked again.
- How the Home card looks beside the other two cards in the sheet was not seen.

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P014d1b):

- Phone script D1 to D4 (section 11).
- Bengali review of the 16 notice strings.
- Lawyer: is a choice stored only on the phone a sufficient record of consent; the answer
  surviving sign-out.

**Next: P014d1c** (say "P014d1c"): "Report a problem", the existing events wired to the
reporter, the remaining keys, the Play data-safety draft. To show the "Send by email"
button, Rahul will need a support address as the Gradle property `saferoute.supportEmail`
in his user-level `gradle.properties`; without it the button is hidden. Then "P014d2".
P015 is not started.

## 11. How Rahul can verify

On the computer: `cd android` and `./gradlew lint testDebugUnitTest assembleDebug`.

On the phone. For D2 and D3 add `saferoute.debugCrashReports=true` to your user-level
`gradle.properties` and install the debug build again. Write down only "worked" or what
you saw; no numbers, names or places.

**D1. Off by default.**

1. Clear the app's data, open it, go through age and the account notice.
2. The next screen is "Help fix crashes". The box is not ticked.
3. Tap "Continue" without ticking. The phone number screen follows.
4. After signing in, open Settings, "Diagnostics": the switch is off.

**D2. Opt in and crash.**

1. In Settings switch "Send crash reports" on.
2. Open Settings, Developer. The line under "Crash reports" says "Collecting".
3. Tap "Test non-fatal", then "Test crash". The app closes.
4. Open the app again and leave it open for a minute.
5. In the Firebase console, Crashlytics: within a few minutes there is a crash and a
   non-fatal. The text is `...DeveloperTestCrash [UNCAUGHT]` and `[TEST]`. The words
   "Test crash from the Developer screen" must **not** be there.
6. Look at the keys of the report: only `appVersion`, `buildType`, `androidApi`.

**D3. Opt out and repeat.**

1. Switch "Send crash reports" off. Close the app and open it again.
2. Developer: the line says "Not collecting". Tap "Test crash", open the app again.
3. Wait ten minutes: no new crash arrives in the console.

**D4. The card on Home.** Only on a phone that still has the app's data from before this
version: after the update, open the Home sheet. A card asks "Send crash reports?". Tap
"No thanks": it is gone, and it does not come back after a restart.

## 12. Learning notes

- **A slot** in Compose is a parameter that is itself a piece of screen
  (`diagnosticsSection: @Composable () -> Unit`). `SettingsScreen` stays a plain function
  that tests can call; the caller decides what goes into the slot.
- **`rememberSaveable`** keeps a small value across a rotation of the phone. Here it holds
  whether the box is ticked until "Continue" is tapped.
- **`toggleable`** makes a whole row react to a tap and tells TalkBack that it is a
  checkbox or a switch, so the target is the row and not only the small box.
- **`stateIn`** turns a stream of stored values (a `Flow`) into something a screen can
  read at once (a `StateFlow`). The first value here is `null`, meaning "not read yet", so
  that neither the step nor the card flashes before the phone has read the flags.
- **Why a debug build needs a flag.** A developer's phone crashes all day. Without the
  flag, every one of those would land in the same console as the testers' reports.
- **A non-fatal** is an error the app caught and survived. Crashlytics keeps it and sends
  it the next time the app starts, which is why the script says to open the app again.
