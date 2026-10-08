# ADR 0023: Live local context: the map stays Home, a staged path through the bottom sheet

- **Status:** Proposed
- **Date:** 2026-10-09
- **Prompt:** P012g
- **Plan refs:** Plan v7 §1, §3.3, §9.2, §22; [addendum v7.5](../plan/addendum-v7.5.md); [addendum v7.4](../plan/addendum-v7.4.md) D, E, F; [addendum v7.3](../plan/addendum-v7.3.md) D, I; [ADR 0010](0010-adults-only-and-consent-records.md), [ADR 0015](0015-map-stack-and-location-policy.md), [ADR 0017](0017-collect-statewide-publish-by-gate.md), [ADR 0021](0021-post-mvp-product-direction.md), [ADR 0022](0022-follow-me-navigation.md)

## Context

- Rahul described a long-term direction: "Google Maps tells you where to go. SafeRoute tells
  you what nearby people report along the way."
- Today the app has a map, search, routes and follow-me navigation. It shows no reports and
  no alerts, and no region is published.
- [ADR 0021](0021-post-mvp-product-direction.md) set the order after the MVP and put a
  community feed last, behind eight gates. This ADR does not reopen that.
- Showing what people report, live, is the hardest version of user content: it must be fresh,
  it is tied to a place, and it can identify the reporter, start a rumour or point at a
  person or a community.
- A live surface with nothing in it is worse than no surface. On the map of one small town
  there was no police station, bank or pharmacy at all (addendum v7.3, section I), and no
  community report exists anywhere yet.
- Alerts for the route a person is following would need the server to know that route and
  the person's position while the phone is in a pocket. The app promises today that routes
  and positions stay in memory on the phone ([ADR 0022](0022-follow-me-navigation.md)).
- One developer builds SafeRoute, and the MVP is not finished.
- Nothing in this ADR is built or measured. It is **Proposed**: it records a direction for
  Rahul to accept, change or reject, and no code depends on it.

## Decision

1. **The map stays Home.** A person opens SafeRoute to a map, as today. Local context is
   added to the **Home bottom sheet**, which already exists, not put in front of the map.
   This holds until beta data shows otherwise, and changing it needs a new ADR that cites
   that data.
2. **The bottom sheet evolves in four stages** (addendum v7.5, section B):
   1. **Nearby panel:** essentials from map data, labelled "may be incomplete";
   2. **Curated alerts:** posted by staff from official or verified sources;
   3. **Community reports:** live, with expiry, confirmation counts, abuse controls and
      delays that protect the reporter;
   4. **Social posts:** only behind every gate of addendum v7.4, section F.
3. **What triggers each stage.** A stage starts only when all of its conditions hold and the
   stage before it is running:

   | Stage | Conditions |
   | --- | --- |
   | 1 | The MVP is shipped; the "may be incomplete" label and an honest empty state are designed (addendum v7.3, section I; v7.5, section E) |
   | 2 | A registered company; a terms check of each source, recorded in an ADR before that source is used; a named person who posts and corrects alerts |
   | 3 | Every conflict of addendum v7.5, section C answered in an ADR; lawyer review (**to be verified by a lawyer**); the region has reached its minimum density (a number recorded in the plan first); moderation capacity for live content, with a backup |
   | 4 | Every gate of addendum v7.4, section F |

4. **Route-affected alerts are not part of any stage.** They are blocked until a design for
   background location, consent, retention and an opt-in exists and a lawyer has reviewed it,
   and until a superseding ADR for ADR 0015 and ADR 0022 is accepted (addendum v7.5,
   section D).
5. **The vision sentence is a direction, not a claim.** Public text describes the live
   feature set and the live coverage only (addendum v7.5, section A).
6. **No guardrail is loosened.** No safety score, label or "safe" wording; no "quiet" or
   "normal" either; no traffic or weather claim without a licensed source; the k-threshold
   and the publication gate of the safety layer unchanged.

## Alternatives considered

- **A feed as Home, now.** The app would open on a list of nearby posts and reports, with
  the map one tap away. It would show the vision at once. **Rejected:**
  - there is nothing to put in it. No region is published and no report is shown anywhere,
    so the first thing a new user would see is an empty list;
  - it needs everything ADR 0021 put behind gates (a company, moderators, tooling, legal
    review) before the first line of code;
  - it turns a navigation and safety app into a social app in the user's eyes, and people
    open a map every day (ADR 0021, "daily utility first");
  - one developer cannot run live moderation next to an unfinished MVP.
- **Community reports first, skipping stages 1 and 2.** Gets to the vision fastest. Rejected:
  it starts with the riskiest content, on an empty map, with no way to tell a thin area from
  a calm one.
- **Do nothing beyond the MVP's safety layer.** The aggregated cells of Plan v7 §9.2 already
  show reports. Not chosen as the end state: cells are slow by design (a k-threshold, a
  48-hour moderation SLA) and say nothing about the hour ahead. It remains the baseline, and
  the fallback if stage 3 is never reached.
- **A separate tab for local context.** Keeps Home untouched. Not chosen for now: the bottom
  sheet is already where a place card and directions appear, and a tab that is empty in most
  regions advertises its own emptiness. Open again when beta data exists.

## Consequences

- Easier: each stage is useful alone and can stop there. Stage 1 needs no company, no
  moderation and no new personal data.
- Easier: the Home screen, the emergency control and the map's behaviour stay as tested.
- Harder: the vision arrives slowly, and may never arrive in regions that stay thin.
- Harder: stage 3 needs a second way of handling reports next to the cell-based safety
  layer, and the two must not contradict each other.
- The bottom sheet gets more to carry. It must never cover or delay the emergency control
  (ADR 0015, ADR 0022).
- Follow-up before stage 3: lawyer review of live reports, defamation, intermediary duties
  and background location; terms of official sources for alerts; evaluation of weather and
  traffic data sources.
- Revisit when: the MVP is in beta and there is data on how people use Home; a company is
  registered; a lawyer has reviewed live reports; or a region approaches the density at which
  stage 3 becomes arguable.
- This ADR stays Proposed until Rahul accepts it. A stage is not started on the strength of
  a Proposed ADR.

## References

- [Addendum v7.5](../plan/addendum-v7.5.md): vision, stages, conflicts, architecture
  implications, cold start, measures, risks.
- [Addendum v7.4](../plan/addendum-v7.4.md), sections D to F; [ADR 0021](0021-post-mvp-product-direction.md).
- [Addendum v7.3](../plan/addendum-v7.3.md), sections D and I.
- Prompt log: [`docs/prompt-logs/012g-live-local-context-vision.md`](../prompt-logs/012g-live-local-context-vision.md).
