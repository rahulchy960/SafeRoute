# ADR 0008: Android foundation (package name, SDK levels, Compose, Hilt, design rules)

- **Status:** Accepted
- **Date:** 2026-10-02
- **Prompt:** P007 (split: P007a Gradle, Hilt, theme and components; P007b navigation, screens and their tests)
- **Plan refs:** Plan v7 §1, §5.1–§5.4, §12.3, §15.1, §17

> **Note of 2026-10-08 (P012e2): the emergency control is compact and never an overlay.**
> On a phone the large "Emergency 112" pill, drawn on top of everything above the sheet,
> covered the first route card. It is replaced by `SosControl`: a 48 dp red circle that shows
> "SOS" and is read by TalkBack as "SOS and call 112".
>
> - **It always has a place of its own in a layout.** While the sheet only peeks it is the last
>   of the map controls. When the sheet is at, or on its way to, half or full height, it is at
>   the end of the sheet's header row, next to the close button. On the search screen it is an
>   action at the end of the top bar. It is on screen exactly once and never disappears.
> - **It can not cover content**, because it is not drawn over any: `SosPlacementTest` fails if
>   its bounds intersect a route card, a result row, a row or button of the sheet, a map
>   control or the search pill, at every detent, at double font size, in Bengali, in the dark
>   theme and in landscape.
> - **It stays 48 dp at every font size.** The letters do not grow with the font setting; the
>   spoken description is the accessible name. The meaning still does not rest on colour.
> - **Behaviour is unchanged:** a tap opens the same dialog, "Call 112" opens the dialer with
>   `ACTION_DIAL` and `tel:112`, and the dialog says that SafeRoute is not an emergency service.
> - **Room for P014:** `SosControl` only reports a tap. P014 replaces what the tap does (the
>   real SOS flow, with a hold to arm if that is the design) without moving the control. A
>   Quick Settings tile is a separate entry point and needs no place on this screen.
> - This replaces "the emergency button always visible above the sheet" in the Decision below;
>   the `sos` red is still used only by this control and the emergency dialog.
>
> **Note of 2026-10-08 (P012f1): emergency shortcuts outside the app, and their one
> destination.**
>
> - **One destination.** Every shortcut outside the app opens `EmergencyActivity`, named in
>   one place (`EmergencyShortcut`). Today it shows the same dialog as the in-app SOS control
>   ("Call 112" through `ACTION_DIAL`, "SafeRoute is not an emergency service"). P014 changes
>   what that destination does (the countdown); the shortcuts do not change.
> - **A second activity, on purpose.** The app stays single-activity for everything a signed-in
>   user sees. `EmergencyActivity` exists because it may be shown **over the lock screen**
>   (`showWhenLocked`), which Android allows for a tile whose action is safe while locked. It
>   shows fixed text and a button for the dialer: no account, no map, no position. It unlocks
>   nothing, turns no screen on, is not exported, and needs no sign-in. `MainActivity` never
>   shows over the lock screen; a test checks both.
> - **The Quick Settings tile is the dependable shortcut** (addendum v7.1, B.1). It is a
>   `TileService` in "active" mode: bound by the system for a tap, not a running service. It
>   needs no permission from the user. On Android 14 and newer it starts the activity through
>   an immutable `PendingIntent` (the `Intent` form throws there); before that through an
>   `Intent`.
> - **Adding the tile.** Settings has "Add the SOS tile". On Android 13 and newer it uses the
>   system's own request (`StatusBarManager.requestAddTileService`), made only from that tap;
>   on older versions, and when the request is refused or fails, it shows four steps for
>   adding the tile by hand.
> - **Honest limits, also said in the app.** A tile exists only if the user adds it, and some
>   phones have no app tiles. What the dialer does on a locked phone is the phone's rule. The
>   in-app SOS control always works.
> - **The pinned notification is not built yet** (P012f2). Its limits are known from the
>   documentation and recorded here so that nothing is promised: since Android 14 a user can
>   swipe away an "ongoing" notification (except on the lock screen); the system or a phone
>   maker's battery manager can remove it; it needs the notification permission on Android 13
>   and newer; after a force-stop nothing of the app runs until the user opens it. It will be
>   posted without a foreground service.
> - **Built in P012f2 (2026-10-08): the pinned notification, as an option.**
>   - **Off until the user turns it on** in Settings ("Emergency shortcut in notifications"),
>     or follows a one-time offer shown after the first use of the in-app SOS control. Never
>     at launch, never in onboarding.
>   - **What it is:** one silent notification (low importance, no sound, vibration or badge)
>     with fixed text, shown in full on the lock screen. "Call 112" opens the dialer with
>     `ACTION_DIAL` and `tel:112`; "Open SOS" and a tap open `EmergencyActivity` through
>     `EmergencyShortcut`, the same destination as the tile. Nothing about the user is in it.
>   - **No foreground service.** Nothing of the app runs to keep it there.
>   - **One rule: shown if, and only if, the switch is on and Android allows it**
>     (`EmergencyNotificationController.sync`). The rule is applied when the app opens, after
>     `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` (one unexported receiver that starts nothing),
>     and whenever the switch changes.
>   - **Permission, in context.** `POST_NOTIFICATIONS` is requested only after the switch was
>     turned on and the app's own explanation was continued; one system dialog per tap. A
>     refusal, a refusal for good, notifications off for the app and the category switched
>     off each have their own words; system settings are offered where they can help.
>   - **The limits are said in the app** (Settings and the explanation, English and Bengali):
>     it can be swiped away; the phone or a battery manager can remove it; the app puts it
>     back when it is opened, after a restart and after an update; the tile is the dependable
>     shortcut. A user who swipes it away keeps the switch on.
>   - **Stored:** the switch and an "offer shown" flag, in the app's one DataStore file.
>     Signing out clears that file, so it also turns the shortcut off and removes the
>     notification.
>   - **Permissions added:** `POST_NOTIFICATIONS` and `RECEIVE_BOOT_COMPLETED`. No
>     foreground-service, full-screen-intent, overlay or call permission.
>   - **Not verified on a device:** what phone makers' battery managers do to it, whether the
>     dialer opens from the lock screen without the unlock, and that the two broadcasts arrive
>     on every phone.
>   - **Checked on one phone on 2026-10-08 (Samsung, Android 17), reported by Rahul:** the tile
>     was added and opens the emergency dialog with the screen on and from the lock screen; the
>     notification's switch and permission flow work; "Call 112" opens the dialer showing 112;
>     a swiped-away notification is back when the app is reopened; it is back after a reboot;
>     after a force-stop it is gone until the app is started. Still not recorded: battery
>     managers over time, the dialer's behaviour from the lock screen, an app update, other
>     phone makers and Android versions (the revisions of the two P012f prompt logs list them).
> - **Colour.** A tile's icon is tinted by the system, so the tile is not red. The `sos` red
>   is still used only by the SOS control and the emergency dialog.

## Context

- P007 turns the project Rahul created with Android Studio's wizard into the base every later
  Android prompt builds on. Several choices made here are expensive to change later, and one
  (the application ID) can't be changed at all once the app is on Google Play (Plan v7 §15.1).
- The plan still names the package path `in/saferoute` and the product "SafeRoute Kolkata".
  Both are superseded: the product is "SafeRoute" and identifiers carry no city name
  ([ADR 0005](0005-product-name-and-multi-city-readiness.md)).
- What the wizard produced differed from what the P007 prompt assumed. It was a project
  **without an activity and without Compose** (AppCompat and Material Views dependencies), on
  AGP 9.4.1 and Gradle 9.6.0, with **compileSdk 37 and targetSdk 37**. Only platform 37 was
  installed in the local SDK. The package `com.saferoute.app` was as expected.
- Plan v7 §5.1 and the prompt ask for compileSdk 36 and targetSdk 36. On 2026-10-02 the
  current stable AndroidX libraries declare `minCompileSdk=37` in their metadata (checked for
  Core 1.19.1, Compose UI 1.12.1, Navigation Compose 2.10.2, Lifecycle 2.11.0 and
  Hilt-ViewModel-Compose 1.4.0). A project on compileSdk 36 can't use them.
- Material 3 1.4.0 is the latest stable Material 3. In it `BottomSheetScaffold` is marked
  `@ExperimentalMaterial3Api` and its sheet has two visible positions. The Material 3
  Expressive APIs are not public in 1.4.0 (the opt-in annotation is `internal`); they exist
  only in 1.5.0 alphas.
- Rahul is new to Android. Rules that can be checked by a test are better than rules that rely
  on memory.

## Decision

1. **Application ID and namespace are `com.saferoute.app`.** The application ID is permanent
   after the first Play upload. The namespace (the package of generated code such as `R`) could
   change but won't, so that the two never differ.
2. **One activity, Jetpack Compose, Navigation Compose with type-safe routes.** `MainActivity`
   is the only activity; screens are Compose destinations whose routes are
   `@Serializable` Kotlin objects, not strings. (The navigation host arrives with P007b.)
3. **Hilt for dependency injection, with KSP.** No kapt. Hilt is the only global container; no
   hand-written singletons.
4. **One Gradle module (`:app`) with feature packages** (`core/designsystem`, `core/di`,
   `feature/<name>`, `navigation`) until build time or ownership problems are measured
   (Plan v7 §5.2). A package is created by the prompt that needs it, not in advance.
5. **SDK levels: minSdk 26, targetSdk 36, compileSdk 37.** `targetSdk` decides how Android
   treats the app at runtime; it stays at 36 as planned and is raised deliberately, with
   testing. `compileSdk` only decides which APIs the compiler can see; it is 37 because the
   current AndroidX libraries require it. This is a deviation from the plan's compileSdk 36.
6. **Toolchain.** Kotlin and Java compile with JDK 17 (`jvmToolchain(17)`). Gradle itself runs
   on the JDK named in `gradle/gradle-daemon-jvm.properties` (25, as generated by Android
   Studio). Kotlin support is AGP 9's built-in one; the Kotlin Gradle plugin is raised to the
   catalog version so that it matches the Compose compiler plugin. Every version lives in
   `gradle/libs.versions.toml`.
7. **Stable APIs only.** No alpha, beta or RC dependency and no `@OptIn` of an experimental
   API in production code. Consequence now: the bottom sheet is our own component on Compose
   Foundation's stable `anchoredDraggable`, with three detents (peek, half, full), instead of
   a wrapper around `BottomSheetScaffold`. Material 3 Expressive is not used.
8. **No backup.** `android:allowBackup="false"`, and data-extraction rules that exclude every
   storage domain from both cloud backup and device-to-device transfer. The app will hold SOS
   records, live-share sessions and a sign-in; restoring them blindly on another phone or after
   a reinstall could resurrect a finished SOS or a stale session. A deliberate backup design,
   if wanted, belongs to P020.
9. **No permission until a feature needs it.** The manifest declares none. Each permission is
   added by the prompt whose feature uses it.
10. **Dynamic colour (Material You) is off by default.** `SafeRouteTheme(dynamicColor = false)`.
    Wallpaper-derived colours would make the accent, and the contrast we tested, different on
    every phone. The semantic colours never follow dynamic colour, even if it is switched on.
11. **Semantic colour rule.** `SafeRouteColors` (a CompositionLocal) holds `sos`, `caution`,
    `positive`, `mapOverlay` and `scrim` with their on-colours for both themes. **The `sos` red
    is used only for the emergency button and the emergency dialog**, never as decoration, and
    colour is never the only signal (icon or text accompanies it). `SosColourUsageTest` reads
    the sources and fails on any other use.
12. **Contrast is tested.** Body text pairs reach at least 4.5:1 and UI component pairs at
    least 3:1 in both themes; `ThemeContrastTest` computes the WCAG ratios from the theme
    values. Touch targets are at least 48 dp.
13. **Maps-style layout principles.** The map is full-bleed and is the content. Controls float
    over it on neutral surfaces: a search pill at the top, round map buttons at the end edge, a
    persistent bottom sheet, and the emergency button always visible above the sheet. Surfaces
    are quiet greys with one teal accent so that the map and later safety overlays stand out.
    The app draws edge to edge and keeps controls clear of system bars with insets. Safety
    information is presented as context, never as a guarantee (Plan v7 §1).
14. **Text.** Every user-visible string is a resource, in English (`values/`) and Bengali
    (`values-bn/`), with the same keys (`StringResourceParityTest`). Bengali text written by
    Claude Code is a **draft and must be reviewed by a fluent speaker before release**. The
    emergency number is written `112` in Latin digits in every language. Languages are declared
    in `locales_config.xml` (system per-app language on Android 13+). Fonts are the system's.

## Alternatives considered

- **`in.saferoute.app`** (the plan's suggestion). Not chosen: the project was created as
  `com.saferoute.app` and the P007 prompt fixes that value. Both are city-neutral.
- **compileSdk 36 with older libraries.** Keeps the plan's number, but pins Compose, Navigation
  and Lifecycle to releases that are already superseded, and needs platform 36 installed.
  Rejected: compileSdk has no effect on runtime behaviour.
- **targetSdk 37** (the wizard's default). Opts the app into Android 17 behaviour changes
  before any feature exists to test them with. Deferred; lint reports `OldTargetApi` as a
  warning, which is the intended reminder.
- **`BottomSheetScaffold` with `@OptIn`.** Less code, but experimental, and two positions
  instead of three. Rejected by the stable-only rule.
- **Dynamic colour on.** More "native" look; rejected for the reasons in decision 10.
- **kapt for Hilt.** Slower and in maintenance mode; KSP is supported by Hilt.
- **Several Gradle modules from the start.** Rejected by Plan v7 §5.2 until measured.
- **`jvmToolchain(21)`**, which would let Robolectric emulate API 36. Not chosen now: the plan
  and prompt say 17. Robolectric therefore runs the tests against API 35, the newest it
  supports on Java 17.

## Consequences

- AndroidX libraries can be kept current. When targetSdk is raised, it is a one-line change in
  the catalog plus a pass through the platform's behaviour changes.
- JVM tests run on an Android 15 framework image, one version below the target. Raising the
  JDK to 21 and Robolectric's SDK to 36 is a follow-up.
- We own the bottom sheet: one file, including its accessibility actions. Its behaviour
  with a real, pannable map underneath is unproven until P010. If Material ships a stable
  three-detent sheet, revisit.
- Whether `com.saferoute.app` is free on Google Play is unknown until the first upload (P022).
  If it is taken, the ID must change **before** publishing, with a new ADR.
- No backup means a reinstall or a new phone starts empty. Emergency contacts and settings must
  therefore be recoverable from the server (P013, P020).
- The data-extraction rules use an `<exclude>` per storage domain. Android's documentation
  describes the elements but not a single "exclude everything" switch, so the effect on a real
  device-to-device transfer is unverified (follow-up for P020).
- Bengali quality is unknown until reviewed; the UI must not ship to users before that.
- Three JDKs are involved on a developer machine: one to run Gradle (25, Android Studio's own
  works), one to compile (17, downloaded by Gradle on first use) and whatever `java` is on the
  PATH (irrelevant once Gradle has started).

## References

- Plan v7 §1, §5.1–§5.4, §12.3, §15.1, §17.
- [ADR 0001](0001-kotlin-native-openapi.md) (native Kotlin client),
  [ADR 0005](0005-product-name-and-multi-city-readiness.md) (naming).
- Android: "Configure your build" and `uses-sdk` (compileSdk vs targetSdk); "Back up user data
  with Auto Backup" (<https://developer.android.com/identity/data/autobackup>); "Per-app
  language preferences".
- WCAG 2.2, success criteria 1.4.3 (contrast, minimum) and 1.4.11 (non-text contrast).
- [`android/README.md`](../../android/README.md) (versions table),
  [`android/THIRD_PARTY.md`](../../android/THIRD_PARTY.md).
- Prompt log: [`docs/prompt-logs/007a-android-foundation.md`](../prompt-logs/007a-android-foundation.md).
