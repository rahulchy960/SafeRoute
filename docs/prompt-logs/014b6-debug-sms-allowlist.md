# P014b6: debug builds send SMS only to allowlisted test numbers

| Field | Value |
| --- | --- |
| Prompt | P014b6 · a safeguard between part b and part c of P014 (a follow-up of P014b3) |
| Milestone | M5 (follows P014b5 and P013c, merged; `main` at `29b2170`) |
| Branch | `fix/014b6-debug-sms-allowlist` |
| PR title | `fix(sos): debug builds send SMS only to allowlisted test numbers [P014b6]` |
| Notion | [P014b6 row in the Prompt Log](https://app.notion.com/p/3f707370772081f7833cc1ab64269659) |
| Date | 2026-10-12 |
| Plan refs | Plan v7 §7.1, §7.5; ADR 0027 (note of 2026-10-12), ADR 0024 |

> **This changes how you test.** A debug build made **without** the Gradle property
> `saferoute.debugSmsAllowlist` no longer sends an SMS by itself: it opens your SMS app. To
> try automatic sending, put your own test phones on the list (section 11).
>
> **Release builds are unchanged**: no list, no banner, every emergency contact is texted.
>
> **Nothing was run on a phone.** All tests run on the JVM (Robolectric).

## 1. Objective

A debug build carries the SMS permission, and since P014b3 a finished SOS countdown texts
every emergency contact of the account. A developer's or tester's account can hold the
numbers of real people. Make a debug build text only numbers that were named on purpose
when it was built, say so on the screen, and leave release builds exactly as they are.

## 2. Context & prerequisites

- Preflight: `main` synced at `29b2170`; PR #60 (P013c) and PR #59 (P014b5) merged; no open
  pull requests; hook path `.githooks`. Notion: P013c set to Merged, a new row for P014b6.
- `android-ci` on `main`: green for `4334232` (the last run that touched Android).
- From P014b1 to P014b3: `SosAlerts` (who is told, decided once), `SosDispatch` (the way of
  sending), `AndroidSmsGateway` (the one place that sends), `SmsModeSource` (the app itself
  or the SMS app), the SKIPPED state for a contact who opted out.
- The follow-up this closes: "Safeguard for testers: a debug account with real contacts
  sends real SMS on every finished SOS" (P014b3).

## 3. Workflow executed

1. `/start-prompt`: sync, pull requests merged, Notion, branch.
2. Read the sender, the dispatcher, the gateway, the build file's SMS switch, the existing
   debug and release source sets, the arm dialog and the SOS screens, and the rule that
   turns a typed number into the stored form.
3. The design (section 7), then the code: the limit in `core/emergency`, the two places
   that enforce it, the list in `src/debug`, the stand-ins in `src/release`, the banner and
   its way to the three screens.
4. Tests. One of my own source checks was too strict (it found the word "BuildConfig" in a
   comment) and was narrowed to the use of the class.
5. A release build with CI's dummy settings, and a comparison of the two APKs.
6. `android-ci`: a step that checks the release APK. actionlint.
7. README, ADR 0027, CLAUDE.md, the failure matrix (row A13), the manual scripts (M29),
   the diagram.
8. The Android gate three times in a row; `/ship-prompt`.

## 4. Changes

**Build (`android/app/build.gradle.kts`)**

- The Gradle property `saferoute.debugSmsAllowlist`: phone numbers separated by commas.
  It becomes `BuildConfig.DEBUG_SMS_ALLOWLIST` in the **debug build type only**; a release
  build has no such field. A value with other characters than digits, `+`, commas, spaces,
  brackets, dots and hyphens fails the build with a message that names the property and
  never its content.

**`core/emergency` (the rule, in `src/main`)**

- `SmsRecipientLimit` (new): whether this build limits who it texts by itself, and whether
  a number is allowed. `UnlimitedSmsRecipients` is the answer of a release build.
- `SosAlerts.skipNotAllowed`: every message that has not left and whose number is not
  allowed becomes SKIPPED with the category `debug_not_allowlisted`.
- `SosDispatch`: before the first message leaves, those messages are skipped. When the
  build has a limit and **none** of the waiting contacts is allowed (an empty or missing
  list, or a list that matches nobody), the phone's SMS app is used for all of them, as in
  a build without the permission. The follow-up after "I'm safe" follows the same choice.
- `SosAlertSummary.held`: messages held back by the list are counted apart from contacts
  who opted out.
- `SmsLimitNotice` and `SmsLimitNotices`: what the screens are told: whether the app can
  send by itself, and to how many of the contacts. Counts only.

**`feature/emergency`**

- `AndroidSmsGateway`: refuses a number outside the limit before anything else (the second
  lock).
- `src/debug/.../BuildSmsRecipientLimit.kt` (new): `DebugSmsAllowlist` reads the list with
  the same rule as a contact's number, counts a number once and ignores unusable entries.
- `src/debug/.../BuildSmsNotice.kt` (new): the banner.
- `src/release/...` (new): the two stand-ins: no limit, no banner.
- `SosArmViewModel` and `SosViewModel` expose the notice; the arm dialog (Home and Search),
  the countdown and the active screen show it. A practice run shows none.
- The active screen: a line for messages held back by the list.

**Strings**: `sos_alerts_held` in the app; `debug_sms_banner_sending`,
`debug_sms_banner_none`, `debug_sms_banner_no_permission` in debug builds only. English and
Bengali, the same keys.

**CI (`.github/workflows/android-ci.yml`)**: a step that looks for the field, the class and
the banner strings in both APKs; the release APK must have none, the debug APK all three.

**Docs**: `android/README.md`, ADR 0027 (note of 2026-10-12), `CLAUDE.md`,
`docs/sos/failure-matrix.md` (row A13), `docs/sos/manual-test-scripts.md` (M29, the
precondition of M11, a safety paragraph, a troubleshooting item), the diagram.

**No change**: manifest, permissions, database, API contract, backend, the consent notice
and its version, the SMS texts.

## 5. Diagram

[`docs/diagrams/014b6-debug-sms-allowlist.svg`](../diagrams/014b6-debug-sms-allowlist.svg)
(source `.json`, also `.excalidraw` and `.png`): from the property to the two kinds of
build, and what happens to each contact when a countdown finishes.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug --rerun-tasks`, run 1 | Pass: 1107 tests, 0 failures, 0 skipped (4 min 48 s) |
| `./gradlew lint testDebugUnitTest assembleDebug --rerun-tasks`, run 2 | Pass: 1107 tests, 0 failures, 0 skipped (4 min 29 s) |
| `./gradlew lint testDebugUnitTest assembleDebug --rerun-tasks`, run 3 | Pass: 1107 tests, 0 failures, 0 skipped (4 min 18 s) |
| `./gradlew assembleRelease` with CI's dummy settings and `sendSmsEnabled=false` | Pass |
| Release APK: the field, the class, the banner strings | 0, 0, 0 hits (debug APK: 1, 6, 1) |
| actionlint 1.7.12 with shellcheck | Clean |
| `pnpm generate` (diagrams) | Pass |
| Every test named in the failure matrix exists | Pass: 277 names |
| markdownlint, link check, gitleaks | Pass (134 Markdown files; links and anchors 0 broken; no leaks) |

34 new tests (1073 before, 1107 now):

| Requirement | Tests |
| --- | --- |
| Parsing and normalisation (spaces, `+91`, duplicates, invalid entries) | `DebugSmsAllowlistTest`, 7 tests |
| The sender skips contacts that are not on the list | `SosDispatchTest` ("a build that limits its recipients texts only the allowlisted contacts and records the others as skipped", "the safe follow-up of a limited build goes only to the contact who was texted", "after a process death a limited build still texts nobody outside its list"); `SosAlertsTest` (2); `SosSmsTest` ("a number outside the build's list is refused before anything else and never reaches the phone") |
| An empty property disables automatic sending | `SosDispatchTest` ("with an empty list of test numbers nothing is sent by the app and the SMS app gets everyone", "with test numbers that match none of the contacts the SMS app is used, as with an empty list"); `DebugSmsAllowlistTest` ("a debug build always has a limit, and an empty or missing list allows nobody") |
| Release behaviour unchanged | `SosDispatchTest` ("without a limit every contact is texted and nothing is held back"); `SosAlertsTest` ("a build without a limit skips nobody"); `SmsRecipientLimitTest` ("no limit means every number, and nothing to tell the screens"); `DebugSmsAllowlistLayoutTest` ("the release build sets no limit and reads no list"); every earlier test, which runs with no limit; the release build and the APK check |
| Banner text | `BuildSmsNoticeTest`, 7 tests; `SmsRecipientLimitTest` (the counts); `SosAlertLinesTest` (the "held" line) |
| No numbers in logs | `DebugSmsAllowlistTest` ("the list never prints its numbers"); `DebugSmsAllowlistLayoutTest` ("the debug code of the list never logs or prints", "the build file gives the list to the debug build type only and never prints it", "no tracked properties file holds a list of numbers"); `BuildSmsNoticeTest` ("the banner holds a count and no digits of a phone number") |

A release build cannot be unit-tested here (the tests run against the debug variant). Its
two stand-in files are checked as sources, compiled by `assembleRelease`, and the APK is
scanned in CI.

**Failure matrix** (`docs/sos/failure-matrix.md`)

| Row | What changed |
| --- | --- |
| A13 (new): debug build whose account holds real contacts | Tests listed under its Evidence section; script M29. Status **Manual pending**: the tests are on `main` only after this merge, and "Automated pass" needs a green `android-ci` run on `main` |
| 1, 7 | No status change. In a debug build, automatic sending (M11, M12, M15, M16, M22) now needs the list; without it the build behaves as in row 7 (the SMS app) |
| All others | Unchanged |

## 7. Decisions & ADRs

ADR 0027, note of 2026-10-12 (P014b6). No new ADR.

- **The rule is in the core, the list is in the debug source set.** `core/emergency` knows
  only "a build may limit who it texts"; what the limit is comes from a class that exists
  twice, in `src/debug` and `src/release`. Nothing in `src/main` names the list.
- **Two locks.** The dispatcher records the truth (SKIPPED, with its own category); the
  gateway refuses the number whoever asks. Either alone would protect; together a later
  change to one cannot open the door.
- **"No list" means the SMS app, not "nobody".** The prompt asks for composer mode when the
  property is missing or empty. The same is done when the list matches none of the
  contacts: the banner the prompt gives for that case says "composer mode".
- **The SMS app is not limited.** There the tester sees every recipient and presses Send.
- **A third banner text.** When the app cannot send by itself anyway (no SMS permission in
  the build, or not granted), "sent for real to N contacts" would be false, and "no
  allowlisted contacts" would name the wrong reason. The banner then says that the
  permission is off.
- **The banner is also on the active screen**, so that "the SOS screen shows why" holds
  after the countdown too.
- **Held and opted out are counted apart.** Both are SKIPPED in the record; on the screen
  "the contact opted out" must not be said of a contact who did not.
- **The debug strings are debug resources.** A release APK does not contain the banner's
  words; one generic string (`sos_alerts_held`) is in the app because the active screen is.
- **A CI step, although the prompt says "Android only".** It is the Android workflow, and
  the property of a release build that matters most here is checked nowhere else.
- **Row A13 stays "Manual pending".** The rule of P013c is kept as written.

## 8. Security & privacy notes

- **Phone numbers at build time.** The list is personal data. It is read by Gradle from the
  user-level `gradle.properties`; Claude Code never reads that file. A tracked properties
  file with a list fails a test.
- **A debug APK built with a list contains those numbers** (a `BuildConfig` string). Such an
  APK must not be shared or attached anywhere public. CI builds without a list.
- The numbers are never shown, logged or printed: the list hides itself in `toString()`,
  the screens get counts, and the build error does not contain the value.
- No new permission, no manifest change, no new dependency.
- A tester can still text real people: by putting their number on the list, or by pressing
  Send in the SMS app. Both are deliberate acts with the recipients in view.
- Tests use the fake pattern of the contacts tests (`+91 X0000 100NN`) and `+1 000 ...`.

## 9. Known issues & risks

- **Unverified on a phone.** Script M29 is owed.
- **The "Send alerts automatically" card** under Emergency contacts still says "On" in a
  debug build without a list. The banner on the SOS screens says the truth; the card does
  not mention the list (a follow-up).
- **The banner's count is worked out when the screen opens.** A contact added a moment
  before is counted the next time the dialog opens; the sending itself always uses the
  list as it is.
- **A wrong number on the list is not noticed** by anything: an unusable entry is ignored
  silently, and the banner then counts fewer contacts than expected. Read the banner.
- 4 Bengali strings are drafts and **need human review before release**: the three
  `debug_sms_banner_*` (debug builds only) and `sos_alerts_held`.
- Lint: the new strings use "%1$d ... contacts" like the existing ones (a plural warning,
  not an error).

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database:

- **Closed:** "Safeguard for testers: a debug account with real contacts sends real SMS on
  every finished SOS" (P014b3), with a record of the closure (source P014b6).
- Phone check of the debug list (script M29) and Bengali review of 4 strings.
- Matrix row A13: set to "Automated pass" after `android-ci` is green on `main` with these
  tests; the card that says "On" without a list.

**For P014c:** the tile, the widget and the notification must start an SOS through the same
dispatcher, so the limit holds for them without further work; the readiness screen should
say in a debug build what the banner says. P014c also updates row A13.

## 11. How Rahul can verify

1. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug`.
2. **Before your next sending test**, add one line to your user-level `gradle.properties`
   (`<home>/.gradle/gradle.properties`), with the numbers of phones you own, and rebuild:
   `saferoute.debugSmsAllowlist=<your second phone>`. Do not paste the number to me or
   into any file of the repository.
3. Install the debug build and open the SOS control: the banner reads "DEBUG build: SMS is
   sent for real to 1 allowlisted contacts." (or the number of your contacts that are on
   the list).
4. Run script **M29** in `docs/sos/manual-test-scripts.md` (three short parts).
5. Check `android-ci` on the pull request, including the new step "The release APK has no
   debug SMS allowlist and no debug banner".

## 12. Learning notes

- **Build types and source sets.** An Android app is built in variants; this one has
  `debug` and `release`. Besides `src/main`, each has its own folder (`src/debug`,
  `src/release`) whose code and resources are compiled only into that variant. Two files
  with the same class name, one in each folder, let the rest of the app call one name and
  get different behaviour per variant.
  [Docs](https://developer.android.com/build/build-variants).
- **`BuildConfig` fields.** `buildConfigField` in the build file writes a constant into a
  generated class at build time. Declared inside `debug { }`, the constant exists only in
  debug builds; code that reads it must then live in `src/debug`.
- **Gradle properties.** `providers.gradleProperty("name")` reads a value from the command
  line (`-Pname=...`) or from a `gradle.properties` file; the one in your home folder is
  yours alone and never part of the repository.
  [Docs](https://docs.gradle.org/current/userguide/build_environment.html).
- **Allowlist.** A list of what is permitted; everything else is refused. Safer than a list
  of what is forbidden when the cost of a mistake is a message to a real person.
- **Defence in depth.** Two independent checks for one rule, so that a bug or a later
  change in one of them does not break the rule.
- **Fail closed.** With no list the build does the cautious thing (sends nothing by
  itself), not the convenient one.
