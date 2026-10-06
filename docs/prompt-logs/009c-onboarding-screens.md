# P009c: onboarding screens, Settings account section, consent notice

| Field | Value |
| --- | --- |
| Prompt | P009 · the third and last part (P009a backend, P009b sign-in foundation, **P009c screens**) |
| Milestone | M2 (depends on P009b, merged as `14f3ccc`) |
| Branch | `feat/009c-onboarding-screens` |
| PR title | `feat(android): onboarding screens, 18+ gate, consent notice and Settings account section [P009c]` |
| Notion | [P009c row in the Prompt Log](https://app.notion.com/p/3f00737077208177acaff8f6cb69e6d5) (umbrella row: [P009](https://app.notion.com/p/3eb07370772081f9abd7c40514a78bc0)) |
| Date | 2026-10-06 |
| Plan refs | Plan v7 §1, §3.2 F-01, §5, §7, §12.1, §17; addendum v7.1 B.2, B.3; ADRs 0008, 0009, 0010, 0012 |

## 1. Objective

Finish P009 part b with everything that has a screen:

- **B6:** onboarding: Welcome → age gate → consent notice → phone → SMS code → account
  creation → Home, plus the blocked, progress and retry screens.
- **B7:** Settings account section (masked phone, sign out, consents) and the Developer
  "Who am I" check.
- **B9:** strings in English and Bengali; accessibility.
- **B11:** `docs/legal/consent-notice-v1.md`, README, rules.
- The remaining tests of the prompt's section 10B.

Rahul's decisions for this part:

- Under 18: no in-app undo; a confirmation dialog before the choice is recorded; the blocked
  screen shows the 112 note and a `[grievance contact]` placeholder; recorded in ADR 0010.
- A comment that the test phone numbers are never used to send anything.
- Report the names of the two known lint warnings.
- A Notion follow-up about an AGPL additional permission for the Firebase and Play libraries.

Not in scope, and not built: contacts, SOS, live share, reports, location or notification
permission, account deletion, profile editing, a per-app language switcher, consent UI for the
optional purposes.

## 2. Context & prerequisites

- P009b merged (PR #19, `14f3ccc`). Rahul confirmed the staging deploy (migration 0002).
- `android/app/google-services.json` is present locally and git-ignored. Claude Code did not
  open it.
- **No phone or emulator.** Everything here is verified with JVM tests (Robolectric). The
  manual checks in section 6 are for Rahul and have **not been run**.

## 3. Workflow executed

1. `/start-prompt P009c`: `main` at `14f3ccc`; Notion P009b → Merged, P009c row created; the
   AGPL follow-up added; the under-18 follow-up updated with the decision; branch created.
2. `Session` interface extracted from `SessionRepository`, with `account()` and phone masking
   (`2a3241f`).
3. Strings (English and Bengali), the screens, both ViewModels, `OnboardingNavHost`, the gate
   in `SafeRouteApp` (`2b81fca`).
4. Settings account section (`5bf4905`). Developer "Who am I" (`9a92e8a`).
5. Tests. First full run: 286 of 287 passed. The one failure was a test that looked for the
   word "Ready", which the Developer screen now shows twice; the assertion was corrected.
6. Lint showed two new warnings, both fixed at the cause instead of being accepted:
   - `FrequentlyChangingValue`: the notice read the scroll position during composition. It now
     watches it with `snapshotFlow`.
   - `AppBundleLocaleChanges`: Google Play installs only the phone's languages from an app
     bundle, so the notice's Bengali text could be missing on an English phone. Language splits
     are now off.
7. `docs/legal/consent-notice-v1.md` generated from the strings; ADR 0010 addendum; README;
   `CLAUDE.md`; diagram labels (`1c0e1a0`).
8. This log, quality gates, `/ship-prompt`.

## 4. Changes

| Area | What |
| --- | --- |
| `core/session/` | `Session` interface, `AccountSummary`, `AccountResult`, `maskPhone`, `SessionBindingModule`; `SessionRepository.account()` |
| `feature/onboarding/` (new) | `WelcomeScreen`, `AgeGateScreen`, `BlockedScreen`, `WorkingScreen`, `ProblemScreen`, `ConsentNoticeScreen`, `PhoneEntryScreen`, `CodeEntryScreen`, `SignInRoute`, `OnboardingLayout`; `OnboardingViewModel`, `SignInViewModel` |
| `navigation/` | `OnboardingNavHost` with seven type-safe destinations and `onboardingDestinationFor(state, welcomeSeen)` |
| `SafeRouteApp.kt` | the gate: the app's own screens only for `SessionState.Ready` |
| `feature/settings/` | `AccountViewModel`, `SettingsRoute`; Account and Privacy sections in `SettingsScreen` |
| `src/debug` | Developer screen: session state and "Who am I" |
| Strings | 93 new entries in each of `values/` and `values-bn/` (one of them a plural), 5 in each debug file |
| `app/build.gradle.kts` | `bundle { language { enableSplit = false } }` |
| Tests | 6 new classes, `FakeSession`; 7 existing test files adapted |
| Docs | `docs/legal/consent-notice-v1.md`; ADR 0010 addendum; README; `CLAUDE.md`; diagram labels; this log |

**Size:** about 3,870 changed lines without generated diagram files: code 1,690, strings 240,
tests 1,730, documents 210. Far over the ~800-line guide. It was not split a fourth time:
the screens only make sense together with the navigation that reaches them, and the five
commits are separate review units.

**API contract diff:** none. **Migrations:** none. **New dependencies:** none.
**Deployment impact:** none.

**New Bengali strings, all drafts that need human review before release:**

- Main: `settings_account_*` (6), `settings_sign_out*` (4), `settings_privacy_*` (3),
  `consent_purpose_*` (4), `onboarding_*` (8), `welcome_*` (3), `age_*` (8), `blocked_*` (5),
  `notice_*` (27), `phone_*` (9), `code_*` (8 including the plural), `signin_error_*` (8).
- Debug only: `developer_check_session` and four `developer_check_who_am_i*` strings.
- **The notice (`notice_*`) needs a native speaker AND a lawyer.**

## 5. Diagram

[`docs/diagrams/009b-onboarding-and-session.svg`](../diagrams/009b-onboarding-and-session.svg),
updated: the onboarding lane is no longer "screens in P009c", the notice node says
"EN / BN", and the under-18 node says "after a confirmation ... no undo". The flow is the one
drawn in P009b, so no new diagram was needed.

## 6. Quality gate & test results

| Command | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` (in `android/`) | BUILD SUCCESSFUL |
| → unit tests | **287 tests, 0 failures, 0 skipped** (209 before; 78 new) |
| → lint | 0 errors, 2 warnings |
| `./gradlew assembleRelease -Psaferoute.apiBaseUrl=https://example.invalid/` | BUILD SUCCESSFUL |
| Release APK scan | developer classes: debug 5, release 0 |
| `pnpm check` in `tools/diagrams` | 14 diagrams up to date |
| markdownlint-cli2 · JSON validity · gitleaks 8.30.1 | 0 errors · 43 files valid · no leaks |
| `git ls-files android \| grep -iE "local.properties\|\.jks\|google-services\.json"` | no output |

**The two known lint warnings** are both `OldTargetApi` ("Not targeting the latest versions of
Android"), reported for the same setting in two places: `android/app/build.gradle.kts`
(`targetSdk = ...`) and `android/gradle/libs.versions.toml` (`targetSdk = "36"`). The target is
36 on purpose while `compileSdk` is 37 (ADR 0008).

| Class | Tests | Covers |
| --- | --- | --- |
| `SignInViewModelTest` | 15 | phone field filter; invalid numbers ask Firebase for nothing; send code; double tap; **resend timer in virtual time**; six-digit filter; right and wrong code; each Firebase failure on both steps; auto-retrieval; change number |
| `OnboardingScreensTest` | 26 | every screen; under-18 confirmation and cancel; **"I agree" disabled until scrolled to the end**; language switch changes the notice and not the app locale; **English and Bengali notices have the same sections**; decline; phone and code keyboard and autofill hints; errors announced; **200% font on every screen**; 48 dp targets |
| `OnboardingNavigationTest` | 7 | real `MainActivity`: fresh install to Home in order with **Home unreachable before**; **under 18 with zero sign-in and token calls**; decline; back on the code screen; wrong code; each session state's screen; sign out from Settings |
| `OnboardingViewModelTest` | 9 | root ViewModel; the state-to-screen rule; **process death resumes from the stored flags**; `AccountViewModel` |
| `AccountSummaryTest` | 8 | masking keeps three digits and hides anything unexpected; `account()` results and failures |
| `ConsentNoticeDocumentTest` | 3 | the legal document equals the app's notice in both languages and names the notice version |
| `ScreensTest` | +7 | Settings account, privacy, sign-out confirmation, unavailable and loading states, 200% font |
| `DeveloperCheckViewModelTest` | +3 | session state by name; "Who am I"; unavailable |
| `StringResourceParityTest` | unchanged | now also guards the 93 new keys, the plural and the placeholders |

### P009 part b requirements across P009b and P009c

| Req. | What | Where | Status |
| --- | --- | --- | --- |
| B0 | Spike: versions, APIs, how to test Firebase code | P009b (ADR 0012) | done |
| B1 | Firebase configuration handling, dummy file, release guard, ADR 0012 | P009b | done |
| B2 | Dependencies (Firebase Auth, coroutines-play-services, DataStore) | P009b | done |
| B3 | `FirebaseIdTokenProvider` replaces the signed-out provider | P009b | done |
| B4 | `PhoneAuthGateway` with a Firebase implementation and a fake | P009b | done |
| B5 | Session state machine, offline open after "ready once" | P009b | done |
| B6 | Onboarding flow, type-safe routes, process-death safe | **P009c** | done; not run on a phone |
| B7 | Settings account section; Developer auth state and "Who am I" | **P009c** | done; not run on a phone |
| B8 | DataStore flags; never phone, token or code | P009b | done |
| B9 | Strings EN and BN, parity, accessibility | **P009c** | done; Bengali is a draft |
| B10 | CI: dummy file, release-assembly check, proof of the guard | P009b | done |
| B11 | README, `CLAUDE.md`, ADR 0012, `docs/legal`, diagram, logs, Notion | P009b and **P009c** | done |

Tests of section 10B: phone normalisation (b); onboarding order and process death (c);
under-18 and decline make zero calls (b for the session, c for the screens); bootstrap body
(b); every session outcome (b); OTP timer and error mapping (c and b); `IdTokenProvider` (b);
logging (b, and c in the full-flow test); notice screen (c); string parity, font scale, touch
targets (c); release guard (b, in CI).

### Manual checks on Rahul's phone: NOT RUN

Claude Code has no device. These are the checks the prompt lists; none has a result yet.

| # | Check | Result |
| --- | --- | --- |
| 1 | Sign in with a Firebase **test** number and its console code | not run |
| 2 | Kill the app on the code screen and reopen: phone screen shows | not run |
| 3 | Airplane mode during sign-in: a friendly error, no crash | not run |
| 4 | After sign-in: airplane mode, restart: Home opens | not run |
| 5 | "I am under 18": dialog, then the blocked screen; reopen: still blocked | not run |
| 6 | Bengali notice: switch on the notice screen; text reads sensibly | not run |
| 7 | Settings: masked number shown; Sign out returns to Welcome | not run |
| 8 | Developer → Who am I shows role `user` and the language | not run |
| 9 | TalkBack reads each onboarding screen top to bottom; errors are spoken | not run |
| 10 | Optional, once, after 1–8 pass: one real SMS to Rahul's own number (costs money) | not run |

**SOS failure matrix (Plan v7 §7.5):** not applicable, no SOS code. The offline-open rule is
the only device-first item (tested in P009b; check 4 above on the phone).

**CI on the pull request:** see the PR; recorded in Notion when it finishes.

## 7. Decisions & ADRs

**ADR 0010 addendum** (under-18 answer): no in-app undo; confirmation first; one local flag;
blocked screen with the 112 note and `[grievance contact]`; clearing storage or reinstalling
asks again, and nothing is stored to prevent that. No new ADR.

Implementation decisions:

- **The session state decides the screen.** `onboardingDestinationFor` is the whole rule.
  Each state change replaces the screen, so the back stack holds one onboarding screen and
  **back leaves the app** instead of returning to a finished step. Exception: back on the code
  screen returns to the phone screen.
- **"I agree" is enabled after the notice was scrolled to its end** (the prompt asked to
  decide and document). If the notice fits on the screen it is enabled at once.
- **The notice's language switch re-reads the strings from a context in that language.** The
  app's locale is untouched. The language shown when "I agree" is tapped is what gets recorded.
- **The whole notice screen follows the switch,** buttons included, so a Bengali reader does
  not agree through an English button.
- **Language splits are off** so both languages are always installed (section 3, step 6).
- **The blocked screens have no button.** The 112 note is text; there is no dialer button
  there, because this is not an emergency surface and the emergency red is reserved.
- **Sign out stays available when the account cannot be loaded.**
- **The phone number is masked inside `SessionRepository`.** No ViewModel or screen receives
  the full number.
- **Process death on the code screen returns to the phone screen** (decided in P009b: the
  verification id and the number are not stored).
- **The code field does not submit by itself at six digits.** The user taps "Verify" (or the
  keyboard's done key), which avoids sending a half-corrected code.
- **The legal document is generated from the strings** and a test compares them, so they
  cannot drift apart.

## 8. Security & privacy notes

- **Nothing new is stored.** The phone number, the SMS code and the verification id are in
  `SignInViewModel`'s memory only, and are dropped on sign-in.
- **No full phone number reaches the UI** after sign-in: Settings gets `+91 ••••• ••123`.
- **Nothing is logged** by the new code. The full-flow test reads Logcat for the typed number
  and the token.
- **Before consent:** the screens for welcome, age and notice call no sign-in function (tested
  in the real activity).
- **The under-18 path** makes no network or sign-in call and writes one flag (tested).
- **Error sentences never repeat the number or the code** (tested in both languages).
- **Test phone numbers** are made-up patterns that pass the validator and are never used to
  send anything; the tests now say so.
- **The consent notice is a draft.** It names Google Cloud in India and Google's Firebase
  service, and carries the placeholders `[operator name]` and `[grievance contact]`. Debug
  builds mark it "DRAFT" on screen. **Release builds do not show the marker**, so the lawyer
  review must be finished before any release build is given to anyone.
- No new permission, no new dependency. Public-repository check on the diff: no identifiers,
  local paths or real numbers.

## 9. Known issues & risks

- **Not run on a phone.** Layout on real screens, the keyboard and autofill behaviour, SMS
  auto-retrieval, the reCAPTCHA fallback and TalkBack are untested until Rahul's checks.
- **Bengali is a draft throughout,** and the notice is legal text translated by a machine.
- **The notice text is not legally reviewed** and makes factual claims (where data is kept,
  what Firebase receives) that the lawyer and Rahul must confirm.
- **`[grievance contact]` is shown to users as a literal placeholder**, including on the
  blocked screen, until an operator exists.
- **An adult who confirms "under 18" by mistake** can only recover by clearing the app's
  storage or reinstalling. The screen points to the grievance contact, which does not exist
  yet.
- **Resend timer and process death:** the 60-second wait restarts if the app is killed,
  because nothing about the verification is stored.
- **Over the size guide** (section 4).
- **Correction to a commit message:** `9a92e8a` says 6 new Bengali debug strings; there are 5.

## 10. Follow-ups & prerequisites for next prompt

New in Notion (*Follow-ups*): AGPL additional permission for the Firebase and Play libraries
(lawyer list; added at `/start-prompt`) · fill `[operator name]` and `[grievance contact]`
before any release · Bengali review of the onboarding and notice strings (native speaker and
lawyer) · make sure a release build cannot ship while the notice is a draft (P022).

Updated: "Decide the under-18 undo rule and wording" → done · "show and record DPDP consent
before bootstrap" → done · "P009: real-device auth check against Firebase staging" → waits for
Rahul's manual checks · "P009: city-neutral consent and onboarding copy" → done (no city in the
strings).

Still open from P009: lawyer review of the notice, privacy policy and terms; Play Data safety
form; consent UI for optional purposes; Hindi; phone-number change; multiple devices; SMS cost
monitoring; App Check monitor mode.

**Next: P010** (`feat/010-map-location`), not started. It needs this pull request merged and,
ideally, the manual checks above done first, since P010 builds on a signed-in app.

## 11. How Rahul can verify

1. Read the pull request commit by commit: session seam, onboarding, Settings, Developer,
   docs. Read [`docs/legal/consent-notice-v1.md`](../legal/consent-notice-v1.md) and the
   ADR 0010 addendum.
2. `git check-ignore android/app/google-services.json` prints the path.
3. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug` → green, 287 tests.
4. Sync in Android Studio and run the **debug** app on the phone. If it opens straight to
   Home, clear the app's storage first (*Settings → Apps → SafeRoute → Storage*).
5. Do the manual checks 1–9 from section 6 with a Firebase **test** number. Type the number
   and code on the phone only.
6. Tell Claude Code the results (pass or what happened), so they can be recorded.
7. CI green; squash and merge; delete the branch.

## 12. Learning notes

- **State-driven navigation.** The app does not remember "which screen comes next". It looks
  at one value, the session state, and shows the screen for it. After a restart the same value
  is worked out again from what is stored, so the right screen appears by itself.
- **Type-safe destinations.** Each screen is a Kotlin object (`OnboardingAgeDestination`), not
  a text route, so a typing mistake is a compile error.
  <https://developer.android.com/guide/navigation/design/type-safety>
- **`LaunchedEffect(key)`.** Runs a block when the composable first appears and again whenever
  `key` changes. Here: when the target screen changes, navigate to it.
- **`popUpTo`.** Removes screens from the back stack while navigating. Removing all of them
  means "back" leaves the app.
- **`BackHandler`.** Lets one screen decide what the system back gesture does (code screen →
  phone screen).
- **`rememberSaveable`.** Keeps a small piece of UI state (is the dialog open?) across a
  rotation. `remember` alone would lose it.
- **`CompositionLocalProvider` and a localized context.** Composables read things like the
  current resources from "composition locals". Providing a different one for part of the
  screen changes it only there: that is how the notice shows Bengali while the app stays in
  English.
- **`snapshotFlow`.** Watches a Compose state value from a coroutine. Reading the scroll
  position directly would redraw the screen for every pixel scrolled.
- **Autofill hints (`contentType`).** Tell Android what a field is for, so it can offer your
  own phone number or the code from the SMS.
- **Semantics for accessibility.** `heading()`, `error(...)` and `liveRegion` describe the
  screen to TalkBack: what is a title, what is wrong with a field, and what should be read out
  when it appears.
- **Font scale.** People can set text up to 200% in Android's settings. Screens are scrolling
  columns so that large text pushes content down instead of cutting it off.
- **Virtual time in tests.** `advanceTimeBy(60_000)` moves the test's clock by a minute
  instantly, so the 60-second resend wait is tested in milliseconds.
- **Plurals.** `<plurals>` lets a language choose the wording by number ("1 second",
  "2 seconds"); `pluralStringResource` picks the right one.
- **App bundle language splits.** Google Play can install only the languages a phone uses.
  Switching them off keeps both of ours installed, which the notice's language switch needs.
- **Masking.** Showing `+91 ••••• ••123` instead of a full number. It is done once, as close to
  the source as possible, so the full number cannot leak into a screen, a log or a screenshot.
