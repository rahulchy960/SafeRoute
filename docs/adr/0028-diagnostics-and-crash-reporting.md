# ADR 0028: Diagnostics and crash reporting

- **Status:** Accepted
- **Date:** 2026-10-13
- **Prompt:** P014d1a (the build set-up and `core/diagnostics`). P014d1b (the choice on
  screen) and P014d1c ("Report a problem") add notes here.
- **Plan refs:** Plan v7 §12 (privacy), §13 (operations); [ADR 0010](0010-adults-only-and-consent-records.md),
  [ADR 0012](0012-firebase-config-in-builds.md), [ADR 0019](0019-privacy-in-urls.md),
  [ADR 0027](0027-sos-device-flow.md)

Legal statements here are drafts, **to be verified by a lawyer**.

## Context

- A closed test is about to start. Without a crash report the developer learns about a
  crash only if a tester writes, and then without a stack trace.
- The app handles the most sensitive data a phone has: where a person is, who their
  emergency contacts are, and what an SOS said. An error's message can quote any of it.
- The app's rule is that nothing about a person is processed before they agreed
  (ADR 0010). A crash report is tied to an installation of the app and travels to a third
  party (Google).
- ADR 0012 allowed Firebase Authentication only: "No Analytics, Crashlytics, App Check".
  This ADR changes that sentence for Crashlytics and for nothing else.
- The app has no code shrinking yet (R8 arrives with P022), so stack traces are readable
  without a mapping file.

### What the spike found (read and tried on 2026-10-13)

| Topic | Finding | Certainty |
| --- | --- | --- |
| Versions | The Crashlytics Gradle plugin 3.0.8 builds with AGP 9.4.1 and Gradle 9.6.0, debug and release. The documented minimum is AGP 8.1.0, Gradle 8.0, Google services plugin 4.4.1. The BoM in the catalog (34.19.0) picks `firebase-crashlytics` 20.1.1. | Certain (built here). Two open reports describe plugin 3.0.6 failing with AGP 9; 3.0.8 did not fail here. |
| Opt-in | The manifest entry `firebase_crashlytics_collection_enabled` = `false` switches automatic collection off; `setCrashlyticsCollectionEnabled(true)` switches it on at run time and is remembered across starts. Switching it off applies from the next start. | Certain (guide) |
| A crash while off | The library still keeps the crash on the phone and sends it when collection is switched on later. | Certain (guide). Hence "delete before enabling" below. |
| What a report holds | A stack trace, the phone's model and Android version, the app's version, an installation id of the library, a Firebase installation id, and whatever the app adds (keys, logs, a user id). | Certain (Firebase's privacy page) for the list; the app adds only what this ADR allows. |
| Retention | Crash traces and their identifiers are kept for 90 days. | As published by Firebase; not something the app controls. |
| Google Analytics | Not required. Only "breadcrumb logs" need it. | Certain (guide). It is not added. |
| Extra libraries | `firebase-sessions`, `firebase-installations`, `firebase-datatransport` and the transport runtime, `firebase-encoders`, four interop stubs. No analytics or measurement library. | Certain (dependency report) |
| Merged manifest | No new permission. New: six component registrars, the service `SessionLifecycleService` (declared switched off), the transport library's two services and one receiver. | Certain (`DiagnosticsDeviceTest` pins the list) |
| The sessions library | It sends a session, fetches its settings and asks for an installation id only when a subscriber (here: Crashlytics) has collection enabled. | Read in the library's source on its main branch, **not** in the 3.0.8 binary, and not watched on a network. |
| Mapping upload | `mappingFileUploadEnabled = false` for both build types; no upload task ran. | Certain (built here) |
| Build steps of the plugin | One writes a build id into the resources (the library refuses to start without it); one writes the git revision and the repository's address. | Certain (task list). The repository is public. |
| The dummy Firebase file in CI | The build has no network step. | **Not certain** until `android-ci` has run on the pull request. |

## Decision

1. **Crash reports are opt-in.** One device-local setting, "Send crash reports", off by
   default (`DiagnosticsPreferences`, two flags in DataStore). It is not part of the
   account and is never sent to the SafeRoute server. Collection is switched off in the
   manifest; the app switches it on only after the person did.
2. **A debug build collects nothing** unless the developer sets the Gradle property
   `saferoute.debugCrashReports=true`. A release build follows the person's choice alone.
3. **Delete before enabling.** Before collection is switched on, and at every start that
   finds the choice off, the app deletes the reports the library stored on the phone. A
   crash from before the person agreed is therefore never sent.
4. **One door.** `core/diagnostics/DiagnosticsReporter` is the only way to report. Only
   `CrashlyticsBackend.kt` uses the library (`DiagnosticsBoundaryTest`). The backend makes
   four calls: collection on or off, delete unsent reports, set a key, record a non-fatal
   error. It never sets a user id, never writes a log line into a report and never forces
   an upload.
5. **Redaction rules.**
   - An error's **message is thrown away**, for caught errors and for crashes alike. What
     is reported is the name of the error's class, a fixed category and the stack trace
     (`RedactedError`); a cause is treated the same way, four levels deep. A handler in
     front of the library's own does this for a crash nobody caught.
   - A **non-fatal report is a typed event**: `SMS_SEND_FAILED(result)`, `SERVICE_KILLED`,
     `RECOVERY_SHOWN`, `LOCATION_UNAVAILABLE`, `API_ERROR(kind)`, `ALERTS_GIVEN_UP`. An
     event has no field for text; the two values are enums of fixed words.
   - A **custom key** is one of ten (`DiagnosticKey`: app version, build type, Android API
     level, phone-maker family, language, kind of network, SMS mode, three permission
     states). Each key has a list or a narrow shape of accepted values; anything else is
     dropped. A second filter drops whatever looks like a phone number, a coordinate, an
     address, a token or free text.
6. **Never reported, and why.**
   - An SOS that started or went well, and any SOS start: a report would say that this
     installation had an emergency at that time.
   - Contacts, searches, routes, positions, message texts, phone numbers, tokens: they are
     what the app exists to protect.
   - A user id, an account id or a phone number: a report must not name a person.
   - Screens visited, taps, sessions for statistics: that is analytics.
7. **No analytics.** No Google Analytics, Performance Monitoring, Remote Config, App Check
   or Cloud Messaging arrives with this ADR. Breadcrumb logs stay off because they need
   Analytics. A test fails if one of these libraries is declared or one of their
   components appears in the merged manifest.
8. **"Report a problem" is the other path** (P014d1c): a screen shows a short text with
   the app version, the phone and the last 20 event categories; the person reads it and
   sends it by e-mail or the share sheet themselves. The app sends nothing. The list of
   categories (`DiagnosticsLog`) lives in memory only and holds a category and a time.
9. **SOS reliability is not measured through crash reports.** It comes from the tests,
   the manual scripts in `docs/sos/` and, from P015, the server's SOS sessions. A crash
   report says that something broke on a phone whose owner opted in; it says nothing
   about how often an SOS works.
10. **Mapping upload is off** until the release pipeline exists (P022).

## Alternatives considered

- **On by default with an opt-out.** More reports. Rejected: it processes data about a
  person before they agreed (ADR 0010), and a tester of a safety app should not have to
  find a switch to stop it.
- **No crash reporting, only "Report a problem".** Nothing leaves the phone without a
  hand on it. Rejected as the only path: a crash at start cannot be reported from inside
  the app, and most people never write. It is kept as the second path.
- **A crash collector of our own.** Full control of the data. Rejected: a new server that
  receives stack traces is a new place to leak from and to operate, for one developer.
- **Passing the library to the whole app.** Less code. Rejected: one `recordException`
  with an unredacted error would undo every rule above, and no test could see it.
- **A filter for "bad" text only, without lists per key.** Rejected: a filter cannot tell
  a first name from an ordinary word.

## Consequences

- Reports come only from testers who opted in. The picture of field failures is partial
  by design; the failure matrix says so from P014d1b.
- Every crash is reported under the class `RedactedError`, with the original class in the
  text. Grouping in the console relies on the stack trace. If the console groups badly,
  revisit the form of the error, not the redaction.
- A debug build that is not collecting keeps the original message for the developer's
  Logcat. Such a crash may be stored by the library; rule 3 deletes it before any upload.
- Whether a crash in the same run after switching off is still sent is not known. The app
  deletes unsent reports when the choice is switched off and at each start while off.
- Until P014d1b nobody can switch the setting on: nothing is collected.
- Reports that were sent stay with Google until its retention ends. Deleting them on
  request, and a production Firebase project, are follow-ups.
- The legal basis and the wording of the choice need a lawyer; the Play data-safety form
  must name crash logs, diagnostics and the installation id (P014d1c).
- Revisit this ADR if a second product of the same vendor is wanted, if the sessions
  library is seen to send anything before the choice, or when R8 is switched on.

## References

- Firebase: "Get started with Crashlytics for Android"
  (<https://firebase.google.com/docs/crashlytics/android/get-started>); "Customize crash
  reports" (<https://firebase.google.com/docs/crashlytics/android/customize-crash-reports>);
  "Get readable crash reports"
  (<https://firebase.google.com/docs/crashlytics/android/get-deobfuscated-reports>);
  "Privacy and security in Firebase" (<https://firebase.google.com/support/privacy>).
- `android/app/src/main/java/com/saferoute/app/core/diagnostics/`,
  `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`.
- Prompt log: [`docs/prompt-logs/014d1a-crash-reporting-core.md`](../prompt-logs/014d1a-crash-reporting-core.md).

## Note of 2026-10-13 (P014d1b): where the choice is put

- **Onboarding.** One step after the account notice and before the phone number: the
  notice text and an unticked box, "Send crash reports". "Continue" works either way and
  records what the box says. Nothing is sent from that screen, and the order "age, consent,
  phone" is unchanged: the step sits between the last two.
- **Settings.** A section "Diagnostics" with the switch and the same notice, including
  that reports already sent stay until the provider deletes them.
- **Home, once.** A person who finished onboarding before this version has never been
  asked. They see a card in the Home sheet with two buttons of equal weight. It is a card,
  never a dialog: it blocks nothing, does not cover the SOS control and is not part of any
  SOS screen. Either button answers it for good.
- **The answer stays on the phone across sign-out.** It is a setting of the device, not of
  the account, and the server never learns it. A second person who signs in on the same
  phone finds the switch as it was left, in Settings. To be looked at by the lawyer.
- **The text** is the `diagnostics_*` strings, mirrored in
  [`docs/legal/diagnostics-notice-v1.md`](../legal/diagnostics-notice-v1.md)
  (`CrashReportsChoiceTest`), version `2026-10-diagnostics-draft1`. Changing what a report
  holds means changing the text and its version in the same prompt.
- **The debug Developer screen** has "Test crash" and "Test non-fatal" and says whether
  this build is collecting. They exist in debug builds only.
