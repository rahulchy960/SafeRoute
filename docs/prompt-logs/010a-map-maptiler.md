# P010a: MapLibre map with MapTiler style, key handling and offline states

| Field | Value |
| --- | --- |
| Prompt | P010 · the first of two parts (**P010a map**, P010b location) |
| Milestone | M3 (depends on P007, P008, P009; P009d merged as `2f1772b`) |
| Branch | `feat/010a-map-maptiler` |
| PR title | `feat(android): MapLibre map with MapTiler style, key handling and offline states [P010a]` |
| Notion | [P010a row in the Prompt Log](https://app.notion.com/p/3f1073707720818da3ccd8240838cc97) (umbrella row: [P010](https://app.notion.com/p/3eb07370772081f7b83ce3df30c25c83)) |
| Date | 2026-10-06 |
| Plan refs | Plan v7 §1, §3.2 F-02, §5.1, §5.3, §12.3, §12.4, §14.1, §16 M3; addendum v7.1; ADRs 0005, 0008, 0009, 0013, 0015 |

> **Verified on the JVM only.** Claude Code has no phone or emulator, and a map is native code
> that cannot run in unit tests. Everything in `core/map/MapLibreEngine.kt` (the part that
> talks to MapLibre) compiles and links but **has not been seen drawing a map**. The map
> counts as working only after Rahul's checks in section 11.

## 1. Objective

Replace the Home screen's map placeholder with a real MapLibre map in a modern style (light
and dark) using MapTiler tiles, with safe key handling and a calm message for every way the map
can fail. Display only: no coordinates leave the device, and a map failure must never affect
the emergency button.

An amendment to the prompt added a portability rule: all MapLibre types stay in one package
(`core/map`), screens and tests see only the app's own types, a test enforces it, and
ADR 0015 says what switching the map provider would take.

Location (permission, disclosure, the blue dot) is part b and is not in this pull request.

## 2. Context & prerequisites

- P009d merged (PR #21, `2f1772b`). No open pull requests. Hooks active. Clean tree.
- Home had `MapPlaceholder`, a search pill, two disabled map controls, a three-detent sheet and
  the emergency button (P007).
- The map key is Rahul's, in his user-level `gradle.properties` as `saferoute.mapTilerKey`.
  Claude Code never read that file and does not know whether the property is set.
- The Plan PDF cannot be read on this machine; the plan text quoted in the prompt was used.

## 3. Workflow executed

1. `/start-prompt`: `git switch main`, `git pull --ff-only`, PR #21 confirmed merged, Notion
   rows for P009d and P009 already "Merged", branch `feat/010a-map-maptiler`, Notion page.
2. **Spike (A0)**, before any code. Sources: Maven Central metadata, POMs and AARs downloaded
   to a scratch folder and read with `unzip` and `javap`; ELF headers read with a small script;
   MapTiler's terms and documentation; developer.android.com. Results in section 7.
3. Build: version catalog, `saferoute.mapTilerKey` → `BuildConfig`, `checkReleaseMapKey`,
   manifest removals (commit `7d244a0`).
4. `core/map`: types, provider config, state holder, engine, MapLibre binding, tests
   (`daa79d0`).
5. Home: map slot, status card, credit chip and dialog, padding, saved camera, strings, tests
   (`64185b3`).
6. CI: dummy key in the release check, guard proof, 16 KB check (`d7d800b`).
7. Carry-over A9: Revision sections in the P009c and P009d logs (`eb7a8db`); Notion follow-up
   closed.
8. Docs: ADR 0015, `android/README.md`, `android/THIRD_PARTY.md`, `CLAUDE.md` (`3329697`).
9. `/ship-prompt`: quality gate, this log, push, pull request, Notion.

Three bugs were found by the new tests while writing them and fixed before the first commit:
the memory-pressure rule treated "UI hidden" as pressure (the levels are not a simple scale);
the top padding was measured relative to the wrong parent (8 dp short); a test left the sheet
fully open, which covers the search pill as it always has.

## 4. Changes

| Area | What |
| --- | --- |
| `gradle/libs.versions.toml` | `org.maplibre.gl:android-sdk-opengl` 13.6.1. No other new dependency |
| `app/build.gradle.kts` | Gradle property `saferoute.mapTilerKey` → `BuildConfig.MAPTILER_KEY` and `MAPTILER_KEY_CONFIGURED`; loose format check at configuration time (8–64 URL-safe characters; the value is never printed); `checkReleaseMapKey` on `preReleaseBuild`; a key starting with `dummy-` counts as no key; lint rule `LogNotTimber` off |
| `AndroidManifest.xml` | `tools:node="remove"` for the three permissions MapLibre's manifest would merge in: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_WIFI_STATE`. **The permission list is unchanged** |
| `core/map/MapTypes.kt` | `LatLng`, `CameraState`, `MapPadding`, `MapStyleVariant`, `MapLoadState`, overlay descriptions (`Marker`, `AccuracyCircle`, `Polyline`, `Polygons`), `MapController`. Plain Kotlin; positions do not print their coordinates |
| `core/map/MapProviderConfig.kt` | `MapKey`, `MapStyleUrl` (never printed), redaction, style ids, credit links, 50 MB cache size, `RegionDefaults` |
| `core/map/MapStateHolder.kt` | the state machine; keeps camera, padding and style across map views; a watchdog so that Loading always ends |
| `core/map/MapEngine.kt` | `MapEngine`, `NetworkStatus` (+ `ConnectivityNetworkStatus`), `MapLifecycleForwarder` |
| `core/map/MapLibreEngine.kt` | **the only file that imports MapLibre**: `MapView` in `AndroidView`, lifecycle forwarding, options (compass only when rotated, no tilt, own logo and "i" off), log filter, cache size |
| `core/map/di/MapModule.kt` | Hilt bindings; replaced in every Hilt test by `FakeMapModule` |
| `feature/home/HomeScreen.kt` | map slot under a neutral backdrop; status card; credit chip under the search pill; padding from the pill and the sheet detent; `MapPlaceholder.kt` deleted |
| `feature/home/MapStatus.kt` | `MapStatusCard`, `MapAttributionChip`, `MapAttributionDialog` |
| `feature/home/HomeViewModel.kt` | owns the `MapController`; camera in `SavedStateHandle` |
| Strings | 18 new in English and Bengali (`map_*`); `map_placeholder_label` removed |
| `.github/workflows/android-ci.yml` | release check with a dummy key and the allow flag; proof that the guard refuses a missing and a dummy key; 16 KB check (`zipalign -c -P 16` and `readelf`) |
| Tests | `MapProviderConfigTest` (9), `MapStateHolderTest` (14), `MapLifecycleForwarderTest` (7), `MapLibreBoundaryTest` (5), `MapFailureEmergencyTest` (5), `HomeScreenTest` +12 −2, `HomeViewModelTest` +4, `FakeMap.kt` |
| Docs | ADR 0015, `android/README.md` "Map and MapTiler", `android/THIRD_PARTY.md`, `CLAUDE.md`, Revision sections in the P009c and P009d logs, this log |

No backend or contract change. No new permission. No diagram.

**Size:** about 3,300 changed lines: code and build 1,440, tests 1,130, documents and CI 730.
Far over the ~800-line guide. It was not split again: the map component, its key handling and
its place on Home only work together (a pull request with `core/map` alone would add a
dependency and a module nothing uses), and each further part would have had to wait for a
merge. The commits are separated by concern for review.

## 5. Diagram

No diagram needed in part a: no new user flow, state machine across screens, schema or
infrastructure. The location permission flow diagram comes with P010b.

## 6. Quality gate & test results

| Command | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` (in `android/`) | BUILD SUCCESSFUL |
| → unit tests | **363 tests, 0 failures, 0 skipped** (308 before; 55 more) |
| → lint | 0 errors, 2 warnings (both `OldTargetApi`, known, ADR 0008) |
| `./gradlew assembleRelease -Psaferoute.apiBaseUrl=https://example.invalid/ -Psaferoute.mapTilerKey=dummy-map-key-for-ci -Psaferoute.allowDummyMapKey=true` | BUILD SUCCESSFUL |
| `:app:checkReleaseMapKey` with the placeholder · with a `dummy-` key | fails, "Release builds need a real map key…" (both) |
| `:app:checkReleaseMapKey` with a `dummy-` key and the allow flag · with a well-formed key | passes (both) |
| a malformed key (spaces, `!`) | configuration fails; the message does not contain the value |
| `zipalign -c -P 16 4` (build-tools 36.0.0) on the debug and release APKs | verification successful (both) |
| `LOAD` alignment of every 64-bit native library in both APKs | 16 KB (`libmaplibre.so`, `libandroidx.graphics.path.so`, `libdatastore_shared_counter.so`, for `arm64-v8a` and `x86_64`) |
| actionlint 1.7.12 (with shellcheck) on `android-ci.yml` | clean |
| `git ls-files android \| grep -iE "local.properties\|\.jks\|google-services\.json"` | prints nothing |
| markdownlint-cli2 · JSON validity · gitleaks 8.30.1 | see the "Ship checks" line below |

What the tests prove:

| Test | What it proves |
| --- | --- |
| key rule | well-formed keys pass; empty, short, long and unsafe ones fail without being echoed |
| nothing prints the key | `toString()` of the key, the config and the style address hide it |
| redaction | the key and any `key=` parameter are removed from library messages |
| state machine | Loading → Ready; failure online → Error; retry; 401/403/429 → RateLimited before and after loading; one failed tile does not break a working map; offline with nothing cached → Offline after 3 s; offline with a cached style stays Ready; the connection returning recovers by itself; Loading ends after 20 s; no key → NotConfigured and nothing is requested |
| theme | dark asks for `streets-v4-dark`, light for `streets-v4`; switching reloads |
| camera | restored from saved state; kept across a new map view; saved when the map rests; a damaged saved value is ignored |
| lifecycle | every step forwarded once and in order; leaving while visible walks pause → stop → destroy; nothing after destroy; late observers catch up; only real memory pressure is passed on |
| boundary | exactly one file refers to the map library; `core/map` never touches `core/network`, OkHttp or `setOkHttpClient`; the API client is used only inside `core/network`; a MapTiler address is not an API host, so the token interceptor skips it |
| Home, every map state | search, settings, the sheet and the emergency button work; the dialog can call |
| `MapFailureEmergencyTest` | in the real `MainActivity`: emergency → dialog → `ACTION_DIAL` `tel:112` in **every** map state; rotation keeps the map state |
| Home layout | 200% font with a map problem; padding follows the pill and the sheet (peek, half, full); the credit stays clear of the sheet; no technical details in any message; Bengali |
| manifest | the permission list is the same as before (`MainActivityTest`, unchanged) |
| strings | English and Bengali have the same keys and placeholders |

**SOS failure matrix (Plan v7 §7.5):** not applicable; no SOS, live-location or contacts logic.
The related check is the independence of the emergency button from the map, above.

**Not verified (needs a phone):** see the note at the top and section 11.

Ship checks: markdownlint-cli2 0 errors · JSON validity 43 files valid · gitleaks 8.30.1 no
leaks.

## 7. Decisions & ADRs

[ADR 0015](../adr/0015-map-stack-and-location-policy.md) (Accepted; its location section is to
be confirmed in P010b). Spike findings, in short:

| Question | Finding |
| --- | --- |
| MapLibre version | 13.6.1, BSD-2-Clause, `minSdk` 23. Chosen artifact: `android-sdk-opengl` |
| Why not the default artifact | it renders with Vulkan and declares Vulkan as a **required** device feature, which would hide the app from phones without it |
| Compose | no stable official Compose API → `MapView` in `AndroidView` with explicit lifecycle forwarding |
| 16 KB pages | 64-bit libraries are 16 KB aligned in the AAR and in both APKs; `zipalign -c -P 16` passes. CI checks it. The 32-bit libraries are 4 KB aligned, which is outside the rule |
| HTTP client | MapLibre has its own; ours is never given to it |
| Merged permissions | MapLibre's manifest brings fine and coarse location and Wi-Fi state; removed in ours |
| Cache | ambient cache, honours the server's cache headers; limit set to 50 MB |
| MapTiler styles | `streets-v4` (documented) and `streets-v4-dark` (**not confirmed**: every keyless request answers 403, so the id could not be probed) |
| MapTiler terms | free plan: non-commercial use and R&D only; attribution on screen; a temporary per-user cache is allowed |
| Key restriction for mobile | allowed user-agent substring; MapLibre sends the package name |

Decisions made while building:

- **The credit line is the app's own element**, under the search pill, instead of MapLibre's
  "i" button: always visible, translated, 48 dp, and not coverable by the sheet.
- **The status is a small card, never a full-screen error.** The map may still be partly
  usable (cached areas), and nothing else on Home depends on it.
- **After the map has loaded, only a refusal or being offline changes the state.** MapLibre
  reports single failed tiles through the same callback as a failed style.
- **The debug map label is gone.** Without a key the app now says "Map not configured" in every
  build type.
- **Tests replace the map for the whole test source set** (`@TestInstallIn`), so no test can
  load native code or contact MapTiler, whatever key the machine has.

## 8. Security & privacy notes

- **The key** is not in the repository, the workflow, this log or the pull request. CI uses
  `dummy-map-key-for-ci`. In code it is wrapped so that it cannot be printed; MapLibre's URL
  logging is off and its log lines are filtered. A key in an APK is still readable by anyone
  who unpacks it: the real controls are the dashboard restriction, the usage alert and
  rotation (ADR 0015, `android/README.md`).
- **No SafeRoute token, user id, phone number or locale goes to MapTiler.** Map traffic uses
  MapLibre's own HTTP client; a test keeps the two apart.
- **What MapTiler sees:** the phone's IP address, the area and zoom viewed, and a `User-Agent`
  with the app name, version, package name, Android version and processor type. To be stated
  in the privacy policy, the consent notice and the Data safety form (follow-up).
- **No location in this part.** No permission was added; the map opens at a fixed public
  point. The camera position (where the map looks, not where the user is) is kept in the
  ViewModel's saved state, not in a file or a log.
- `allowBackup` stays `false`. The map cache is in the app's private storage.
- Public-repository check: no keys, local paths or real coordinates of people. Test
  coordinates are round numbers; the default camera is a city centre.

## 9. Known issues & risks

- **Nothing native was run** (top of this log).
- **`streets-v4-dark` is unconfirmed.** If it does not exist, the dark theme shows "The map
  couldn't load"; the id is one constant in `MapProviderConfig.kt`.
- **RateLimited depends on MapLibre's wording** ("HTTP status code 403"). If the text differs
  on a device, a refused key shows the generic error instead. Check 5 in section 11.
- **Offline, a never-seen area stays blank without a message** when the style itself is
  cached: the library waits for a connection instead of failing. The prompt's manual check
  "airplane mode on a fresh area shows the offline state" will therefore only hold when the
  style is not cached either (a fresh install). Recorded as a follow-up.
- **OkHttp:** MapLibre asks for 4.12, the app resolves 5.5.0 for both.
- **`ACCESS_WIFI_STATE` removed:** no class in the SDK refers to Wi-Fi, but it was not run.
- **APK size:** debug about 57 MB, release (unshrunk, four processor types) about 53 MB;
  before P010a about 15 MB and 10.5 MB.
- **MapLibre's compass** has an English-only content description.
- **Free MapTiler plan:** development only; its logo requirement versus our text credit is
  open.
- **Maps and TalkBack:** a touch map is hard to use with a screen reader. The map has a
  description ("Map") and its states are announced; Search (P011) is the accessible way to find
  a place.
- **Bengali strings are drafts**, needing human review before release: all 18 `map_*` strings.
- **Over the size guide** (section 4).
- The new CI steps run for the first time on this pull request.

## 10. Follow-ups & prerequisites for next prompt

New in Notion (*Follow-ups*, source P010a):

- MapTiler plan before release: paid plan or agreement, limits, quota alert, a production key
  separate from the development key; logo requirement of the free plan.
- Privacy policy, consent notice and Play Data safety form: name MapTiler as a third party that
  receives IP address and viewed area.
- Confirm on a device: dark style id, failure wording for a refused key, OkHttp 5 with
  MapLibre, no effect from removing `ACCESS_WIFI_STATE`.
- Offline hint for never-seen areas when the style is cached.
- Bengali map labels; a translated description for the compass.
- Accessibility of the map for TalkBack users.
- Self-hosted tiles (PMTiles, Plan v7 §14.2 Stage 2).
- ABI splits and App Bundle size (P022).
- `RegionDefaults` becomes configuration when regions arrive (`regionCode`, ADR 0013).
- Human review of the Bengali `map_*` strings.

Already open and still relevant: "Android third-party SDK license review (Firebase / Play
services, MapLibre) before P022"; "Third-party licenses / NOTICE screen in the Android app".

Closed: "Record Rahul's manual phone checks for P009c".

**Next: P010b** (`feat/010b-location-permission`), when Rahul says "P010b". Not started. It
removes the two location `tools:node="remove"` lines from the manifest, adds
`play-services-location`, and confirms the location section of ADR 0015.

## 11. How Rahul can verify

1. Read the pull request and [ADR 0015](../adr/0015-map-stack-and-location-policy.md).
2. Check that `saferoute.mapTilerKey=<your key>` is in your user-level `gradle.properties`,
   and in the MapTiler dashboard restrict the key (allowed user-agent: `com.saferoute.app`) and
   set a usage alert. `android/README.md`, "Map and MapTiler", has the steps.
3. Sync Gradle and run on the phone. Expected: the map of Kolkata under the search pill; pan,
   zoom and rotate work; after rotating the map a compass appears at the top right and a tap
   turns north up again; "© MapTiler © OpenStreetMap contributors" under the search pill opens
   a dialog with two links. Switch the phone to dark mode: the map turns dark.
4. Rotate the screen, and send the app to the background and back: the map shows the same
   place.
5. Airplane mode after viewing an area: the area still shows. Clear the app's storage, turn on
   airplane mode, open the app: "Map unavailable offline"; turn airplane mode off: the map
   loads by itself.
6. The emergency button with a broken map: put a wrong value in `saferoute.mapTilerKey` (keep
   the real one aside), rebuild. Expected: "Map temporarily unavailable"; search, settings and
   the sheet work; the emergency button opens the dialog and the dialer shows 112. **Tell me
   which message you saw**: if it says "The map couldn't load" instead, the failure wording
   differs from what the code expects. Then remove the property: "Map not configured". Put the
   real key back.
7. Pull the sheet to half height: the credit stays visible and the map centre moves up.
8. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug` (363 tests).
9. CI green on the pull request, squash and merge, then tell me "P010b".

Watch the request count in the MapTiler dashboard from now on: tiles are the main running
cost (Plan v7 §14.1).

## 12. Learning notes

- **MapLibre and vector tiles.** A map on a phone is cut into square "tiles". Older maps sent
  each tile as a picture. *Vector* tiles send the shapes (roads, buildings, names) as data, and
  the phone draws them. That is why the map stays sharp at any zoom, can rotate with the labels
  staying upright, and can switch to a dark look without downloading different tiles. MapLibre
  Native is the open-source library that does the drawing.
  <https://maplibre.org/maplibre-native/android/api/>
- **Styles and attribution.** A *style* is a JSON file that says which tiles to load and how to
  draw each kind of thing. Light and dark are two styles over the same data. The data comes
  from OpenStreetMap volunteers and the tiles from MapTiler; both ask for a visible credit in
  return, which is the "©" line on the map.
- **A lifecycle-aware native view.** Compose screens have no `onStart()` or `onDestroy()`, but
  the map is an old-style Android `View` backed by native code that needs those calls to start
  drawing, to stop when hidden and to free memory. `AndroidView` puts such a view inside
  Compose, and `MapLifecycleForwarder` passes the calls on.
  <https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose>
- **`DisposableEffect`.** Runs set-up code when a composable appears and clean-up code
  (`onDispose`) when it leaves. Here it connects and disconnects the map view.
- **Native libraries and ABIs.** MapLibre is written in C++ and shipped as `.so` files, one per
  processor type (ABI). That is why the APK grew, and why JVM tests cannot run it.
- **16 KB page size.** Memory is handed out in "pages". Newer phones use 16 KB pages instead of
  4 KB, and native libraries must be built and packed for that. Google Play requires it for
  apps that target Android 15 or newer.
  <https://developer.android.com/guide/practices/page-sizes>
- **Why a key in an APK is not secret.** An APK is a zip file; anyone can open it and read the
  strings inside. A map key therefore has to be safe to expose: limited in the provider's
  dashboard to our app, watched by a usage alert, and replaceable. Hiding it from the
  repository and the logs only avoids giving it away for nothing.
- **Manifest merging and `tools:node="remove"`.** Every library has its own manifest, and the
  build merges them into the app's. A library can bring permissions the app never asked for;
  this line takes one out again.
- **`SavedStateHandle`.** A small store inside a ViewModel that Android keeps even if it kills
  the app in the background to free memory. The map's camera goes there.
  <https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-savedstate>
- **`@TestInstallIn`.** Tells Hilt to replace a module for all tests at once. Every test gets
  the fake map without having to ask.
  <https://developer.android.com/training/dependency-injection/hilt-testing>
- **Connectivity callback.** `ConnectivityManager.registerDefaultNetworkCallback` tells the app
  when the phone goes online or offline, instead of the app asking again and again.
- Fine and coarse location, "while using the app", Fused Location and permission state
  machines belong to P010b and are explained in its log.
