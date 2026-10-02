# P007b: Android app shell (navigation, Home / Search / Settings, the 112 dialog)

| Field | Value |
| --- | --- |
| Prompt | P007 · Android app shell, **part b** of two |
| Milestone | M2 (depends on P007a, merged as `c90126d`) |
| Branch | `feat/007b-navigation-and-screens` |
| PR title | `feat(android): app shell with navigation, Home, Search, Settings and the 112 dialog [P007b]` |
| Notion | [P007b row in the Prompt Log](https://app.notion.com/p/3ed0737077208147ad93fa17b9122f72) |
| Date | 2026-10-03 |
| Plan refs | Plan v7 §1, §5.1–§5.4, §12.3, §17, §18; ADR 0005, ADR 0008 |

## 1. Objective

Finish P007: put the P007a components on real screens. Type-safe navigation between Home,
Search and Settings; the maps-style Home layout with a placeholder where the map will be;
`HomeViewModel`; and the one real behaviour of the shell, the emergency button that can open
the phone dialer with 112. With tests for all of it, the app-structure diagram and the
remaining learning notes.

Not in scope, and not built: a real map, location, search results, routing, SOS, contacts,
Firebase, an API client, onboarding, language persistence, release signing.

## 2. Context & prerequisites

- P007a merged (PR #14, `c90126d`). No open pull requests. Hooks active.
- **The working tree was not clean at `/start-prompt`:** `android/gradlew.bat` had an
  uncommitted change that Claude Code did not make (the `CLASSPATH` lines removed, which is
  what a regenerated Gradle wrapper script looks like). It was neither stashed, discarded nor
  committed. The branch was created from the merged `main` without a checkout of `main`
  (`git fetch origin main:main`, then `git switch --no-track -c … origin/main`), so the file
  stayed as it was, and it is in no commit of this branch. Rahul decides what to do with it.
- The P007 prompt text was still at hand from the P007a session.
- Versions for this part were already checked in P007a (log section 4).

## 3. Workflow executed

1. `/start-prompt P007b`: `main` fast-forwarded to `c90126d`, PR #14 confirmed merged, Notion
   P007a → Merged, new row P007b, branch `feat/007b-navigation-and-screens`.
2. Read the Navigation, Lifecycle, Hilt and Material 3 sources of the chosen versions for the
   exact signatures and for experimental markers.
3. Dependencies, destinations, `SafeRouteNavHost`, `HomeViewModel`, the dialer helper, the
   emergency dialog, the map placeholder, the three screens, strings. `assembleDebug` →
   successful. Commit `3334c2d`.
4. Tests. Three failed at first, all for the same reason in the tests (section 6). Commit
   `6c6315e`.
5. Diagram, README, third-party list, two Android rules in `CLAUDE.md`, this log.
6. Quality gate, `/ship-prompt`.

## 4. Changes

| Area | What |
| --- | --- |
| Build | Navigation Compose 2.10.2, Lifecycle runtime-compose and viewmodel-compose 2.11.0, `hilt-lifecycle-viewmodel-compose` 1.4.0, kotlinx.serialization core 1.11.0 and its Kotlin plugin, Turbine 1.2.1 (tests); `buildConfig = true` |
| `navigation/` | `HomeDestination`, `SearchDestination`, `SettingsDestination` (`@Serializable` objects); `SafeRouteNavHost` |
| `SafeRouteApp` | the temporary body from P007a is replaced by the navigation host |
| `feature/home` | `HomeRoute` (connects the ViewModel), `HomeScreen` (stateless, with a `map` slot), `HomeViewModel` and `EmergencyDialogState`, `EmergencyDialog`, `EmergencyDialer` (`ACTION_DIAL tel:112`), `MapPlaceholder` |
| `feature/search` | `SearchScreen`: back button, autofocused text field, empty state |
| `feature/settings` | `SettingsScreen`: disabled language row, About (name, version, AGPL notice, repository as text), safety notice |
| Strings | 24 new, in `values/` and `values-bn/` (38 in total) |
| Tests | 5 new classes, 2 changed; 58 tests in total (was 28) |
| Docs | diagram `007-android-app-shell`, `android/README.md` (status, structure, how a screen is put together, versions), `android/THIRD_PARTY.md`, two rules added to `CLAUDE.md` "Android rules", this log |

**Size:** about 2,300 hand-written lines: Kotlin 1,013 (including KDoc and previews), tests 623,
strings 78, build 24, docs and this log about 550. Over the ~800-line guide, like P007a; the
commits are separate review units (screens, tests, docs).

**API contract diff:** none. **Migrations:** none. **Deployment impact:** none; nothing under
`backend/` or a deploy workflow changed.

### How Home is laid out

- The map fills the screen. It is a **slot** (`map: @Composable (Modifier) -> Unit`): P010
  passes the MapLibre view and nothing else on Home changes.
- The search pill is at the top inside the safe insets, with a settings button at its end.
- The two map controls sit at the end edge, above the emergency button's row, so the two can't
  overlap even at 200% font. Both are disabled and say "not available yet".
- The emergency button sits just above the sheet's peek area and is **drawn after the sheet**:
  when the sheet is pulled up it covers the map controls but never the emergency button.
- TalkBack order is set explicitly (`traversalIndex`): search → map controls → emergency → sheet.

## 5. Diagram

[`docs/diagrams/007-android-app-shell.svg`](../diagrams/007-android-app-shell.svg): MainActivity
→ SafeRouteApp and theme → NavHost → Home, Search, Settings; what Home contains; the emergency
button → dialog → system dialer chain; Hilt providing `HomeViewModel` and the Clock and
dispatchers; and where P008, P009, P010 and P014 attach. 20 nodes.

## 6. Quality gate & test results

| Command | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` (in `android/`) | BUILD SUCCESSFUL |
| → lint | 0 errors, 2 warnings (both `OldTargetApi`, deliberate, ADR 0008) |
| → unit tests | 58 tests, 0 failures, 0 skipped |
| → assembleDebug | `app-debug.apk` built |
| `pnpm generate` in `tools/diagrams` | no errors; PNG checked by eye |
| markdownlint-cli2 | 0 errors |
| JSON validity | all valid |
| gitleaks 8.30.1 | no leaks |
| `git ls-files android \| grep -iE "local.properties\|\.jks\|google-services"` | no output |

`android-ci` runs on the pull request; its result is added below once it is known.

| Class | Tests | Covers |
| --- | --- | --- |
| `HomeViewModelTest` (Turbine) | 5 | Hidden → OfferDialer → Hidden; dialer opened; dialer unavailable; no duplicate emission |
| `EmergencyDialerTest` | 3 | intent is `ACTION_DIAL` with `tel:112` and is not `ACTION_CALL`; returns false instead of crashing on `ActivityNotFoundException` |
| `AppNavigationTest` (real `MainActivity`, Hilt) | 8 | starts on Home; Home → Search → system back; Home → Search → back arrow; Home → Settings (real version shown) → back; back on Home finishes the activity; emergency button → dialog → "Call 112" starts exactly one activity, `ACTION_DIAL tel:112`; Cancel starts nothing; no dialer app → the number is shown |
| `HomeScreenTest` | 10 | search pill, settings button, two disabled map controls, emergency button, sheet handle and title are shown; events; TalkBack groups in order; map label in debug only; 200% font; both dialog states; Bengali |
| `ScreensTest` | 4 | Search: focused field, typing, empty state, back. Settings: version, licence, repository text, safety notice; language row disabled without a click action; 200% font |
| from P007a, unchanged | 22 | contrast, string parity, components, sheet, Hilt graph |
| `MainActivityTest`, `SosColourUsageTest` (changed) | 6 | start screen is Home; `EmergencyDialog.kt` added to the files allowed to use the `sos` colour |

**What failed on the way:** three new tests asserted that icon buttons are 48 dp wide. A
Material icon button is drawn 40 dp wide and accepts touches on 48 dp. The assertions now
measure the touch area (`testing/TouchTarget.kt`), which is what the 48 dp rule is about. No
screen code changed because of it.

**Contrast:** no colour changed in this part; the P007a ratios stand (text pairs ≥ 6.22:1,
component pairs ≥ 4.12:1). The dialog's "Call 112" button uses `onSos` on `sos` (6.54:1 light,
7.75:1 dark). The disabled language row is deliberately faded (38% opacity), as Material does
for disabled content; it also says "Coming soon" in words.

**Not verified:** anything on a real device. Claude Code has no phone or emulator. In
particular: how the sheet feels under a finger, the splash, the real dialer opening, TalkBack's
spoken output, and the keyboard on the Search screen. These are in section 11.

**SOS failure matrix (Plan v7 §7.5):** not applicable. There is no SOS logic: no countdown,
no SMS, no session, no location. The emergency button opens a dialog whose only action is to
show the system dialer with 112. P014 brings the real flow and the matrix.

## 7. Decisions & ADRs

No new ADR; ADR 0008 covers this part. Choices made here:

- **`hilt-lifecycle-viewmodel-compose` instead of `hilt-navigation-compose`.** The prompt
  names the latter; `hiltViewModel()` has moved to the former.
- **Own top bars (a row with a back button) instead of Material's `TopAppBar`.** One of its
  overloads is still experimental in Material 3 1.4.0 and its signature refers to an
  experimental type; a row avoids any opt-in (ADR 0008, stable APIs only).
- **Three dialog states, not a boolean:** hidden, offer the dialer, dialer unavailable. The
  last one keeps the dialog open and shows the number instead of a snackbar, so the message
  can't be missed or time out.
- **Opening the dialer is a function called from `HomeRoute`,** not from the ViewModel: it
  needs an Android context, and the ViewModel stays free of framework types. The ViewModel is
  told the outcome.
- **`HomeViewModel` does not inject the Clock.** Nothing on Home needs the time. The Hilt
  module from P007a stays covered by `CoreModuleTest`; P008 is its first production user.
- **The map label and grid are both debug-only**; a release build shows a plain surface.
- **The emergency button floats above the sheet** at every detent (see section 4).
- **`popBackStack()` for the back arrows, `launchSingleTop` for forward navigation.** No
  custom back handling; predictive back is NavHost's.
- **The dialog explains what "Call 112" does** ("opens your phone app with 112 entered. You
  start the call yourself."), so that it can't be read as the app calling for help.

## 8. Security & privacy notes

- No permission added; the manifest is unchanged. `ACTION_DIAL` needs none, and
  `MainActivityTest` still asserts that no Android permission is requested.
- No network code. The repository address in Settings is plain text.
- Nothing typed into the Search field is stored, logged or sent. There are no `Log` calls.
- New libraries are AndroidX and Kotlin (Apache-2.0); Turbine (Apache-2.0) is test-only.
  Listed in `android/THIRD_PARTY.md`.
- No keystore, `google-services.json` or `local.properties` tracked.
- Public-repository check on the diff: no local paths, e-mail addresses or IDs. The Settings
  screen names the public repository.
- New personal data: none.

## 9. Known issues & risks

- **Bengali strings are drafts and need human review before release** (all 38, 24 of them new;
  the emergency dialog texts matter most).
- When the sheet is fully open, the emergency button floats over the sheet's content. That is
  intended for now; what the sheet shows around it is a design question for P010 and P014.
- The sheet and the screens are untested on a device.
- The language row does nothing. On Android 13+ the language can already be changed in the
  system settings for the app; older versions follow the phone's language.
- `android/gradlew.bat` is modified locally and uncommitted (section 2).

## 10. Follow-ups & prerequisites for next prompt

Recorded in Notion (*Follow-ups*, source P007b): language choice inside the app with DataStore
persistence · Android Studio run-configuration tips for Rahul · the emergency button over a
fully open sheet (P010 / P014) · make the repository address in Settings a link once a browser
intent is in scope · decide about the regenerated `gradlew.bat`.

Still open from P007a: Bengali review, fonts, app icon and splash, dark map style, instrumented
tests, `android-ci` as a required check, baseline profiles and R8, the sheet with the real map,
JDK 21 with Robolectric API 36, the data-extraction rules on a device, targetSdk 37, the
application ID on Google Play.

**Before P008** (`feat/008-android-api-client`): this pull request merged.

## 11. How Rahul can verify

1. Read the pull request (three commits: screens, tests, docs).
2. Open the `android` folder in Android Studio, let it sync, and run on your phone.
3. Switch the phone to dark mode, set the font size to the largest, and (Android 13+) set
   *Settings → Apps → SafeRoute → Language* to বাংলা. On Home, Search and Settings nothing
   should be cut off or overlap.
4. Tap the search pill (the keyboard opens), go back; tap the settings icon in the pill, go
   back. Drag the bottom sheet through its three positions; the emergency button stays visible.
5. Tap the emergency button. The dialog says SOS is not available and that SafeRoute is not an
   emergency service. Tap **Call 112**: the phone's dialer opens showing 112. **Do not press
   call.** Go back.
6. Turn on TalkBack and swipe through Home. Expected: search → settings → the two map controls
   ("not available yet", "disabled") → "Emergency 112, button, double tap to show emergency
   options" → the panel handle (it says "Collapsed" and offers "Expand panel" in the actions
   menu) → "Where to?" and the two rows.
7. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug` and confirm it is green.
8. Check CI is green; squash and merge; delete the branch.

Also decide about `android/gradlew.bat` (section 2): keep the regenerated script (commit it in
a later prompt) or discard the change with `git restore android/gradlew.bat`.

Next prompt: P008 (`feat/008-android-api-client`). Not started.

## 12. Learning notes

- **ViewModel.** A class that holds a screen's state and survives things that recreate the
  screen, such as rotating the phone. The screen asks for it with `hiltViewModel()`; the system
  keeps one per destination until that destination leaves the back stack.
  <https://developer.android.com/topic/libraries/architecture/viewmodel>
- **StateFlow.** A holder of one current value that tells its listeners when the value
  changes. The ViewModel owns a `MutableStateFlow` and exposes it read-only; the screen reads
  it with `collectAsStateWithLifecycle()`, which also stops listening while the app is in the
  background. <https://developer.android.com/kotlin/flow/stateflow-and-sharedflow>
- **Unidirectional data flow.** State goes down (ViewModel → screen), events go up (screen →
  ViewModel, as `onEmergencyClick()`). The screen never changes state by itself, so there is
  one place to look when something is wrong.
- **Route and Screen.** `HomeScreen` only draws what it is given; `HomeRoute` fetches the
  ViewModel and wires it up. Previews and tests use `HomeScreen` directly.
- **Navigation and the back stack.** `NavHost` shows one destination at a time. `navigate()`
  puts a screen on top of a stack; back removes the top one; back on the last one leaves the
  app. Destinations are Kotlin objects (`SearchDestination`), not strings, so a typo can't
  compile. <https://developer.android.com/guide/navigation/design/type-safety>
- **Predictive back.** On recent Android versions the back gesture shows a preview of where it
  leads before you let go. It works because the app doesn't intercept back itself.
- **Intents; `ACTION_DIAL` vs `ACTION_CALL`.** An intent asks Android to do something with
  another app. `ACTION_DIAL` opens the phone app with a number typed in; the person presses
  call, and no permission is needed. `ACTION_CALL` would place the call at once, needs the
  `CALL_PHONE` permission, and Android does not allow it for emergency numbers. For 112 the
  dialer is both the only correct and the safest choice: no accidental calls.
  <https://developer.android.com/reference/android/content/Intent#ACTION_DIAL>
- **`ActivityNotFoundException`.** Thrown when no app can handle an intent (a tablet without a
  phone app). We catch it and show the number instead of crashing.
- **`BuildConfig`.** A class generated at build time with facts about the build: `DEBUG`,
  `VERSION_NAME`, `VERSION_CODE`. Home uses `DEBUG` for the map label; Settings shows the
  version.
- **Slots.** A composable parameter that is itself a composable (`map`, `trailingContent`).
  The caller decides what goes in, which is how P010 will swap the placeholder for the map.
- **TalkBack order.** TalkBack normally reads top to bottom. `isTraversalGroup` and
  `traversalIndex` set the order when the layout order isn't the logical one.
  <https://developer.android.com/develop/ui/compose/accessibility/traversal>
- **Touch target vs size.** A control may be drawn smaller than the area that reacts to a
  finger. The 48 dp guidance is about the area that reacts.
- **Turbine.** A small test library for flows: `flow.test { awaitItem() }` reads emitted
  values one by one. <https://github.com/cashapp/turbine>
