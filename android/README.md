# android/

The native Android app for SafeRoute: Kotlin, Jetpack Compose, Material 3 and Hilt
(Plan v7 §5). Decisions and their reasons are in
[ADR 0008](../docs/adr/0008-android-foundation.md).

**Status (P007):** the app shell. Home shows a placeholder where the map will be, a search pill,
two map buttons (disabled), a bottom sheet and the emergency button; Search and Settings are
reachable from it. The only real behaviour is the emergency button: a dialog that can open the
phone dialer with 112. The map arrives with P010, sign-in with P009, SOS with P014.

![App structure](../docs/diagrams/007-android-app-shell.svg)

## Open it in Android Studio

1. *File → Open* and pick the **`android`** folder (not the repository root). Android Studio
   treats that folder as the Gradle project.
2. Wait for "Gradle sync" to finish (bottom status bar). The first sync downloads the libraries
   and a JDK 17, so it needs a network connection and a few minutes.
3. The file `local.properties` that Android Studio creates holds the path of your Android SDK.
   It is ignored by git and must never be committed.

## Run it on a phone

1. On the phone: *Settings → About phone*, tap *Build number* seven times, then switch on
   *Developer options → USB debugging*.
2. Connect the phone by USB and accept the "Allow USB debugging" prompt.
3. In Android Studio pick the phone in the device menu (top toolbar) and press **Run** (the
   green triangle). The run configuration is called `app`.

## Run the quality gate from a terminal

In the `android` folder. Android Studio's terminal (*View → Tool Windows → Terminal*) opens
at the right place if you opened the `android` folder.

```sh
./gradlew lint testDebugUnitTest assembleDebug
```

On Windows PowerShell write `.\gradlew` instead of `./gradlew`.

- `lint` checks the code and resources for Android-specific mistakes. Errors fail the build;
  warnings don't. Report: `app/build/reports/lint-results-debug.html`.
- `testDebugUnitTest` runs the tests on your computer (no phone needed). Report:
  `app/build/reports/tests/testDebugUnitTest/index.html`.
- `assembleDebug` builds the debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

One test: `./gradlew testDebugUnitTest --tests "*ThemeContrastTest"`.

If Gradle can't find Java, point it at the JDK that ships with Android Studio **for this
terminal session only** (don't set it system-wide). In PowerShell, with the default install
location:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

CI runs the same command: [`.github/workflows/android-ci.yml`](../.github/workflows/android-ci.yml).

## Project structure

One Gradle module (`:app`), packages by feature (Plan v7 §5.2):

```text
android/
  gradle/libs.versions.toml        every version, in one place
  gradle/gradle-daemon-jvm.properties   which JDK runs Gradle itself (25)
  app/build.gradle.kts             the app module's build
  app/src/main/
    AndroidManifest.xml            app entry points; no permissions yet
    java/com/saferoute/app/
      SafeRouteApplication.kt      process entry point (@HiltAndroidApp)
      MainActivity.kt              the single activity
      SafeRouteApp.kt              root composable: background + navigation host
      navigation/                  destinations (type-safe routes) and the NavHost
      feature/home/                Home screen, HomeViewModel, map placeholder, 112 dialog
      feature/search/              Search screen (layout only until P011)
      feature/settings/            Settings screen with the About section
      core/designsystem/theme/     colours, type, shapes, spacing: the design tokens
      core/designsystem/component/ SearchPill, MapControlButton, EmergencyButton, sheet
      core/designsystem/preview/   @SafeRoutePreviews (light, dark, Bengali, 200% font)
      core/di/                     Hilt module: Clock and coroutine dispatchers
    res/values/strings.xml         English text
    res/values-bn/strings.xml      Bengali text
    res/xml/locales_config.xml     languages offered by the system per-app language picker
  app/src/test/                    tests that run on the JVM (JUnit, Robolectric)
```

Packages for later features (`data/`, `service/`, `work/`, more `feature/…`) are created by the
prompt that needs them.

How a screen is put together (see `feature/home`):

- `HomeScreen` is plain UI: it takes values and callbacks, knows nothing about Hilt or
  navigation, and can be previewed and tested alone.
- `HomeRoute` connects it to `HomeViewModel`, which holds the state as a `StateFlow`.
- `navigation/SafeRouteNavHost.kt` is the only place that knows which screen leads where.
- Features depend on `core`; `core` never depends on a feature.

## Versions

Chosen on 2026-10-02 from Google Maven and Maven Central metadata; all are stable releases.

| What | Version | Note |
| --- | --- | --- |
| Gradle | 9.6.0 | wrapper, as created by the wizard |
| Android Gradle Plugin | 9.4.1 | Kotlin support is built in |
| Kotlin and Compose compiler plugin | 2.4.20 | |
| KSP | 2.3.12 | runs Hilt's code generator (no kapt) |
| Hilt (Dagger) | 2.60.1 | |
| Compose BOM | 2026.09.00 | Compose UI 1.12.1, Material 3 1.4.0, icons core 1.7.8 |
| Activity Compose | 1.13.0 | |
| Navigation Compose | 2.10.2 | type-safe routes |
| Lifecycle (runtime-compose, viewmodel-compose) | 2.11.0 | |
| Hilt ViewModel for Compose (`hilt-lifecycle-viewmodel-compose`) | 1.4.0 | provides `hiltViewModel()` |
| kotlinx.serialization (core) | 1.11.0 | makes routes `@Serializable`; plugin version = Kotlin |
| Core SplashScreen | 1.2.0 | |
| kotlinx.coroutines | 1.11.0 | |
| JUnit 4 · Robolectric · AndroidX Test · Turbine | 4.13.2 · 4.17 · core 1.7.0, ext-junit 1.3.0 · 1.2.1 | tests only |
| compileSdk · targetSdk · minSdk | 37 · 36 · 26 | see ADR 0008 for why compileSdk is not 36 |
| JDK | 17 to compile and test; 25 to run Gradle | Gradle downloads 17 if it is missing |

To change a version, edit `gradle/libs.versions.toml` only.

## Design tokens

Everything visual comes from `core/designsystem/theme/`:

| File | Holds | Read it with |
| --- | --- | --- |
| `Color.kt` | Material 3 light and dark colour schemes | `MaterialTheme.colorScheme.primary` |
| `SafeRouteColors.kt` | meaning-bearing colours: `sos`, `caution`, `positive`, `mapOverlay`, `scrim` | `SafeRouteTheme.colors.sos` |
| `Type.kt` | the type scale (system fonts) | `MaterialTheme.typography.bodyLarge` |
| `Shape.kt` | corner shapes; pill and sheet shapes | `MaterialTheme.shapes.medium`, `SafeRouteShapeTokens.Pill` |
| `Dimens.kt` | spacing (4/8/12/16/24/32 dp), elevation, the 48 dp touch target | `SafeRouteTheme.spacing.md` |

Rules: don't write colour or dp literals in screens; the `sos` red is only for the emergency
button and dialog; after changing a colour run `ThemeContrastTest`, which checks the contrast
ratios in both themes.

To see a component without running the app, open its file and choose *Split* (top right of the
editor): the previews render light, dark, Bengali and 200% font.

## Add a string (always in both languages)

1. Add it to `app/src/main/res/values/strings.xml`:
   `<string name="home_title">Where to?</string>`
2. Add the **same name** to `app/src/main/res/values-bn/strings.xml` with the Bengali text.
   If you can't translate it, write a draft and list it in the prompt log as "needs human
   review".
3. Use it: `stringResource(R.string.home_title)` in a composable. Never put user-visible text
   directly in Kotlin.
4. Counted text ("1 contact", "3 contacts") uses `<plurals>` and `pluralStringResource`.

`StringResourceParityTest` fails if the two files don't have the same names or placeholders.
The Bengali strings in the repository are **drafts** until a fluent speaker has reviewed them.

## License

Code in this folder is licensed under `AGPL-3.0-only` (see [`../COPYRIGHT.md`](../COPYRIGHT.md)).
Every Kotlin, Gradle and XML source file starts with an SPDX line. Third-party parts and their
licences: [`THIRD_PARTY.md`](THIRD_PARTY.md). The app will need a third-party notices screen
before release (follow-up for P022).
