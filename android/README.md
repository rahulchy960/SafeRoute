# android/

The native Android app for SafeRoute: Kotlin, Jetpack Compose, Material 3 and Hilt
(Plan v7 §5). Decisions and their reasons are in
[ADR 0008](../docs/adr/0008-android-foundation.md) and
[ADR 0009](../docs/adr/0009-android-api-client.md).

**Status (P007):** the app shell. Home shows a placeholder where the map will be, a search pill,
two map buttons (disabled), a bottom sheet and the emergency button; Search and Settings are
reachable from it. The only real behaviour is the emergency button: a dialog that can open the
phone dialer with 112. The map arrives with P010, sign-in with P009, SOS with P014.

**Status (P008a):** the app can talk to the backend: a generated API client and the network
layer around it (see [Talking to the backend](#talking-to-the-backend)).

**Status (P008b):** debug builds have *Settings → Developer*, a server check that uses that
client. Release builds don't contain it.

![App structure](../docs/diagrams/007-android-app-shell.svg)

## Open it in Android Studio

1. *File → Open* and pick the **`android`** folder (not the repository root). Android Studio
   treats that folder as the Gradle project.
2. Wait for "Gradle sync" to finish (bottom status bar). The first sync downloads the libraries
   and a JDK 17, so it needs a network connection and a few minutes.
3. The file `local.properties` that Android Studio creates holds the path of your Android SDK.
   It is ignored by git and must never be committed.
4. The build needs `app/google-services.json` (the Firebase configuration). See
   [Signing in](#signing-in) for where the real file goes, or how to write a dummy one.

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
It also runs when `contracts/` changes, builds the release variant with the dummy address
`https://example.invalid/`, checks that a release without `saferoute.apiBaseUrl` fails, and
checks that the release APK has none of the debug-only code. Since P009b it first writes a
dummy `google-services.json`, and proves that a release build refuses that dummy file and a
missing one (see [Signing in](#signing-in)).

## Project structure

One Gradle module (`:app`), packages by feature (Plan v7 §5.2):

```text
android/
  gradle/libs.versions.toml        every version, in one place
  gradle/gradle-daemon-jvm.properties   which JDK runs Gradle itself (25)
  app/build.gradle.kts             the app module's build
  app/src/main/
    AndroidManifest.xml            app entry points; one permission (INTERNET); the Firebase
                                   libraries merge in two more (see ADR 0012)
    java/com/saferoute/app/
      SafeRouteApplication.kt      process entry point (@HiltAndroidApp)
      MainActivity.kt              the single activity
      SafeRouteApp.kt              root composable: background + navigation host
      navigation/                  destinations (type-safe routes) and the NavHost
      feature/home/                Home screen, HomeViewModel, map placeholder, 112 dialog
      feature/search/              Search screen (layout only until P011)
      feature/settings/            Settings: account, privacy, About
      feature/onboarding/          welcome, age gate, consent notice, phone and code, blocked
      core/designsystem/theme/     colours, type, shapes, spacing: the design tokens
      core/designsystem/component/ SearchPill, MapControlButton, EmergencyButton, sheet
      core/designsystem/preview/   @SafeRoutePreviews (light, dark, Bengali, 200% font)
      core/di/                     Hilt module: Clock and coroutine dispatchers
      core/network/                HTTP client, token seam, retries, error mapping (ADR 0009)
      core/auth/                   phone sign-in behind PhoneAuthGateway; the only Firebase code
      core/session/                session state machine and the stored onboarding flags
    res/values/strings.xml         English text
    res/values-bn/strings.xml      Bengali text
    res/xml/locales_config.xml     languages offered by the system per-app language picker
    res/xml/network_security_config.xml   HTTPS only, system certificates only
  app/google-services.json         Firebase configuration (not in git; real or dummy)
  scripts/write-dummy-google-services   writes the dummy Firebase configuration
  app/build/generated/openapi/     the generated API client (not in git, never edited)
  app/src/debug/                   debug builds only: the developer server check and its strings
  app/src/release/                 release builds only: no-op stand-ins for the debug hooks
  app/src/test/                    tests that run on the JVM (JUnit, Robolectric)
  app/src/testDebug/               tests for the debug-only code
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
| OkHttp | 5.5.0 | HTTP client (chosen 2026-10-03) |
| Retrofit and its kotlinx.serialization converter | 3.0.0 | turns the generated interfaces into calls |
| kotlinx.serialization (JSON) | 1.11.0 | |
| OpenAPI Generator (Gradle plugin `org.openapi.generator`) | 7.25.0 | build time only; writes the API client |
| Firebase BoM | 34.19.0 | picks firebase-auth 24.2.0 (chosen 2026-10-06) |
| Google Services Gradle plugin | 4.5.0 | build time only; reads `google-services.json` |
| DataStore Preferences | 1.2.1 | the stored onboarding flags |
| kotlinx-coroutines-play-services | 1.11.0 | `await()` for Play services tasks |
| OkHttp MockWebServer | 5.5.0 | tests only |
| JUnit 4 · Robolectric · AndroidX Test · Turbine | 4.13.2 · 4.17 · core 1.7.0, ext-junit 1.3.0 · 1.2.1 | tests only |
| compileSdk · targetSdk · minSdk | 37 · 36 · 26 | see ADR 0008 for why compileSdk is not 36 |
| JDK | 17 to compile and test; 25 to run Gradle | Gradle downloads 17 if it is missing |

To change a version, edit `gradle/libs.versions.toml` only.

## Talking to the backend

The app never contains hand-written request or response classes. They are generated from the
contract, [`contracts/openapi.json`](../contracts/openapi.json), every time the app is built
([ADR 0009](../docs/adr/0009-android-api-client.md)).

![Network layer](../docs/diagrams/008-android-network-layer.svg)

**Generation.** The Gradle task `generateApiClient` runs before the Kotlin compiler. It writes
Retrofit interfaces (`OperationalApi`, `MeApi`) and data classes (`Health`, `Me`,
`ProblemDetails`, ...) to `app/build/generated/openapi`, package
`com.saferoute.app.core.network.generated`. When the contract changes, the next build
regenerates them; if the change breaks the app's code, the build fails. To regenerate by hand:

```sh
./gradlew generateApiClient
```

**Where the server address comes from.** From the Gradle property `saferoute.apiBaseUrl`. It
is not in the repository. Put it in your **user-level** Gradle properties file, which lives in
your home folder, outside every project (`<home>/.gradle/gradle.properties`; create the file if
it does not exist):

```properties
saferoute.apiBaseUrl=https://<your staging host>/
```

It must start with `https://` and end with `/`; anything else stops the build with a message.
Then run *File → Sync Project with Gradle Files* in Android Studio.

- Without the property a debug build uses the placeholder `https://api.invalid/`, an address
  that exists nowhere. The app then knows that no server is configured.
- A **release** build without the property fails (`checkReleaseApiBaseUrl`), so a release can
  never point at the placeholder. To build one locally:
  `./gradlew assembleRelease -Psaferoute.apiBaseUrl=https://<host>/`.

**Using the client** (from a repository class, not from a screen):

```kotlin
when (val result = apiCall { operationalApi.getHealth() }) {
    is ApiResult.Success -> result.value.version
    is ApiResult.Failure -> result.failure   // Problem, Unauthorized, NoConnection, Unexpected
}
```

**Checking the connection on a phone (debug builds only).** *Settings → Developer* opens the
server check. It calls `GET /health` and `GET /health/ready` through the real client and shows:
whether a server is configured, the health status with the backend's version, the readiness
status, and the last request id. Each failure has its own message ("Cannot reach the server",
"Server unavailable, try again", ...). It never shows the address or a token.

How it stays out of release builds: the screen, its ViewModel, `ServerCheck` and their strings
are in `app/src/debug`. `navigation/SafeRouteNavHost.kt` calls two hooks,
`developerDestinations()` and `DeveloperSettingsEntry()`; `src/debug` defines them with the
screen, `src/release` defines them as empty. `DeveloperToolsLayoutTest` checks that nothing in
`src/main` or `src/release` refers to the debug code, and CI scans the release APK for it.

**Finding a request in the backend's logs.** Every request carries an `X-Request-Id`. A debug
build writes one line per request to Logcat under the tag `SafeRouteHttp` (method, path, status,
duration, request id; never a header, a body or a query string); the server check screen shows
the last one. Search Cloud Logging for that id as described in
[`docs/runbooks/observability-staging.md`](../docs/runbooks/observability-staging.md).

**What not to do:**

- Never edit or commit anything under `app/build/generated`. Change the backend's contract.
- Never write a request or response class by hand.
- Never put the server address in a tracked file, a test, a log, a screenshot or a pull request.
- Never send the ID token to any server but the API, and never log a request or response body.
- Never add an exception to `network_security_config.xml` (no cleartext, no user certificates).

## Signing in

Users sign in with a phone number and an SMS code through Firebase Authentication
([ADR 0012](../docs/adr/0012-firebase-config-in-builds.md)). The order is fixed: **age → consent
→ phone**. Nothing is sent to any server before the user has said "I am 18 or older" and
accepted the consent notice ([ADR 0010](../docs/adr/0010-adults-only-and-consent-records.md)).

![Onboarding and session](../docs/diagrams/009b-onboarding-and-session.svg)

**The screens (since P009c).** Welcome → "How old are you?" → the consent notice → mobile
number → SMS code → Home. Home cannot be reached before all of them are done.

- **Age.** "I am under 18" asks for confirmation and then blocks the app on that phone. There
  is no undo in the app; clearing the app's storage or reinstalling asks the question again
  (ADR 0010, addendum).
- **Consent notice.** English or Bengali, switchable on that screen only. "I agree" becomes
  available once the notice has been scrolled to its end. The text is a **draft**; debug
  builds say so on the screen. It is mirrored in
  [`docs/legal/consent-notice-v1.md`](../docs/legal/consent-notice-v1.md), and a test fails if
  the two differ. Changing the text means changing `NOTICE_VERSION`.
- **Phone and code.** `+91` is fixed. A new code can be requested after 60 seconds. If Android
  kills the app on the code screen, you come back to the phone screen.
- **Settings → Account** shows the masked phone number (`+91 ••••• ••123`), "Sign out" (asks
  first, then returns to the start) and the consents on record.
- **Settings → Developer** (debug builds) also shows the session state and "Who am I": the
  role and language the server has for you. It proves the phone's ID token is accepted.

### Try it on a phone with a Firebase test number

1. Have the real `google-services.json` in place and `saferoute.apiBaseUrl` set (see above and
   "Talking to the backend").
2. In the Firebase console, add a **test phone number** with a fixed code (*Authentication →
   Sign-in method → Phone → Phone numbers for testing*). No SMS is sent and nothing is charged.
3. Run the debug app, go through the screens, type the test number without `+91`, then its
   code.
4. *Settings → Developer → Ask the server who I am* should answer with role `user`.

Type the test number and code on the phone only. Don't put them in the repository, in Notion,
in a chat or in a screenshot.

### The Firebase configuration file

The build needs `android/app/google-services.json`. It is **never in the repository**.

- **You have the real file** (downloaded from the Firebase console for the staging project):
  put it at `android/app/google-services.json`. Git ignores it. Check with
  `git check-ignore android/app/google-services.json`, which must print the path. Never commit
  it, paste it anywhere, or screenshot it.
- **You don't** (a fresh clone, CI): write a dummy one.

  ```sh
  android/scripts/write-dummy-google-services
  ```

  It creates the file with fake values (project `demo-saferoute`) and never overwrites an
  existing file. With it the app builds and every test passes, but sign-in cannot work. On
  Windows run it from Git Bash, or with `sh android/scripts/write-dummy-google-services`.

Without either, Gradle stops with an error from the Google Services plugin.

**Release builds** refuse the dummy file and a missing file (`checkReleaseFirebaseConfig`). CI
passes `-Psaferoute.allowDummyFirebase=true` for the one release build it makes to check that
a release still assembles; never use that flag for a build that goes to a phone.

### Firebase console settings sign-in depends on

- The Android app `com.saferoute.app` is registered in the project.
- The **SHA-1 and SHA-256 fingerprints** of the key that signs the build are added to that app.
  Firebase uses them to check that a sign-in request comes from the real app. For debug builds
  that is your debug keystore (*Gradle → app → Tasks → android → signingReport* in Android
  Studio). A release key's fingerprints come with P022.
- *Authentication → Sign-in method → Phone* is enabled, and the SMS region policy allows India.
- **Test phone numbers** (*Phone → Phone numbers for testing*): a number and a fixed 6-digit
  code that work without an SMS being sent, cost nothing and don't count against quotas. Use
  one for development. The numbers and codes live in the console only: never in the
  repository, in Notion, in a chat or in a screenshot.

### How the code is organised

| Piece | Where | What it does |
| --- | --- | --- |
| `PhoneAuthGateway` | `core/auth` | Sign-in as the app sees it: start verification, verify code, resend, sign out, ID token. The only door to Firebase |
| `FirebasePhoneAuthGateway` | `core/auth` | The implementation on the Firebase SDK. Thin; checked on a phone |
| `FirebaseIdTokenProvider` | `core/auth` | Gives the network layer the ID token (the seam from "Talking to the backend") |
| `normaliseIndianMobile` | `core/auth` | Turns typed input into `+91` and ten digits, or rejects it |
| `Session` / `SessionRepository` | `core/session` | Decides the `SessionState` from the stored flags, Firebase and the API. Screens use the `Session` interface |
| `OnboardingNavHost` | `navigation` | Shows the onboarding screen that belongs to the session state |
| Welcome, age, notice, phone, code, blocked screens | `feature/onboarding` | Plain UI plus `OnboardingViewModel` and `SignInViewModel` |
| `SessionStore` | `core/session` | Six flags in Jetpack DataStore; never the phone number, a token or a code |

The session states: `Loading`, `NeedsAge`, `NeedsConsent`, `SignedOut`, `NeedsBootstrap`,
`Ready`, `Blocked(UNDER_18 | ACCOUNT_DELETED)`, `Error(retryable)`.

**Opening without a connection.** Once the app has been `Ready` with a sign-in, it shows Home
at once on the next start and checks with the server in the background. Only a definite answer
(the sign-in is no longer valid, the account was deleted, the consent is out of date) takes the
user out of Home. A first run needs a connection.

**In tests** nothing talks to Firebase or the server. A test that starts the real activity
replaces the session with `FakeSession` (ready, so the app's own screens show):

```kotlin
@HiltAndroidTest
@UninstallModules(SessionBindingModule::class)
class MyTest {
    @BindValue @JvmField val session: Session = FakeSession(SessionState.Ready)
}
```

A test that goes through sign-in also replaces `AuthModule` with `FakePhoneAuthGateway`
(see `OnboardingNavigationTest`).

**What not to do:**

- Never log, store or put in a test a real phone number, an ID token, an SMS code or a
  Firebase user id. Tests use the `FAKE_...` values next to `FakePhoneAuthGateway`.
- Never call the Firebase SDK outside `core/auth`.
- Never ask for the phone number before the age declaration and the consent notice.
- Never add Analytics, Crashlytics or another Firebase product without a prompt that asks for
  it.

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
