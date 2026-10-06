# ADR 0015: Map stack (MapLibre Native + MapTiler) and location policy

- **Status:** Accepted
- **Date:** 2026-10-06
- **Prompt:** P010a (map), P010b (location policy)
- **Plan refs:** Plan v7 §1, §3.2 (F-02), §4, §5.1, §5.3, §12.3, §12.4, §14.1, §14.2; [ADR 0005](0005-product-name-and-multi-city-readiness.md), [ADR 0008](0008-android-foundation.md), [ADR 0009](0009-android-api-client.md), [ADR 0013](0013-regions-and-expansion.md)

## Context

- Home needs a real map (Plan v7 §3.2 F-02). The rest of the stack is open: OpenStreetMap data,
  OSRM routing, provider adapters for tiles, geocoding, routing and push (Plan v7 §4).
- A map SDK is native code, has its own networking and cache, and needs a key for the tile
  provider. A key that ships inside an APK can be read by anyone who unpacks the file.
- The Home screen carries the emergency button. Whatever the map does must not affect it.
- Claude Code has no device: everything decided here that can only be seen on a phone is
  listed under "Not verified" and is a manual check in the P010a prompt log.

Facts found in the P010a spike (2026-10-06), with their sources:

| Question | Finding | Source |
| --- | --- | --- |
| Current stable SDK | `org.maplibre.gl:android-sdk` 13.6.1; BSD-2-Clause; `minSdk` 23 | Maven Central metadata, POM and the AAR's manifest |
| Rendering | Since 13.0 the default artifact renders with Vulkan and declares `android.hardware.vulkan.version` as a **required** device feature. `android-sdk-opengl` 13.6.1 is the same API on OpenGL ES, without that requirement | the two AARs' manifests |
| Merged permissions | Both artifacts declare `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE`, `INTERNET` | the AARs' manifests |
| Compose | No Compose API in the official artifact. A separate community project (`org.maplibre.compose`) is at 0.19, not 1.0 | Maven Central |
| 16 KB pages | The 64-bit libraries (`arm64-v8a`, `x86_64`) have 16 KB `LOAD` alignment; the 32-bit ones have 4 KB, which is outside the requirement. AGP 9.4 stores native libraries uncompressed and 16 KB zip-aligned: `zipalign -c -P 16` passes on the debug and release APKs | ELF headers read from the AAR and the APKs; `zipalign` from build-tools 36.0.0 |
| HTTP client | MapLibre builds its own `OkHttpClient` (own dispatcher). It adds `User-Agent` and cache validators (`If-None-Match`, `If-Modified-Since`, `Range`) and nothing else. Another client is used only if the app calls `HttpRequestUtil.setOkHttpClient` | the SDK's class files |
| Cache | Responses are stored in an "ambient cache" (SQLite file in the app's private storage) and reused according to the server's `Cache-Control`, `Expires`, `ETag` and `Last-Modified` headers. `OfflineManager.setMaximumAmbientCacheSize` sets the limit | the SDK's class files |
| Offline behaviour | Without a connection the SDK does not fail a request: it waits for the network | SDK behaviour as known from its source; not seen on a device |
| MapTiler styles | `https://api.maptiler.com/maps/{mapId}/style.json?key=…`; the documentation's current example id is `streets-v4`. A request without a key answers 403 | MapTiler API documentation; a keyless request |
| MapTiler terms | Free plan: "limited to non-commercial use and research & development for commercial products applications"; use beyond the limits "may result in temporary suspension". Attribution "is required to be shown on screen while the map is displayed". Results "can be stored in a temporary personal cache (browser cache, mobile app cache, etc.) for use by a single end-user only". "You are responsible for all use of your API keys" | MapTiler Cloud Terms §1.2, §5.4, §5.7, §3.2 |
| Key restriction | A key can be limited to allowed HTTP origins (web) and to an **allowed user-agent substring** (meant for mobile apps). The check is a string comparison and can be imitated | MapTiler documentation, "How to protect your map key" |
| Google Play rule | Apps that target Android 15 (API 35) or higher must support 16 KB page sizes on 64-bit devices | developer.android.com, "Support 16 KB page sizes" |

## Decision

### Map library and tiles

- We will draw the map with **MapLibre Native for Android**, artifact
  `org.maplibre.gl:android-sdk-opengl` **13.6.1**, pinned in the version catalog.
- We will use the **OpenGL ES build**, not the default Vulkan one: the Vulkan artifact marks
  Vulkan as required, which would hide the app on Google Play from phones without it. A safety
  app must not exclude older and cheaper phones for a rendering backend.
- We will show **MapTiler** styles: `streets-v4` in the light theme and `streets-v4-dark` in
  the dark theme. The style follows the app theme (light/dark), not Material You.
- We will host the map view the plain way: MapLibre's `MapView` inside Compose's `AndroidView`,
  with the screen's lifecycle forwarded explicitly (`MapLifecycleForwarder`). There is no
  stable official Compose API, and ADR 0008 allows stable APIs only.

### One package, one door (portability)

- **All MapLibre types stay inside `core/map`**, and in practice inside one file,
  `MapLibreEngine.kt`. Screens, ViewModels and tests see only the app's own types: `MapEngine`,
  `MapController`, `LatLng`, `CameraState`, `MapPadding`, `MapLoadState` and the overlay
  descriptions (`Marker`, `AccuracyCircle`, `Polyline`, `Polygons`), all plain Kotlin data
  classes.
- The style names, the URL pattern, the credit links, the cache size and the launch region's
  default camera live in one file, `MapProviderConfig.kt`.
- `MapLibreBoundaryTest` fails the build if any other file refers to the library.

### Key handling and its limits

- The key comes from the Gradle property `saferoute.mapTilerKey` in the developer's user-level
  `gradle.properties` and reaches the app as `BuildConfig.MAPTILER_KEY`. It is never in the
  repository, in CI, in a log, on a screen or in an exception message.
- Without the property a debug build works and the map area says "Map not configured". A
  release build fails (`checkReleaseMapKey`) unless `-Psaferoute.allowDummyMapKey=true` is
  passed, which only CI's release-assembly check does, with a key that starts with `dummy-`.
- **A key in an APK is not a secret.** The controls are, in this order: the restriction in the
  MapTiler dashboard (allowed user-agent substring; MapLibre sends the app's package name in
  its `User-Agent`), a usage alert and a hard limit on the account, a separate key for
  production, and **rotating the key** if it leaks or is abused. Keeping the key out of the
  repository and the logs only avoids handing it out for free.
- In code the key is wrapped (`MapKey`, `MapStyleUrl`): `toString()` hides it, and the URL is
  built at runtime in one place. MapLibre's URL logging is switched off, and all of its log
  lines pass through a filter that removes the key.

### Map traffic is separate from API traffic

- Tile and style requests go from the phone straight to MapTiler, with MapLibre's own HTTP
  client. **The app's API client is never given to MapLibre** (`setOkHttpClient` is not
  called), so the SafeRoute sign-in token, user id, phone number and locale cannot reach the
  tile provider. No custom headers are added.
- What MapTiler does receive: the phone's IP address, the area and zoom being viewed (the tile
  coordinates), and a `User-Agent` with the app's name, version and package name, the Android
  version and the processor type. This must be stated in the privacy policy, the consent notice
  and the Play Data safety form (follow-up).

### Attribution

- The credit "© MapTiler © OpenStreetMap contributors" is **always visible while a map is
  shown**: a 48 dp chip under the search pill, the one place the bottom sheet does not cover
  while any map is visible. Tapping it opens a dialog with links to both copyright pages.
- It is the app's own element, so it is translated, large enough to tap and testable. The
  library's own logo and "i" button are switched off in its favour.
- OpenStreetMap data is under the ODbL, which requires the credit.

### Cache and offline

- The ambient cache is limited to **50 MB**, set explicitly. It is the temporary per-user cache
  the MapTiler terms allow. It makes areas seen before draw without a connection.
- **Offline map packs (downloading a region in advance) are out of scope** and are not allowed
  by this decision; they need the provider's terms checked and their own ADR.

### Failure states

- The map area has six states: Loading, Ready, Error (retry), Offline, NotConfigured and
  RateLimited (the provider refused the key or the quota is used up: HTTP 401, 403, 429; shown
  as "Map temporarily unavailable" without details).
- Loading always ends: after 3 seconds without a connection it becomes Offline, after 20
  seconds Error.
- The map and the emergency dialog are separate pieces of state. In every map state the search
  pill, the controls, the sheet and the emergency button keep working; tests prove it.

### 16 KB page size

- The app targets API 36, so the rule applies. The chosen version passes (table above). CI
  checks every pull request: `zipalign -c -P 16` on both APKs and the `LOAD` alignment of every
  64-bit native library.

### Launch region

- The map opens at `RegionDefaults`: the centre of Kolkata, the launch city, at city zoom.
  Regions become configuration when they arrive (`regionCode`, ADR 0013); no identifier names a
  place (ADR 0005).

### Location policy (confirmed in P010b)

Facts found in the P010b spike (2026-10-06): `com.google.android.gms:play-services-location`
21.4.0 is the current release (Google's Maven repository); its manifest declares no
permissions; `LocationRequest.Builder`, `Priority` and `requestLocationUpdates` with a
callback are the current API (read from the artifact's class files). The library is
proprietary ("Android Software Development Kit License").

- **Foreground only.** Location updates run only while Home is visible and the permission is
  granted, and stop in `onStop`. The manifest declares `ACCESS_FINE_LOCATION` and
  `ACCESS_COARSE_LOCATION` and nothing else: no `ACCESS_BACKGROUND_LOCATION`, no
  foreground-service permission (Plan v7 §5.3). A test pins the list.
- **Nothing is stored, logged or sent.** For the map, the position goes from
  `LocationRepository` to the map overlay and the camera. The last fix is a field in memory.
  `LatLng`, `LocationFix` and `RawFix` hide their values in `toString()`; tests capture Logcat
  and scan the saved state. Sharing happens only when the user starts SOS or live sharing
  (later prompts).
- **Disclosure first, and only on request.** The system dialog is requested only after the
  user tapped "my location" and chose "Continue" on the app's own explanation (what is
  collected, why, what is not done, how to stop). Never at app start or during onboarding.
  "Not now" is final until the next tap. The wording is a draft until a lawyer has reviewed it.
- **The permission is a state machine, recomputed on every resume**
  (`LocationPermissionViewModel`): NotAsked, DisclosureShown, RationaleNeeded, Granted (precise
  or approximate), DeniedOnce, DeniedPermanently, ServicesOff, PlayServicesUnavailable. The
  user can change any of it in system Settings at any time, so the state is read from Android,
  not remembered. One system dialog per tap at most; no loops.
- **"Denied for good" is inferred**, because Android does not say it: the request came back
  refused without `shouldShowRequestPermissionRationale` turning true, either after an earlier
  refusal or within 400 ms (no dialog can have been shown). A dialog closed without an answer
  on the first request is treated as a single refusal.
- **"Only this time"** grants are taken back by Android when the app is closed. The next start
  finds no permission and starts again from the explanation.
- **Accuracy and interval.** Precise permission: `PRIORITY_HIGH_ACCURACY` about every 3 seconds
  (2 at the fastest) while the map is visible, for a dot that moves smoothly. Approximate
  permission: `PRIORITY_BALANCED_POWER_ACCURACY` about every 10 seconds. Nothing otherwise. A
  fix older than 30 seconds is shown as stale; 45 seconds without a first fix is reported as
  unavailable.
- **Approximate location is used as it is**, with a hint and one offer of "Use precise
  location". If that is declined the offer is not repeated.
- **A custom layer, not MapLibre's `LocationComponent`.** `LocationRepository` is the one
  source of the phone's position (the map now, SOS in P014). The dot, the accuracy circle and
  the heading arrow are ordinary overlay descriptions drawn by the map component. This keeps
  one source of truth, keeps the logic testable on the JVM, and keeps the map library
  replaceable. `LocationComponent` would have brought its own location engine and its own
  permission assumptions inside the map library.
- **Location switched off** is handled by opening the system's location settings, not by the
  Play services resolution dialog: one code path, works without Play services, and the state
  is read again on return.
- **Phones without Google Play services** get no position (the map and the emergency button
  work). An alternative source is a follow-up.
- **Mock locations** are recognised and kept as an internal flag; nothing acts on it yet
  (P014).

## Switching the map provider

What would have to change if MapLibre or MapTiler were replaced:

| Part | Change |
| --- | --- |
| Map component | A new `MapEngine` implementation (`core/map`), replacing `MapLibreEngine.kt`. `MapStateHolder`, the lifecycle forwarder and every screen stay |
| Styling | Style names and URL pattern in `MapProviderConfig.kt`; light and dark styles that match the design system |
| Overlays | How `Marker`, `AccuracyCircle`, `Polyline` and `Polygons` are drawn with the new library (the descriptions do not change) |
| Live-share web page | The public viewer (P016) draws a map too and must switch with the app, or users and their contacts see different maps |
| Privacy and consent text | The third party named in the privacy policy, the consent notice, the Data safety form and `android/THIRD_PARTY.md`; the credit line and its links |
| Offline caching | The cache size, where it lives, and what the new provider's terms allow |
| Keys and CI | The Gradle property, the release guard and the dashboard restrictions |

A switch needs **a superseding ADR** and **a parity test suite at the `MapController`
interface**: the same tests (states, camera save and restore, padding, overlays) passing for
the old and the new engine before the old one is removed.

Self-hosted tiles (PMTiles on our own storage, Plan v7 §14.2 Stage 2) are the expected first
switch: the tile provider changes, the map library does not.

## Alternatives considered

- **Google Maps SDK for Android.** Polished and well known. Rejected: closed source, a second
  Google dependency in the core of the app, usage-based cost and terms that restrict caching
  and combining its map with other data, and a different data source from the OSRM routes
  (OpenStreetMap), so a route could disagree with the map under it.
- **MapLibre's default (Vulkan) artifact.** Newer renderer. Rejected for now because of the
  required device feature; revisit when the requirement is gone or the device data says it does
  not matter.
- **A community Compose wrapper.** Less code. Rejected: not 1.0, and it would put a second
  API surface between the app and the library.
- **The library's own attribution button.** Zero work. Rejected: a small "i" that the sheet and
  the controls can cover, English only, and the credit would be one tap away instead of on
  screen.
- **Routing tile requests through the app's own HTTP client.** One networking stack. Rejected:
  it would send the sign-in token's interceptors past a third party.
- **Proxying tiles through the SafeRoute API to hide the key.** The key stays secret. Rejected
  for the MVP: every tile would cost Cloud Run time and egress, and the API would learn where
  each user is looking.

## Consequences

- Easier: the map library can be replaced behind `MapEngine`; map state is unit-tested without
  native code; the key cannot leak through logs.
- Harder: the app is about 50 MB larger as a universal APK, because the map's native library
  ships for four processor types (ABI splits and the App Bundle are P022 work).
- MapLibre asks for OkHttp 4.12 and the app uses 5.5.0. Gradle picks 5.5.0 for both; OkHttp 5
  is designed to be binary compatible with 4. Seen to compile and link, not seen on a device.
- **The free MapTiler plan covers development only.** A public release needs a paid plan or a
  written agreement, and the free plan's logo requirement must be checked against the text-only
  credit (follow-ups).
- Not verified without a phone: that the map draws; the dark style id (`streets-v4-dark`)
  exists; the wording of MapLibre's failure texts ("HTTP status code 403"), on which the
  RateLimited state depends; behaviour in airplane mode; that removing `ACCESS_WIFI_STATE`
  has no effect. All are manual checks in the P010a log.
- Known gap: with the style cached and no connection, a part of the map never seen before
  stays blank without a message, because the library waits instead of failing.
- Revisit when: MapTiler's terms or prices change; tile requests approach the plan limit; the
  Vulkan requirement is dropped; an official stable Compose API appears.

## References

- Plan v7 §3.2, §5.1, §5.3, §12.3, §12.4, §14.1, §14.2.
- MapLibre Native: <https://github.com/maplibre/maplibre-native>
- MapTiler Cloud terms: <https://www.maptiler.com/terms/cloud/> · key protection:
  <https://docs.maptiler.com/guides/maps-apis/maps-platform/how-to-protect-your-map-key> ·
  Maps API: <https://docs.maptiler.com/cloud/api/maps/>
- OpenStreetMap copyright and licence: <https://www.openstreetmap.org/copyright>
- Android, 16 KB page sizes: <https://developer.android.com/guide/practices/page-sizes>
- [`android/README.md`](../../android/README.md), "Map and MapTiler";
  [`docs/prompt-logs/010a-map-maptiler.md`](../prompt-logs/010a-map-maptiler.md).
