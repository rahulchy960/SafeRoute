# P007a: Android foundation (Gradle, Hilt, theme and design-system components)

| Field | Value |
| --- | --- |
| Prompt | P007 · Android app shell, **part a** of two |
| Milestone | M2 (depends on P006d, merged as `582f263`) |
| Branch | `feat/007a-android-foundation` |
| PR title | `feat(android): Compose foundation with Hilt, theme and design-system components [P007a]` |
| Notion | [P007a row in the Prompt Log](https://app.notion.com/p/3ed07370772081f78dacd3b9a4810368) |
| Date | 2026-10-02 |
| Plan refs | Plan v7 §1, §5.1–§5.4, §12.3, §17, §18; ADR 0005, ADR 0008 |

## 1. Objective

Turn the Android Studio wizard project in `android/` into the base the Android prompts build
on: a Compose build with a version catalog, Hilt, the SafeRoute theme, the reusable
"maps-style" components, English and Bengali strings, accessibility basics and Android CI.

P007 is split in two, as the prompt allows above ~800 changed lines:

- **P007a (this log):** Gradle, entry point, Hilt, theme, components, their tests, CI, ADR 0008,
  Android rules and docs.
- **P007b (next):** navigation (Home, Search, Settings), the screens, `HomeViewModel`, the
  emergency dialog with the 112 dialer, their tests and the app-structure diagram.

## 2. Context & prerequisites

- P006d merged (PR #13, `582f263`). Hooks active, no open pull requests.
- **The working tree was not clean at `/start-prompt`:** the wizard project was staged on
  `main` and one file was untracked. Those files are this prompt's input, so they were carried
  onto the branch unchanged instead of stopping. Nothing was stashed or deleted.
- **The wizard project was not the Compose template the prompt assumed.** Found:

  | Item | Wizard value |
  | --- | --- |
  | Template | no activity, no Compose; AppCompat 1.6.1 and Material (Views) 1.10.0 |
  | AGP · Gradle | 9.4.1 · 9.6.0 |
  | Kotlin | none declared (AGP 9 built-in, 2.2.10) |
  | Compose BOM | none |
  | compileSdk · targetSdk · minSdk | 37 · 37 · 26 |
  | namespace, applicationId | `com.saferoute.app` (as required) |
  | Java source/target | 11 |

- Prerequisite check: `settings.gradle.kts`, `app/build.gradle.kts` and the wrapper exist;
  package correct; `local.properties` exists and is ignored by git. JDKs found: 21 on the PATH
  and Android Studio's bundled 25. **Platform 36 is not installed; only platform 37 is.** The
  prompt says to stop in that case. It was not necessary to stop, because the build no longer
  needs platform 36 (see section 7, compileSdk).
- The plan PDF can't be read on this machine; the prompt text was used.

## 3. Workflow executed

1. `/start-prompt P007`: synced `main`, confirmed PR #13 merged, Notion P006d → Merged, branch
   created, then renamed to `feat/007a-android-foundation` when the split was decided.
2. Inventory (table above). Read the latest stable versions from the Google Maven and Maven
   Central `maven-metadata.xml` files, and each library's `aar-metadata.properties`
   (`minCompileSdk`).
3. Baseline: `./gradlew assembleDebug` on the untouched wizard project → successful.
4. Gradle: version catalog, plugins, toolchain, lint, build types. Build → successful. Commit.
5. Entry point, manifest, Hilt module, theme tokens, strings. Build → successful. Commit.
6. Components, previews, two icons fetched from the official Material Symbols repository,
   tests. Fixed three things the tests found (section 6). Commit.
7. `android-ci.yml`, ADR 0008, `CLAUDE.md`, READMEs, `THIRD_PARTY.md`, diagram. Commit.
8. Quality gate, this log, `/ship-prompt`.

`JAVA_HOME` was set to Android Studio's JDK inside each Gradle command only. Gradle downloaded
a Temurin JDK 17 into its own cache for the compile toolchain (expected with `jvmToolchain(17)`).

## 4. Changes

| Area | What |
| --- | --- |
| `android/` build | `gradle/libs.versions.toml` (every version, SDK levels, JDK), root and app `build.gradle.kts`: Compose compiler plugin, KSP, Hilt, `jvmToolchain(17)`, lint `abortOnError`, explicit debug and unsigned release types, `en`/`bn` locale filter; `gradle-daemon-jvm.properties` tracked; `gradlew` marked executable |
| Entry | `SafeRouteApplication`, `MainActivity` (splash screen, edge-to-edge), temporary `SafeRouteApp` root |
| Manifest and resources | no permissions; `allowBackup=false` plus data-extraction rules that exclude everything; `localeConfig` (en, bn); predictive back; platform window themes plus the AndroidX splash theme; wizard sample backup file and the Views themes removed |
| `core/designsystem/theme` | light and dark `ColorScheme`, `SafeRouteColors` (sos, caution, positive, mapOverlay, scrim), typography, shapes, spacing, elevation, `MinTouchTarget`, `SafeRouteTheme` |
| `core/designsystem/component` | `SearchPill`, `MapControlButton`, `EmergencyButton`, `SheetHandle`, `SafeRouteBottomSheet` (three detents), each with KDoc and previews |
| `core/designsystem/preview` | `@SafeRoutePreviews`: light, dark, Bengali, 200% font |
| `core/di` | `CoreModule` (`Clock`, IO and Default dispatchers) and qualifiers |
| Strings | 14 strings in `values/` and `values-bn/` |
| Icons | `ic_my_location.xml`, `ic_layers.xml` (Material Symbols, Apache-2.0) |
| Tests | 7 classes, 28 tests (section 6); `robolectric.properties` |
| CI | `.github/workflows/android-ci.yml` |
| Docs | ADR 0008 and index row, `CLAUDE.md` "Android rules" and gate row, `android/README.md`, `android/THIRD_PARTY.md`, root README package name, PR template line for Android screenshots, diagram |

Removed from the wizard output: the two example tests, `backup_rules.xml`, the AppCompat,
Material (Views), Espresso and AndroidX JUnit (instrumented) dependencies.

**Size:** about 3,200 hand-written lines (Kotlin 1,250 including KDoc and previews, tests 734,
docs and this log about 680, build files, CI and resources about 540), plus the wizard's
generated files. That is well over the ~800-line guide even after the split. The four commits are separate review units:
build, entry and theme, components and tests, CI and docs.

**API contract diff:** none. **Migrations:** none. **Deployment impact:** none; nothing under
`backend/` or the deploy workflow changed.

### Version decisions (R0)

| Item | Decision | Why |
| --- | --- | --- |
| AGP 9.4.1, Gradle 9.6.0 | kept | AGP is the latest stable; Gradle 9.8.0 exists but nothing needs it |
| Kotlin | 2.4.20 | latest stable; raised from AGP's built-in 2.2.10 so that it matches the Compose compiler plugin |
| KSP | 2.3.12 | latest stable; works with Kotlin 2.4.20 and Hilt here |
| Hilt | 2.60.1 | latest stable |
| Compose BOM | 2026.09.00 | latest stable: UI 1.12.1, Material 3 1.4.0, icons core 1.7.8 |
| Activity Compose 1.13.0, SplashScreen 1.2.0, coroutines 1.11.0 | latest stable | |
| Robolectric 4.17, JUnit 4.13.2, AndroidX Test core 1.7.0 / ext-junit 1.3.0 | latest stable | |
| Material 3 Expressive | **not used** | not public in Material 3 1.4.0; only in 1.5.0 alphas |
| compileSdk | **37, not 36** | current stable AndroidX declares `minCompileSdk=37` |
| targetSdk · minSdk | 36 · 26 | as planned |
| Navigation, Lifecycle, kotlinx.serialization, hilt-navigation-compose, Turbine | **not added yet** | first used in P007b. Checked: Navigation Compose 2.10.2, Lifecycle 2.11.0, serialization 1.11.0, Turbine 1.2.1; `hiltViewModel` now lives in `hilt-lifecycle-viewmodel-compose` 1.4.0 |

## 5. Diagram

[`docs/diagrams/007a-android-foundation.svg`](../diagrams/007a-android-foundation.svg): the
build and CI chain, what the app consists of after P007a, and where P007b, P010 and P014
attach. The app-structure diagram the prompt describes (`007-android-app-shell`) needs the
screens and comes with P007b.

## 6. Quality gate & test results

| Command | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` (in `android/`) | BUILD SUCCESSFUL |
| → lint | 0 errors, 2 warnings (both `OldTargetApi`: targetSdk 36 while 37 exists; deliberate, ADR 0008) |
| → unit tests | 28 tests, 0 failures, 0 skipped |
| → assembleDebug | `app-debug.apk` built |
| actionlint 1.7.12 on all workflows | clean |
| `pnpm generate` in `tools/diagrams` | no errors; PNG checked by eye |
| markdownlint-cli2 | 0 errors |
| JSON validity | all valid |
| gitleaks 8.30.1 | no leaks |
| `git ls-files android \| grep -iE "local.properties\|\.jks\|google-services"` | no output |

**android-ci has not run yet.** Its first run is on this pull request; the result is not known
at the time of writing. Unverified until then: that the runner provides platform 37, and that
the two-JDK setup works on Linux.

Tests (all JVM; Robolectric where the Android framework is needed):

| Class | Tests | Covers |
| --- | --- | --- |
| `ThemeContrastTest` | 4 | 21 text pairs ≥ 4.5:1 and 9 component pairs ≥ 3:1, both themes; the helper against known WCAG values |
| `StringResourceParityTest` | 5 | same keys and placeholders in English and Bengali; `112` in both |
| `SosColourUsageTest` | 2 | sos tokens used only in `EmergencyButton.kt` |
| `ComponentsTest` | 6 | roles, labels, 48 dp targets, disabled state, handle actions, 200% font, Bengali resources |
| `SafeRouteBottomSheetTest` | 5 | detent positions; expand/collapse actions; tap cycle; drag |
| `CoreModuleTest` | 2 | Hilt provides one UTC `Clock` and the qualified dispatchers |
| `MainActivityTest` | 4 | the real activity starts; application ID; no Android permission; backup off |

What the tests caught while writing: a Kotlin property named `default` (a Java keyword, rejected
by Hilt's code generator); Robolectric's API 36 image needing Java 21 (now pinned to API 35);
and a test container taller than Robolectric's screen. None of the three was a fault in a
component. The two guard tests (`SosColourUsageTest`, key parity) were checked to fail on a
planted violation, which was then removed.

**Contrast ratios verified** (lowest and notable pairs; the test prints all 60):

| Pair | Light | Dark | Minimum |
| --- | --- | --- | --- |
| onSurface / surface (body text) | 16.54 | 14.37 | 4.5 |
| lowest text pair in each theme | 6.22 (primary / surface) | 7.21 (onCautionContainer / cautionContainer) | 4.5 |
| onPrimary / primary | 6.45 | 7.74 | 4.5 |
| onSos / sos (emergency button label) | 6.54 | 7.75 | 4.5 |
| mapOverlayVariant / mapOverlay (search hint) | 9.35 | 8.51 | 4.5 |
| sos / surfaceContainerLow (button against the sheet) | 5.98 | 7.51 | 3 |
| outline / surfaceContainerLow (sheet handle) | 4.12 | 5.41 | 3 |
| lowest component pair in each theme | 4.12 (the sheet handle) | 4.56 (outline / mapOverlay) | 3 |

**Not verified:** anything on a real device. Claude Code has no phone or emulator; the
components were checked with JVM tests only. Previews were written but not rendered here.

**SOS failure matrix (Plan v7 §7.5):** not applicable. There is no SOS logic; the emergency
button is a component that reports a tap, and nothing is wired to it until P007b.

## 7. Decisions & ADRs

[ADR 0008](../adr/0008-android-foundation.md) (Accepted) records the lasting ones. Deviations
from the prompt, each for a reason found during the work:

- **compileSdk 37 instead of 36.** Staying on 36 would mean pinning superseded AndroidX
  releases. targetSdk, which governs runtime behaviour, stays 36.
- **Did not stop for the missing platform 36 or the unclean tree** (section 2).
- **The sheet is not a wrapper around `BottomSheetScaffold`.** That API is experimental in
  Material 3 1.4.0 and has two positions; the prompt also forbids experimental opt-ins. The
  sheet uses Foundation's stable `anchoredDraggable`.
- **"Spring motion 200–300 ms":** a spring has no duration. Stiffness 600 without bounce
  settles in roughly that time.
- **Robolectric runs API 35**, one below targetSdk, because its API 36 image needs Java 21.
- **Data-extraction rules exclude device-to-device transfer too**, beyond `allowBackup=false`.
  On Android 12+ `allowBackup=false` alone still allows transfers on some devices.
- **`android-ci.yml`, ADR 0008 and the Android rules ship in P007a**, not P007b, so that the
  first Android pull request is already checked by CI and its decisions are recorded with it.
- **Two JDKs in CI** (17 and 25) instead of only 17: Android Studio generated daemon criteria
  asking for 25 to run Gradle.
- **`hilt-navigation-compose` will not be used** in P007b; `hiltViewModel` has moved to
  `hilt-lifecycle-viewmodel-compose`.
- The wizard's `versionCode 1` / `versionName "1.0"` were left as they are.

## 8. Security & privacy notes

- No network code, no permissions, no analytics, no logging. The merged manifest contains one
  permission, `com.saferoute.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which AndroidX adds
  for itself; it grants nothing and is covered by a test.
- `allowBackup=false`; nothing is backed up or transferred.
- No keystore, `google-services.json` or `local.properties` tracked (check in section 6). The
  release build type is unsigned.
- Third-party code: AndroidX, Compose, Hilt, Kotlin (Apache-2.0); tests only: Robolectric
  (MIT), JUnit 4 (EPL-1.0). Listed in `android/THIRD_PARTY.md`. Nothing incompatible with
  AGPL-3.0.
- The launcher icon is the wizard's Android robot (attribution in `THIRD_PARTY.md`), kept as a
  placeholder as the prompt allows.
- Public-repository check on the diff: no local paths, e-mail addresses or IDs. The README
  names Android Studio's default install folder, which is not specific to this machine.
- New personal data: none.

## 9. Known issues & risks

- **Bengali strings are drafts and need human review before release** (all 14).
- The bottom sheet is untested on a device and with a real map underneath.
- The effect of the data-extraction rules on a real device transfer is unverified.
- Whether `com.saferoute.app` is free on Google Play is unknown until the first upload.
- The launcher and splash icon is the Android robot placeholder.
- On Rahul's machine the command line needs a JDK 25 to start Gradle (Android Studio's own
  works); otherwise Gradle downloads one.

## 10. Follow-ups & prerequisites for next prompt

Recorded in Notion (*Follow-ups*): Bengali strings human review · fonts decision · app icon and
splash design · dark map style with P010 · instrumented tests on a device · make `android-ci` a
required check · baseline profiles and R8 for release (P022) · sheet behaviour with the real
map (P010) · JDK 21 and Robolectric API 36 · verify the data-extraction rules on a device
(P020) · review targetSdk 37 · check the application ID on Google Play (P022).

Left for P007b's log: DataStore language persistence, Android Studio run-configuration tips.

**Before P007b:** this pull request merged. P007b then adds Navigation Compose, Lifecycle,
kotlinx.serialization and Turbine, the three screens, `HomeViewModel`, the 112 dialog and the
`007-android-app-shell` diagram.

## 11. How Rahul can verify

1. Read the pull request, commit by commit.
2. Check `repo-checks` and `android-ci` are green. If `android-ci` fails, the log of the
   failing step is what Claude Code needs.
3. In Android Studio: *File → Open* → the `android` folder, wait for sync, press Run with your
   phone connected. Expected: a short splash, then "SafeRoute" and "SafeRoute is not an
   emergency service." centred, in light or dark following the system.
4. Switch the phone to dark mode and to the largest font size: the two lines stay readable and
   nothing is cut off.
5. *Settings → Apps → SafeRoute → Language* (Android 13+): choose বাংলা. The notice appears in
   Bengali.
6. Open `SearchPill.kt`, `EmergencyButton.kt` and `SafeRouteBottomSheet.kt` in Android Studio
   and choose *Split*: the previews show light, dark, Bengali, 200% font and the sheet detents.
7. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug` and confirm it is green.
8. Squash and merge; delete the branch.

Tapping, dragging the sheet, the 112 dialer and the TalkBack walk-through belong to P007b,
where the components are on a screen. TalkBack checklist prepared for it: focus order search →
map controls → emergency → sheet; each control announces a name and "button"; the two
unavailable map controls announce "not available yet" and "disabled"; the emergency button
announces "Emergency 112, button, double tap to show emergency options"; the sheet handle
announces its state ("Collapsed", "Half open", "Fully open") and offers Expand panel and
Collapse panel in the actions menu.

## 12. Learning notes

- **What an Android app is made of.** The *manifest* (`AndroidManifest.xml`) tells the system
  what the app contains: its entry points, permissions and settings such as backup. An
  *activity* is one window the system can start; SafeRoute has exactly one. *Resources* (`res/`)
  are everything that is not code: strings, icons, themes, with variants in folders such as
  `values-bn` (Bengali) or `values-night` (dark). *Gradle* is the build tool that turns all of
  it into an APK. <https://developer.android.com/guide/components/fundamentals>
- **applicationId vs namespace.** `applicationId` is the app's identity on a phone and on
  Google Play; two apps with the same ID can't coexist, and it can never change after
  publishing. `namespace` is only the Kotlin package for generated code such as `R`. We keep
  them equal. <https://developer.android.com/build/configure-app-module>
- **compileSdk, targetSdk, minSdk.** minSdk: the oldest Android the app installs on (26 =
  Android 8). targetSdk: the Android version whose behaviour rules the app agrees to follow.
  compileSdk: which Android APIs the compiler knows about. Raising compileSdk changes nothing
  for users; raising targetSdk does.
- **Version catalog.** `gradle/libs.versions.toml` lists every library and version once; build
  files refer to them as `libs.something`. One file to edit when upgrading.
  <https://docs.gradle.org/current/userguide/version_catalogs.html>
- **Compose basics.** A *composable* is a function marked `@Composable` that describes a piece
  of UI. When the data it reads (*state*) changes, Compose calls it again (*recomposition*) and
  updates the screen. *State hoisting* means a component doesn't own its state: it receives
  values and reports events (`onClick`), so the caller decides. `SafeRouteSheetState` is an
  example. <https://developer.android.com/develop/ui/compose/mental-model>
- **Previews.** A function with `@Preview` is drawn by Android Studio without running the app.
  `@SafeRoutePreviews` draws four variants at once.
- **Hilt in plain words.** Instead of a class creating the things it needs, it asks for them
  in its constructor or with `@Inject`, and Hilt supplies them. `@HiltAndroidApp` creates the
  container, `@AndroidEntryPoint` lets an activity receive from it, a `@Module` teaches Hilt how
  to build things it can't construct itself (here a `Clock`). Tests can then swap in fakes.
  <https://developer.android.com/training/dependency-injection/hilt-android>
- **KSP.** Hilt works by generating code at build time. KSP is the tool that runs such code
  generators for Kotlin; it replaces the older, slower kapt.
- **Edge-to-edge and insets.** The app draws behind the status and navigation bars. *Insets*
  tell a composable how much of the screen those bars cover, so it can pad its content
  (`safeDrawingPadding()`). <https://developer.android.com/develop/ui/compose/system/insets>
- **Material theming and semantic colours.** `MaterialTheme` hands every component a set of
  colour roles (`primary`, `surface`, `onSurface`…), a type scale and shapes. Roles describe
  where a colour is used, not what it means. "Emergency", "caution" and "positive" are
  meanings, so they live in our own `SafeRouteColors`.
  <https://developer.android.com/develop/ui/compose/designsystems/material3>
- **Why dynamic colour is off.** Material You recolours apps from the wallpaper. For a safety
  app the accent and the tested contrast must be the same on every phone.
- **Per-app language.** `locales_config.xml` lists the app's languages; on Android 13+ the
  system shows a language picker for the app in Settings and loads `values-bn` when Bengali is
  chosen. Older versions follow the phone's language.
  <https://developer.android.com/guide/topics/resources/app-languages>
- **Robolectric.** A library that imitates the Android framework on the computer, so UI tests
  run in seconds without a phone. It is an imitation: device checks are still needed.
  <https://robolectric.org>
- **Contrast ratio.** A number from 1 to 21 comparing how light two colours are. WCAG asks for
  4.5 for normal text and 3 for large text and controls.

ViewModel and StateFlow, Navigation and the back stack, and why the 112 button uses
`ACTION_DIAL` rather than `ACTION_CALL` are explained in P007b's log, with the code they
belong to.
