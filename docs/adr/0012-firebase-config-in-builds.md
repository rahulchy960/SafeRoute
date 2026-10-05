# ADR 0012: Firebase configuration in builds, and how sign-in is wrapped and tested

- **Status:** Accepted
- **Date:** 2026-10-06
- **Prompt:** P009b
- **Plan refs:** Plan v7 §3.2 (F-01), §5, §12.4, §13.2, §17; [addendum v7.1](../plan/addendum-v7.1.md) B.2, B.3

## Context

- Users sign in with a phone number and an SMS code through Firebase Authentication
  ([ADR 0006](0006-authentication-and-roles.md)). The Android SDK reads its configuration from
  `app/google-services.json`, which the Google Services Gradle plugin turns into string
  resources at build time. **The plugin fails the build when the file is missing.**
- The repository is public. `google-services.json` holds the Firebase project id, project
  number, app id and an API key. None of them is a password (they ship inside every APK), but
  they identify the project, and `CLAUDE.md` forbids committing them.
- CI must build and test the app without any secret, including for pull requests from forks.
- A release built with a fake configuration would install fine and then fail for every user at
  sign-in.
- Claude Code never reads the real file and has no phone or emulator. Code that calls the
  Firebase SDK cannot be exercised on the JVM: under Robolectric there is no initialised
  `FirebaseApp` (found in the P009b spike: `FirebaseAuth.getInstance()` throws).

Spike results (2026-10-06, from Google Maven and Maven Central metadata; stable releases only):

| What | Version |
| --- | --- |
| Firebase BoM | 34.19.0 (pins firebase-auth 24.2.0) |
| Google Services Gradle plugin | 4.5.0 (works with AGP 9.4.1 and the configuration cache) |
| DataStore Preferences | 1.2.1 |
| kotlinx-coroutines-play-services | 1.11.0 (same as kotlinx.coroutines) |

firebase-auth publishes no sources. Its phone-auth API (`PhoneAuthOptions`,
`PhoneAuthProvider.verifyPhoneNumber`, the callbacks, `signInWithCredential`, `getIdToken`) and
its `ERROR_...` codes were read from the compiled library with `javap`.

## Decision

1. **The real `google-services.json` stays on the developer's machine**, at
   `android/app/google-services.json`, ignored by git. It is never committed, printed, pasted
   into Notion or a pull request, or opened by Claude Code.
2. **CI writes a dummy file before Gradle runs**
   ([`android/scripts/write-dummy-google-services`](../../android/scripts/write-dummy-google-services)).
   Every value is obviously fake: project id `demo-saferoute`, zeros for the numbers, and an API
   key that does not have the shape of a real one, so secret scanners stay quiet. The script
   never overwrites an existing file.
3. **A release build fails** (`checkReleaseFirebaseConfig`, run before every release variant)
   when the file is missing, has no project id, or has a project id starting with `demo-`.
   The only exception is the Gradle property `-Psaferoute.allowDummyFirebase=true`, used by the
   CI step that checks a release still assembles. CI also proves both refusals on every run.
   Error messages never contain a value from the file.
4. **Firebase Auth only.** No Analytics, Crashlytics, App Check, Google or e-mail sign-in.
   Firebase's app verification for phone sign-in (Play Integrity, with a reCAPTCHA fallback) is
   part of firebase-auth and needs no code here beyond passing the activity.
5. **Only `core/auth` uses the Firebase SDK.** The rest of the app sees the interface
   `PhoneAuthGateway` (start verification, verify code, resend, sign out, current user, auth
   state, ID token). `FirebasePhoneAuthGateway` is a thin wrapper with no decisions of its own.
   `FirebaseIdTokenProvider` implements the network layer's `IdTokenProvider`
   ([ADR 0009](0009-android-api-client.md)) on top of the gateway and caches nothing itself.
6. **How it is tested.** Unit tests use `FakePhoneAuthGateway` and never start Firebase. The
   mapping from Firebase's exceptions to `PhoneAuthError` is tested with hand-built exceptions.
   The Hilt graph gets `FirebaseAuth` lazily, so building it does not touch Firebase; a test
   that needs sign-in replaces `AuthModule`. The wrapper itself is checked on a real phone with
   a Firebase test phone number.
7. **What sign-in exposes.** `AuthUser` carries no user id and no phone number.
   `PhoneAuthError` is an enum; the SDK's message is dropped, because it can contain the phone
   number or project details. Nothing in `core/auth` or `core/session` logs.
8. **What the phone stores.** Jetpack DataStore (Preferences) holds six flags: welcome seen,
   age confirmed, under 18, accepted notice version and locale, "ready once". Never the phone
   number, a token, an SMS code or a verification id. `allowBackup` stays `false`.
9. **Permissions merged in by the libraries.** `ACCESS_NETWORK_STATE` (firebase-auth,
   reCAPTCHA) and `com.google.android.providers.gsf.permission.READ_GSERVICES` (reCAPTCHA).
   Both are install-time permissions without a dialog. A test pins the full list, so a library
   update that adds another one fails the build.
10. **Real configuration for release builds in CI** (from a secret, at build time) is decided
    in P022.

## Alternatives considered

- **Commit `google-services.json`.** Common in open-source apps, since the values are not
  secret. Rejected: it would publish the project id and number against the repository rules,
  and invite others to build against the staging project.
- **Apply the plugin only when the file exists.** The build would work without the file, but a
  build that silently has no Firebase is worse than one that says so. Rejected.
- **Configure Firebase in code from Gradle properties** (`FirebaseOptions`). Avoids the file,
  but moves five values into every developer's Gradle properties and drops the standard setup
  every Firebase guide assumes. Rejected.
- **Provide the real file to CI from a secret now.** Needed for a real release, but pull
  requests from forks get no secrets, and nothing in CI signs in. Deferred to P022.
- **Run the Firebase Auth emulator in tests.** It needs Java tooling and a running process,
  and its tokens are unsigned, which the backend rejects by design (ADR 0006). Rejected.
- **Mock `FirebaseAuth` with a mocking library.** Tests would then describe the mock, not the
  SDK, and a new dependency would be needed. Rejected in favour of the interface and a fake.

## Consequences

- A fresh clone needs one command before the first build
  (`android/scripts/write-dummy-google-services`), or the real file. `android/README.md` →
  "Signing in" says so.
- With the dummy file the app builds and its tests pass, but sign-in cannot work.
- The Firebase wrapper has no automated test. Regressions there show up only on a phone.
- **Proprietary dependencies:** firebase-auth, Play services (basement, tasks,
  auth-api-phone) and reCAPTCHA are under Google's Android Software Development Kit License,
  and Play Integrity under its own terms. They are not open source. The existing follow-up
  "Android third-party SDK license review before P022" covers what that means next to
  AGPL-3.0. They are listed in `android/THIRD_PARTY.md`.
- The app now depends on Google Play services being present on the phone for sign-in.
- Revisit when: P022 sets up release signing and the real configuration in CI; App Check is
  turned on; a second sign-in method is proposed.

## References

- Firebase: "Authenticate with Firebase on Android using a phone number"
  (<https://firebase.google.com/docs/auth/android/phone-auth>); "Add Firebase to your Android
  project" (<https://firebase.google.com/docs/android/setup>).
- [ADR 0006](0006-authentication-and-roles.md), [ADR 0008](0008-android-foundation.md),
  [ADR 0009](0009-android-api-client.md), [ADR 0010](0010-adults-only-and-consent-records.md).
- `android/app/build.gradle.kts` (`checkReleaseFirebaseConfig`),
  `.github/workflows/android-ci.yml`, `android/app/src/main/java/com/saferoute/app/core/auth/`.
- Diagram: [`docs/diagrams/009b-onboarding-and-session.svg`](../diagrams/009b-onboarding-and-session.svg).
- Prompt log: [`docs/prompt-logs/009b-signin-onboarding.md`](../prompt-logs/009b-signin-onboarding.md).
