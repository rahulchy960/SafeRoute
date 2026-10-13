# P014d1: progress notes

A running note for the parts of P014d1, so that a new session can continue from `git log`
and this file alone. One line per finished step. The prompt logs
(`014d1a-...`, `014d1b-...`, `014d1c-...`) are the record; this file is only the bookmark.

## How part 1 is cut

P014d1 is above the ~800-line limit of one pull request, so it is shipped in three parts:

- **P014d1a** (`feat/014d1a-crash-reporting-core`): the spike, the build set-up and
  `core/diagnostics` (reporter, redaction, typed events, the device-local setting, the
  in-memory list of categories) with its tests, and the ADR. Nobody can switch collection on
  yet: no screen offers it.
- **P014d1b**: the choice (onboarding, Settings, once on Home), the notice document, the two
  buttons on the debug Developer screen.
- **P014d1c**: "Report a problem", the existing events wired to the reporter, the Play
  data-safety draft, the diagram.

## Spike findings (requirement 1)

Certain (tried on this build, or read in the Firebase documentation on 2026-10-13):

- The Crashlytics Gradle plugin 3.0.8 applies and builds with AGP 9.4.1 and Gradle 9.6.0,
  debug and release. The documented minimum is AGP 8.1.0, Gradle 8.0 and the Google services
  plugin 4.4.1. Two open reports say that plugin 3.0.6 failed with AGP 9; 3.0.8 did not fail
  here.
- The Firebase BoM in the catalog (34.19.0) picks `firebase-crashlytics` 20.1.1. The BoM was
  not raised.
- New libraries that arrive with it: `firebase-sessions` 3.0.8, `firebase-installations`,
  `firebase-datatransport` and the `datatransport` runtime, `firebase-encoders`, and four
  "interop" or "connector" stubs. No `firebase-analytics` and no `play-services-measurement`.
- Merged manifest: no new permission. New entries: six component registrars, the service
  `SessionLifecycleService` (declared `enabled="false"`), and from the transport library a
  service `TransportBackendDiscovery`, a service `JobInfoSchedulerService` and a receiver
  `AlarmManagerSchedulerBroadcastReceiver`.
- Collection is switched off with the manifest entry
  `firebase_crashlytics_collection_enabled` = `false` and switched on at runtime with
  `setCrashlyticsCollectionEnabled(true)`. The runtime value persists across launches.
  Switching it off takes effect at the next launch.
- While collection is off, the library still **keeps a crash on the phone** and sends it if
  collection is switched on later. So the app deletes unsent reports before it switches
  collection on, and again whenever it finds the setting off.
- Google Analytics is not required. Only "breadcrumb logs" need it; they stay off.
- Limits: 64 custom keys of up to 1 kB each; the eight most recent non-fatal errors are
  kept and sent at the next launch.
- Retention as published by Firebase: crash traces and their identifiers are kept for 90
  days.
- Mapping upload: `mappingFileUploadEnabled = false` for both build types. With it off, a
  build ran no upload task. Nothing is obfuscated yet (R8 arrives with P022).
- The plugin adds two build steps per variant: one writes a build id into the resources (the
  library refuses to start without it), one writes the git revision and the repository
  address into the resources. The repository is public, so this reveals nothing new.

Not certain:

- The sessions library was read in its source on the main branch, not in the 3.0.8 binary:
  there it sends a session, fetches its settings and asks for an installation id only when
  a subscriber (here: Crashlytics) has collection enabled.
- Whether a crash in the same run after switching off is still sent. The app deletes unsent
  reports when it is switched off and at every start while off.
- Whether the build with the dummy Firebase file passes in CI: it has no network step, but
  only the `android-ci` run can show it.
- What Google keeps of the installation id, and for how long after a deletion request.

## Steps

- P014d1a step 1 (spike and build set-up): done. Next: `core/diagnostics`.
- P014d1a step 2 (`core/diagnostics`: reporter, redaction, events, setting, tests): done. Next: ADR 0028, README, prompt log, gate, ship.
- P014d1a step 3 (ADR 0028, diagram, README, CLAUDE.md rule): done. Next: gate three times, release build, prompt log, ship.
- P014d1a step 4 (gate three times, release build, prompt log): done. Next: push, PR, Notion. Then P014d1b.
- P014d1b step 1 (choice in onboarding, Settings, Home; developer buttons; notice document; tests): done. Next: ADR note, matrix line, phone script, log, gate, ship.
- P014d1b step 2 (ADR note, matrix gap 9, gate three times, release build, prompt log): done. Next: push, PR, Notion. Then P014d1c.
- P014d1c step 1 (Report a problem, event wiring, facts, tests): done. Next: Play data-safety draft, ADR note, docs, gate, ship.
- P014d1c step 2 (Play draft, ADR note, gate three times plus once, release build, prompt log): done. Next: push, PR, Notion. Part 1 complete; then P014d2.
