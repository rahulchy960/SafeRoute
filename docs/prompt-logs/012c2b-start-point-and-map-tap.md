# P012c2b: follow-me navigation, part 2: a start of the user's choice, and routes chosen on the map

| Field | Value |
| --- | --- |
| Prompt | P012 · part c2, second half (**P012c2b start point and map tap**; P012c2a was following a route) |
| Milestone | M4 (depends on P012c2a, merged as `c797359`) |
| Branch | `feat/012c2b-start-point-and-map-tap` |
| PR title | `feat(android): choose a start point and select a route by tapping it on the map [P012c2b]` |
| Notion | [P012c2b row in the Prompt Log](https://app.notion.com/p/3f3073707720812e8bc1d04fa9470ed4) |
| Date | 2026-10-08 |
| Plan refs | Plan v7 §3.2 (F-04), §5.3; ADRs 0008, 0015, 0018, 0020, 0022 |

> **Nothing ran on a phone.** One step in particular cannot be tested without one: turning the
> routes' points into screen pixels when the map is tapped (section 9).

## 1. Objective

Two things P012c2 asked for and P012c2a left out: a route may start at a place the user picks
in search, and an alternative route can be chosen by tapping its line on the map.

## 2. Context & prerequisites

- P012c2a (pull request #42) merged as `c797359` before this started.
- Search and Home already talk through `MapSelection`; directions and Home through
  `RouteDisplay`. Both stay the only connections.
- ADR 0022, decision 3: following starts only with a precise, recent position of the user.

## 3. Workflow executed

1. `/start-prompt`: main at `c797359`, clean tree, hook active; P012c2a's Notion row set to
   Merged; branch and Notion page created.
2. The hit test and its tests; the tap's way from the map to the list; the start point's way
   from search to directions; the sheet; strings.
3. `./gradlew lint testDebugUnitTest assembleDebug`; local commit of the code.
4. A note in ADR 0022, the diagram updated, `CLAUDE.md`, this log; repo checks; push; pull
   request; Notion.

One change of mind on the way: "Change start" was first a row of its own under the title.
That pushed the first route card and Start below the visible half of the sheet on a small
screen and broke five tests that said so. It is now part of the "From ..." line in the header.

## 4. Changes

Android only: 304 changed lines outside tests, 564 in tests. No backend, contract,
permission, dependency or build-file change.

- **`core/map/RouteHitTest.kt` (new).** `nearestLine`: plain arithmetic on screen pixels. The
  nearest line within the tolerance wins; where two are equally near (a shared road), the one
  listed later, which is the one drawn on top.
- **`core/map/MapTypes.kt`, `MapStateHolder.kt`.** `MapController.routeTaps`: the overlay id of
  a tapped route. A tap anywhere else reports nothing.
- **`core/map/MapLibreEngine.kt`.** On a tap, the route overlays' points are turned into
  pixels with the map's own projection and handed to `nearestLine`, with 24 dp as tolerance.
  The travelled part of a followed route is not a target.
- **`core/map/RouteDisplay.kt`.** `routeIdOf(overlayId)`, and `refit()` for Preview.
- **`feature/home/HomeViewModel.kt`.** A tap selects the route in `RouteDisplay`, unless a
  route is followed.
- **`feature/directions/DirectionsViewModel.kt`.**
  - It follows `RouteDisplay`'s selection, so the list and the map agree whichever was tapped.
  - `DirectionsUiState.Open.origin`: null is the user's location, otherwise the chosen place.
  - `onChangeStartClick` marks the next search as a choice of start; the place chosen there
    becomes the origin and the routes are asked for again. `onUseMyLocationAsStart` switches
    back. `onStartClick` does nothing while a start is chosen; `onPreviewClick` shows the
    route whole.
- **`core/map/MapSelection.kt`.** `choosingStart` and `chosenStart`; `choose(place)` sends a
  search result to the right one of the two. Search resets the flag when it is left.
- **`feature/search/`.** The search field says "Search for a start point" in that mode.
  Nothing else about search changes: same request, same area, same privacy rules.
- **`feature/directions/DirectionsSheet.kt`.** The "From ..." line is one 48 dp row that says
  "Change start"; with a chosen start: Preview, the sentence why, and "Start from my
  location". The selected card is brought into view.
- **Strings:** 6 new, English and Bengali.
- **Docs:** a dated note in ADR 0022, the diagram, `CLAUDE.md`.

## 5. Diagram

[`012c2-follow-me-states.svg`](../diagrams/012c2-follow-me-states.svg), updated: routes are
selected by card or by a tap on the line, and a route from a chosen start is preview only.

## 6. Quality gate & test results

| Command | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` (in `android/`) | BUILD SUCCESSFUL; 714 tests, 0 failures (695 before); lint 0 errors, 3 warnings (as before) |
| `pnpm generate` (in `tools/diagrams/`) | no errors |
| markdownlint, JSON validity, gitleaks | see the pull request |
| `./gradlew assembleRelease ...` | not run: no build file, `src/release` or `src/debug` changed |

19 new tests:

- **`RouteHitTestTest` (8):** a tap on a line; just inside and just outside the tolerance;
  beyond the end of a line; the nearer of two; a shared road; empty and degenerate lines; the
  24 dp; overlay ids; a tap's way through the controller.
- **`StartPointTest` (7):** the chosen place becomes the start and routes are asked for again;
  switching back; a chosen start needs no location; Start refused and Preview offered; Change
  refused without routes or while following; the real `SearchViewModel` in start mode; a tap
  on a line selects in the list and on the map and moves nothing; nothing logged.
- **`StartPointFlowTest` (3):** the real `MainActivity`: Change start → search → a place →
  routes from it → Preview → back to the user's location; leaving search without choosing; a
  tap on a line selects its card.
- **`FollowScreenTest` (1 new):** the sheet with a chosen start, touch targets included.

Two older assertions changed from "is displayed" to "exists" (`FollowFlowTest`, one line; and
one in the new flow test): on the 640 dp test screen those lines are in the part of the sheet
that is pulled up. See section 9.

**"Nothing is logged" covers:** Logcat as Robolectric captures it, and `toString()` of the
state. Not the network.

**SOS failure matrix (Plan v7 §7.5):** no row is changed. The flow test checks the SOS
control is on screen with a chosen start.

## 7. Decisions & ADRs

Recorded as a note in [ADR 0022](../adr/0022-follow-me-navigation.md). In short:

- **A route from a chosen start is previewed, never followed.** The user is not on it.
- **The controller reports the overlay id** of the tapped route; `routeIdOf` gives the route.
  The map component knows overlays, not routes.
- **The list follows `RouteDisplay`**, not the other way round only: one place holds "which
  route is selected", and both the card and the tap write to it.
- **Search learns its purpose from `MapSelection`**, not from a navigation argument: the
  search screen stays one destination, and no place or mode travels in a route string.
- **A place chosen as start does not become the place on the map.** The destination, its pin
  and its card stay.
- **Changing the mode keeps the chosen start.** Closing directions forgets it.
- **Preview lowers the sheet and fits the route**, and does nothing else.

## 8. Security & privacy notes

- **No new permission, storage or network call.** A chosen start is sent only as the start of
  the routes request the user asked for (POST body, as before).
- **A chosen start needs no location permission**: routes between two chosen places work with
  location denied, and nothing about the user's position is sent then.
- **Memory only.** The chosen start is a field of the directions state; it is not written to
  the saved state, to disk or to a log, and `toString()` hides it (tested).
- **Search in start mode** sends what a search always sends (ADR 0018): the text and the
  rounded area. Nothing is added.
- **Bengali strings needing human review before release:** the 6 new ones.

## 9. Known issues & risks

- **Not seen on a phone.**
- **The tap itself is untested.** `nearestLine` is tested in pixels; the conversion from map
  points to pixels is MapLibre's (`projection.toScreenLocation`) and runs only on a device.
  Unknown until checked: how it behaves for points far off screen, with the map rotated, and
  whether a tap on a line also triggers anything else on the map.
- **Routes with thousands of points** are converted on every tap. Expected to be fast enough;
  not measured.
- **Small screens:** at half height the sheet shows less than before by one line's worth; the
  reasons under Start and the Preview sentence can sit below the fold (follow-up).
- **Start from a chosen place and "Start"**: someone standing at the place they chose still
  gets Preview. They can switch to "Start from my location".

## 10. Follow-ups & prerequisites for next prompt

Notion *Follow-ups*, new (source P012c2b):

- Phone check: choose a start point, select a route by tapping its line (High).
- Bengali review: start point strings.
- Directions sheet: content below the half-height fold on small screens (Low).

Closed: "P012c2b: choose a start point, select a route by tapping it on the map" (source
P012c2a).

**Next:** P013 (`feat/013-emergency-contacts`). P012c2 is complete with this pull request.

## 11. How Rahul can verify

1. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug`.
2. CI green, squash and merge. Nothing deploys.
3. On the phone, against staging. Note the phone and the Android version:
   1. Search a place, Directions. The line under the title reads "From your location ·
      Change start". Two or three routes are on the map.
   2. **Tap the grey line of another route.** It becomes the strong one and its card is
      selected. Try a tap right on the line, a finger's width beside it, and clearly away
      from it (nothing should happen). Try it zoomed out, zoomed in and **with the map
      rotated**. Where two routes share a road, a tap there keeps the selected one.
   3. Tap a card: the map follows, as before.
   4. Tap **Change start**: search opens and its field says "Search for a start point".
      Choose a place. Back on the map: "From (that place)", routes from there, the same
      destination, and **Preview** instead of Start with one sentence why.
   5. **Preview**: the sheet steps down and the map shows the whole route.
   6. **Start from my location**: routes from where you are again, and Start is back.
   7. Change start, then leave search with Back: nothing changed. Open search from the pill:
      an ordinary search.
   8. **With location denied** for SafeRoute: Directions says it needs your location; choose
      a start with Change start and routes appear all the same.
   9. While following a route: no "Change start", and taps on the map select nothing.
   10. Bengali, the largest font size, dark mode, TalkBack ("From ..., Change start" is one
       button).

## 12. Learning notes

- **A tap on a map is two coordinate systems.** The map knows places (latitude, longitude);
  the finger lands on pixels. The map's *projection* converts between them for the current
  zoom, rotation and position. Comparing in pixels makes "24 dp from the line" mean the same
  on screen at every zoom.
- **dp and px.** A dp is a size that looks the same on every screen; pixels are what the
  screen has. `density` converts: 24 dp is 72 px on a screen with density 3.
  [Support different pixel densities](https://developer.android.com/training/multiscreen/screendensities)
- **`SharedFlow` for events, `StateFlow` for state.** A tap is something that happens, not
  something that is: a `StateFlow` would drop a second tap on the same route (equal value)
  and replay the last one to a new listener. A `SharedFlow` does neither.
  [StateFlow and SharedFlow](https://developer.android.com/kotlin/flows/stateflow-and-sharedflow)
- **Screens that do not know each other.** Search and directions have separate ViewModels.
  They share a small object that lives as long as the activity (`@ActivityRetainedScoped`);
  one writes, the other listens.
  [Hilt component scopes](https://developer.android.com/training/dependency-injection/hilt-android#component-scopes)
- **Merged semantics.** A clickable row with two texts is one element for TalkBack and for
  tests: "From your location, Change start, button". That is also why it counts as one 48 dp
  touch target.
  [Semantics in Compose](https://developer.android.com/develop/ui/compose/accessibility/semantics)
- **`BringIntoViewRequester`** asks the nearest scrolling parent to scroll until an element is
  visible; used so that a card selected from the map is not left out of sight.
