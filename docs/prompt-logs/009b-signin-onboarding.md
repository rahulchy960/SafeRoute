# P009b: Firebase sign-in foundation, session state machine, Firebase config in builds

| Field | Value |
| --- | --- |
| Prompt | P009 · part b, **split again**: this is P009b (no screens); the screens are P009c |
| Milestone | M2 (depends on P009a, merged as `3ee2ac0`) |
| Branch | `feat/009b-signin-onboarding` |
| PR title | `feat(android): Firebase sign-in foundation, session state machine and Firebase config in builds [P009b]` |
| Notion | [P009b row in the Prompt Log](https://app.notion.com/p/3f007370772081fd8b01f1e70d0ac721) (umbrella row: [P009](https://app.notion.com/p/3eb07370772081f9abd7c40514a78bc0)) |
| Date | 2026-10-06 |
| Plan refs | Plan v7 §3.2 F-01, §5, §7, §12.1, §12.4, §17; addendum v7.1 B.2, B.3; ADRs 0006, 0008, 0009, 0010 |

## 1. Objective

Part b of P009 is the Android sign-in: age gate → consent notice → Firebase phone OTP →
bootstrap → Home. **It was split in two because of its size** (section 4):

- **P009b (this pull request): everything without a screen.** B0 spike, B1 Firebase
  configuration in builds with ADR 0012, B2 dependencies, B3 `FirebaseIdTokenProvider`, B4
  `PhoneAuthGateway`, B5 session state machine, B8 DataStore flags, B10 CI, and the parts of B11
  that describe them.
- **P009c (next): the screens.** B6 onboarding flow, B7 Settings account section and the
  Developer additions, B9 strings and accessibility, the consent notice text and
  `docs/legal/consent-notice-v1.md`, the OTP resend timer, and the manual checks on the phone.

After this part the app behaves as before for a user (it still opens to Home). What changed is
underneath: it is built with Firebase, tokens come from Firebase, and the session logic exists
and is tested.

## 2. Context & prerequisites

- P009a merged (PR #18, `3ee2ac0`). No open pull requests. Hooks active. Clean tree.
- `android/app/google-services.json` exists locally and `git check-ignore` prints its path.
  **Claude Code did not open it.** Gradle read it during local builds, as the build must.
- The `deploy-staging` run for `3ee2ac0` shows "success" in `gh run list`. Rahul has not
  reported it, so the deploy still counts as unverified (CLAUDE.md "Deployment rules").
- No phone or emulator: JVM tests only. Nothing here was run against Firebase or staging.

## 3. Workflow executed

1. `/start-prompt P009b`: `main` at `3ee2ac0`; Notion P009a → Merged, P009b → In progress;
   branch created.
2. **B0 spike.** Latest stable versions from Google Maven and Maven Central metadata. Licence
   names from the POMs. firebase-auth publishes no sources, so its API and error codes were
   read from the compiled library with `javap` and a string search.
3. Build files, dummy-configuration script, release guard. First build with the plugin: works
   with AGP 9.4.1 and the configuration cache.
4. `core/auth`, then `core/session`.
5. Tests. First full run: 199 of 206 passed. Three causes, all fixed:
   - `FirebaseAuth.getInstance()` throws under Robolectric (no `FirebaseApp`). The wiring tests
     no longer call the SDK; a second test replaces the sign-in module with the fake.
   - DataStore's file replace failed on a plain JVM on Windows. The test now runs under
     Robolectric, where DataStore uses the same file move as on a phone.
   - The permission test found two permissions merged in by the Firebase libraries. They are
     now listed and explained (section 8).
6. Release guard tried in a scratch copy of the project that had **no** real configuration
   file (the real file was never moved or copied): missing file, dummy file, allow flag, and
   the whole gate with the dummy file.
7. CI workflow, ADR 0012, README, `CLAUDE.md`, diagram. Five commits.
8. This log, repo-wide checks, `/ship-prompt`.

## 4. Changes

| Area | What |
| --- | --- |
| `android/gradle/libs.versions.toml`, build files | Firebase BoM, Google Services plugin, DataStore, coroutines-play-services; task `checkReleaseFirebaseConfig` |
| `android/scripts/write-dummy-google-services` | writes the dummy configuration; never overwrites |
| `core/auth/` (new package) | `PhoneAuthGateway`, `AuthUser`, `VerificationEvent`, `SignInResult`, `PhoneAuthError`; `FirebasePhoneAuthGateway`; `FirebaseIdTokenProvider`; error mapping; `normaliseIndianMobile`; `AuthModule` |
| `core/session/` (new package) | `SessionState`, `BlockReason`, `SessionRepository`, `SessionStore` + `DataStoreSessionStore`, `AppLocale`, `NOTICE_VERSION`, `SessionModule` |
| `core/network/` | `SignedOutIdTokenProvider` and its binding removed; three problem codes added |
| Tests | 8 new classes, 2 support files; `MainActivityTest` and `NetworkModuleTest` updated |
| CI | `android-ci.yml`: dummy file before Gradle; allow flag on the release-assembly step; two refusal checks |
| Docs | ADR 0012; `android/README.md` "Signing in"; `android/THIRD_PARTY.md`; `CLAUDE.md` Android rules; diagram; this log |

**Versions (B0):**

| What | Version |
| --- | --- |
| Firebase BoM | 34.19.0 (firebase-auth 24.2.0) |
| Google Services Gradle plugin | 4.5.0 |
| DataStore Preferences | 1.2.1 |
| kotlinx-coroutines-play-services | 1.11.0 |

**Size:** about 2,860 changed lines without generated diagram files: code and build 990, tests
1,350, documents and CI 520. Far over the ~800-line guide even after the split; the whole of
part b would have been roughly twice that. The five commits are separate review units.

**API contract diff:** none. **Migrations:** none. **Deployment impact:** none (no backend,
image, deploy workflow, cloud configuration or secret).

**New dependencies with a proprietary licence** (flagged as `CLAUDE.md` requires):
firebase-auth, Google Play services and reCAPTCHA (Android Software Development Kit License),
Play Integrity (its own terms). Listed in `android/THIRD_PARTY.md`.

## 5. Diagram

[`docs/diagrams/009b-onboarding-and-session.svg`](../diagrams/009b-onboarding-and-session.svg)
(16 nodes): the onboarding order, the checks after sign-in, and the outcomes. Red for the
under-18 block and for sign-out and deletion; grey for the later Trusted Circle consent. Two
renders were needed: the first had arrows crossing boxes, so two outcome nodes were merged.

## 6. Quality gate & test results

| Command | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` (in `android/`, local configuration) | BUILD SUCCESSFUL |
| → unit tests | **209 tests, 0 failures, 0 skipped** (146 before; 63 new) |
| → lint | 0 errors, 2 warnings (both `OldTargetApi`, known, ADR 0008) |
| `./gradlew assembleRelease -Psaferoute.apiBaseUrl=https://example.invalid/` | BUILD SUCCESSFUL |
| Same gate plus `assembleRelease` in a scratch copy with **only the dummy file** and the allow flag | exit 0 |
| `:app:checkReleaseFirebaseConfig`, no file | fails: "Release builds need app/google-services.json" |
| `:app:checkReleaseFirebaseConfig`, dummy file | fails: "must not use the dummy Firebase configuration" |
| `:app:checkReleaseFirebaseConfig`, dummy file, `-Psaferoute.allowDummyFirebase=true` | passes |
| `:app:checkReleaseFirebaseConfig`, local file | passes |
| `write-dummy-google-services` twice | writes valid JSON; second run leaves it untouched |
| actionlint 1.7.12 with shellcheck | clean |
| `pnpm check` in `tools/diagrams` | 14 diagrams up to date |
| markdownlint-cli2 · JSON validity · gitleaks 8.30.1 | 0 errors · 43 files valid · no leaks |
| `git ls-files android \| grep -iE "local.properties\|\.jks\|google-services\.json"` | no output |

| Class | Covers |
| --- | --- |
| `PhoneNumberTest` | 10 digits starting 6–9; spaces, dashes, dots, brackets, non-breaking space; `+91`, `91`, `0091`, trunk `0`; everything else rejected, including other scripts' digits |
| `PhoneAuthErrorMappingTest` | wrong code, expired, invalid number, too many requests, quota or blocked, network, app not verified, unknown; the SDK message is never kept |
| `FirebaseIdTokenProviderTest` | null when signed out; token when signed in; `forceRefresh` passed through; nothing cached; a failed refresh becomes an `IOException` |
| `SessionRepositoryTest` | order age → consent → phone with zero requests; under 18 = one local write, zero network and sign-in calls; decline = nothing; process death resumes each step; bootstrap body exact, no phone; `bootstrap_required`; `account_deleted`; 401; 503; `auth_unavailable`; offline first run → retry; offline after ready once → Home; unknown codes → generic error; consent missing, withdrawn or old → `NeedsConsent` → `PUT`; sign out |
| `DataStoreSessionStoreTest` | real DataStore file: restart, clear, unreadable file, flow, and that the file holds flags only |
| `SessionLoggingTest` | with debug logging on and the real Logcat logger: no token, phone number, SMS code or verification id in the log |
| `SessionWiringTest`, `AuthWiringTest` | the Hilt graph: Firebase bound in production; real DataStore and locale lookup with a fake gateway |

**Covered in P009c, not here:** the OTP resend timer, the notice screen, font scale and touch
targets, string parity for new strings, navigation ("Home unreachable until..."), and the
manual checks on Rahul's phone.

**Not verified:** anything against Firebase or staging. `FirebasePhoneAuthGateway` has no
automated test by design (ADR 0012).
**SOS failure matrix (Plan v7 §7.5):** not applicable, no SOS code. The offline-open rule is
the only device-first item, and it is tested.

**CI on the pull request:** see the PR; recorded in Notion when it finishes.

## 7. Decisions & ADRs

**[ADR 0012](../adr/0012-firebase-config-in-builds.md) (Accepted):** the real configuration
file stays local; CI writes a dummy; a release refuses the dummy or a missing file; Firebase
Auth only; only `core/auth` uses the SDK; tests use a fake and never start Firebase.

Implementation decisions:

- **Split into P009b and P009c** (section 1).
- **"Ready once" shows Home immediately.** The prompt says to open offline after the first
  sign-in. Waiting for the request to time out first would mean a long blank screen, so the
  state is `Ready` at once and the check runs behind it.
- **With "ready once", only definite answers take the user out:** 401 after the refresh,
  `account_deleted`, or a successful answer saying the consent is old. A 500 or an unknown
  code keeps Home open. Without "ready once" those are an error screen.
- **401 keeps the age and consent flags** (the user only verifies the phone again). **Sign
  out clears every flag** (the prompt: "returns to onboarding").
- **A notice accepted on the phone is sent on the next check** if the server does not have it
  yet. That makes "I agree" survive the app being killed before the answer.
- **`bootstrap_required` without local age or consent** goes back to that step and sends
  nothing.
- **"I am under 18" has no undo.** Offering one would defeat the gate. It can only be reset
  by clearing the app's data. Rahul may want different wording or a different rule.
- **The verification id is not stored.** After process death on the code screen the user
  returns to phone entry. Storing the id without the phone number would not be enough to
  resend, and the phone number must not be stored.
- **`AuthUser` has no fields.** Nothing above `core/auth` can read a Firebase uid or the phone
  number; the masked phone in Settings will come from `GET /v1/me`.
- **A deleted or disabled Firebase user yields no token** instead of an error, so the API
  answers 401 and the normal sign-out path runs.
- **`NOTICE_VERSION = "2026-10-draft1"`.** The text arrives in P009c.
- **The sensitive-file check in `CLAUDE.md` now matches `google-services\.json`**, because
  the tracked script's name contains "google-services".

## 8. Security & privacy notes

- **No real identifier in the diff:** no project id, project number, app id, API key, token,
  phone number or SMS code. The dummy file's values are zeros and `demo-saferoute`. gitleaks
  is clean.
- **Test phone numbers are made up.** The validator only accepts numbers starting with 6–9, so
  the usual `+91 00000 00000` cannot test the accepting path; the tests use patterns such as
  `+91 90000 00001`. They were not taken from anyone, but a number of that form may exist.
- **Stored on the phone:** six flags in DataStore. A test reads the file and checks that
  nothing looks like a phone number, token or code. `allowBackup` stays `false`.
- **Logs:** `core/auth` and `core/session` do not log. A test signs in with debug logging on
  and reads Logcat.
- **Before consent:** no request is sent and Firebase is not touched (tested).
- **Permissions merged in by libraries, not asked for in our manifest:**
  `ACCESS_NETWORK_STATE` (firebase-auth, reCAPTCHA) and
  `com.google.android.providers.gsf.permission.READ_GSERVICES` (reCAPTCHA). Both are granted
  at install with no dialog and give no access to location, contacts, SMS or the phone
  number. The libraries also add their own activities, a service and
  `FirebaseInitProvider` to the merged manifest.
- **Firebase is now in the app:** phone sign-in sends the phone number and device signals to
  Google (Play Integrity, reCAPTCHA). The consent notice in P009c has to say so.
- CI: no secrets, pinned actions, no `pull_request_target`.

## 9. Known issues & risks

- **Nothing was run on a phone or against Firebase.** The wrapper's behaviour (SMS,
  auto-retrieval, reCAPTCHA fallback, error codes in practice) is first seen in P009c.
- **No screen uses the session state yet.** A signed-in state cannot be reached in this
  build, so the new token provider always returns null for now.
- **The error-code list comes from the compiled library**, not from documentation. Unlisted
  codes map to `UNKNOWN`.
- **The first check after a restart can take a while on a bad connection** for a user who
  was never ready (connect timeout and retries) before the retry screen appears.
- **Proprietary SDKs next to AGPL code** need the licence review before release.
- **APK size:** debug about 15 MB, release (unshrunk) about 10.5 MB.
- **Over the size guide** (section 4).

## 10. Follow-ups & prerequisites for next prompt

New in Notion (*Follow-ups*, source P009b): real `google-services.json` for release builds in
CI (P022) · App Check in monitor mode (existing item, noted) · SMS cost and quota monitoring ·
sign-in funnel metrics need an analytics decision · decide the under-18 undo rule and wording ·
multiple devices and phone-number change (existing item, noted).

Still open from the P009 prompt, for **P009c**: lawyer review of the notice (item exists);
Play Data safety form and target audience; Hindi; consent UI for optional purposes (item
exists).

**Next: P009c** (`feat/009c-onboarding-screens`), not started. It needs:

- This pull request merged.
- Staging deployed with migration 0002 (the run for `3ee2ac0` looks successful; please
  confirm).
- The real `google-services.json` in place, `saferoute.apiBaseUrl` set, a phone, and a
  Firebase test phone number and code that only Rahul types.

## 11. How Rahul can verify

1. Read the pull request commit by commit: build, gateway, session, CI, docs.
2. `git check-ignore android/app/google-services.json` prints the path;
   `git ls-files | grep google-services` shows only the script.
3. Android Studio: *File → Sync Project with Gradle Files*. Run the debug app on the phone.
   It must behave as before: Home opens, *Settings → Developer* still reports the server.
   (There is no sign-in screen yet.)
4. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug` → green, 209 tests.
5. Optional: `./gradlew :app:checkReleaseFirebaseConfig` passes with your real file. Do not
   test the dummy script on your machine by moving your real file; CI proves that path.
6. In the pull request's `android-ci` run, look for the two steps "A release build with the
   dummy Firebase configuration fails without the allow flag" and "A release build without
   google-services.json fails": both must be green.
7. Read ADR 0012 and the README section "Signing in". Tell Claude Code if "I am under 18"
   should be reversible.
8. CI green; squash and merge; delete the branch. Then say "P009c".

## 12. Learning notes

- **Firebase phone auth.** The app asks Firebase to text a 6-digit code to a number. The user
  types it, or Android reads the SMS itself (auto-retrieval). Firebase then signs the user in.
  <https://firebase.google.com/docs/auth/android/phone-auth>
- **App verification: Play Integrity, reCAPTCHA, SHA fingerprints.** Before sending an SMS,
  Firebase wants proof that the request comes from the real app and not a script. Play
  Integrity gives that proof silently; it relies on the app's signing key, whose **SHA-1 and
  SHA-256 fingerprints** you registered in the console. If that fails, Firebase opens a browser
  tab with a reCAPTCHA check. That is why the gateway needs the `Activity`.
- **ID token and refresh.** An ID token is a signed note saying "this is user X", valid for
  about an hour. Firebase also keeps a long-lived refresh token and swaps it for a new ID token
  when needed. `forceRefresh` asks for a new one now; the app does that once after a 401.
- **`google-services.json` and the Google Services plugin.** The file describes which Firebase
  project the app belongs to. The plugin turns it into string resources at build time, and
  Firebase reads those when the app process starts.
- **Interface plus fake.** `PhoneAuthGateway` is an interface with two implementations: the
  real one on Firebase and a fake for tests. Code written against the interface can be tested
  without Firebase, a phone or an SMS.
- **`callbackFlow`.** Turns an API that calls you back (a listener) into a Kotlin `Flow` that
  you collect. The listener is added when collection starts and removed when it stops.
- **Play services `Task` and `await()`.** Firebase returns `Task` objects that finish later.
  `await()` lets a coroutine wait for one without blocking a thread.
- **State machine.** A fixed list of states and the rules for moving between them. The app is
  always in exactly one `SessionState`, and the screen follows from it.
- **`StateFlow`.** A value that always has a current state and tells its observers when it
  changes. <https://developer.android.com/kotlin/flow/stateflow-and-sharedflow>
- **DataStore vs Room.** DataStore (Preferences) is a small key-value file for a handful of
  settings. Room is a database for many structured records (it arrives with SOS). Six flags
  belong in DataStore. <https://developer.android.com/topic/libraries/architecture/datastore>
- **Process death.** Android may kill an app in the background to free memory, for example
  while you read the SMS. Variables in memory are gone; files are not. Onboarding progress is
  therefore saved in DataStore.
- **`Mutex`.** Lets one coroutine at a time run a block. Two session checks never interleave.
- **Why consent comes before the phone number.** The number is personal data. Asking for it
  is already collecting it, so the notice is shown and accepted first.
- **Adults only, and no date of birth.** The app asks "18 or older?" and records only the
  answer and the time. A typed date is no more reliable and would be more personal data to
  protect (ADR 0010).
- **Merged manifest.** The final `AndroidManifest.xml` combines yours with every library's.
  That is how a library can add a permission you never wrote; a test pins the full list.
