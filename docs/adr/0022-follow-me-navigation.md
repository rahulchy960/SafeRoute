# ADR 0022: Follow-me navigation, foreground only

- **Status:** Accepted
- **Date:** 2026-10-08
- **Prompt:** P012c2a
- **Plan refs:** Plan v7 §3.2 (F-04), §5.3, §7.5; [ADR 0008](0008-android-foundation.md), [ADR 0015](0015-map-stack-and-location-policy.md), [ADR 0020](0020-routing-osrm.md), [ADR 0021](0021-post-mvp-product-direction.md)

## Context

- Since P012c1 a route can be looked at: a line, a duration and a distance. It cannot be
  followed. A person walking it has to keep matching the dot to the line themselves.
- The routing API returns a line, a duration and a distance per route. It returns no steps
  ("turn left in 200 m"), so the app has nothing to announce turn by turn.
- The location policy (ADR 0015) is foreground only: no background location permission, no
  foreground service. Both would need a Play declaration, a disclosure and a prompt of their own.
- A route says where a person is and means to go. Nothing about one is stored or logged today.
- Nothing in this ADR is measured on a street. The numbers below are first values.

## Decision

1. **Following happens on screen only.** "Start" keeps the camera on the user and shows what is
   left of the route while SafeRoute is the app on screen.
   - No foreground service, no background location, no new permission, no notification.
   - When the app leaves the screen, location updates stop as they always did (ADR 0015) and
     following pauses. Coming back continues it and says once that it had paused.
   - Why: a background navigation service is the kind of continuous tracking that needs its
     own disclosure, a Play declaration and battery work. A person following a route on foot
     or at the wheel has the screen in view; the cost of the limit is small and it is stated.
   - The screen is kept on while following, and at no other time.
2. **Nothing is kept.** The followed route, the progress and the positions live in memory. They
   are not saved, logged or sent. After the app's process was ended there is no route to come
   back to; the user starts again.
3. **Start needs a precise, recent position.** Precise location permission and a position from
   the last 10 seconds. Otherwise the screen says what is missing and offers the existing
   permission flows. Following never starts on approximate location (accurate to kilometres)
   or on an old position.
4. **Progress is arithmetic on the route's line** (`RouteTracker`, plain Kotlin):
   - a fix is projected onto the stretch of the line just behind (30 m) and ahead of the last
     known place on it; the stretch ahead is 50 m plus 35 m for every second since the last
     match, so that a gap in GPS is caught up with;
   - progress never moves backwards;
   - where the line passes a position twice (a loop, a road out and back), the earlier pass
     wins, so progress does not jump ahead;
   - a fix less accurate than 50 m decides nothing: not progress, not off-route, not arrival;
   - remaining distance and time are the route's own figures scaled by the length left. The
     time is an estimate from the route, not a measurement of the person's speed.
5. **Off the route:** farther from the line than max(50 m, 2 × accuracy) for 10 seconds
   without a break. One fix back near the line ends it.
   - The app then says "You seem to be off the route" and offers **Recalculate**.
   - **No automatic rerouting.** A new route is a request that carries the user's position to
     the server; it is sent only on a tap. One request at a time, a 20-second limit, and the
     existing sentences for "too many requests", "service starting" and "offline". The old
     route stays until a new one has arrived.
6. **Arrived:** within max(30 m, accuracy) of the end of the route. Following stops and the
   screen may switch off again. Reaching the place by another way counts: it is about the
   place, not the line.
7. **No position for 10 seconds:** "Searching for GPS". The route and the figures stay.
8. **Ending takes a question.** Back, or "End", asks "End navigation?" first. The permission
   being taken away, or precise location being switched off, ends following with the reason.
9. **The SOS control stays where it is** in every state, and the banner is limited to 40% of
   the screen's height so that it cannot reach the control; what does not fit scrolls inside
   the banner. The 112 flow and the shortcuts are unchanged.
10. **What the banner says:** a distance, a time, a clock time, and the states above. No score,
    label or colour that could be read as a statement about safety or traffic (ADR 0021).

## Not included

- Turn-by-turn instructions and voice: the routing API returns no steps; that needs a contract
  change (ADR 0004) and a look at the routing engine's instruction data.
- Automatic rerouting.
- Background navigation with a foreground service.
- Stops along a route, saved places.
- A choice of start point and selecting a route by tapping it on the map: added in P012c2b,
  see the note below.

## Note of 2026-10-08 (P012c2b): a chosen start, and routes chosen on the map

- **A start of the user's choice.** "Change start" opens search; the place chosen there
  becomes the start of the route and the routes are asked for again. "Start from my location"
  switches back. A chosen start needs no location permission at all.
- **Following starts only from the user's own location.** A route from another place cannot
  be followed: the person is not on it. With a chosen start the button is "Preview" (the map
  shows the whole route) and one sentence says how to follow a route. This keeps decision 3.
- **The chosen start is treated like every place:** memory only, hidden in `toString()`,
  sent only as the start of the routes request the user asked for.
- **A tap on a route's line selects that route**, as a tap on its card does. The nearest line
  within 24 dp wins; where routes share a road, the one drawn on top (the selected one). The
  arithmetic is `nearestLine` in `core/map/RouteHitTest.kt`, on screen pixels; only
  `MapLibreEngine.kt` turns map points into pixels. While a route is followed taps select
  nothing: only that route is drawn.

## Alternatives considered

- **A foreground service that keeps navigating with the screen off.** What navigation apps do.
  Rejected for now: new permissions (`FOREGROUND_SERVICE_LOCATION`), a Play declaration, a
  changed disclosure and a lawyer's review, for a first version nobody has walked with yet.
- **Reroute automatically when off the route.** Convenient, but it sends the position to the
  server without the user asking, repeatedly, and can loop when GPS is poor. Rejected.
- **Match against the whole line every time.** Simpler, but on a loop or a road walked out and
  back the nearest point can be the wrong pass, and progress jumps.
- **Estimate the arrival time from the person's measured speed.** Needs smoothing and is wrong
  at every stop. The route's own duration, scaled, is stable and honest about being an estimate.
- **MapLibre's own location and tracking component.** It would put the map library in charge
  of location; ADR 0015 keeps the library behind `MapEngine` and location in `core/location`.

## Consequences

- Following stops being useful the moment the phone is locked or another app is opened. The
  app says so when the user returns; the log's phone checks include it.
- The screen staying on costs battery. Not measured; a phone check asks for the figure.
- The thresholds (50 m, 10 s, 30 m, 35 m/s) are untested on a street and may need tuning.
- A person who leaves the route and rejoins it far ahead is found again only once enough time
  has passed for the search stretch to reach that far; until then the app shows "off the
  route" and Recalculate.
- Recalculate sends the current position as the start of a route, the same thing a first
  request does and the route note already describes. The location disclosure is unchanged;
  whether it should name following is **to be verified by a lawyer**.
- Any later step to background navigation needs a superseding ADR.

## References

- [`RouteProgress.kt`](../../android/app/src/main/java/com/saferoute/app/feature/directions/RouteProgress.kt),
  [`DirectionsViewModel.kt`](../../android/app/src/main/java/com/saferoute/app/feature/directions/DirectionsViewModel.kt)
- Diagram: [`012c2-follow-me-states.svg`](../diagrams/012c2-follow-me-states.svg)
- Prompt log: [`012c2a-follow-me.md`](../prompt-logs/012c2a-follow-me.md)
