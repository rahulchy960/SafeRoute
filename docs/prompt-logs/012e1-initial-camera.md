# P012e1: the map opens on the region, then once on the user's position

| Field | Value |
| --- | --- |
| Prompt | P012 · part e, split in two: **P012e1 initial camera** (this log) and P012e2 compact SOS control (not started). P012c2 (follow-me) is also still open |
| Milestone | M4 (depends on P012c1 and P011e1, both merged) |
| Branch | `fix/012e1-initial-camera` (the prompt named `fix/012e-home-start-and-sos-placement`; renamed before any push) |
| PR title | `fix(android): open the map on the region overview, then once on the user's position [P012e1]` |
| Notion | [P012e1 row in the Prompt Log](https://app.notion.com/p/3f307370772081668175cabb6a569e19) |
| Date | 2026-10-08 |
| Plan refs | Plan v7 §5.3, §12.3; addendum v7.2 (launch geography); ADRs 0005, 0015, 0016, 0018 |

> **Part e is split.** This part is requirement 1 (initial camera) with its tests and the ADR
> 0015 section. Requirement 2 (the compact SOS control), its layout tests and the ADR 0008
> note are P012e2. **The emergency button is unchanged in this part** and still covers content.
>
> **Nothing ran on a phone.** Verified with JVM tests (Robolectric) and fakes. The overview's
> zoom is computed, the "user moved the map" signal is taken from the map library's class
> files, and Play services is not present in tests. Screenshots are for Rahul to add.

## 1. Objective

Evidence from Rahul's phone in a small town in Uttar Dinajpur: the map always opens on the
default region (Kolkata) until "my location" is tapped. Open the map on the whole state, and,
when the location permission is already granted, move it once to where the user is.

## 2. Context & prerequisites

- P011e1 merged (PR #35, `79ed6f8`). No open pull requests. Clean tree, hooks active.
- **The prompt's "Depends on: P011e merged" is half true.** P011e1 (server) is merged; P011e2
  (the app sends the user's coarse position as the search area) is not built. The prompt's
  line "search `near` keeps using the same coarse location source" assumes it. Search is left
  as it was: it sends the map's centre (section 7).
- The Plan PDF cannot be read on this machine; the prompt text is the specification.

## 3. Workflow executed

1. `/start-prompt`: main synced, PR #35 confirmed merged, P011e1 set to Merged in Notion,
   branch, Notion page.
2. Read the Home ViewModel and screen, the location repository, the map engine and the fakes.
3. Checked the map library's gesture listener in the artifact's class files (`javap`).
4. Split decided; branch renamed.
5. Code and tests (`9aa1f3f`); ADR 0015, README, diagram, this log; `/ship-prompt`.

## 4. Changes

**`core/map`**

- `RegionDefaults.overview` (new) replaces `RegionDefaults.camera`: the middle of the launch
  region's bounding box at zoom 6. The city camera is removed; no identifier names a place.
- `MapController.userGestures`: how often the user has started to move the map by hand. Set
  from MapLibre's `OnCameraMoveStartedListener` (`REASON_API_GESTURE`) in `MapLibreEngine.kt`,
  the only file that imports MapLibre.

**`core/location`**

- `LocationSource.lastKnown`: the position the phone already has, with its age.
  `FusedLocationSource` reads it from Fused Location's `lastLocation`.
- `DefaultLocationRepository`: asks for it on `start()` when it has no position of its own;
  ignores one older than 10 minutes; otherwise reports it as a current or an old (grey)
  position until a new one arrives.

**`feature/home`**

- `HomeViewModel`: the initial-camera state machine (`Pending`, `Waiting`, `Settled`). One
  move per launch to the user's position at zoom 15, when the permission was already granted
  and a position arrives within 8 seconds. Given up on a user gesture, a tap on "my location",
  a chosen place or route, a missing permission or no position.
- The search area is not shared while the map is zoomed out below 9 (section 7).
- `MyLocation.kt`: the four constants.

**Not changed:** the emergency button and its dialog, the permission flow and the disclosure,
strings (none added or changed), the manifest (no new permission), any backend, contract or
workflow file, dependencies.

**Size:** about 660 changed lines in `android/` (about 440 of them tests), plus documents.

## 5. Diagram

[`docs/diagrams/012e-initial-camera.svg`](../diagrams/012e-initial-camera.svg): the three
states and what ends the wait.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` (in `android/`) | BUILD SUCCESSFUL; 560 tests, 0 failures (539 before); lint 0 errors |
| `tools/diagrams` `pnpm generate` | no errors |
| markdownlint, JSON validity, gitleaks, forbidden files | 94 files, 0 errors · 61/61 tracked JSON valid (and the new diagram files) · no leaks · none |
| `git ls-files android \| grep -iE "local.properties\|\.jks\|google-services\.json"` | prints nothing |

`assembleRelease` was not run: no build file, `src/release` or `src/debug` changed.

New tests:

- `InitialCameraTest` (16): no permission; a permission granted later in Settings; a recent
  last known position; the 10-minute edge; an old one then a new position within 8 s; no
  position then one within 8 s; the timeout; no position can be had; once per launch; outside
  the launch region; a user gesture before the position; a tap on "my location"; a chosen
  place; rotation; process death; nothing stored beside the camera and nothing in Logcat.
- `DefaultLocationRepositoryTest` (4 new): a recent last known position; one of a few seconds
  ago; one older than ten minutes; not asked for without the permission, and never replacing a
  position of this session.
- `MapStateHolderTest` (1 new): only the user's gestures are counted.
- `HomePlaceTest`, `SearchFlowTest`: the search area with the map zoomed in and zoomed out.
- Unchanged and passing: `MapLibreBoundaryTest` (no MapLibre import outside `core/map`),
  `StringResourceParityTest`, `MainActivityTest` (the permission list),
  `MapFailureEmergencyTest`.

**What these tests cannot see:** the real map and its gestures, Play services' answer for the
last known position, how the overview looks on a screen, TalkBack.

**SOS failure matrix (Plan v7 §7.5):** not applicable; no SOS, live-location or contacts code.
The emergency button and dialog are untouched and their tests pass.

## 7. Decisions & ADRs

No new ADR; [ADR 0015](../adr/0015-map-stack-and-location-policy.md) gains "Initial camera".

Choices made here, for Rahul to confirm. Each goes beyond the prompt's wording:

- **The split** into P012e1 and P012e2.
- **Overview zoom 6, not 6.5 to 7.** At 6.5 the state would be about 800 dp tall and cut off
  at the top and bottom of a phone; at 6 it is about 570 dp. This is arithmetic (512 dp for
  the world at zoom 0), not an observation. If the phone shows otherwise, it is one number.
- **The city camera is removed**, not kept beside the overview.
- **A last known position older than 10 minutes is ignored everywhere**, not only for the
  camera. Otherwise an hours-old position from another town could be drawn as the user's dot
  or taken as the start of a route.
- **A recent last known position is drawn** (grey when older than 30 seconds) until a new one
  arrives, like any position after a pause.
- **"First resume of Home" is taken strictly.** A permission switched on in system Settings
  while the app is open does not move the map; the next launch does.
- **Choosing a place or asking for routes also ends the wait**: the camera was moved for a
  reason, and a later jump to the user would undo it.
- **No search area below zoom 9.** Not in the prompt. Without it, a user without the
  permission would search around the geometric middle of the state, and since P011e1 the
  server filters to 50 km around the area it is sent. With no area the server uses its default
  bias and no filter.
- **Search still sends the map's centre**, as before. P011e2 is where the app starts to send
  the position itself.
- **The move is animated** (the map flies from the overview). "At once" is read as "without
  waiting", not "without animation".

## 8. Security & privacy notes

- **No new permission.** The last known position is read only with the permission granted and
  Home visible. The permission is still requested only after a tap on "my location" and the
  disclosure; a test covers "no permission, nothing starts".
- **Nothing new is stored or logged.** The last known position is a field in the repository's
  memory. A test captures Logcat and checks that only the camera is in the saved state.
- **The saved camera holds the position after the automatic move**, as it already does after a
  tap on "my location": the camera is saved so the map reopens where it was. It is the
  ViewModel's saved state, held by the system, not a file or a log of this app.
- **One real change in what leaves the phone, without a new code path.** After the automatic
  move the map's centre is the user's position, and a search sends that centre, rounded to two
  decimals (about 1 km), to SafeRoute's server and the geocoding provider. Before, that needed
  a tap on "my location". The disclosure says that for showing the location "nothing is sent
  to SafeRoute's servers". Whether it must mention search is **to be verified by a lawyer**,
  with the P011e2 disclosure change. Recorded in ADR 0015 and as a High follow-up.
- No Bengali strings were added or changed in this part.
- No secrets, keys or local paths in the diff; `google-services.json` and the user-level
  `gradle.properties` were not opened.

## 9. Known issues & risks

- **Never seen on a phone.** The overview's fit, the gesture signal and the last known
  position are untested on a device.
- **The overview is a fixed camera.** On tablets or in landscape it may show too much or too
  little.
- **Requirement 2 is not in this part**: the large emergency button still covers content.
- **Without the permission, search now has no area** (zoomed-out map), so results come from
  the server's default bias, which is still a city centre. Better than a filter around the
  middle of the state, but not good for someone far from that city.
- **A stale comment in the backend** says the default search centre is where the app's map
  opens. Not changed: this prompt is Android only.
- **`CLAUDE.md` still describes the search area as "the map's centre"**, which is still true;
  it does not mention the automatic move. Left for P011e2, which rewrites that rule.
- Unchanged from earlier parts: nothing in P012 has run on a phone except Rahul's own checks;
  P012c2 is open.

## 10. Follow-ups & prerequisites for next prompt

Notion *Follow-ups*, new (source P012e1):

- P012e2: compact SOS control that never covers content (High).
- Check where the map opens on a phone, with screenshots (High).
- Lawyer: the map centre is the user's position without a tap, and search sends it rounded
  (High).
- Backend comment: the default search centre is no longer where the app's map opens (Low).
- Fit the region overview to the screen instead of a fixed zoom (Low).

Not created here, because they belong to P012e2: the Bengali review of changed strings, and
"P014 replaces the control's behaviour with hold-to-arm". Both are in the P012e2 follow-up's
notes.

**Next: P012e2** (proposed branch `fix/012e2-compact-sos-control`). Also open: P011e2 and
P012c2. None is started.

## 11. How Rahul can verify

1. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug`.
2. CI green, squash and merge. Nothing deploys (no `backend/` change).
3. On the phone, with a build from `main`. Please add screenshots to the pull request (light,
   dark, Bengali, large font) of steps 3.1 and 3.2:
   1. **Permission denied** (system Settings → Apps → SafeRoute → Permissions → Location →
      Don't allow), then swipe the app away and open it: the map shows **the whole of West
      Bengal**, not a city. Is the state fully visible between the search pill and the sheet?
      If it is cut off or too small, say so: the zoom is one number.
   2. **Permission allowed**, swipe the app away and open it: the map opens on the state and
      moves **once** to where you are, at street level. No permission dialog appears.
   3. Do it again and **drag the map at once**, before it moves: it must stay where you put it.
   4. After it has moved, drag the map away and wait: it does not jump back. Turn the phone:
      the map stays where it was.
   5. Open the app, go to another app for a minute, come back: the map is where you left it.
   6. **First launch** (clear the app's storage, sign in again): no location dialog appears at
      any point before you tap "my location"; the map shows the state.
   7. Indoors with a weak signal: within about 8 seconds it either moves or stays on the state;
      it never moves later by itself. The dot may still appear later.
   8. With the map on the state overview, search "station": results come (from the default
      area). After it has moved to you, search "bank": nearby places come first (P011e1).
   9. The red 112 button is visible and opens the dialer in every state above.

## 12. Learning notes

- **`SavedStateHandle` as "have I been here before?"** Android can kill the app's process in
  the background and later rebuild the screen. What a ViewModel put in its `SavedStateHandle`
  comes back; everything else is new. The saved camera therefore tells a fresh launch (nothing
  saved) from a return (a camera saved), and that decides whether the map may move by itself.
  [Saved state](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-savedstate)
- **Rotation is not a restart for a ViewModel.** Turning the phone destroys and rebuilds the
  screen (the activity), but the ViewModel stays alive. That is why "once per launch" is a
  field in the ViewModel and survives rotation without any saving.
- **Last known location.** The phone keeps the most recent position any app or the system
  obtained. Reading it starts no GPS and costs no battery, so it arrives at once; but it can
  be hours old, which is why its age is checked.
  [Last known location](https://developer.android.com/develop/sensors-and-location/location/retrieve-current)
- **`elapsedRealtime`, not the wall clock.** The age of a position is measured with the clock
  that counts since the phone was switched on. The wall clock can jump (time zones, the user
  changing the time), and an age computed from it could be negative or hours wrong.
- **A timeout as a coroutine.** "Wait up to 8 seconds" is `launch { delay(8_000); giveUp() }`,
  and cancelling that `Job` when the position arrives. In the tests the 8 seconds pass
  instantly (`advanceTimeBy`), so both sides of the edge are checked exactly.
- **Telling a finger from the app.** The map reports every camera move. MapLibre also says why
  a move started; only moves that a gesture started count as "the user took over". The rest
  of the app sees that as a plain counter and knows nothing about MapLibre.
