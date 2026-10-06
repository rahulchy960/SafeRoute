# P010b: location permission flow, current-location marker and recenter

| Field | Value |
| --- | --- |
| Prompt | P010 · the second and last part (P010a map, **P010b location**) |
| Milestone | M3 (depends on P010a, merged as `4b00ffe`) |
| Branch | `feat/010b-location-permission` |
| PR title | `feat(android): location permission flow, current-location marker and recenter [P010b]` |
| Notion | [P010b row in the Prompt Log](https://app.notion.com/p/3f107370772081d089ffd42c85b8440b) (umbrella row: [P010](https://app.notion.com/p/3eb07370772081f7b83ce3df30c25c83)) |
| Date | 2026-10-06 |
| Plan refs | Plan v7 §1, §3.2 F-02, §5.1, §5.3, §12.3, §16 M3; addendum v7.1; ADRs 0008, 0010, 0015 |

> **Verified on the JVM only, on top of a map that has not been confirmed on a device.**
> Claude Code has no phone or emulator. Two files cannot run in unit tests:
> `core/location/FusedLocation.kt` (needs Google Play services) and the overlay drawing in
> `core/map/MapLibreEngine.kt` (native map). Both compile and link; neither has been seen
> working. P010a was merged without a report of its phone checks, so the map itself is also
> still unconfirmed. **No manual check on any device was run for this part**; section 11 lists
> them.

## 1. Objective

Add the location layer to the map: a clear disclosure before Android's permission dialog, the
runtime permission flow in all its outcomes, a current-location marker with an accuracy
circle, and a working "my location" control. Display only: the position stays on the phone,
is not stored, logged or uploaded, and is used in the foreground only. A location failure must
never affect the emergency button.

## 2. Context & prerequisites

- P010a merged (PR #22, `4b00ffe`). No open pull requests. Hooks active. Clean tree.
- P010a left `setOverlays` as a no-op, removed the two location permissions that MapLibre's
  manifest merges in, and marked the location section of ADR 0015 "to be confirmed".
- The P010a phone checks have not been reported.
- The consent notice (P009) already mentions location for navigation under `account_core`.
  This prompt adds the runtime permission and its disclosure, not a server-side consent
  purpose: nothing about location reaches the server.

## 3. Workflow executed

1. `/start-prompt`: main synced, PR #22 confirmed merged, Notion P010a set to Merged, branch
   `feat/010b-location-permission`, Notion page.
2. **Spike (B0)**: `play-services-location` versions from Google's Maven repository; its POM,
   manifest and class files read with `unzip` and `javap`; MapLibre's style-layer API read the
   same way. Results in section 7.
3. Manifest, dependency, pinned permission test (`d6a3889`).
4. `core/location` (`087f4df`).
5. Permission state machine, disclosure, notices, the button, the map layer, Home wiring,
   strings (`e8b9d26`).
6. Debug "Location" row (`5b87dfb`).
7. Diagram (`f567894`), docs and ADR (`56e794e`), lint tidy-up (`acda04a`).
8. `/ship-prompt`: quality gate, this log, push, pull request, Notion.

Found by the new tests and fixed before the first commit:

- The spinner state of the button had its description on a 24 dp child instead of on the
  48 dp button.
- **Android can drop a permission request without asking the user** (it answers with an
  empty result when another request is already open). The first version treated that as an
  instant refusal, which the state machine reads as "denied for good". It is now recognised
  and ignored (`onPermissionRequestCancelled`).

## 4. Changes

| Area | What |
| --- | --- |
| `AndroidManifest.xml` | `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` declared; P010a's two removals deleted; `ACCESS_WIFI_STATE` stays removed. **No background location, no foreground-service permission** |
| `gradle/libs.versions.toml` | `com.google.android.gms:play-services-location` 21.4.0 |
| `core/location/LocationRepository.kt` | `LocationState` (NoPermission, Searching, Fix, Stale, Unavailable), `LocationFix`, `LocationRepository`, `DefaultLocationRepository`, `LocationSource`, `LocationEnvironment`, debug summary in buckets |
| `core/location/FusedLocation.kt` | **the only file that uses Google's location library**: `FusedLocationSource`, `AndroidLocationEnvironment`, the Hilt module |
| `feature/home/LocationPermissionViewModel.kt` | the permission state machine and the notices |
| `feature/home/MyLocation.kt` | `MyLocationButton` (five states), `LocationDisclosureDialog`, `LocationNoticeCard`, `locationOverlays`, `myLocationControl` |
| `feature/home/HomeViewModel.kt` | starts and stops location; first fix after a tap centres once; second tap follows; a drag ends follow mode; overlays cleared when the permission is lost |
| `feature/home/HomeScreen.kt` | permission launcher, refresh on resume, start on visible and stop on `onStop`, notices, disclosure; the button is no longer disabled |
| `core/map/MapOverlayGeoJson.kt` | overlay descriptions → GeoJSON: accuracy circle as a 48-point ring, dot, ring for approximate, grey for stale, heading |
| `core/map/MapLibreEngine.kt` | `setOverlays` implemented: one GeoJSON source and four layers (fill, line, heading symbol, dot); colours from the design system |
| Design system | `location` and `locationStale` colours; `MapControlButton` can be `busy` or `selected`; two icons drawn for the app |
| Debug | Developer screen: a "Location" row with permission, Location switch and fix-age bucket only |
| Strings | 29 new in English and Bengali (`my_location_*`, `location_disclosure_*`, `location_notice_*`), 1 debug string; `map_control_my_location_unavailable` removed |
| Tests | `LocationPermissionViewModelTest` (22), `DefaultLocationRepositoryTest` (12), `HomeLocationTest` (20), `HomeLocationScreenTest` (9), `LocationFlowTest` (10), `MapLibreBoundaryTest` +2, `DeveloperCheckViewModelTest` +1, `MainActivityTest` permission list, fakes |
| Docs | ADR 0015 (location policy accepted), `android/README.md` "Location", `android/THIRD_PARTY.md`, `CLAUDE.md`, diagram, this log |

No backend or contract change.

**Size:** about 3,850 changed lines without the generated diagram files: code and strings
1,690, tests 1,700, documents 470. Far over the ~800-line guide. It was not split again: the
permission flow, the repository and the map layer are one feature (a pull request with the
permission but no dot, or the reverse, would ask users for location and show nothing), and a
further part would have had to wait for a merge. The commits are separated by concern.

## 5. Diagram

[`docs/diagrams/010-location-permission-flow.svg`](../diagrams/010-location-permission-flow.svg)
(13 nodes): tap → disclosure → system dialog → granted (precise, approximate) or refused
(once, for good), location off, no Play services, stop on background, and the recompute on
every resume.

## 6. Quality gate & test results

| Command | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` (in `android/`) | BUILD SUCCESSFUL |
| → unit tests | **439 tests, 0 failures, 0 skipped** (363 before; 76 more) |
| → lint | 0 errors, 2 warnings (both `OldTargetApi`, known, ADR 0008) |
| `./gradlew assembleRelease` with the dummy base URL, dummy map key and allow flag | BUILD SUCCESSFUL |
| `zipalign -c -P 16 4` on the debug and release APKs | verification successful (both) |
| 64-bit native libraries with 16 KB `LOAD` alignment | all 6 (unchanged: the location library has no native code) |
| Merged manifest | `INTERNET`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_NETWORK_STATE`, `READ_GSERVICES` |
| `pnpm generate` · `pnpm check` in `tools/diagrams` | 15 diagrams, up to date |
| `git ls-files android \| grep -iE "local.properties\|\.jks\|google-services\.json"` | prints nothing |
| markdownlint-cli2 · JSON validity · gitleaks 8.30.1 | 0 errors · 45 files valid · no leaks |

What the tests prove:

| Test | What it proves |
| --- | --- |
| no request on start | opening the app, resuming and rotating request nothing and start nothing (ViewModel and real `MainActivity`) |
| disclosure first | a tap shows the explanation; only "Continue" reaches the system dialog; "Continue" without the explanation on screen does nothing |
| "Not now" | nothing asked, nothing shown, the map and the emergency button work, the button still works |
| precise · approximate | both are used; approximate gets a hint and one offer of precise; a declined offer is not repeated; an accepted one restarts location as precise |
| deny once · twice | once: a note and a working button; twice: "denied for good", the button points to Settings and never asks Android again |
| dialog closed · dropped | a dialog closed without an answer, or a request Android dropped, is not a refusal for good |
| location off, then on | its own state; the button offers location settings; back with it still off changes nothing (no loop); switched on → location starts by itself |
| revoked while running | on return the state is read again: location stops and the dot is removed |
| process death | "denied for good" is remembered; a grant is read afresh; "only this time" starts over |
| no Play services | reported; nothing requested |
| repository | starts only when permitted and possible; one start for two calls; Searching → Fix → Stale with virtual time; Unavailable after 45 s; **no update after `stop()`**, including a callback already on its way |
| foreground only | location stops when the activity goes to the background and resumes when it returns (real `MainActivity`) |
| no logging, no storage | fixes with distinctive digits are fed through the repository while Logcat is captured: none of the digits appear; the saved state holds only the camera; `core/location` uses no network, storage or log API |
| map layer | accuracy ring is closed and at the right distance all round; dot, ring (approximate) and grey (stale) differ in shape or colour and in the button's description; heading only for a fresh precise fix |
| camera | first fix after a tap centres once at street zoom and keeps a closer zoom; later fixes move only the dot; second tap follows; a drag ends following; stale does not move the camera |
| button | five states with five distinct descriptions, never disabled, 48 dp |
| Home integration | no location notice or state blocks the emergency button, the search pill or the sheet; 200% font for the disclosure and a notice; Bengali |
| manifest | exact permission list; background location, foreground service and Wi-Fi state absent |
| debug row | permission, switch and age bucket only; no digit of a position reaches the screen |

**SOS failure matrix (Plan v7 §7.5):** not applicable; no SOS, live-location-sharing or
contacts logic. The related checks are that location trouble never blocks the emergency button
and that nothing is stored or sent.

**Manual checks: none were run.** Devices available to Claude Code: 0. The plan's M3 exit
wants three test devices; how many Rahul has, and their Android versions, is to be recorded in
a Revision section when he reports.

## 7. Decisions & ADRs

[ADR 0015](../adr/0015-map-stack-and-location-policy.md), "Location policy (confirmed in
P010b)". Status: Accepted.

| Question | Finding or decision |
| --- | --- |
| Library | `play-services-location` 21.4.0, proprietary; its manifest adds no permissions |
| API | `LocationRequest.Builder(priority, interval)`, `requestLocationUpdates` with a `LocationCallback`; updates rather than `getCurrentLocation`, because the dot must keep moving |
| Accuracy and interval | precise: `PRIORITY_HIGH_ACCURACY`, about every 3 s; approximate: `PRIORITY_BALANCED_POWER_ACCURACY`, about every 10 s; nothing when Home is not visible |
| Stale and unavailable | a fix older than 30 s is drawn grey; 45 s without a first fix is reported |
| Approximate (Android 12+) | the system dialog lets the user choose; only the coarse permission is then granted. Used as it is, with one offer of precise |
| "Only this time" | Android takes the grant back when the app is closed; the app starts again from the explanation |
| Permanent denial | not reported by Android; inferred from a refusal without rationale after an earlier refusal, or within 400 ms |
| Location off | the system location settings are opened, not the Play services resolution dialog: one path, works without Play services, state re-read on return |
| No Play services | reported as unavailable; no fallback source yet |
| Map layer | **a custom layer driven by `LocationRepository`**, not MapLibre's `LocationComponent`: one source of truth (reused by P014), logic testable on the JVM, map library stays replaceable |

Decisions made while building:

- **Two taps, not one, to follow.** The first tap centres the map; a tap while centred turns
  following on; dragging the map turns it off.
- **Going to the background keeps the dot** (it turns stale on return); losing the permission
  removes it.
- **Notices only answer a tap, and can be closed.** The app never raises one by itself.
- **Approximate is a ring, not a dot**, so shape, not only colour, says "roughly here".

## 8. Security & privacy notes

- **Precise location is personal data.** In this part it stays on the phone, in memory.
  Nothing writes it to disk, a log or the network; tests check each of the three.
- **Foreground only.** No background location and no foreground service; a test pins the
  permission list.
- **Disclosure before the dialog**, with no pre-selected choice. Its wording must be reviewed
  by a lawyer, and the Bengali by a native speaker (follow-ups).
- The types that hold a position (`LatLng`, `LocationFix`, `RawFix`, `CameraState`) print
  "hidden".
- The debug row shows buckets only.
- `allowBackup` stays `false`. No new server-side consent purpose: nothing reaches the server.
- Google Play services receives location requests from the app, as for any app that uses
  Fused Location; to be named in the privacy policy (follow-up).
- Public-repository check: test coordinates are round numbers (10, 20) and digit patterns
  (12.345678); no real place of a person, no keys, no local paths.

## 9. Known issues & risks

- **Nothing in this part was run on a device, and the map under it is unconfirmed** (top).
- **The overlay layers are untested MapLibre code.** If the style expressions are wrong the
  dot does not appear; the rest of the app is unaffected.
- **Permanent-denial detection is a heuristic.** A user who closes Android's dialog in under
  400 ms on the very first request is treated as "denied for good" and sent to Settings.
- **Heading** comes from movement (GPS bearing), not from the compass: no arrow while standing
  still.
- **A stale position keeps its last accuracy circle**; it does not grow with age.
- **Indoors** the first fix can take longer than 45 s; the button then says "unavailable" until
  a fix arrives or the user taps again.
- **Phones without Google Play services** get no position.
- **The offline gap from P010a remains**: the dot works over cached tiles, but a never-seen
  area is blank without a message.
- **Bengali strings are drafts**: all 29 new ones. The disclosure also needs the lawyer.
- **Over the size guide** (section 4).

## 10. Follow-ups & prerequisites for next prompt

New in Notion (*Follow-ups*, source P010b):

- Lawyer review of the location disclosure wording; native-speaker review of its Bengali.
- Privacy policy, consent notice and Data safety form: the location permission (added to the
  P010a item about MapTiler).
- Confirm on devices what P010b could not run; record how many devices and which Android
  versions (M3 exit wants three).
- Devices without Google Play services: an alternative location source.
- Persist the last known location for SOS (P014), with its own retention rule.
- Mock-location detection and what to do with it (P014+).
- Compass heading while standing still; an accuracy circle that grows as a fix ages.

Still open from P010a: MapTiler plan and production key; device confirmations for the map;
offline hint; Bengali map labels; TalkBack accessibility of the map; self-hosted tiles; ABI
splits; `RegionDefaults` as configuration; the licence review (now also
`play-services-location`).

**Next: P011** (`feat/011-place-search`). Not started. Inputs Rahul must prepare:

- **A server-side geocoding key.** Plan v7 §12.4: geocoding keys stay on the server, never in
  the app. Decide the provider (MapTiler Geocoding is the natural candidate since the account
  exists; check its plan limits and terms for server-side use and for storing results), create
  a key that is **separate from the map key** and restricted for server use.
- **Put the key in Secret Manager for staging** by the usual runbook path
  ([`docs/runbooks/gcp-staging-setup.md`](../runbooks/gcp-staging-setup.md)); Claude Code never
  sees it. P011 will say which secret name and variable the API reads.
- **The P010 phone results** (map and location), so that search results are not built on an
  unconfirmed map.
- A decision on search bias and bounds for the launch region (config, not code: ADR 0013).

## 11. How Rahul can verify

Do the P010a checks first if they are still open (its log, section 11).

1. Read the pull request and the disclosure wording
   (`location_disclosure_*` in `values/strings.xml` and `values-bn/strings.xml`).
2. Run on the phone. Open the app: **no permission dialog appears**. Tap "my location": the
   explanation appears. "Not now": it closes and nothing was asked.
3. Tap again → "Continue" → "While using the app", "Precise": a blue dot with a circle; the
   map centres on you once. Tap the button: it centres. Tap again: it follows (the icon
   becomes an arrowhead). Drag the map: following stops. Walk: an arrowhead shows the
   direction.
4. Clear the app's storage, repeat and choose **"Approximate"**: a ring and a wide circle, and
   a hint with "Use precise location". Try the offer both ways.
5. Clear storage, **deny once**: a short note, the button still works. Deny **again**: the
   note offers "Open Settings", which opens SafeRoute's page. Allow it there and return: the
   dot appears.
6. Turn the phone's **Location off** and return: tap the button → "Location settings". Turn
   it on and return: the dot comes back by itself.
7. **Background** the app: the location indicator in the status bar disappears. In Logcat no
   line contains coordinates.
8. **Airplane mode** with the area already viewed: the dot still moves over the cached map.
9. Go **indoors**: the circle grows; after a while without a fix the dot turns grey.
10. **Dark mode** and **200% font**: the explanation can be read to the end; the dot is
    visible on the dark map.
11. Settings → Developer: the "Location" row shows words only, never numbers of a position.
12. A second device if you can borrow one, ideally a different Android version. Tell me how
    many devices and which versions.
13. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug` (439 tests).
14. CI green, squash and merge.

## 12. Learning notes

- **Fine and coarse location.** Android has two location permissions.
  `ACCESS_FINE_LOCATION` is GPS-level (a few metres). `ACCESS_COARSE_LOCATION` is rounded to a
  few kilometres. Since Android 12 the system dialog lets the user pick "Precise" or
  "Approximate", so an app that asks for fine must also ask for coarse and must work with
  either. <https://developer.android.com/develop/sensors-and-location/location/permissions>
- **"While using the app" and why background location is avoided.** A foreground grant lets
  the app read location only while it is visible. Background location is a separate permission
  with a separate, stricter dialog and a Google Play review; it is how apps track people
  without their noticing. SafeRoute does not need it for a map, so it does not ask.
- **"Dangerous" permissions.** Declaring one in the manifest only makes it possible to ask.
  The user answers at runtime and can change the answer at any time in Settings.
- **What Fused Location does.** Google Play services combines GPS, Wi-Fi and mobile networks
  and returns the best position for the battery cost you ask for (the *priority*). The app
  says how accurate and how often; the phone decides how.
  <https://developer.android.com/develop/sensors-and-location/location/request-updates>
- **Permission state machines, and why we recompute on resume.** The user can grant, pick
  approximate, deny, deny for good, grant once, switch Location off, or change any of it in
  Settings while the app is in the background. Remembering "granted" would be wrong the moment
  they do. So the app keeps a small set of named states and, every time the screen comes back,
  asks Android what is true now.
- **`rememberLauncherForActivityResult`.** How a Compose screen shows a system dialog and gets
  the answer: `launch()` opens it, a callback receives the result.
  <https://developer.android.com/training/permissions/requesting>
- **`shouldShowRequestPermissionRationale`.** Android's hint that the user refused once and
  the app should explain before asking again. Android never says "refused for good"; apps
  infer it.
- **`LifecycleStartEffect` and `LifecycleResumeEffect`.** Compose helpers that run code when
  the screen becomes visible or comes to the front and clean up when it stops or pauses. Here:
  start location when visible, stop it in `onStop`; re-read the permission on resume.
  <https://developer.android.com/topic/libraries/architecture/compose#lifecycle-effects>
- **GeoJSON, sources and layers.** A map style has *sources* (data) and *layers* (how to paint
  it). The app puts its shapes into one GeoJSON source; layers pick the shapes they paint.
  Updating the dot only replaces the data.
- **Prominent disclosure.** Google Play requires an app to explain, in its own words and
  before the system dialog, what location data it collects and why. Plan v7 §12.3 asks for the
  same.
- **Mock locations.** A developer setting that lets another app feed fake positions. Useful
  for testing; something SOS will have to think about (P014).

## Revision 2026-10-07 (P010c): manual phone checks passed

Added after the merge. Sections 1–12 above are unchanged and describe what was known when the
pull request was opened.

On 2026-10-06, after the merge (PR #23, `2e08043`), Rahul reported that all phone checks
passed. The result was reported as a whole, without per-check details. The same note is on the
Notion page of this prompt.

On 2026-10-07 Rahul reported the devices: **one device, Android 17**. The device model was not
recorded. The plan's M3 exit asks for three test devices, so the checks are **two devices
short**: no second Android version, no other manufacturer and no phone without Google Play
services has been tried. The Notion follow-up "Record the device count and Android versions for
M3" stays open until two more devices have been used.
