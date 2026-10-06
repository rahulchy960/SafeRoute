# ADR 0016: Statewide coverage in three layers

- **Status:** Accepted
- **Date:** 2026-10-07
- **Prompt:** P010c
- **Plan refs:** Plan v7 §1, §3.2–3.4, §9, §12, §13–14, §21–22; [addendum v7.1](../plan/addendum-v7.1.md); [addendum v7.2](../plan/addendum-v7.2.md); [ADR 0005](0005-product-name-and-multi-city-readiness.md), [ADR 0013](0013-regions-and-expansion.md), [ADR 0015](0015-map-stack-and-location-policy.md)

## Context

- Plan v7 and addendum v7.1 describe a launch in Kolkata, with expansion region by region
  afterwards ([ADR 0013](0013-regions-and-expansion.md)).
- Rahul decided that the launch geography is the whole of West Bengal, with Kolkata as the
  first pilot area for community safety data.
- The parts of the product depend on different things:
  - SOS by SMS, the 112 dialer, emergency contacts and live location sharing need a phone, a
    mobile network and GPS. They do not need local data.
  - The map, search and routing need provider coverage and a road graph of the area.
  - Community reports need reporters and moderators in the area, and the k-threshold (at least
    3 distinct reporters) before anything is shown.
- One word, "coverage", would therefore hide three different facts. A reader could take
  "available across the state" to mean "safety data across the state". Plan v7 §1 says safety
  information is context, never a guarantee; an area without reports must not look safe.
- Nothing has been measured yet: routing memory and latency for the state, search quality
  outside Kolkata, signal in rural areas.

## Decision

1. **The launch geography is West Bengal.** Kolkata is the first pilot area for community
   reports and moderation.
2. **Coverage is always described in three layers**, never as a single claim:
   1. **Core safety tools** (device-first SOS by SMS, 112 dialer, emergency contacts, live
      location sharing): statewide, wherever cellular signal and GPS exist. Rural signal gaps
      are documented.
   2. **Navigation:** the map statewide (tile provider coverage); search statewide, with
      quality measured per district; routing statewide only if the P012 measurements pass the
      thresholds in addendum v7.2, section D. Otherwise a documented fallback extent and a
      clear "Routes aren't available here yet" state, never an error.
   3. **Safety data** (community reports, official aggregates, the exposure metric): only in
      active regions.
3. **Active regions.** A server-side regions configuration (a versioned file first, no
   migration now) lists each region's code, name, boundary, status (`active_reports`,
   `context_only` or `planned`) and routing coverage flag.
   - The initial active region is the Kolkata metropolitan area. Its boundary is defined and
     recorded in P017.
   - Reports are accepted only inside active regions. Overlays and the exposure metric show
     only active regions.
   - Elsewhere the map shows context (police stations, hospitals, 24-hour pharmacies where
     tagged) and the text "Community reports aren't available here yet".
   - The k-threshold is unchanged. No "safe" label anywhere.
   - Opening a region follows the readiness checklist of ADR 0013, plus moderator capacity for
     that region.
4. **The claims rule.** Every public statement (the app, the repository, pitch materials, the
   website, the Play listing, press) describes coverage using the three layers and the current
   list of active regions. It never implies community safety data, safety ratings or a "safe"
   status for context-only regions, and never states user numbers or reliability figures that
   are not measured.
5. **No code, contract, migration or workflow changes now.** The scopes of P011, P012, P017 and
   P019 change as addendum v7.2 describes.

## Alternatives considered

- **Kolkata-only launch (Plan v7 as written).** Simplest to operate and to describe. Rejected:
  the core safety tools work wherever there is signal, and limiting them to one city would
  withhold them from people they could serve, for no technical reason.
- **Claiming statewide community reports.** One simple message. Rejected: there are no
  reporters or moderators outside the pilot area, so the overlay would be empty or below the
  k-threshold, and an empty overlay reads as "nothing happens here". That is the false
  confidence Plan v7 §1 forbids.
- **Lowering the k-threshold outside the pilot area** to make regions look covered. Rejected
  already in ADR 0013 (rule 5); unchanged.

## Consequences

- Easier: the core safety tools are not held back by report density. Public statements have
  one rule to follow.
- Harder: every public text must name the layer it talks about. The app needs two honest empty
  states ("Routes aren't available here yet", "Community reports aren't available here yet").
- P011 gains a search-quality evaluation across districts; P012 gains the West Bengal extract,
  the measurements and the "outside covered area" result; P017 gains the regions configuration
  and the pilot boundary; P019 shows safety data only in active regions.
- ADR 0013 stays in force and gets a dated note pointing here. Its rule 6 (the capacity model)
  still holds: Plan v7 §14 must be redone in v8 for a larger extent.
- New risks are listed in addendum v7.2, section K.
- Follow-ups: lawyer review of the coverage and advertising wording (**to be verified by a
  lawyer**); the boundary of the Kolkata metropolitan area (P017).
- Revisit when P012's measurements are in, and when a second active region is proposed.

## References

- [Addendum v7.2](../plan/addendum-v7.2.md); [addendum v7.1](../plan/addendum-v7.1.md);
  Plan v7 §1, §3.2–3.4, §14.
- [ADR 0005](0005-product-name-and-multi-city-readiness.md),
  [ADR 0013](0013-regions-and-expansion.md).
- Prompt log: [`docs/prompt-logs/010c-statewide-coverage-plan.md`](../prompt-logs/010c-statewide-coverage-plan.md).
