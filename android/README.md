# android/

Native Android app for SafeRoute Kolkata: Kotlin, Jetpack Compose, Material 3, Hilt,
MapLibre, Room, WorkManager and a location-type foreground service for device-first SOS
(Plan v7 §5, §7).

**Status:** empty. Rahul creates the Gradle project with Android Studio's *Empty Compose
Activity* wizard at the start of **P007** (`feat/007-android-app-shell`). After that,
P008–P016 add to this folder.

## Package name (decide once)

The Android application ID / package name **must be chosen once and never changed after the
first Play Store upload** (Plan v7 §15.1). `in.saferoute.app` is only a suggestion. Rahul
confirms the final value in P007, and it gets recorded in an ADR.

## Quality gate (from P007 onwards)

```sh
./gradlew lint testDebugUnitTest assembleDebug
```

## License

Code in this folder is licensed under `AGPL-3.0-only` (see [`../COPYRIGHT.md`](../COPYRIGHT.md)).
From P003 onward, every new Kotlin/Gradle source file starts with
`// SPDX-License-Identifier: AGPL-3.0-only`, and every new module uses the same license id. The
app will need a third-party licenses / notices screen before release (follow-up for P022).
