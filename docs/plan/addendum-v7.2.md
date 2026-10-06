# SafeRoute Plan v7 — Addendum v7.2: statewide coverage

> Copyright (C) 2026 Rahul Chowdhury. Licensed under CC BY-NC-ND 4.0
> (`CC-BY-NC-ND-4.0`), like everything under `docs/`: see [`../LICENSE.md`](../LICENSE.md).

- **Date:** 2026-10-07 · **Prompt:** P010c · **Decision:**
  [ADR 0016](../adr/0016-statewide-coverage-layers.md)
- **Where this addendum differs from Plan v7 and from
  [addendum v7.1](addendum-v7.1.md), v7.2 wins.** Read the PDF first, then v7.1, then this file.
- The PDF and the text of v7.1 are unchanged. v7.2 supersedes v7.1 only where section I says so.
- A full **v8** will be written after the MVP and company registration.
- Nothing here is measured yet. Thresholds, extents and boundaries marked "to be set" or "to be
  defined" are open until the prompt named next to them records them.
- Legal points are marked **to be verified by a lawyer**. This file draws no legal conclusion.

## A. Launch geography

- **The launch geography is West Bengal.**
- **Kolkata is the first pilot area** for community reports and moderation.
- "Coverage" always means the three layers in section B. It is never a single claim: a place can
  be covered by one layer and not by another.

## B. Coverage layers

| Layer | What it contains | Where it works |
| --- | --- | --- |
| 1. Core safety tools | Device-first SOS by SMS, the 112 dialer, emergency contacts, live location sharing | Statewide, wherever there is cellular signal and GPS |
| 2. Navigation | Map, search, routing | Map and search statewide; routing statewide only if the P012 measurements pass (section D) |
| 3. Safety data | Community reports, official aggregates, the exposure metric | Only in **active regions** (section C) |

1. **Core safety tools.**
   - They depend on the phone, the mobile network and GPS, not on a region being opened.
   - Rural signal gaps are documented, not hidden.
   - SMS location check-ins (MVP+) matter most where mobile data is weak.
2. **Navigation.**
   - **Map:** statewide, as far as the tile provider covers it.
   - **Search:** statewide, with quality measured per district (section E).
   - **Routing:** statewide only if the P012 measurements pass the thresholds in section D.
     Otherwise a documented fallback extent, and outside it a clear state, "Routes aren't
     available here yet". Never an error.
3. **Safety data.**
   - Shown only in active regions.
   - Elsewhere the map shows context (police stations, hospitals, and 24-hour pharmacies where
     they are tagged) and the text "Community reports aren't available here yet".
   - The k-threshold (at least 3 distinct reporters) is unchanged everywhere.
   - **No "safe" label anywhere**, and least of all where there is no data. An area without
     reports is an area without data, not a safe area (Plan v7 §1).

## C. Region model (extends ADR 0013)

- `regionCode` identifies a city, a district or a state-level region
  ([ADR 0013](../adr/0013-regions-and-expansion.md)).
- A **server-side regions configuration** lists, for each region: code, name, boundary, status
  and a routing coverage flag.
  - Status is one of `active_reports`, `context_only`, `planned`.
  - It starts as a versioned file. No migration now.
- **Initial active region:** the Kolkata metropolitan area. Its boundary is **to be defined and
  recorded in P017**.
- Reports are accepted only inside active regions.
- The P019 overlays and the exposure metric show only active regions.
- Opening a region follows the readiness checklist in ADR 0013, plus moderator capacity for that
  region.
- The naming rule is unchanged: no region names in identifiers, paths or schema names
  ([ADR 0005](../adr/0005-product-name-and-multi-city-readiness.md)). Region names are data in
  the configuration.

## D. Routing (P012 scope)

- Build the road graph from a **boundary extract of West Bengal**. Record its source and
  licence. The extent is configurable.
- **Measure on staging**, for (a) the Kolkata metropolitan area only and (b) the whole state:
  - memory;
  - start-up time;
  - p95 latency.
- **Acceptance:**
  - p95 at or under 2 s, including the exposure metric (Plan v7 §14.3);
  - a start-up time compatible with the hosting choice;
  - the monthly cost recorded.
- **Hosting** (Cloud Run with enough memory, or a small VM) is decided from the measurements.
- If the whole state fails, choose the largest extent that passes and record it.
- A route requested outside the covered extent returns a typed "outside covered area" result
  that the app handles. It is not an error.

## E. Search (P011 scope)

- P011 includes a **search-quality evaluation**.
- A committed fixture of at least 100 real-world queries across at least 10 districts.
  - No personal data: public places, landmarks, stations, markets, hospitals, localities.
  - It includes Bengali script and common English transliterations.
- Record the hit rate by district.
- The threshold is **to be set in P011 with Rahul**.
- Below the threshold: evaluate swapping the geocoding provider behind the adapter
  (Plan v7 §4) before launch.

## F. Police numbers (MVP+)

- **112 is the primary number statewide.**
- Station numbers are bundled per jurisdiction, and only when verified: source, date, verifier.
- Kolkata Police, the other police commissionerates and the district police are separate
  jurisdictions.
- Unknown jurisdiction or unverified number: 112 only.
- **Never show an unverified number.**

## G. Languages

- Bengali and English at launch.
- Hindi moves up to an MVP+ candidate.
- Nepali is evaluated for the northern districts, based on survey data.
- Decide after the second survey round (section H).

## H. Research

- Run a **second survey round** with respondents from several districts before claiming
  statewide demand.
- Keep the first round's note: it is small and skewed (addendum v7.1, E).

## I. Plan edits

These add to the table in addendum v7.1, section C. Where both name the same section, this
table wins.

| § | Change |
| --- | --- |
| §1, §3.1 | Geography wording: launch in West Bengal; Kolkata is the first pilot area for community reports and moderation. |
| §3.2 F-03, F-04 | Coverage notes as in B.2: search statewide with quality measured per district; routing statewide only if the measurements pass, with the "not available here yet" state otherwise. |
| §3.4 | Launch criteria, add: the routing coverage statement verified in at least 5 districts; SOS and live share tested in weak-signal areas; the search evaluation recorded; the regions configuration present; public coverage statements match this addendum. |
| §9 | Official data is handled per jurisdiction (section F). |
| §13.1 | OSRM sizing comes from the measurements in section D. |
| §14 | The capacity model must be redone in v8 for a larger extent. The MVP assumptions are unchanged. This replaces v7.1's "before the first expansion" wording. |
| §21 | Regional expansion becomes "open more active regions", not "new cities". This replaces item 7 of v7.1, section D; the rest of that roadmap stands. |
| §22 | Risks extended by section K. |

## J. Claims rule

This applies to every public statement: the app, this repository, pitch materials, the website,
the Play listing and press.

- Describe coverage using the three layers and the current list of active regions.
- Never imply community safety data, safety ratings or a "safe" status for context-only
  regions.
- Never state user numbers or reliability figures that are not measured.
- Legal review of the advertising and consumer-protection wording is a follow-up: **to be
  verified by a lawyer**.

## K. Risks added (§22)

- Sparse or no report data outside active regions.
- Moderator capacity per region.
- Stale or wrong police numbers.
- OpenStreetMap data gaps in rural areas.
- Routing memory and cost.
- False confidence from coverage claims.
- Funders or users reading "coverage" as "safety data".
