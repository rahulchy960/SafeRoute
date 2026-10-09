# P014a1: SOS on the device: local records, state machine, recovery rules and retention

| Field | Value |
| --- | --- |
| Prompt | P014 · part a, first half (**P014a1 records and logic**; P014a2 service and screens; P014b SMS; P014c entry points and readiness) |
| Milestone | M5 (depends on P013b2, merged as `fe7f621`, and P012f, merged in PR #40) |
| Branch | `feat/014a1-sos-data-and-state-machine` |
| PR title | `feat(sos): device-first SOS records, state machine, recovery rules and retention [P014a1]` |
| Notion | [P014a1 row in the Prompt Log](https://app.notion.com/p/3eb07370772081d48618c394ee99fa4f) |
| Date | 2026-10-09 |
| Plan refs | Plan v7 §3.2 F-08, §7.1–7.5, §12.2; addendum v7.1 section B; ADRs 0008, 0010, 0015, 0024, 0027 |

> **P014a is split, and this is the first half.** Part a of the prompt (three tables, the
> state machine, a foreground service, three screens, practice mode, recovery, retention and
> their tests) is several times the ~800-line guide. The clean cut is the same as in P013b:
> **data and logic here, everything the user sees or Android runs in P014a2.** This part has
> no screen, no service, no user-visible string and **no manifest change**. It can ship alone
> without harm: nothing can start an emergency yet.
>
> **Moved to P014a2:** A3 (service), A4 (screens), A5 (practice mode), A6 (112 in every SOS
> state), the screens of A7, A8 (location fallbacks), the manifest part of A9, the daily
> WorkManager purge of A1 (WorkManager changes the merged manifest, and this part leaves the
> manifest alone; the purge at app start is here), the manual phone checks.
>
> **Size:** about 1,950 added lines outside the generated schema and diagram files (source
> 700, tests 650, documents 600). Over the guide; a third is tests and a third is documents.
>
> **Not verified here:** anything on a phone. All tests run on the JVM (Robolectric).
>
> **Open point:** the prompt's place for the P013b phone results was left empty. They are
> recorded as "not recorded" (revision of the P013a log), not as "not yet run".

## 1. Objective

Make an emergency exist on the phone as data that survives the death of the app's process:
the records, the rules for moving between states, the rules for what to show when a record
is found again, and how long it is kept. No server call, no screen and no message yet.

## 2. Context & prerequisites

- Preflight: `main` synced at `d327a04`; PR #50 (P013b2), PR #52 (P012i) and PR #40 (P012f)
  merged; no open pull requests; hook path `.githooks`. Notion: P012i set to Merged.
- Present before this part: Room with the `contacts` table (version 1), `ActiveSosContacts`,
  the session machine, the emergency dialog, tile and pinned notification.
- The Plan PDF cannot be read on this machine; the specification in the prompt was used.

## 3. Workflow executed

1. `/start-prompt`: sync, merged PRs checked, Notion, branch.
2. A0 spike: Android guides (foreground service types, background start restrictions,
   notification permission) and the platform sources of API level 37 installed with the SDK
   (`TileService`, `KeyguardManager`, `Activity`, `Service`, `VibrationAttributes`). The
   reference pages of developer.android.com could not be read by the fetch tool, so the
   sources were used. Results: ADR 0027, "What the spike found", each marked certain or
   uncertain.
3. Decided the split (above).
4. Wrote `core/emergency` (model, state machine, engine, recovery, housekeeping, UUID v7) and
   the Room part in `core/data` (entities, DAO, migration 1 → 2, store), then the tests.
5. Carry-over: revision of the P013a log with the staging checks Rahul reported.
6. Later instruction in the session: create or update `docs/sos/failure-matrix.md`. It did
   not exist (there is no P013c in the repository), so it was **created** from the table in
   section 6.
7. ADR 0027, diagram, CLAUDE.md, `/ship-prompt`.

## 4. Changes

**`core/emergency`** (new package; plain Kotlin except for the injection annotations)

- `SosModel.kt`: `SosState`, `SosEntryPoint`, `SosSyncState`, `SosEvent`, the pure function
  `nextSosState`, `SosRecord`, `SosPoint` (hides itself in `toString()`), the `SosStore`
  interface, `SOS_COUNTDOWN` (5 s) and `SOS_RETENTION` (30 days).
- `SosEngine.kt`: `startCountdown`, `cancel`, `trigger`, `markActive`, `resolve`, `current`,
  `recovery`; the pure function `recoveryFor`. Each step writes first and reports whether it
  happened.
- `SosHousekeeping.kt`: purge at app start, wipe on sign-out, start screen and block.
- `UuidV7.kt`: `UuidV7Generator` (RFC 9562), no dependency.

**`core/data`**

- `local/SosEntities.kt`: `sos_records`, `sos_actions`, `sos_points` with the columns of A1;
  actions and points reference the record with `ON DELETE CASCADE`.
- `local/SosDao.kt`, `local/Migrations.kt` (`MIGRATION_1_2`), `RoomSosStore.kt`.
- `SafeRouteDatabase`: version 2; `app/schemas/.../2.json` committed.
- `di/DataModule.kt`: the migration, `SosDao`, `SosStore`, `UuidV7Generator`.

**App**: `SafeRouteApplication` starts `SosHousekeeping`.

**No change:** manifest, permissions, strings, navigation, API contract, backend.

**Docs**: ADR 0027; `docs/sos/failure-matrix.md` (new); CLAUDE.md, Android rules; revision of
`013a-contacts-backend.md`.

## 5. Diagram

[`docs/diagrams/014a-sos-device-state-machine.svg`](../diagrams/014a-sos-device-state-machine.svg)
(source `.json`, also `.excalidraw` and `.png`): the states, which are stored, and the three
recovery cases. P014a2 adds the service to the same diagram.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` | Pass: 896 tests, 0 failures, 0 skipped; lint without errors |
| `cd tools/diagrams && pnpm generate` | Pass; only the new diagram's files changed |
| markdownlint, gitleaks, relative links | See the pull request (run in `/ship-prompt`) |
| `assembleRelease` | Not run: no build file, `src/release` or `src/debug` change |

New tests (38): `SosStateMachineTest` (4), `SosRecoveryTest` (5), `UuidV7Test` (5),
`SosEngineTest` (15), `SosHousekeepingTest` (2), `SosCoreBoundaryTest` (1), one migration
test, and the existing schema-file test now covers version 2. `SosEngineTest` runs on a real
SQLite file and acts out process death by closing the database and opening the same file
with new objects.

**Failure matrix** (the full table with test names is `docs/sos/failure-matrix.md`):

| Row | Covered here | Status |
| --- | --- | --- |
| 1 No mobile data | No | P014b |
| 2 No signal | Only "local record kept" (no network code in the core) | Automated pass for that; rest P014a2, P014b; manual pending |
| 3 API down | No | P015 |
| 4 App killed | Restore from Room in every state, three recovery cases | Automated pass at data and logic level; manual pending |
| 5 Phone rebooted | Same logic as row 4 with a later clock | Automated pass at data and logic level; manual pending |
| 6 Duplicate trigger | One record for many starts; one trigger for many timers | Automated pass at data and logic level; manual pending |
| 7 SEND_SMS denied | No | P014b |
| 8 No GPS fix | Only storage of a point without accuracy and with a mock flag | Storage only; logic P014a2 |
| 9 FCM failure | No | P015 |
| 10 Worker crash | No | P015 |
| 11 Accidental trigger | Cancel deletes; after the trigger only "I'm safe" | Automated pass at data and logic level; manual pending |

**Requirements of A10 not met in this part** (all move to P014a2): hold-to-arm timing,
countdown timer, service stopped on cancel, location fallbacks, lock-screen redaction,
practice mode, accessibility, string parity for new strings, the manifest list.

## 7. Decisions & ADRs

[ADR 0027](../adr/0027-sos-device-flow.md), Accepted. Decisions that differ from, or add
to, the prompt:

- **ARMING is not stored.** A hold the process did not survive is a released hold.
- **A countdown found after its end is always asked about**, also when it is only a second
  late. The prompt requires the question beyond 60 seconds and says "never silently send".
- **The package is `core/emergency`**: a package named after the three letters fails
  `SosColourUsageTest`, which looks for that word.
- **The purge goes by the record's start**, whatever its state. An emergency left active for
  30 days is deleted; the long-running policy is a follow-up.
- **`sos_actions.type` and `.state` are text**, not enums, until P014b defines them.
- `practice` exists as a column and is always false.

## 8. Security & privacy notes

- No network call, no log line, no new permission, no manifest change. `SosCoreBoundaryTest`
  reads the sources of the core and fails on logging, network, messaging or key-value
  storage in them.
- `sos_points` (where the user was) and `sos_actions` (a contact's name and number) are
  personal data in the app's private storage, never backed up, deleted with their record,
  after 30 days, and when the session leaves the signed-in states. Nothing writes to them yet.
- Types that hold a position or a contact hide it in `toString()` (tested).
- The id of an emergency carries its creation time and random bits, nothing else.
- The database is **not encrypted** (follow-up from P013b1, now more urgent).
- Tests use round fixture coordinates and "Test Contact N" with `+9190000100NN`.
- Retention and purpose wording are drafts, to be verified by a lawyer.

## 9. Known issues & risks

- Unverified on a phone; the only phone-visible effect is the database upgrade.
- If the process dies inside the countdown and the app is not opened again, nothing is sent
  (ADR 0027, known limits).
- Signing out during an active emergency deletes it.
- Times are wall-clock times: a changed clock moves the purge and turns a countdown into the
  question.
- A Hilt test that starts `MainActivity` now also runs the purge on the in-memory database
  from a background thread. The full suite passed; if a flaky test appears, look here first.
- The spike could not confirm the vibration behaviour in Do Not Disturb or what a restarted
  sticky `location` service may do; the design relies on neither.

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P014a1):

- Encrypt the local database before the closed test (raises the P013b1 follow-up).
- Long-running SOS policy: what happens to an emergency left active for days.
- Should sign-out be refused, or ask, while an SOS is active?
- Lawyer review: 30-day retention of the trail on the phone.
- Report the P013b2 phone checks (still not recorded).

**For P014a2:** inject `SosEngine`; call `startCountdown(entryPoint)` and start the service
only for `SosStart.Started`; run the timer from `record.countdownEndsAt` and call `trigger()`
at its end (it answers once); do the side effects only on a non-null answer, then
`markActive()`; call `engine.recovery()` on start, resume and notification taps and show the
three cases; `trigger(force = true)` is "Send now"; write points with `SosStore.addPoint`;
add the daily purge job (`SosHousekeeping.purgeExpired`), the three permissions, the manifest
test, the screens, practice mode and the manual scripts; add the new emergency files to
`SosColourUsageTest` where they use the red.

## 11. How Rahul can verify

1. Read ADR 0027, especially "What the spike found" and "Known limits", and the diagram.
2. With the build from `main` installed and at least one contact saved, install this branch's
   debug build over it (no uninstall). In airplane mode open Settings → Emergency contacts:
   the same contacts are there. That is the database upgrade from version 1 to 2.
3. Sign out and in again: the contacts come back.
4. Nothing else is visible: there is no new screen, permission or notification.
5. Say "P014a2" for the service and the screens.

## 12. Learning notes

- **Process death.** Android may end the app's process whenever it is not in front; all
  objects in memory are gone, files and databases remain. Code that matters must be able to
  start again from what is on disk. See developer.android.com, "Processes and app lifecycle".
- **State machine.** A fixed list of states and a table that says which event leads from
  which state to which. Written as one pure function (`nextSosState`), it can be tested
  completely: every state against every event.
- **Write first, act second.** If the app writes "triggered" and then dies, the restart sees
  it and continues. If it acted first and died before writing, it would act again.
- **Conditional update.** `UPDATE ... WHERE state = 'COUNTDOWN'` changes the row only if it
  is still in that state and reports how many rows changed. Two callers get 1 and 0, so only
  one goes on. This is how a duplicate trigger is prevented without trusting timers.
- **Room migration.** A phone that has version 1 of the database gets the SQL statements
  that turn it into version 2. Room compares the result with the exported schema file; the
  test does the same on the JVM. See "Migrate your Room database".
- **Foreign key with CASCADE.** The points and actions name their record; deleting the
  record deletes them too, in the database itself.
- **UUID version 7.** An id that begins with the time, so later ids sort later.
- **Foreground service** (for the next part): a service with a visible notice that Android
  keeps running. One of type `location` can only be started while a screen of the app is
  visible. See "Foreground service types".
