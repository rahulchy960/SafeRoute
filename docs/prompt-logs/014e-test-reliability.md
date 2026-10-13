# P014e: test reliability

| Field | Value |
| --- | --- |
| Prompt | P014e (test reliability) |
| Milestone | M5 (follows P014d2, merged as `e99fc04`) |
| Branch | `fix/014e-test-reliability` |
| PR title | `fix(android): remove real-time waits and close databases in unit tests [P014e]` |
| Notion | [P014e row in the Prompt Log](https://app.notion.com/p/3f8073707720817ab5e8d8e42e5d79db) |
| Date | 2026-10-13 |
| Plan refs | Plan v7 §17.5 (quality gate); ADR 0008, ADR 0027 |

> **Tests and test setup only.** No file under `src/main`, `src/debug` or `src/release`
> changed, no test was skipped, deleted or given a retry, and no test exposed a production
> bug. Two things changed what a test runs under, and both are named in section 9: tests
> without a named application get a plain one, and one assertion of `CoreModuleTest` now
> looks at the module instead of the test's graph.

## 1. Objective

Make the Android unit tests independent of how fast the machine is: no sleeping, no polling
with a time limit, and nothing left open when a test ends.

## 2. Context & prerequisites

- P014c3 (PR #68) is merged; P014d2 (PR #72) is merged as `e99fc04` and its Notion row was
  set to Merged at the preflight.
- Evidence the prompt starts from (P014c3 log, section 6): eight test files with
  `Thread.sleep` or a real-clock deadline, ten with the Compose rule's `waitUntil`, no Hilt
  test closing its in-memory database. P014b5 had fixed one flaky test.
- What the survey of this prompt added: **48 of 72 Robolectric test classes started the
  real application.** It opens the real database and starts the app-lifetime work for
  every test, and no test can close either. Most of the "failed to call close" lines came
  from there, not from the Hilt tests. And every Hilt test left its app-lifetime scope
  running into the tests after it.

## 3. Workflow executed

1. `/start-prompt` steps: `main` synced, tree clean, hook active, P014c3 and P014d2 merged,
   Notion rows updated, branch created.
2. Survey with `grep` over `src/test` and `src/testDebug` (sleeps, clocks, `waitUntil`,
   database builders, the application each test runs under).
3. Step 2 first (databases and scopes), one commit; then step 1 (waits), one commit; then
   the five gate runs; then the documents.
4. Gate commands, inside `android/`, with `JAVA_HOME` set to Android Studio's JDK for the
   command only and `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC -Duser.language=en
   -Duser.country=US`: `./gradlew lint testDebugUnitTest assembleDebug --rerun-tasks`.

## 4. Changes

**How a Hilt test waits (new).** `core/di/TestCoreModule.kt` replaces `CoreModule` in every
Hilt test. It hands out the same dispatchers and the same app-lifetime scope, on the same
threads, wrapped so that every piece of work handed to them is counted (`BackgroundWork`).
Room's query and transaction threads are counted the same way (`inMemoryDatabase(context,
work)`). `BackgroundWork` is registered as an Espresso idling resource, so
`compose.waitForIdle()` and `Espresso.onIdle()` return when the count is zero.
`ComposeSettled.kt` adds `compose.settled { condition }`: wait for rest, then require the
condition. `BackgroundWorkTest` tests the count with a hand-run stand-in for the threads.

**How a Hilt test cleans up (new).** `core/data/HiltTestCleanup.kt`: a rule that cancels the
app-lifetime scope, waits for it to end and then closes the database. `TestDatabaseModule`
notes both when the database is created. The rule stands next to every `HiltAndroidRule`
(23 places in 22 files, plus `EmergencyActivityTest`); `HiltTestCleanupTest` fails when one
is missing, when a test sleeps, or when a file other than `SignInToHomeTest` polls with a
time limit.

**The application a test runs under.** `src/test/resources/robolectric.properties` now
names a plain `android.app.Application` as the default. Six tests needed the app's Hilt
graph and said so by failing: the five of `EmergencyActivityTest` (now a Hilt test) and the
test of the clean-up job (moved from `SosDeviceTest` to the Hilt class
`SosForegroundServiceTest` in the same file). The other 46 classes passed unchanged.

**A clock a test can stop.** `TestClock` (in `TestCoreModule.kt`) is the real clock until
`stop()`. `ContactsFlowTest`, `FollowFlowTest`, `SearchFlowTest` and `StartPointFlowTest`
compared fixture times with `System.currentTimeMillis()` against windows of 10 seconds,
5 minutes and 60 seconds; they now stop the app's clock and read it.

**Single tests.**

- `SosSmsTest`, "it hands one multipart message to the phone and waits for an answer per
  part": was a second thread with a polling loop; now a coroutine in `runTest` that runs on
  the test's thread until it waits for the phone's answer. Its time limit counts in
  virtual time.
- `ApiContactsRepositoryTest`, "a list that arrives after the copy was emptied is not
  written": was a 300 ms server delay and a sleep loop; now `FakeApi.hold(route)` keeps
  the answer until the test releases it. Real HTTP, so a real thread, but the order is the
  test's.
- `CoreModuleTest`: see section 9.

**Every affected test** (50 tests in 13 files; the wait or the clock they use changed):

- `ContactsFlowTest.kt` (5):
  - "from the home card to an invited contact, with the sms app in between"
  - "not now hides the card and is remembered as a point in time"
  - "settings leads to the list, a contact opens, and an opted-out one cannot be invited"
  - "offline the list opens read-only and adding explains itself"
  - "stop removes everything after one confirmation"
- `CrashReportsFlowTest.kt` (5):
  - "a new person is asked before sign-in, and continuing unticked leads on with a no"
  - "ticking the box in onboarding stores a yes"
  - "a person who was asked before goes straight to sign-in"
  - "a person onboarded earlier sees the card on Home once, beside a working SOS control"
  - "Settings leads to Report a problem, which shows the text and notes what was recorded"
- `DirectionsFlowTest.kt` (4):
  - "the first time - the note, then routes from my location, drawn on the map, and back in two steps"
  - "without the location permission it explains, sends nothing, and never asks by itself"
  - "while routes load the emergency button still reaches the dialer"
  - "the start, the destination, the routes and the credit never reach the log"
- `FollowFlowTest.kt` (3):
  - "Start follows on screen only - leaving pauses with a note, and ending asks first"
  - "the permission taken away while following ends it and says what is needed"
  - "with approximate location Start explains and offers precise location instead of following"
- `SearchFlowTest.kt` (6):
  - "search, choose a result, see the pin and the card, then back clears it and back again leaves"
  - "the search prefers the area the map shows, and needs no location permission"
  - "with location allowed and a recent position, the search is sent from there - still no permission dialog"
  - "the close button on the card clears the place"
  - "when search fails the message is calm and the emergency button still reaches the dialer"
  - "the typed text and the chosen place never reach the log"
- `StartPointFlowTest.kt` (3):
  - "Change start leads through search to routes from the chosen place, with Preview, and back"
  - "leaving search without choosing keeps the start, and the next search is an ordinary one"
  - "a tap on a route's line selects its card"
- `CrashReportsChoiceTest.kt` (4):
  - "the onboarding answer is stored and counts as asked"
  - "the Settings switch writes the choice"
  - "a person who was never asked sees the card once, and no thanks is a no"
  - "the card's other button is a yes, and the card goes away too"
- `SosRestartTest.kt` (3):
  - "showing posts one notification, and the running SOS or its end takes it away"
  - "on a locked phone I am safe from a notification asks for the unlock before the confirmation"
  - "on an unlocked phone it leads straight to the confirmation"
- `SosScreensTest.kt` (9):
  - "emergency contacts leads to the SOS setup checklist, which says that alerts are not set up"
  - "an emergency nobody is running opens the emergency screen when the app comes to the front"
  - "started after the hold it shows the countdown over the lock screen, and cancel leaves nothing"
  - "the tile, the widget and the notification each open the countdown, and the record says which"
  - "with alerts not set up a shortcut shows Call 112 and the way to the setup, and no countdown"
  - "set up SOS alerts opens the app at emergency contacts and closes the emergency screen"
  - "on a locked phone set up SOS alerts asks for the unlock first"
  - "in Bengali at double font size the not-set-up dialog still reaches Call 112 and the setup"
  - "a practice run shows the banner and starts no service and no record"
- `SosSetupOffersTest.kt` (4):
  - "while not ready Home leads to the checklist, with the SOS control still there"
  - "not now on Home puts the card away"
  - "after the first contact the contacts screen offers the checklist once"
  - "opening the checklist by its own button answers the offer too"
- `SosDeviceTest.kt` (2):
  - "started with nothing running, the service stops itself at once"
  - "during an SOS it is in the foreground with the fixed notification, and ends on cancel"
- `SosSmsTest.kt` (1):
  - "it hands one multipart message to the phone and waits for an answer per part"
- `ApiContactsRepositoryTest.kt` (1):
  - "a list that arrives after the copy was emptied is not written"

Also changed, without a change to any single test's steps: the 23 Hilt classes that gained
the clean-up rule, and every Hilt test through `TestCoreModule`.

**Left as they are, and why.**

- `SignInToHomeTest` keeps `compose.waitUntil` with a ten-second limit. It runs the real
  `SessionRepository` against a local HTTP server, as `CLAUDE.md` requires for the session
  flow; the HTTP client's threads are not counted, so idling cannot stand in.
- `ApiErrorMappingTest`, the time-out test: a 200 ms read time-out against a server that
  answers after 5 seconds. A socket time-out is real time by design, and a slow machine
  only makes it fire later.
- `FakeDirections`, `FakeSearch`, `DeveloperCheckViewModelTest`, `SosDispatchTest`: their
  `await()` is on a `CompletableDeferred` the test completes. No clock is involved.
- `EmergencyNotificationTest` reads `System.nanoTime()` for a unique file name, not to wait.

**Documents.** `docs/sos/failure-matrix.md` ("About the tests themselves", history),
`CLAUDE.md` (one Android rule: how tests wait and clean up), `android/gradle/libs.versions.toml`
and `android/app/build.gradle.kts` (`espresso-core` named as a test dependency).

## 5. Diagram

No diagram needed: no flow, state machine, schema, API sequence, infrastructure or
pipeline changed.

## 6. Quality gate & test results

Five runs in a row of `./gradlew lint testDebugUnitTest assembleDebug --rerun-tasks`,
UTC and en-US:

| Run | Result (lint, tests, assembleDebug) | Tests | Failures | Errors | Skipped | "failed to call close" lines | Time |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | BUILD SUCCESSFUL | 1338 | 0 | 0 | 0 | 0 | 343 s |
| 2 | BUILD SUCCESSFUL | 1338 | 0 | 0 | 0 | 0 | 295 s |
| 3 | BUILD SUCCESSFUL | 1338 | 0 | 0 | 0 | 0 | 282 s |
| 4 | BUILD SUCCESSFUL | 1338 | 0 | 0 | 0 | 0 | 269 s |
| 5 | BUILD SUCCESSFUL | 1338 | 0 | 0 | 0 | 0 | 267 s |

- Tests: 1331 at the start of this prompt, 1338 now. Added: 4 in `HiltTestCleanupTest`,
  5 in `BackgroundWorkTest`, 1 in `CoreModuleTest` (10). The count rose by 7 between the
  two full runs because 3 of the 10 were already there at the first one. None removed.
- "failed to call close" lines in the test results: none in any of the five runs. The first full run of this prompt, before the last test was moved off the real application, still had 4.
- **Random order: not run.** Gradle's JUnit 4 runner has no option to shuffle classes or
  methods, and the Robolectric runner adds none. What was run in its place: the suite once with every test class in a JVM of its own (a command-line init script, nothing committed), so that no class can lean on one that ran before it: 1338 of 1338 in 146 classes, no close warning. It does not show the opposite, a class disturbed by one before it; the five runs in the usual order cover that only for that order.
- Not run: `assembleRelease`. No build file of the app's own code, `src/release` or
  `src/debug` changed; the one build change is a test dependency.
- Repo checks: markdown lint on the changed documents and gitleaks on `main..HEAD`: pass.

**Failure matrix rows touched:** none. No SOS behaviour and no production file changed; the
statuses stay as they are, and they still depend on a green `android-ci` run on `main`.

## 7. Decisions & ADRs

No ADR: nothing here is hard to reverse. Decisions, with the alternative not taken:

- **Count the work instead of making it virtual.** Running the whole app on a test
  dispatcher with virtual time would also remove the threads, and with them what these
  tests are for: the real wiring of scope, dispatchers and Room. Counting keeps the
  threads and removes the guess about how long they take.
- **A plain application by default** instead of closing the real one. The real
  application's database cannot be reached from a test without a production change.
- **The clean-up rule in every Hilt test** instead of closing "when the next test opens a
  database": a failure to close then belongs to the test that caused it.

## 8. Security & privacy notes

- Nothing personal is involved: test code only. Fixture values are the existing fake ones.
- No test sends an SMS, places a call or reaches a network other than the local test server.
- `espresso-core` 3.5.0 is Apache-2.0 and was already on the test classpath through the
  Compose test library; it ships in no APK.

## 9. Known issues & risks

- **No JVM test starts the real application any more.** Before, 48 classes did so by
  accident, which is how a start-up race was caught in P014d1c. A fault in
  `SafeRouteApplication.onCreate` would now pass the unit tests. Follow-up 1.
- **`CoreModuleTest` changed its meaning in one place.** It asserted that the test's graph
  hands out `Dispatchers.IO` and `Dispatchers.Default`; the graph now hands out counted
  wrappers. The test checks the real module's functions directly, that the module marks the
  clock as a singleton, and that the graph gives a different dispatcher per qualifier.
- **A sleeping coroutine is not counted.** A test whose result arrives only after a
  `delay` in real time will fail at once now instead of passing after a wait; none does
  today, but a new one has to move the time itself.
- **The clean-up waits at most ten seconds** for the app-lifetime scope to end, so that
  work which ignores cancellation cannot hang a run. Nothing is asserted about that wait.
- **The session's DataStore** keeps its own small scope, which the rule does not stop. It
  holds a file in the test's temporary folder, no database.
- Five green runs on one machine are evidence, not proof: the next `android-ci` runs on
  `main` are the real check.

## 10. Follow-ups & prerequisites for next prompt

Recorded in Notion (source P014e):

1. A deliberate test of the real application's start-up, once the application can give
   back what it opens (needs a small production change, for example closing in
   `onTerminate`).
2. Count the HTTP client's work in tests, so that `SignInToHomeTest` can lose its limit.
3. A way to run the tests in random order (none exists in the runner in use).
4. Stop the session DataStore's scope in the clean-up rule.

Next prompt: P015 (SOS server orchestration), not started. Its inputs are in the P014d2
log, section 10. One addition: a new Hilt test follows the rule in `CLAUDE.md` ("How tests
wait and clean up").

## 11. How Rahul can verify

1. On the PR, check that `android-ci` is green.
2. In Android Studio's terminal, inside `android/`: `./gradlew testDebugUnitTest`. It
   should end with `BUILD SUCCESSFUL`.
3. Search the test results for "failed to call": nothing should be found
   (`android/app/build/test-results/testDebugUnitTest`).
4. After the merge, watch the next few `android-ci` runs on `main`. A red run whose first
   failure names a time-out is what this prompt was meant to end: report the test's name.

No phone step: nothing on the phone changed.

## 12. Learning notes

- **A unit test on the JVM (Robolectric).** These tests run on the computer, against a copy
  of Android. They are fast, and they still use real threads for background work.
- **Why a time limit in a test is fragile.** "Wait up to five seconds for the result"
  passes on a fast machine and fails on a busy one, although the code is right. Such a
  test is called *flaky*.
- **Virtual time.** `runTest` from the coroutine test library has its own clock: a
  `delay(1000)` takes no real time, and the test decides when time moves.
- **An idling resource** is the test framework's way to ask "is the app busy?". Espresso
  and the Compose test rule go on only when every registered resource says "idle". Here
  the resource is a counter of background work.
  [Espresso idling resources](https://developer.android.com/training/testing/espresso/idling-resource)
- **A dispatcher** decides which thread a coroutine runs on. The app gets its dispatchers
  from Hilt, which is what lets a test hand out a counting one instead.
  [Testing coroutines on Android](https://developer.android.com/kotlin/coroutines/test)
- **A JUnit rule** is code that runs around every test of a class: before it, after it.
  `HiltTestCleanup` is one; its "after" closes what the test opened.
- **`@TestInstallIn`** tells Hilt to use a test module instead of a production one in
  every test. [Hilt testing](https://developer.android.com/training/dependency-injection/hilt-testing)
- **"A resource failed to call close".** Android notices, when it tidies memory, that a
  database was never closed, and prints this. It is harmless in a test and still worth
  removing: it hides real leaks, and it means work is still going on after the test.
