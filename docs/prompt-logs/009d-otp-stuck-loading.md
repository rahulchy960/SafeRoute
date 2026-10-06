# P009d: fix, first sign-in stuck on loading after the OTP

| Field | Value |
| --- | --- |
| Prompt | P009d · bug fix on top of P009c |
| Milestone | M2 (depends on P009c, merged as `d64a662`) |
| Branch | `fix/009d-otp-stuck-loading` |
| PR title | `fix(android): first sign-in stuck on loading after OTP [P009d]` |
| Notion | [P009d row in the Prompt Log](https://app.notion.com/p/3f107370772081e79acffd0e043427aa) |
| Date | 2026-10-06 |
| Plan refs | Plan v7 §3.2 F-01, §7; ADR 0010, ADR 0012 |

## 1. Objective

Fix the bug Rahul found on his phone, the first run of the app on a device:

- First sign-in with a Firebase test number: after the SMS code the app sent `GET /v1/me`
  (403 `bootstrap_required`, expected for a new user) and then **nothing** for about 4.5
  minutes. The screen showed "One moment…" and never reached Home.
- After force-closing and reopening, the app sent `GET /v1/me` → 403, `POST /v1/me/bootstrap`
  → 201, `GET /v1/me` → 200, `GET /v1/me/consents` → 200, and Home opened.

Required: find the root cause, reproduce it with a failing test first, fix it with the smallest
change, and add a guard so that the loading state always ends in Ready or in an error screen
with retry within a bounded time. Keep the offline-open, under-18 and consent rules.

## 2. Context & prerequisites

- P009c merged (PR #20, `d64a662`). No open pull requests. Hooks active. Clean tree.
- The evidence was Rahul's description of the staging logs (method and status only). Claude
  Code did not read cloud logs and has no phone: the fix is verified with JVM tests, and the
  phone checks in section 11 are still to be done.

## 3. Workflow executed

1. `/start-prompt P009d`: `main` at `d64a662`; Notion P009c → Merged, P009d row created; branch.
2. **Read the post-OTP flow end to end** (findings in section 7).
3. **Failing test first.** `PostSignInFlowTest` runs the real `SessionRepository` and the real
   `SignInViewModel` on a fake gateway and a fake API, and clears the sign-in ViewModel when
   the session leaves `SignedOut`, as navigation does. Result on the unfixed code:
   `expected [GET /v1/me, POST /v1/me/bootstrap, GET /v1/me, GET /v1/me/consents] but was
   [GET /v1/me]`. That is the server log from the phone.
4. Fix (section 4). The test passed; 16 variants were added.
5. `SignInToHomeTest`: the same flow in the real `MainActivity` with the real navigation.
6. **Control.** With the session work put back into the caller's scope (and everything else
   kept), 9 of the 17 `PostSignInFlowTest` tests fail again. The file was then restored.
7. Rules, README, diagram labels, this log, quality gates, `/ship-prompt`.

## 4. Changes

| Area | What |
| --- | --- |
| `core/di/` | qualifier `@ApplicationScope` and its provider: a scope that lives as long as the app (`SupervisorJob` + `Dispatchers.Default`) |
| `core/session/SessionRepository.kt` | every state change runs in the app scope, one at a time, and the caller only waits; a 60-second limit and a catch-all turn a check that does not finish into the retry screen; a listener on Firebase's auth state starts the check when a user appears while the session says "signed out"; after a bootstrap the account and consents are read back |
| `core/session/Session.kt` | `onSignedIn()`: like `refresh()`, but does nothing once the session has left "signed out" |
| `feature/onboarding/SignInViewModel.kt` | calls `session.onSignedIn()`; if the session did not move on, the screen becomes usable again instead of staying on "checking" |
| Tests | `PostSignInFlowTest` (17), `SignInToHomeTest` (2), `FakeMeApi`; `SessionRepositoryTest` +2 and adapted; `FakePhoneAuthGateway` can delay or repeat the auth state; `FakeSession` |
| Docs | `CLAUDE.md` rule, `android/README.md`, diagram labels, this log |

**Size:** about 1,200 changed lines: code 144, tests 782, documents 282 (this log is most of
the documents).

**API contract diff:** none. **Migrations:** none. **New dependencies:** none.
**Deployment impact:** none.

**Change in what the app sends:** after creating an account it now reads it back
(`GET /v1/me`, `GET /v1/me/consents`) before showing Home. That is the sequence the working
cold start already produced in the logs.

## 5. Diagram

[`docs/diagrams/009b-onboarding-and-session.svg`](../diagrams/009b-onboarding-and-session.svg),
labels updated: the bootstrap node says "then read the account back", the error node says
"or no answer within 60 s", and the subtitle says where the checks run. The flow itself did not
change, so no new diagram.

## 6. Quality gate & test results

| Command | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` (in `android/`) | BUILD SUCCESSFUL |
| → unit tests | **308 tests, 0 failures, 0 skipped** (287 before; 21 new) |
| → lint | 0 errors, 2 warnings (both `OldTargetApi`, known, ADR 0008) |
| `./gradlew assembleRelease -Psaferoute.apiBaseUrl=https://example.invalid/` | BUILD SUCCESSFUL |
| `pnpm check` in `tools/diagrams` | 14 diagrams up to date |
| markdownlint-cli2 · JSON validity · gitleaks 8.30.1 | 0 errors · 43 files valid · no leaks |

`PostSignInFlowTest` (real repository, real sign-in ViewModel, virtual time):

| Test | What it proves |
| --- | --- |
| first sign-in, sign-in screen cleared mid-flow | **the bug**: bootstrap is sent with `{ageConfirmed, noticeVersion, noticeLocale, purposes ["account_core"]}`, then `/v1/me`, then consents, then Ready |
| auth state arriving AFTER `verifyCode` | nothing is sent until Firebase has a user; the sign-in screen is not stuck; then the account is created |
| auth state arriving DURING `verifyCode` | one check, one bootstrap, although two things report the sign-in |
| a second emission of the same auth state | changes nothing, during or after the check |
| sign-in without typing a code | the account is created with no screen calling anything |
| bootstrap 503 | retry screen; retrying finishes |
| bootstrap that never answers | still "creating account" at 59 s, retry screen at 61 s; the next attempt is not blocked |
| first request that never answers | retry screen |
| no connection during the loading step | retry screen |
| consents fail after the bootstrap | retry screen; the retry does not bootstrap again |
| unexpected failure inside the check | error screen, not a spinner |
| cold start, new user · existing user | unchanged: same calls, same result |
| offline-open rule | a check that hangs keeps Home open for a user who was ready before |
| under-18 rule | a sign-in report never gets past the block and sends nothing |
| consent rule | a signed-in user without the accepted notice is asked first; nothing is created |
| the work survives its caller | cancelling the caller does not cancel the check |

`SignInToHomeTest` (real `MainActivity`, real navigation, real repository): after the SMS code a
new user reaches Home with exactly one bootstrap; when the account cannot be created the retry
screen shows and "Try again" reaches Home.

**What the activity test does not prove on its own.** In the control run (step 6) it still
passed, because there the Firebase listener happened to report the sign-in first and its work
was not tied to the screen. On a phone the order can go either way, which is why the scope
change is the actual fix and `PostSignInFlowTest` is its proof.

**Not verified:** on a phone. Section 11 lists the checks.
**SOS failure matrix (Plan v7 §7.5):** not applicable, no SOS code. The offline-open rule, the
only device-first item, is tested above.

## 7. Decisions & ADRs

No new ADR. A rule was added to `CLAUDE.md` ("Android rules").

### Root cause

1. After the SMS code, `SignInViewModel` called `session.refresh()` inside its own
   `viewModelScope`.
2. `refresh()` first set the session state to `Loading`, then sent `GET /v1/me`.
3. `Loading` made `OnboardingNavHost` replace the sign-in screen with the "One moment…" screen
   and remove the sign-in screen from the back stack.
4. Removing it cleared `SignInViewModel`, which **cancelled the coroutine that was running the
   session check**. The request was already on its way (the server logged the 403), but the
   cancelled coroutine never went on to the bootstrap.
5. Nothing else would start the check again, so the state stayed `Loading`: an endless spinner.
6. On a cold start the check is started by the activity-level `OnboardingViewModel`, which is
   not cleared by navigation, so it ran to the end.

The answers to the questions in the prompt:

| Question | Finding |
| --- | --- |
| Which scope runs each step? | Post-OTP: the sign-in screen's `viewModelScope` (the bug). Cold start: the activity ViewModel's scope |
| Does the bootstrap depend on something that exists only at app start or while the OTP screen is composed? | Yes: on the sign-in screen's ViewModel staying alive, and it is cleared by the very state change the check makes |
| Are the DataStore flags written before this step? | Yes. Age and the accepted notice (version and language) are written when the user taps the buttons, before the phone screen is reachable. Not a cause |
| Swallowed exceptions? | None in this path. `CancellationException` is rethrown on purpose, which is what stopped the flow. There was, however, no handling for an unexpected exception or a hang: both would also have left a spinner. Now covered |
| `verifyCode` and the auth state | `verifyCode` returns after Firebase has the user. The app did not listen to the auth state at all; it does now, as a second way to notice a sign-in |

### Why P009c's tests missed it

`OnboardingNavigationTest` used `FakeSession`, whose `refresh()` finishes instantly and is not a
coroutine that can be cancelled half-way. The real repository and the real navigation were never
exercised together. That gap is now closed and written down as a rule.

### Decisions

- **Fix where the work belongs, not where it is called.** The repository runs its own state
  changes in an app-lifetime scope (`appScope.async { … }.await()`). Every caller is safe by
  construction, including future ones. Moving the call to another ViewModel would have fixed
  this caller only.
- **One at a time stays.** The existing mutex is kept inside the app scope.
- **Bounded time: 60 seconds per check.** One HTTP call already gives up after 45 seconds; a
  first sign-in is four calls. On timeout: the retry screen, or Home if the user was ready
  before (offline-open rule). A slow but working connection can hit the limit mid-way; "Try
  again" then continues where the server is (the bootstrap is idempotent).
- **Unexpected exceptions end in the error screen** (not retryable), or keep Home.
- **`onSignedIn()` is separate from `refresh()`.** Two things report a sign-in (the screen and
  Firebase's listener). `onSignedIn()` looks at the state again inside the one-at-a-time
  section, so the second report does nothing. `refresh()` stays unconditional for app start and
  "Try again".
- **Read the account back after a bootstrap**, as the prompt's expected sequence says. A second
  "no account" answer right after a successful bootstrap is an error, not a loop.
- **No session-machine redesign.** States, flags and rules are unchanged.

## 8. Security & privacy notes

- No new data, permission, dependency or log line. The auth-state listener receives "somebody
  is signed in" and nothing else (`AuthUser` has no fields).
- The under-18 path still sends nothing: a sign-in report while blocked is ignored (tested).
- Consent still comes first: a signed-in user without the accepted notice gets the notice and
  no account is created (tested).
- The listener starts when the repository is created, at app start. It only registers with the
  Firebase SDK on the device; it makes no network call.
- Tests use fake values only. Nothing in the diff contains a phone number in use, a token, a
  Firebase id or a server address. The server-log evidence is described by method and status.

## 9. Known issues & risks

- **Not confirmed on the phone yet.** The reproduction matches the server log exactly, but the
  fix has only run on the JVM.
- **The 60-second limit is a guess.** On a very slow connection a first sign-in may show the
  retry screen although it would have finished. Retrying is safe.
- **`phone_already_registered`.** Rahul's first test number already has an account on staging.
  A new Firebase user for that phone number gets 409, which the app shows as the generic
  "couldn't finish setting up" screen with "Try again" that cannot succeed. Sign out (Settings
  is not reachable from there) is not offered on that screen. Recorded as a follow-up.
- A duplicate sign-in report costs nothing; a `refresh()` that overlaps a sign-in check waits
  its turn and then does a normal check (two reads).

## 10. Follow-ups & prerequisites for next prompt

New in Notion (*Follow-ups*, source P009d):

- The problem screen needs a way out for errors that retrying cannot fix, above all
  `phone_already_registered` (a "use another number" action that signs out), and its own
  message.
- Revisit the 60-second check limit with real measurements on a slow network.

Still open: "Record Rahul's manual phone checks for P009c" (now including the checks below).

**Next: P010** (`feat/010-map-location`), not started.

## 11. How Rahul can verify

1. Read the pull request: the fix is `SessionRepository.kt` (`change`, `init`, `onSignedIn`,
   `checkAccount`) and three lines in `SignInViewModel.kt`. Read section 7 above.
2. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug` → green, 308 tests.
3. Install the debug build on the phone. Clear the app's storage first (*Settings → Apps →
   SafeRoute → Storage → Clear storage*).
4. **First sign-in with a SECOND Firebase test number** (add one in the console). Your first
   number already has an account on staging, and a new Firebase user for that number would get
   `phone_already_registered`. Expected: after the code, "One moment…" / "Setting up your
   account…" for a moment, then Home. In the staging logs: `GET /v1/me` 403,
   `POST /v1/me/bootstrap` 201, `GET /v1/me` 200, `GET /v1/me/consents` 200.
5. **Sign out, then sign in again with the FIRST number** (go through age and notice again).
   Expected: Home, with `GET /v1/me` 200 and `GET /v1/me/consents` 200 and no bootstrap,
   provided the Firebase user for that number is the one the account was created with.
6. **Airplane mode during the loading step.** Clear storage, go through to the code screen,
   switch on airplane mode, enter the code. Expected: "Something went wrong … check your
   internet connection" with "Try again", within a few seconds; never an endless spinner.
   Switch airplane mode off and tap "Try again": Home.
   (With a test number Firebase accepts the code without a network; with a real number the
   code step itself would show "No internet connection".)
7. Tell Claude Code the results. Type test numbers and codes on the phone only.
8. CI green; squash and merge; delete the branch.

## 12. Learning notes

- **Coroutine scope.** Every coroutine runs inside a scope, and cancelling the scope cancels
  everything in it. The scope decides how long the work may live.
- **`viewModelScope`.** A ViewModel's scope. It is cancelled when the ViewModel is cleared,
  which for a screen's ViewModel happens when the screen leaves the back stack.
  <https://developer.android.com/topic/libraries/architecture/coroutines#viewmodelscope>
- **The trap.** If work started in a screen's `viewModelScope` causes that screen to be
  replaced, the work cancels itself. Work whose result decides which screen shows must live
  longer than any one screen.
- **Application scope.** A scope created once for the whole app process. `SupervisorJob` means
  one failing piece of work does not cancel the rest.
- **`async { … }.await()`.** Starts work in another scope and waits for its result. If the one
  waiting is cancelled, only the waiting stops; the work goes on.
- **Cancellation is cooperative and silent.** A cancelled coroutine stops at its next
  suspension point by throwing `CancellationException`, which is not an error and is not
  logged. That is why the app showed nothing wrong: it simply stopped.
- **`withTimeoutOrNull`.** Runs a block for at most a given time and returns null if it took
  longer. It is the guard that turns "never finishes" into "show the retry screen".
- **Why a fake hid the bug.** A test double that finishes instantly cannot be cancelled
  half-way. Bugs about timing and lifetimes need the real object, with answers that take time.
- **`StateFlow` vs `SharedFlow` in tests.** A `StateFlow` drops a value equal to the current
  one; a `SharedFlow` delivers every emission. The fake gateway uses a `SharedFlow` so a test
  can send "the same state again".
- **`ViewModelStore.clear()`.** What navigation does to a screen's ViewModels when the screen is
  removed. The reproduction test calls it directly to play the part of navigation.
- **Idempotent.** An operation that is safe to repeat. Creating the account is: a second
  bootstrap returns the existing account, so retrying after a timeout cannot create two.
