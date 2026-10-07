# P012c1: directions and route alternatives

| Field | Value |
| --- | --- |
| Prompt | P012 · part c, **split in two**: **P012c1 directions and alternatives** (this log), P012c2 follow-me |
| Milestone | M4 (depends on P012b, merged as `681920f`) |
| Branch | `feat/012c1-directions` |
| PR title | `feat(android): directions and route alternatives [P012c1]` |
| Notion | [P012c1 row in the Prompt Log](https://app.notion.com/p/3f207370772081148a36ee36a318924b) |
| Date | 2026-10-08 |
| Plan refs | Plan v7 §3.2 F-04, §12.2, §14.3; addenda v7.1 to v7.3; ADRs 0008, 0009, 0015, 0019, 0020 |

> **Not in this pull request (P012c2):** follow-me mode (camera following, progress, off-route
> banner, Recalculate, arrival, keeping the screen on, the battery note), choosing a route by
> tapping the map, and "Choose start" through Search.
>
> **Nothing ran on a phone or against staging.** Claude Code has no device. Everything here is
> verified with JVM tests (Robolectric) and fakes: the route lines of the real map, the camera
> fit, TalkBack and the real server's answers are checks for Rahul (section 11).

## 1. Objective

Let a signed-in user get walking or driving routes from their position to a place chosen on
the map: up to three alternatives in the sheet and on the map, a calm message for every
reason there are none, and a wait for a sleeping routing service that always ends.

## 2. Context & prerequisites

- **C0:** PR #32 (P012b) is merged as `681920f`; its `deploy-staging` run shows `success`
  (a first run on the same commit failed, a second succeeded). The client was regenerated from
  the committed contract 0.6.0: `RoutingApi.createRoutes(RouteRequest): Response<Routes>`
  exists. Rahul confirmed that `POST /v1/routes` without a token answers 401 on staging.
- Guardrails given with the prompt and applied: no risk label, "safe" wording, safety score
  or traffic claim in the route UI; "My location" is the origin; no saved places, journeys,
  circles or arrival alerts.
- No addendum v7.4 is in the repository; nothing here depends on one.

## 3. Workflow executed

1. `/start-prompt`: main synced, PR #32 confirmed merged, Notion P012b set to Merged,
   branch, Notion page.
2. C0: `./gradlew :app:generateApiClient`, `createRoutes` confirmed.
3. Decided the split (below), renamed the branch to `feat/012c1-directions` before any push.
4. Map abstraction, directions feature, Home wiring, strings, tests (`1986f42`).
5. Lint fix, Android README, ADR 0020 note, diagram, this log; `/ship-prompt`.

## 4. Changes

| Area | What |
| --- | --- |
| `feature/directions/` (new) | `RouteRepository` + `ApiRouteRepository` (request in a POST body; every problem code mapped; one request, no retry), `Polyline6.kt` (decoder), `DirectionsViewModel` (states, one request at a time, bounded wait), `DirectionsSheet.kt` (panel, route cards, the one-time note), `RoutePreferences` (mode and "note read" in DataStore), `di/DirectionsModule` |
| `core/map/` | `MapOverlay.Route`, `MarkerStyle.RouteStart`, `LatLngBounds`, `MapController.fitBounds`, route features in the overlay GeoJSON (casing + line, width and colour per feature), `RouteDisplay` (what Directions and Home share). Two route layers and `fitBounds` in `MapLibreEngine.kt`, still the only file that imports MapLibre |
| `feature/home/` | Directions on the place card is enabled; the sheet shows the directions panel and rises to half when it opens; back closes directions first; Home draws routes under the location dot and the pin and fits the camera once per answer |
| `core/network/` | `RoutingApi` provided; five routing problem codes named |
| Strings | 32 new names (31 strings and one plural) in `values/` and `values-bn/`; `place_card_directions_unavailable` removed; `location_disclosure_why` now names directions |
| Docs | Android README "Directions", ADR 0020 note, diagram |

No new dependency, no new permission, no manifest change. No backend or contract change.

**Deviations from the prompt:**

- **Part c is split.** This half is about 1 100 lines of app code and 1 400 of tests; with
  follow-me it would be roughly double. Follow-me is a separate piece of logic (projection,
  timers, screen-on) with its own manual checks, so the cut is clean. P012c2 also takes
  "tap the map to choose a route" (it needs tap handling in the map engine) and "Choose
  start" through Search.
- **Without a position the sheet offers "Use my location" only**, which is the map's own
  button and permission flow. "Choose start" is P012c2.
- **Route colours reuse existing tokens**: the selected route is the location blue, the
  alternatives the stale grey, the casing white. No new colour, and never the SOS red or a
  green.
- **The location disclosure changed by one sentence** (it said routes would come "later").
  `CLAUDE.md` requires that in the prompt that changes what location is used for; the prompt
  itself only asked for a lawyer follow-up, which is recorded too.
- **The sheet rises to half when directions open.** Not asked for; without it the results
  would sit below the fold.
- **The mode toggle is two radio rows**, not a segmented button: Material 3's segmented
  button is still experimental, and experimental APIs are not allowed in production code.

## 5. Diagram

[`docs/diagrams/012c-directions-flow.svg`](../diagrams/012c-directions-flow.svg): the states
from the place card to routes, the bounded wait, and the two kinds of "no routes".

## 6. Quality gate & test results

| Command | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` | BUILD SUCCESSFUL; **539 tests, 0 failures** (488 before); lint 0 errors |
| `./gradlew assembleRelease -Psaferoute.apiBaseUrl=https://example.invalid/` | BUILD SUCCESSFUL (release guards for the API URL, Firebase file and map key ran) |
| `git ls-files android` for `local.properties`, `.jks`, `google-services.json` | nothing |
| `pnpm generate` · `pnpm check` in `tools/diagrams` | 19 diagrams, up to date |
| markdownlint-cli2 · JSON validity · gitleaks 8.30.1 | 89 files, 0 errors · 59 files valid · no leaks |

What the new tests prove (all on the JVM, with fakes):

| Tests | What they prove |
| --- | --- |
| `DirectionsViewModelTest` (19, virtual time) | closed at start; the note once, and nothing sent before Continue; origin = my position; alternatives; mode switch remembered; a new request cancels the old one; double taps send one request; each error state; **a sleeping service: the message, the wait of exactly `Retry-After`, the retry; at most two automatic retries, then Try again, and no request an hour later**; an absurd `Retry-After` is capped at 15 s; the 5 s "slow" hint; closing cancels the wait; no position → nothing sent, then automatic when one arrives; no automatic rerouting; another place closes directions; log and `toString` capture |
| `ApiRouteRepositoryTest` (9) | the JSON body on the wire; the generated call is a POST with one body and no query or path value; 15 status/code combinations mapped; one request per call; the decoder (textbook vector, six decimals, negative steps, empty, five malformed inputs, 50 000 points) |
| `DirectionsScreenTest` (11) | cards: duration, distance, "Fastest" once, selection, credit; **no safety, risk, danger or traffic word on screen or in any `route_*` string, English or Bengali**; the toggle; **the emergency button and the search pill are shown and work in 12 directions states**; Try again only where it can help; the note; font scale 2.0; Bengali; number formatting |
| `RouteOverlayTest` (8) | draw order (alternatives, selected, start ring), casing and line with widths and colours, only palette colours; bounds; fit once per answer and not on selection; routes under the dot and the pin; following stops; a map failure does not stop the description |
| `DirectionsFlowTest` (4, real `MainActivity` with Hilt) | search → place → Directions → note → routes on the sheet and the map → back in two steps; no permission dialog is requested by opening directions; the dialer is reachable while routes load; nothing in Android's log |
| Existing | `StringResourceParityTest` (same keys and placeholders in both languages), `MapLibreBoundaryTest` (MapLibre only in `MapLibreEngine.kt`), `MainActivityTest` (permission list unchanged) all pass |

**What these tests cannot see:** the real map drawing, the real server, a real phone's
location and TalkBack. The log tests capture Android's log (Logcat) on the JVM; that requests
go to the SafeRoute API only is shown by the repository test and by the Hilt module, not by a
network capture.

**SOS failure matrix (Plan v7 §7.5):** not applicable. No SOS, live-location or contact code.

## 7. Decisions & ADRs

No new ADR. [ADR 0020](../adr/0020-routing-osrm.md) has an "Implementation note of 2026-10-08
(P012c1): the app".

- **Bounded wait:** "starting" message after 5 s or on 503 with `Retry-After`; wait at most
  15 s at a time; at most two automatic retries. Chosen without a measurement; to be revisited
  with the staging cold start.
- **A stale position is accepted as the start.** The dot is grey, and the route starts there.
- **Routes are fitted once per answer**, not when an alternative is chosen.

## 8. Security & privacy notes

- The start and the destination are sent to the SafeRoute API in a POST body, six decimals,
  only after the user continued past the note, and only while directions are open.
- Nothing about a route is stored: memory only, cleared when directions close or the place
  changes. DataStore holds the mode and one flag.
- No new permission. Opening directions never requests the location permission by itself.
- Origins, destinations, routes and geometries are never logged; their types print "hidden".
- **For the lawyer:** the P010b disclosure said that for showing the location nothing is sent.
  Route requests send a precise start and destination. The disclosure now names directions,
  and the route note says what is sent; both wordings, the consent notice and the privacy
  policy need review (follow-up).
- No safety claim, score or label on routes; "Fastest" refers to time only.

**Bengali strings that need human review before release** (drafts by Claude Code): all 32
`route_*` keys and the changed `location_disclosure_why`. In particular `route_mode_driving`
("গাড়িতে"), `route_starting`, `route_error_not_routable` and `route_intro_sent`.

## 9. Known issues & risks

- **Never seen on a real map:** the route layers, the casing, the start ring and the camera
  fit exist only as tested descriptions.
- The retry numbers are guesses until the cold start is measured; the worst case before "Try
  again" is three requests of up to about 30 s each plus two waits.
- With the sheet half open only the first card is sure to be visible; the others need the
  sheet pulled up (its handle is tappable).
- Bengali digits in distances come from the phone's number format; durations use the string
  formatter. Whether both look consistent in Bengali is a review point.
- Walking routes use the stock profile (no trunk roads), and driving times assume a car and
  no traffic (ADR 0020).
- Route colours are the location blue and grey; a colour-blind check on a real map is owed.

## 10. Follow-ups & prerequisites for next prompt

New in Notion (*Follow-ups*, source P012c1):

- Check directions on a phone against staging (High).
- Lawyer: wording for route requests (note, disclosure, consent notice, policy) (High).
- Bengali review of the route strings.
- Revisit the app's wait numbers after the staging cold-start measurement.

**Next: P012c2** (`feat/012c2-follow-me`): follow-me, tap-to-select on the map, "Choose
start", the battery note, and the learning notes about projection and rerouting. Then P013.

## 11. How Rahul can verify

1. In `android/`: `./gradlew lint testDebugUnitTest assembleDebug` (539 tests).
2. CI green, squash and merge. Nothing deploys (no `backend/` change).
3. On the phone, with a build from `main` pointed at staging, signed in:
   1. Search a public place in Kolkata, tap it, tap **Directions**. The note appears once;
      "Not now" closes it and nothing happens; "Continue" goes on.
   2. Without location permission: the sheet says it needs your location; "Use my location"
      starts the usual disclosure and system dialog.
   3. With permission: after a quiet period on staging the first request shows "Starting the
      routing service, this can take a moment" and ends in routes or in "Try again". **Note
      how long it took.**
   4. Routes: up to three cards; the first says "Fastest"; the credit line is under them. On
      the map the chosen route is strong, the others grey, with a ring at the start and the
      pin at the end, and the whole route is visible above the sheet.
   5. Tap another card: it becomes the strong line; the map does not jump.
   6. Switch Walking/Driving: new routes. Close the app, open it: the choice is remembered.
   7. A place outside West Bengal (search a city in another state): "Routes aren't available
      here yet", with no Try again.
   8. Airplane mode, then Directions: "You are offline…" with Try again.
   9. Back: directions close, the place card stays; back again: the place goes.
   10. Dark mode; Bengali; font size at the largest; TalkBack reads the toggle as two radio
       buttons and each card as selected or not.
   11. In every state above the red 112 button is visible and opens the dialer.

## 12. Learning notes

- **Routing and the road graph** (the server's side): a map for routing is a graph of
  junctions and road pieces, prepared ahead of time so that an answer takes milliseconds.
  The app only asks and draws.
- **Polyline.** The route's line arrives as a short text. Each point is stored as the
  difference to the one before, which keeps a 500 km route to a few kilobytes. Decoding it is
  loops and arithmetic, so it runs on a background thread (`withContext(dispatcher)`): the
  main thread draws the screen sixty times a second and must not be kept busy.
- **Two ViewModels, one shared object.** Home and Directions each have a `@HiltViewModel`.
  They never call each other; both hold `RouteDisplay`, an `@ActivityRetainedScoped` object
  that lives as long as the activity (rotations included). One writes routes into it, the
  other draws what it finds. The same pattern connects Search and Home.
  [Hilt scopes](https://developer.android.com/training/dependency-injection/hilt-android#component-scopes)
- **A coroutine `Job` and cancellation.** `viewModelScope.launch { }` returns a `Job`.
  Keeping it and calling `cancel()` before starting the next request is how "only one request
  at a time" works: the cancelled coroutine stops at its next suspension point, and its
  answer never reaches the screen.
  [Coroutines on Android](https://developer.android.com/kotlin/coroutines)
- **`delay` and virtual time.** Waiting ten seconds is `delay(10_000)`: it suspends without
  blocking a thread. In tests a `StandardTestDispatcher` makes time virtual, so
  `advanceTimeBy(10_000)` passes those ten seconds instantly and exactly.
- **DataStore.** A small file of keys and values, read as a `Flow` and written with `edit`.
  Right for two settings; a database would be for many records.
  [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)
- **Selectable rows and TalkBack.** `Modifier.selectable(role = Role.RadioButton)` inside a
  `selectableGroup()` makes TalkBack say "selected, radio button, 1 of 2". The visible radio
  mark and the border mean the choice is not shown by colour alone.
- **Why there is no automatic rerouting.** Asking for a new route whenever the position
  moves would send the user's position to the server again and again without them asking.
  Here a request leaves the phone only on a tap.
