# P014d1a: opt-in crash reporting, the core and its redaction layer

| Field | Value |
| --- | --- |
| Prompt | P014d (diagnostics), part 1, first of three pieces: the spike, the build set-up and `core/diagnostics` |
| Milestone | M5 (follows P014c3, merged as `10bd68e`) |
| Branch | `feat/014d1a-crash-reporting-core` |
| PR title | `feat(android): opt-in crash reporting core with a redaction layer [P014d1a]` |
| Notion | [P014d1a row in the Prompt Log](https://app.notion.com/p/3f80737077208148b8faf429f1d3fc51) |
| Date | 2026-10-13 |
| Plan refs | Plan v7 §12, §13; ADR 0028 (new), ADR 0012 (note), ADR 0010 |

> **Nothing was run on a phone, and nothing was sent to Firebase.** Every test runs on the
> JVM against a stand-in for the library. Nobody has seen a report arrive in the console.
>
> **Nothing is collected after this part.** No screen offers the choice yet, so the
> setting stays off for everyone. The screens are P014d1b.

## 1. Objective

Give the developer crash and error visibility for the closed test without collecting
personal data. This piece builds the part nobody sees: the Crashlytics library behind one
reporter, the rules for what a report may hold, and the device-local setting that decides
whether anything is collected at all.

## 2. Context & prerequisites

- P014c3 (PR #68) is merged as `10bd68e`; its Notion row was set to Merged in the preflight.
- **The prompt's part 1 was cut into three pieces** because it is far above the ~800-line
  limit of one pull request (CLAUDE.md, "Conventions"):
  - **P014d1a** (this one): requirements 1 and 3, the tests that belong to them, the ADR
    and the diagram.
  - **P014d1b**: requirement 2 (the choice in onboarding, in Settings and once on Home; the
    notice document) and requirement 5 (the debug Developer buttons).
  - **P014d1c**: requirement 4 ("Report a problem"), the existing events wired to the
    reporter, the Play data-safety draft.
- The branch was created as `feat/014d1-crash-reporting-and-problem-report` and renamed
  before the first commit.

### What the discovery found

- **Build.** One Gradle module; versions in `gradle/libs.versions.toml`; AGP 9.4.1, Kotlin
  2.4.20, Gradle 9.6.0, the Firebase BoM 34.19.0 with `firebase-auth` only. The Google
  services plugin reads `app/google-services.json`; CI writes a dummy one, and
  `checkReleaseFirebaseConfig` refuses it for a release unless the allow flag is passed.
  R8 is off for both build types.
- **Manifest.** Permissions are pinned by `MainActivityTest`. Nothing about diagnostics.
- **Settings.** `feature/settings/SettingsScreen.kt` (682 lines) with `AccountViewModel`
  and `EmergencyShortcutsViewModel`.
- **Onboarding.** `navigation/OnboardingNavHost.kt`: the session state decides the screen
  (age, account notice, sign-in). The SOS alerts notice is asked later, from the contacts
  screen.
- **DataStore.** One `DataStore<Preferences>` provided by `SessionModule`; small classes
  such as `DataStoreRoutePreferences` hold flags in it, behind an interface with a fake.
- **Developer screen.** `src/debug/.../feature/developer/` with an empty stand-in in
  `src/release` (`DeveloperToolsLayoutTest`).
- **Logging and errors.** The app has two log sites, both filtered
  (`SafeLoggingInterceptor`, the map log filter). API failures are the sealed
  `ApiFailure`. There was no crash handler and no error reporting.
- **Events worth reporting that exist today.** SMS results as fixed words in
  `feature/emergency/SosSms.kt`; the trail modes of `SosTrail`; the recovery screen; the
  retry rule's end after 24 hours; `ApiFailure`. They are wired in P014d1c.
- **Backend.** Not looked at in this piece: it belongs to P014d2.

## 3. Workflow executed

1. `/start-prompt`: main synced, PR #68 confirmed merged, Notion updated, branch created.
2. Discovery, once (section 2).
3. Requirement 1: the spike, with the build changed and built to see what is true.
4. Requirement 3: `core/diagnostics` and its tests.
5. ADR 0028, the diagram, the README and the rule in `CLAUDE.md`.
6. The Android gate three times in a row, the release build, the checks of section 6.
7. A commit after each step, with a line in `docs/prompt-logs/014d1-progress.md`.

## 4. Changes

Build:

- `gradle/libs.versions.toml`, `build.gradle.kts`, `app/build.gradle.kts`: the Crashlytics
  Gradle plugin 3.0.8 and `firebase-crashlytics` (20.1.1 from the BoM);
  `mappingFileUploadEnabled = false` for both build types; the field
  `BuildConfig.CRASH_REPORTS_ALLOWED` (release: true; debug: the Gradle property
  `saferoute.debugCrashReports`, default false).
- `AndroidManifest.xml`: `firebase_crashlytics_collection_enabled` = `false`.

`core/diagnostics` (new package):

- `DiagnosticsReporter.kt`: the interface the app uses, `RedactingDiagnosticsReporter`,
  and `CrashBackend`, the seam to the library.
- `CrashlyticsBackend.kt`: the only file that imports the library. Four calls.
- `DiagnosticEvent.kt`: the six typed events, `SmsFailure`, `ApiErrorKind`,
  `ErrorCategory`.
- `DiagnosticsSanitizer.kt`: `DiagnosticKey` (ten keys, each with its accepted values) and
  the filter for numbers, coordinates, addresses, tokens and free text.
- `RedactedError.kt`: an error with its message replaced.
- `CrashReporting.kt`: connects the choice and the build to the reporter; the handler for
  a crash nobody caught.
- `DiagnosticsPreferences.kt`: the two flags.
- `DiagnosticsLog.kt`: the last 20 categories, in memory.
- `di/DiagnosticsModule.kt`; `SafeRouteApplication` starts `CrashReporting` first.

Docs: ADR 0028, a note in ADR 0012, the ADR index, `android/README.md`, the diagnostics
rule in `CLAUDE.md`, the diagram, this log and the progress note.

Size: about 560 lines of app code and build files and about 730 lines of tests in
`android/`. That is above ~800. The reporter is not worth merging without the tests that
pin its rules, so it was not cut further.

**Deployment impact:** none. No backend, image, workflow or cloud change. The app gains a
library; `android-ci` runs on this pull request.

## 5. Diagram

[`docs/diagrams/014d1-diagnostics-flow.svg`](../diagrams/014d1-diagnostics-flow.svg)
(source: `014d1-diagnostics-flow.json`). One diagram for all three pieces; the later
pieces update it.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` | Pass, three times in a row (the second and third with `--rerun-tasks`) |
| Unit tests | 1,288 tests, 0 failures; 36 of them are new |
| Lint | 0 errors, 34 warnings, none in the new files |
| `./gradlew assembleRelease -Psaferoute.apiBaseUrl=https://example.invalid/` | Pass |
| Tracked-secrets check (`git ls-files android` for properties, keystores, the Firebase file) | Prints nothing |
| Diagram generator | Pass |
| Markdown lint (changed documents) | Pass, 0 findings |
| gitleaks on `main..HEAD` | Pass, no leaks |
| `android-ci` with the dummy Firebase file | **Not known until it runs on the pull request** |

New tests (`core/diagnostics`):

- `DiagnosticsSanitizerTest`: phone numbers in seven ways of writing, coordinates,
  addresses, tokens and ids, free text, names against every key, each key's own values.
- `DiagnosticsReporterTest`: nothing reaches the library before opt-in; opt-in deletes
  stored reports first; opt-out; the keys; events as fixed categories; the list of events;
  message replacement with causes; the crash handler; a failing library; the in-memory
  list; nothing written to the log; collection follows the choice and the build.
- `DiagnosticsBoundaryTest`: one file uses the library; that file names nobody and sends no
  text; no analytics library is declared; mapping upload off; the package never logs,
  stores or connects; the reporter has four methods.
- `DiagnosticsDeviceTest`: the merged manifest switches collection off; the library's
  services and receivers are the recorded ones; no analytics component; the stored choice.

What the log test covers: the app's own log lines in the test process. It says nothing
about what the library writes to Logcat on a phone.

What the release APK holds that sounds like analytics, and is not: `client_analytics.proto`
(the transport library's own description of its upload counters) and
`firebase-measurement-connector.properties` (an empty connector stub). No Analytics library.

## 7. Decisions & ADRs

- **[ADR 0028](../adr/0028-diagnostics-and-crash-reporting.md), Accepted.** Opt-in, one
  door, messages replaced, typed events, keys with lists, no analytics, SOS reliability
  measured elsewhere. It holds the spike's table with what is certain and what is not.
- **A note in ADR 0012**: "Firebase Auth only" now excepts Crashlytics as ADR 0028 says.
- **Two interfaces instead of one.** The prompt asked for one `DiagnosticsReporter` with a
  Crashlytics implementation and a fake. The app sees exactly that. Behind it sits
  `CrashBackend`, so that the redaction rules are tested on what would reach the library,
  without the library.
- **Delete before enabling.** The library keeps a crash on the phone while collection is
  off and would send it later. The app deletes stored reports before switching on.
- **A debug build keeps the original message in Logcat** while it is not collecting, for
  the developer. Everywhere else a crash is handed on with its message replaced.
- **The BoM was not raised** (35.0.0 exists). That is a separate change.

## 8. Security & privacy notes

- Off in the manifest, off by default, off in a debug build without its flag. Nothing is
  sent before a person switches it on, and nobody can switch it on yet.
- A report cannot carry text: events and keys are closed lists, messages are replaced.
- No user id, no log lines in a report, no forced upload.
- The library starts with Firebase at app start and may write its own files on the phone
  before any choice. According to its documentation and source it sends nothing while
  collection is off; **this was not watched on a network.**
- Reports go to Google and are kept there for 90 days as published. The legal basis and
  the wording are for a lawyer.
- No secret, id, number or place is in this change. The Firebase file was not opened.

## 9. Known issues & risks

- **Unverified on a phone and against Firebase.**
- **CI with the dummy Firebase file is unverified** until `android-ci` has run.
- Every report is of the class `RedactedError`; how the console groups them is not known.
- Whether a crash in the same run after switching off is sent is not known.
- The sessions library was read in source, not in the shipped binary.
- Above the ~800-line limit (section 4).
- Until P014d1b the code has no effect a person can see.

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P014d1a):

- Lawyer: the legal basis for opt-in crash reports and Google as a processor.
- R8 mapping upload in the release pipeline (P022).
- Deleting crash data on request, and what Google keeps of the installation id.
- A production Firebase project, separate from staging.
- Raise the Firebase BoM (35.0.0) in its own change.
- Watch on a network that nothing is sent while the choice is off.

**Next: P014d1b** (say "P014d1b"). It needs nothing from Rahul. Then P014d1c, then
"P014d2". P015 is not started.

## 11. How Rahul can verify

Nothing can be seen on a phone yet. On the computer:

1. `cd android` and run `./gradlew lint testDebugUnitTest assembleDebug`: it must pass.
2. Open `core/diagnostics/CrashlyticsBackend.kt`: it is the only file with an import of
   `com.google.firebase.crashlytics`.
3. Install the debug build and use the app as before: nothing changed on screen.
4. On GitHub, check that `android-ci` is green on the pull request. It is the first run
   with the dummy Firebase file and the new plugin.

The phone script of the prompt (opt in, "Test crash", look in the Firebase console, opt
out, "Report a problem") comes with P014d1b and P014d1c, when the buttons exist.

## 12. Learning notes

- **Crashlytics** is a library that notices when the app crashes, writes down where in
  the code it happened (the "stack trace") and uploads that the next time the app starts.
  Guide: <https://firebase.google.com/docs/crashlytics/android/get-started>.
- **A Gradle plugin and a library are two things.** The library is code inside the app.
  The plugin runs on the computer while the app is built; here it writes a build id into
  the app so that the library can match a crash to a build.
- **Manifest `meta-data`** is a named value that a library reads from the app's manifest
  at start. Here it tells the library: do not collect by yourself.
- **An uncaught exception handler** is a function Android calls when an error was caught
  by nobody, just before the app closes. There is one per process, and each new one can
  call the one before it; that is how ours runs in front of the library's.
- **A "seam" for tests.** The app talks to an interface (`CrashBackend`); the real
  implementation calls the library and a test implementation writes the calls down. The
  rules can then be tested on a computer with no Firebase.
- **An allowlist** names what is permitted and refuses everything else. A blocklist names
  what is forbidden and lets everything else through. For personal data the allowlist is
  the safe one, because nobody can list every name.
- **`sealed interface`** in Kotlin: a type with a fixed set of subtypes, all in one file.
  The compiler knows the whole set, so a `when` over it must name each one.
  Docs: <https://kotlinlang.org/docs/sealed-classes.html>.
