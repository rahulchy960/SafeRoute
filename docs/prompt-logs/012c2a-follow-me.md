# P012c2a: follow-me navigation, part 1: Start, progress, off-route and arrival

| Field | Value |
| --- | --- |
| Prompt | P012 · part c2, first half (**P012c2a following a route**; P012c2b brings the choice of start point and route selection on the map) |
| Milestone | M4 (depends on P012c1 and P011e2, both merged; P011e2 as `5bfb212`) |
| Branch | `feat/012c2a-follow-me` |
| PR title | `feat(android): follow-me mode with off-route and arrival, foreground only [P012c2a]` |
| Notion | [P012c2a row in the Prompt Log](https://app.notion.com/p/3f307370772081c0b37ad194a537a835) |
| Date | 2026-10-08 |
| Plan refs | Plan v7 §3.2 (F-04), §5.3, §7.5; ADRs 0008, 0015, 0020, 0021, 0022 |

> **Nothing ran on a phone.** Everything below was checked with JVM tests (Robolectric) on fake
> positions and virtual time. Whether it works on a street, and what it costs in battery, is
> the checklist in section 11.

## 1. Objective

A route can be followed: "Start" keeps the map on the user, shows how far and how long is left
and when that is on the clock, notices leaving the route and arriving, and offers a new route
on a tap. All of it on screen only, with nothing stored.

## 2. Context & prerequisites

- P012c1 showed routes; P012e2 gave the SOS control its fixed places; P011e2 was merged before
  this started (the first `/start-prompt` stopped because its pull request was still open).
- The routing API returns a line, a duration and a distance per route, and no steps.
- ADR 0015: location is foreground only. This prompt adds no permission and no service.
- **Split.** P012c2 allows it, and it was needed:
  - **P012c2a (this):** progress arithmetic, Start, following, the banner, off-route,
    Recalculate, arrival, ending, ADR and diagram.
  - **P012c2b (next):** "From: your location" with "Change" (choose a start in search), Start
    only from the current location, selecting a route by tapping its line on the map.

## 3. Workflow executed

1. `/start-prompt`: main at `5bfb212`, clean tree, hook active; P011e2's Notion row set to
   Merged; branch and Notion page created; directions, Home, map and location code read.
2. `RouteProgress.kt` and its tests first, then the state in `DirectionsViewModel`, then the
   camera in `HomeViewModel`, then the banner and the screen.
3. `./gradlew lint testDebugUnitTest assembleDebug` after each part; local commit of the code.
4. ADR 0022, the diagram, `CLAUDE.md`, this log; repo checks; push; pull request; Notion.

Three things the tests found on the way:

- **A hang.** Following ticks once a second for as long as it lasts. In virtual time a test
  waits for all pending work at its end, so a test that left following running never ended.
  Each such test now closes directions at its end (`followTest`).
- **A stuck projection.** On a road walked out and back, a fix on the way back was matched to
  the same spot on the way out, for ever. The nearest point is now limited to the stretch
  being searched, not only the segments that touch it.
- **A hidden reason.** When following ended because the permission was gone, the sentence
  saying so was in the sheet, which had stepped down to its peek height. The sheet now comes
  back up when following ends.

## 4. Changes

**Size:** 930 changed lines outside tests and 1,297 in tests, 2,227 in all. That is well over
the ~800 guideline even after the split. The arithmetic, its state and the screen that shows
it could not be reviewed apart: one without the others does nothing.

Android only. No backend, contract, permission, dependency or build-file change.

- **`feature/directions/RouteProgress.kt` (new).** `RouteTracker`: plain Kotlin, no Android,
  no clock of its own. The rules are in ADR 0022, section "Decision", 4 to 6.
- **`feature/directions/DirectionsViewModel.kt`.**
  - `DirectionsUiState.Open` gains `follow: FollowState?` (numbers and flags, no position) and
    `startProblem`.
  - `onStartClick`: precise location and a position from the last 10 s, otherwise
    `StartProblem.NoPermission`, `NeedsPrecise` or `NoRecentFix`. It requests nothing itself.
  - While following: each new fix goes to the tracker; a ticker says "Searching for GPS" after
    10 s without one; `onRecalculate` asks once, from the current position, with a 20 s limit;
    `onBackground` / `onForeground` pause and continue; `onLocationPermissionLost` and an
    approximate fix end it with the reason; `onEndClick` asks, `onEndConfirm` ends.
  - The constructor takes the app's `Clock`.
- **`core/map/RouteDisplay.kt`.** `FollowView` (travelled line, position, bearing) and
  `RouteDisplay.following`. `routeOverlays` draws only the followed route, with the travelled
  part on top. `MapOverlay.Route.travelled`: as wide as the selected line, in the muted colour.
- **`feature/home/HomeViewModel.kt`.** The camera goes to the user at zoom 17 and turns with
  the direction of travel (north up while standing still). A pan, zoom or rotation by hand
  leaves it alone until `onRecentre`. A recalculated route does not refit the map. When
  following ends, north is up again.
- **`feature/directions/FollowBanner.kt` (new).** The banner, the "End navigation?" dialog and
  `KeepScreenOn`.
- **`feature/home/HomeScreen.kt`.** The banner takes the search pill's place while following
  and is limited to 40% of the screen's height; "Re-centre" joins the map controls; the
  placeholder Layers button is left out while following; back asks before ending; the sheet
  steps down to peek at Start and back to half at the end.
- **`feature/directions/DirectionsSheet.kt`.** "Start" above the route list, with the reason
  and its way out when following cannot begin. While following the sheet shows the destination
  and the SOS control only.
- **Strings:** 20 new, English and Bengali (`route_start`, `route_start_*`, `route_follow_*`).
- **Docs:** ADR 0022, the diagram, a rule in `CLAUDE.md` ("Android rules"), the ADR index.

**Not changed, and why:** the location disclosure and the "How routes work" note. Following
runs on the phone. Recalculate sends the current position as the start of a route, which the
note describes already ("your start and your destination are sent"). Whether the disclosure
should name following is a follow-up for the lawyer.

## 5. Diagram

[`012c2-follow-me-states.svg`](../diagrams/012c2-follow-me-states.svg)
(source [`012c2-follow-me-states.json`](../diagrams/012c2-follow-me-states.json)): the states
of following and every way out of it.

## 6. Quality gate & test results

| Command | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` (in `android/`) | BUILD SUCCESSFUL; 695 tests, 0 failures (646 before); lint 0 errors, 3 warnings (as before: target API and a dependency version) |
| `pnpm generate` (in `tools/diagrams/`) | no errors |
| markdownlint, JSON validity, gitleaks | see the pull request |
| `./gradlew assembleRelease ...` | not run: no build file, `src/release` or `src/debug` changed |

49 new tests:

- **`RouteTrackerTest` (17):** a straight line; bends; remaining time; jitter never moves
  progress back; a single jump far away; a loop; two legs 20 m apart; out and back on one
  road; the 50 m and 10 s rule; return to the route; the limit growing with accuracy; fixes
  worse than 50 m decide nothing; a shortcut that rejoins; arrival; the app having been away;
  an empty route; nothing prints a position.
- **`FollowRouteTest` (14):** Start, and each reason it does not start; numbers, travelled
  line and bearing; "Searching for GPS" after 10 s; off-route after 10 s with no request sent
  by itself; arrival; permission or precise location lost; background and return; End with
  Cancel and End; closing and another place; Recalculate (success, three taps one request,
  429, 503, offline, no answer for 20 s); nothing logged.
- **`FollowCameraTest` (5):** camera and bearing; a pan and Re-centre; what is drawn; no refit
  on a new route; the end of following.
- **`FollowScreenTest` (10):** the banner; each line and its live region; arrival; the End
  dialog; the screen kept on only while following, across each way out; Start and its
  reasons; Re-centre; the banner clear of the SOS control at double font size, in English and
  in Bengali.
- **`FollowFlowTest` (3):** the real `MainActivity`: Start, leaving and returning, back, End;
  the permission taken away; approximate location.

Covered by tests that existed: string key parity and the banned words in both languages
(`DirectionsScreenTest` reads every `route_` string), MapLibre only in `MapLibreEngine.kt`
(`MapLibreBoundaryTest`), the permission list (`MainActivityTest`).

**"Nothing is logged" covers:** Android's log (Logcat, as Robolectric captures it) and what
`toString()` of the state would print. It cannot see the network; Recalculate uses the same
repository call as a first request (`ApiRouteRepositoryTest`).

**SOS failure matrix (Plan v7 §7.5):** no row is changed. The SOS control and the 112 flow are
untouched; tests check that the control stays visible, tappable and uncovered while following.

## 7. Decisions & ADRs

[ADR 0022](../adr/0022-follow-me-navigation.md) (Accepted) holds the rules. Smaller choices:

- **The follow state lives in `DirectionsViewModel`**, not in a ViewModel of its own: it needs
  the selected route, the destination, the mode and the repository, which are all there.
- **Start accepts a position at most 10 s old**, the same number as "Searching for GPS".
- **Arrival and off-route ignore fixes worse than 50 m**, both. The prompt names the rule for
  off-route; applying it to arrival too keeps a 500 m fix from "arriving" from far away.
- **Arrival is about the place.** Within 30 m of the end counts however the user got there.
- **The pause note is shown once per followed route**, with an OK button, not on every return.
- **A rotation is not "leaving the screen"** (`isChangingConfigurations`), so it shows no note.
- **A failed Recalculate is not retried by itself**, also not for "service starting": the tap
  is the request. The sentence says to try again.
- **The travelled part differs from the rest by colour**; the location dot marks where one
  ends and the other begins, so colour is not the only signal.
- **The Layers placeholder is hidden while following**, to keep room between the banner and
  the controls at large font sizes.
- **Search and Settings are not reachable while following** (the banner is in their place).
  End, then search.

## 8. Security & privacy notes

- **No new permission, service, notification, dependency or network call.** The manifest is
  unchanged.
- **Foreground only.** Location updates stop when Home leaves the screen, as before; following
  reads nothing in the background.
- **Memory only.** The route, the progress and the positions are fields of objects. Nothing is
  written to disk, to the saved state or to a log. After process death there is no route.
- **What leaves the phone:** nothing by itself. Recalculate, on a tap, sends the current
  position and the destination to SafeRoute's API in a POST body, like a first route request.
- **The screen is kept on** only while following (`KeepScreenOn`), and released on every way
  out, including the screen being left.
- **Wording:** distance, time, clock time. No safety, risk or traffic words (tested).
- **To be verified by a lawyer:** whether the location disclosure should name following.
- **Bengali strings needing human review before release:** the 20 new ones.

## 9. Known issues & risks

- **Not seen on a phone.** The thresholds are first values.
- **Locking the phone or opening another app stops following** until the user returns. That
  is the decision (ADR 0022), and it is a real limit for someone who pockets the phone.
- **Battery.** One position a second and the screen on. Not measured.
- **Rejoining far ahead** after leaving the route is found late; until then the banner shows
  "off the route".
- **Direction of travel comes from GPS** and is absent below walking pace: the map turns
  north-up at every stop. It may feel restless at traffic lights.
- **The camera moves once per fix** with the map library's animation; whether that looks
  smooth is a phone check.
- **PR size** (section 4).

## 10. Follow-ups & prerequisites for next prompt

Notion *Follow-ups*, new (source P012c2a):

- Phone check of follow-me navigation (High).
- P012c2b: choose a start point, select a route by tapping it on the map (High).
- Lawyer: does the location disclosure need to name following a route and Recalculate? (High)
- Follow-me: tune thresholds and battery use from phone checks.
- Bengali review: follow-me strings.
- Turn-by-turn instructions (needs steps in the routing API and a contract change).
- Voice guidance · Automatic rerouting · Stops along a route · Background navigation with a
  foreground service (Play declaration).

**Next:** P012c2b, on a branch `feat/012c2b-...`, after this pull request is merged. Then P013.

## 11. How Rahul can verify

1. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug`.
2. CI green, squash and merge. Nothing deploys.
3. On the phone, against staging. **Write down the phone, the Android version and the battery
   percentage before and after**, and whatever differs from what is described:
   1. **A walk of 5 to 10 minutes.** Search a place nearby, Directions, Walking, **Start**.
      The sheet steps down, the banner shows distance, time and "Arrive around ...". The map
      follows you and turns with your direction; the line behind you turns grey.
   2. **Pan the map.** It stays where you put it and "Re-centre" appears. Tap it.
   3. **Leave the route** (a side street, more than 50 m). After about 10 seconds: "You seem
      to be off the route" with Recalculate. Note how long it took and how far you were.
   4. **Go back to the route** without tapping: the line disappears by itself.
   5. Leave again and tap **Recalculate**: a new route, and following goes on.
   6. **Arrive.** Note how far from the place "You've arrived" came. Done closes directions.
   7. **Lock the phone** for a minute while following, unlock: the note that navigation had
      paused, once. Does the position catch up?
   8. **Screen:** it stays on while following. After End, after arriving and after leaving the
      app it switches off as usual.
   9. **Back** while following: "End navigation?". Cancel keeps it; End returns to the routes.
   10. **Approximate location only** (system Settings → Apps → SafeRoute → Permissions →
       Location → "Use precise location" off): Start explains and offers precise location.
   11. **Take the permission away while following** (same place, "Don't allow"), return to the
       app: following has ended and the sheet says what is needed.
   12. **Indoors, GPS lost:** after about 10 seconds "Searching for GPS"; the route stays.
   13. **Aeroplane mode, then Recalculate** while off the route: "You are offline", and the
       old route stays. Switch it off and tap again.
   14. **SOS control:** visible and tappable in every state above; the 112 dialog opens.
       **Do NOT press call.** The Quick Settings tile and the notification still work.
   15. **Driving**, as a passenger: the same checks on one short trip. Is the camera calm?
   16. Dark mode. Bengali. The largest font size (the banner scrolls inside itself; the SOS
       control is never covered). TalkBack: the banner is read first, and "off the route",
       "Searching for GPS" and "You've arrived" are announced when they appear.

## 12. Learning notes

- **Keeping the screen on.** Every Android view has a `keepScreenOn` property; while such a
  view is visible the screen does not time out. No permission is needed (unlike a "wake
  lock"), and it ends by itself when the view goes away. Compose's `DisposableEffect` sets it
  and guarantees the matching "unset" (`onDispose`) on every way out.
  [Keep the device awake](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on)
- **Why following stops when the app leaves the screen.** Android lets an app read location in
  the background only with an extra permission or a foreground service (a visible, running
  notification). SafeRoute has neither, on purpose (ADR 0015, ADR 0022). `LifecycleStartEffect`
  runs code when the screen becomes visible and when it stops being visible.
  [Background location limits](https://developer.android.com/about/versions/oreo/background-location-limits)
- **A rotation rebuilds the screen.** Android destroys and recreates the activity; the
  ViewModel survives. `isChangingConfigurations` tells the two kinds of "stop" apart.
  [Handle configuration changes](https://developer.android.com/guide/topics/resources/runtime-changes)
- **Pure logic in a plain class.** `RouteTracker` uses no Android class, so its tests run in
  milliseconds on the JVM and can try a loop or a U-turn with a few numbers.
- **Virtual time.** With `StandardTestDispatcher`, `delay(1000)` does not wait: the test moves
  the clock (`advanceTimeBy`). A loop that never ends must be cancelled before the test ends.
  [Testing coroutines](https://developer.android.com/kotlin/coroutines/test)
- **Live regions.** A text marked `liveRegion` is read by TalkBack when it appears or changes,
  without the user moving focus to it. "Polite" waits for the current sentence to finish.
  [Semantics in Compose](https://developer.android.com/develop/ui/compose/accessibility/semantics)
- **The back button.** `BackHandler` lets a screen take the system back gesture while a
  condition holds; the last one declared wins. Here it asks before ending.
  [Predictive back and BackHandler](https://developer.android.com/develop/ui/compose/system/predictive-back)
