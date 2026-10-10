# P014b5: the flaky unit test in android-ci (the daily clean-up job test)

| Field | Value |
| --- | --- |
| Prompt | P014b5 · a fix between P014b3 and P014c. No P014b4 exists in the repository |
| Milestone | M5 (follows P014b3, merged as `97669dc`) |
| Branch | `fix/014b5-ci-failing-test` |
| PR title | `fix(android): fix the flaky unit test in CI [P014b5]` |
| Notion | [P014b5 row in the Prompt Log](https://app.notion.com/p/3f4073707720812b952bc397482113df) |
| Date | 2026-10-10 |
| Plan refs | Plan v7 §17.5 (quality gate), §7.5 (failure matrix: no row affected); ADR 0027 |

> **No production code changed.** The failure was a defect of one test. The daily clean-up,
> its scheduler and the wipe at sign-out are as they were.

## 1. Objective

`android-ci` failed on `main` after the merge of P014b3 (run 37969380608, "1071 tests
completed, 1 failed") although the same change had passed on its pull request. Find the
root cause, fix it without weakening the test, add a regression test, and make the next
failure easier to read.

## 2. Context & prerequisites

- Preflight: `main` synced at `97669dc`; PR #58 (P014b3) merged; no open pull requests; hook
  path `.githooks`. Notion: P014b3 set to Merged, a new row for P014b5.
- The prompt left the failing test's name as a placeholder; it was read from the log of the
  run's first attempt and confirmed by Rahul during the session:
  `SosDeviceTest`, "the daily clean-up is planned once, however often it is asked for, and
  runs", `java.lang.AssertionError at SosDeviceTest.kt:159`.
- The run was started again while this prompt was in progress; its second attempt, on the
  same commit, **passed**. The test is flaky; the merge did not break it.

## 3. Workflow executed

1. `/start-prompt`: sync, PR #58 merged, Notion, branch.
2. Read the test, the scheduler (`WorkManagerPurgeScheduler`), the job (`SosPurgeWorker`)
   and, from the library's class files, how WorkManager's test driver decides when a job
   starts.
3. **Reproduction under CI conditions.** JVM in UTC with the locale en-US, through
   `JAVA_TOOL_OPTIONS` for those commands only (nothing committed). JUnit 4 under Gradle
   offers no random test order, so none was used.
   - The test class as it was on `main`, 50 runs: 50 passed. The failure does not occur by
     itself on this machine.
   - The same test with the clean-up slowed by one second (a temporary line in
     `SosPurgeWorker`, reverted, never committed), 3 runs: 3 failed, each with
     `expected:<ENQUEUED> but was:<RUNNING>` at line 159. This is the line of the CI
     failure.
4. **Root cause** (section 7), then the rewritten test, the regression test and the guard
   against a WorkManager left behind.
5. The fixed class, 50 runs under the same conditions: 50 passed. Then the whole unit-test
   task: **one failure in another class** (`SosEmergencyScreenTest`), caused by the new
   teardown of this prompt: it closed WorkManager's database while the regression test's
   held run was unfinished; when the garbage collector found that run, WorkManager ended
   it during a later test, on the closed database. The teardown now finishes every run and
   cancels the plan first. The whole task then passed twice under UTC and en-US.
6. `android-ci`: the unit-test reports are uploaded when the job fails.
7. The survey of tests with the same pattern; follow-ups in Notion.
8. The gate three times; a second batch of 50 runs of the class on the final code.
9. `/ship-prompt`.

## 4. Changes

**`android/app/src/test/.../feature/emergency/SosDeviceTest.kt`** (tests only)

- "the daily clean-up is planned once, however often it is asked for, and runs" gives its
  WorkManager a stand-in job (`HeldCleanUps`) that the test finishes itself, on the test's
  own thread. It now checks more than before: the job is of the class `SosPurgeWorker`,
  starts at once (`RUNNING`), repeats every 24 hours, waits for its next day after the run
  (`ENQUEUED`), is the same job throughout, and is started once however often the plan is
  asked for.
- New, the regression test: "a clean-up that is still running when the plan is asked for
  again is neither doubled nor restarted". It holds still the order of events that failed
  in CI.
- New: "the clean-up job itself runs and reports success" (the real `SosPurgeWorker`,
  called directly; this was the last line of the old test).
- New, before every test of the class: no WorkManager is left behind by an earlier test.
  After every test: runs finished, plan cancelled, WorkManager's database closed, the
  static field emptied.

**`.github/workflows/android-ci.yml`**: a step that uploads
`android/app/build/reports/tests/testDebugUnitTest/` (HTML) and
`android/app/build/test-results/testDebugUnitTest/` (XML) as the artifact
`android-unit-test-reports` when the job failed, kept for 7 days, next to the lint report.

**No change**: production code, manifest, permissions, build files, database, strings, API
contract, backend.

## 5. Diagram

No diagram needed: no flow, state machine, schema or infrastructure changed. The CI
pipeline gained one upload step that runs only after a failure; its order of steps is
unchanged.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| Old test, unchanged code, UTC and en-US, 50 runs | 50 passed (no failure on this machine) |
| Old test, clean-up slowed by 1 s (not committed), 3 runs | 3 failed: `expected:<ENQUEUED> but was:<RUNNING>`, line 159 |
| New tests, clean-up slowed by 1 s (not committed) | Pass |
| New guard with the teardown's reset disabled (not committed) | 4 of the 11 tests of the class fail, as intended |
| Fixed class, UTC and en-US, 50 runs (before the teardown correction) | 50 passed |
| Fixed class, final code, UTC and en-US, 50 runs | 50 passed |
| `:app:testDebugUnitTest`, UTC and en-US, final code, twice | Pass: 1073 tests, 0 failures, 0 skipped |
| `./gradlew lint testDebugUnitTest assembleDebug --rerun-tasks`, run 1 | Pass: 1073 tests, 0 failures, 0 skipped (4 min 28 s) |
| `./gradlew lint testDebugUnitTest assembleDebug --rerun-tasks`, run 2 | Pass: 1073 tests, 0 failures, 0 skipped (3 min 54 s) |
| `./gradlew lint testDebugUnitTest assembleDebug --rerun-tasks`, run 3 | Pass: 1073 tests, 0 failures, 0 skipped (3 min 58 s) |
| actionlint 1.7.12 with shellcheck | Clean |
| markdownlint, gitleaks | Pass (131 Markdown files; no leaks in the 4 commits scanned) |

`assembleRelease` was not run: no build file, `src/release` or `src/debug` changed.

The upload step itself can only be seen in a failing run of `android-ci`; it has not run.

**Failure matrix** (`docs/sos/failure-matrix.md`): no row is affected. The daily clean-up is
not a row of the matrix, and no code of the emergency flow changed. The tests the matrix
names in `SosDeviceTest` are unchanged and pass.

**What was confirmed in passing** (tests in `SosEngineTest`, unchanged, passing):

- "app start purges what is older than thirty days and wipes nothing while signed in";
- "the purge deletes what is older than thirty days and keeps the rest";
- "sign-out, the start screen and a blocked account wipe everything";
- `SafeRouteApplication.onCreate` still calls `SosHousekeeping.start()`, which purges at
  app start, plans the daily job and follows the session.

## 7. Decisions & ADRs

No ADR: nothing hard to reverse.

**Root cause.** Two facts together:

1. WorkManager's test driver starts the **first run of a periodic job as soon as it is
   planned** (later runs wait for the test to say that the period has passed).
2. `SosPurgeWorker` is a coroutine worker; its work happens on a **background thread**.

Planning the job is synchronous in the test (the test's WorkManager uses a synchronous
executor, so "exactly one job" was a sound check). But the old test then expected the state
`ENQUEUED` straight away. That is the state **after** the first run has finished; while
the background thread is still deleting old records, the state is `RUNNING`. A fast
machine finishes in time; the CI runner, on that run, did not. Time zone, locale and test
order play no part.

**A second defect, found on the way.** WorkManager keeps itself in a static field. The old
test left its test WorkManager there, so the app start of every later test in the same JVM
found it and planned the real clean-up on it, on a background thread, with a database from
a test that had ended. Nothing failed because of it, but it is state shared between tests.

**Decisions.**

- The fix is in the test. The production scheduler was checked and is right: unique
  periodic work, the stable name `daily-purge-of-emergency-records`, the policy `KEEP`,
  one day.
- The test controls the job instead of waiting for it: no sleep, no polling, no retry.
- `WorkManagerImpl.setDelegate(null)` is a restricted API of the library. It is used in the
  test's teardown only, because the library offers no public way to take a test
  WorkManager away again.
- The unclosed Room databases behind the "A resource failed to call close" warnings come
  from shared test wiring, not from single classes; they are a follow-up, not part of this
  fix.

## 8. Security & privacy notes

- No production change, no new permission, no new dependency.
- The uploaded reports contain test names, assertion messages and what tests printed.
  Tests use fake values only (`FAKE_...`, `+91000010NNNN`, round coordinates), so the
  reports hold no personal data. The artifact is readable by anyone who can read the
  repository's Actions runs, for 7 days.
- No secret is involved; the workflow's permissions are unchanged (`contents: read`).

## 9. Known issues & risks

- The failure was reproduced by slowing the job, not by itself: 50 unchanged runs passed
  here. The evidence that this was the CI failure is the same assertion, the same line and
  a mechanism that depends on the runner's speed.
- Other tests still depend on real threads and the real clock (section 10). Another flaky
  failure in `android-ci` is possible until they are dealt with.
- The lint crash noted in P014b3 may return.

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P014b5):

- **Close the Room databases that tests leave open.** Two shared sources:
  `TestDatabaseModule` (one in-memory database per Hilt test, 19 test files) and the real
  `SafeRouteApplication` in every non-Hilt Robolectric test (39 files), whose start-up
  clean-up opens the real database file.
- **Non-Hilt Robolectric tests run the real app start on real threads**: work started by
  one test can still run during the next.
- **Polling with the real clock and `Thread.sleep`**: `SosForegroundServiceTest.waitUntil`,
  `SosSmsTest` (line 94), `SosScreensTest.waitUntil`, and `ApiContactsRepositoryTest`
  (line 447: `Dispatchers.IO` and a loop with no time limit).
- **The real clock as test input**: `ContactsViewModelsTest`, `ContactsFlowTest`,
  `FollowFlowTest`, `SearchFlowTest`, `StartPointFlowTest`; real threads on purpose in
  `SignInToHomeTest` and `DataStoreSessionStoreTest`.
- **The JVM default locale is changed and restored** in `SosMessageTest`; safe while tests
  of one JVM run one after another.
- **Make `android-ci` a required check** once it has been stable (proposed in the PR).

Not found: a test or production code that reads the default time zone. The SMS builder
formats times with a fixed zone and `Locale.ROOT`.

**For P014c:** unchanged from section 10 of `014b3-sos-alerts-consent.md`.

## 11. How Rahul can verify

1. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug`.
2. Read the three tests about the daily clean-up in `SosDeviceTest.kt` and the class
   `HeldCleanUps` below them.
3. After the merge, look at the `android-ci` run on `main`. A staging deploy is not
   involved: nothing under `backend/` changed.
4. The upload can be seen only when a run fails: the run's page then lists the artifact
   `android-unit-test-reports`; open `index.html` inside it.
5. The ruleset change proposed in the PR is yours to make, later.

## 12. Learning notes

- **Flaky test.** A test that passes or fails on the same code. The usual cause is that it
  depends on something it does not control: another thread's speed, the clock, the order of
  tests, state another test left behind.
- **Race.** Two things happen "at the same time" and the result depends on which finishes
  first. Here: the test reading the job's state, and the job's thread finishing.
- **WorkManager states.** A periodic job is `ENQUEUED` while it waits, `RUNNING` while it
  works, and `ENQUEUED` again afterwards; it never ends by itself.
  [Docs](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/states).
- **Testing WorkManager.** `WorkManagerTestInitHelper` gives a WorkManager that works on the
  test's thread, and a `WorkerFactory` decides which class does the work, so a test can put
  a stand-in there.
  [Docs](https://developer.android.com/develop/background-work/background-tasks/testing/persistent/integration-testing).
- **`CoroutineWorker` and threads.** Its `doWork` runs on a background dispatcher, which is
  right in the app and awkward in a test.
- **Static state in tests.** All tests of one Gradle test process share one JVM. A static
  field set by one test is still set in the next; a test that sets one must reset it.
- **Garbage collection and futures.** A "future" that nobody can finish any more is ended
  with an error when the garbage collector notices, at a moment nobody chose. That is how
  an unfinished job from one test failed another.
- **CI artifacts.** Files a workflow run keeps for download. Uploading a report only on
  failure costs nothing on a good run.
