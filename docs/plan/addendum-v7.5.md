# SafeRoute Plan v7 — Addendum v7.5: live local context, vision and staged path

> Copyright (C) 2026 Rahul Chowdhury. Licensed under CC BY-NC-ND 4.0
> (`CC-BY-NC-ND-4.0`), like everything under `docs/`: see [`../LICENSE.md`](../LICENSE.md).

- **Date:** 2026-10-09 · **Prompt:** P012g · **Decision:**
  [ADR 0023](../adr/0023-live-local-context-staged-path.md) (Proposed)
- **This addendum supplements [addendum v7.4](addendum-v7.4.md).** It adds a long-term direction
  and the stages towards it. It replaces nothing in v7.4: the sequence of v7.4, section A, its
  guardrails (section D) and its community-feed gates (section F) all stay in force.
- **Reading order: PDF → [v7.1](addendum-v7.1.md) → [v7.2](addendum-v7.2.md) →
  [v7.3](addendum-v7.3.md) → v7.4 → v7.5.** Where they differ, the later document wins.
- The PDF and the texts of v7.1 to v7.4 are unchanged.
- A full **v8** will be written after the MVP and company registration.
- **What this is:** a record of where the product could go after the MVP, the order in which
  it could get there, and the conflicts that must be resolved on the way.
- **What this is not:** a scope change, a schedule or a promise. The MVP prompts (P013–P022)
  keep their scope. Nothing here is built, measured or dated. No number in this file is a
  measurement.
- Legal points are marked **to be verified by a lawyer**. This file draws no legal conclusion.

## A. Vision

> Google Maps tells you where to go. SafeRoute tells you what nearby people report along the
> way.

- **This is a long-term direction, not a launch promise.** At the time of writing the app has
  a map, search, routes and follow-me navigation. It shows no reports, no alerts and no
  community content anywhere, and no region is published (addendum v7.3).
- The sentence describes a possible end state: a map that carries what people nearby have
  reported, shown along the route a person is about to take.
- It is a comparison of kind, not of quality or coverage. SafeRoute does not claim to match
  another product's map, search, traffic or routing.

**Wording rule for the pitch, the website, the store listing and the README.**

- **Every claim must match the live feature set and the live coverage** on the day the text
  is published. A feature that exists only in this addendum is described as planned, or not
  at all.
- The vision sentence may be used as a statement of direction, with words that make that
  plain ("we are building towards"). It is never used as a description of what the app does
  today.
- The three coverage layers and the claims rule (addendum v7.2, section J; v7.3, section I) apply to
  every stage below. "What nearby people report" may be said only for regions where reports
  are shown.
- No usage, density or reliability figure that has not been measured and recorded.
- Naming another company's product in advertising or a store listing is a legal question
  (**to be verified by a lawyer**). Until then the sentence stays in internal and pitch
  material.

## B. Staged path

**Home stays the map** until beta data shows otherwise. The stages change what the Home
bottom sheet shows; they do not replace the map with a feed
([ADR 0023](../adr/0023-live-local-context-staged-path.md)).

| Stage | What | Starts when |
| --- | --- | --- |
| 1. Nearby panel | A panel in the Home bottom sheet with essentials (hospitals, police stations, pharmacies, petrol pumps), from map data, **labelled "may be incomplete"** | After the MVP, as part of MVP+ (v7.4, section A, step 1). The label rule of addendum v7.3, section I applies until a region is verified |
| 2. Curated alerts | Alerts posted by staff, from official or verified sources | A registered company; the terms of **each** source checked and recorded in an ADR before it is used (v7.4, section E); a named person who posts and corrects them |
| 3. Community reports | Reports from users, shown live: with expiry, confirmation counts, abuse controls and delays that protect the reporter | Every conflict in section C resolved in an ADR; lawyer review (**to be verified by a lawyer**); the minimum density of section E reached in that region; moderation capacity for live content |
| 4. Social posts | Free-form posts | **Only behind every gate of v7.4, section F.** Nothing in this addendum loosens them |

- A stage does not start before the ones above it are running. Each stage is built by its own
  prompts, with its own ADR where it changes a rule.
- **Stage 1 is navigation content, not safety data.** A listed police station or hospital is a
  place from the map. It carries no promise that it is open, staffed or the nearest one.
- **Stage 2 is not user-generated content**: staff post, from a named source, with its date.
  It still needs someone to do it every day, which is why it waits for a company.
- **Stage 3 is not the report flow of Plan v7 §9.2.** That flow collects reports, moderates
  them and publishes aggregated cells behind a k-threshold and a publication gate (addendum
  v7.3). A live report is a single, recent, located item. Whether and how the two share data
  is an open design question; the k-threshold is never lowered to make live content appear.
- "Route-affected alerts" (an alert because something lies on the route a person is
  following) are not a stage of their own. They depend on section D and are blocked until the
  background-location and consent design exists.

## C. Guardrail conflicts to resolve before stage 3

Each of these needs a written answer, in an ADR, before any code for stage 3. Several restate
a rule that already exists; live content is where they bite.

| Conflict | Why it is one | What the answer must cover |
| --- | --- | --- |
| No "normal", "quiet" or "safe" wording | A live panel with nothing in it invites a sentence like "all quiet here". That is a safety label (v7.4, section D) | Wording for every state that states what was reported and when, never how the area is |
| Reporter location privacy against live publication | A report shown at once, at its exact spot, says where the reporter is standing right now | A delay before publication, coarsening of the position, and no reporter identity on a report; who decides the values |
| Expiry (TTL) per report type | A report about a broken streetlight and one about a crowd age differently | A lifetime per type, what the item shows as it ages, and what happens at expiry |
| Minimum confirmations | One person's report is a claim, not a fact | How many independent confirmations before it is shown as confirmed, and how "unconfirmed" is displayed without a label that reads as a rating |
| Moderation SLA for live content | The report flow has a 48-hour SLA (addendum v7.3). Live content is stale long before that | Who moderates, in what time, with which backup, and what is shown before a moderator has looked |
| Abuse and brigading | A group can flood an area with false reports, or bury a true one | Rate limits, account age or reputation rules, detection, and removal |
| Defamation and communal tension | A report can name or point at a person, a business, a community or a place of worship | Categories that describe incidents and conditions, never kinds of people (Plan v7 §1); free text or none; takedown (**to be verified by a lawyer**) |
| No traffic or weather claims without licensed sources | "Road blocked" or "flooding ahead" from a user is a report; the same sentence from the app is a claim | Reports stay attributed to reporters with their time; no traffic or weather statement of our own without a licensed source and its recorded terms (v7.4, section D) |

- Intermediary duties for a service that publishes user content are **to be verified by a
  lawyer** before stage 3.
- A conflict that cannot be resolved inside the guardrails of v7.4, section D means stage 3
  does not start, or a new ADR changes the guardrail and says why.

## D. Architecture implications (recorded, not built)

Telling a person that something has been reported **on the route they are following** is a
different system from showing reports on a map. Recorded here so that nobody designs it by
accident:

- **Server-side matching.** The server would have to know the user's active route and their
  position along it, to match new reports against them.
- **Background location.** Matching is only useful while the phone is in a pocket. Today the
  app uses location in the foreground only, and following a route works on screen only
  ([ADR 0015](../adr/0015-map-stack-and-location-policy.md),
  [ADR 0022](../adr/0022-follow-me-navigation.md)). This would need a foreground service, the
  background-location permission and the declaration Google Play asks for when an app uses
  it (the current requirement is read when it is built).
- **New consent purposes** (ADR 0010): one for sending the route and the position for
  matching, asked just-in-time, never bundled, with an **opt-in** and a visible way to turn it
  off.
- **Retention limits.** What the server keeps of a route and of positions, and for how long,
  decided before anything is stored.
- **The current promise changes.** Today a route, the progress along it and the positions are
  kept in memory on the phone and never saved, and the server keeps no route
  ([ADR 0020](../adr/0020-routing-osrm.md), ADR 0022). That changes **only with explicit
  consent and after a lawyer's review** (**to be verified by a lawyer**), and the location
  disclosure and the privacy notice change in the same prompt.
- A superseding ADR for ADR 0015 and ADR 0022 is needed before any of this is built.
- Battery use and Android's limits on background work are part of the design, not a later
  fix.
- None of this is needed for stages 1 and 2, which show content for the area on screen.

## E. Cold start

- **Minimum viable density per region.** The Nearby panel shows community content in a region
  only once that region has enough recent, confirmed reports for the panel to be informative.
  The number is **not recorded**: it is set from beta data, per region, and written into the
  plan before anyone argues that it is met.
- This is separate from the k-threshold of the safety layer (at least 3 distinct reporters),
  which is never lowered.
- **Do not show "0 reports" as if it meant "nothing happening".** Where there is no content,
  or not enough, the panel says that there are no community reports here **yet**, as the
  no-data rule of addendum v7.3, section D already requires. Absence of reports never reads
  as calm, and never as safe.
- **Empty-state design is part of stage 1.** Before any community content exists the panel
  must be useful and honest with essentials alone, and with nothing at all where the map has
  none (addendum v7.3, section I: one small town had no police station, bank or pharmacy on
  the map).
- An empty state offers what always works: the emergency control and 112.
- A region below the density stays on stage 1 or 2 content. Density is per region; no
  statewide switch.

## F. Research questions and success measures

**Research questions for the next survey round: not recorded.** The list was discussed
outside this repository and was not available when this addendum was written. It is added
here by a later prompt, in Rahul's words. Nothing is invented in its place. The second survey
round itself is planned in addendum v7.2, section H.

**Success measures**, to be defined and measured per region once stage 3 exists. Each needs a
definition (what counts, over which period) before a number is quoted:

| Measure | What it would tell |
| --- | --- |
| Reports per area per week | Whether there is enough content for the panel to be worth opening |
| Confirmation rate | The share of reports that other people confirm |
| False-report rate | The share removed as false or abusive, as decided by moderation |
| Moderation time | From a report being made to a moderator's decision |

- No target is set here. A target without a baseline would be a guess.
- These are operational measures. None of them is shown to users as a rating of an area.

## G. Risks

These add to Plan v7 §22 and the risk lists in addenda v7.1 to v7.4.

| Risk | What it means | What reduces it (not removes it) |
| --- | --- | --- |
| Empty feed at launch | A panel with nothing in it makes the product look dead | Stage 1 first; minimum density per region; honest empty states (section E) |
| Misinformation | A false report is believed and acted on | Confirmations, expiry, moderation, attribution to reporters with their time (section C) |
| Panic | A cluster of reports, true or not, alarms people or draws a crowd | Wording that states facts with their time; no push for unconfirmed items; curated alerts only from named sources |
| Reporter identification | A live, located report reveals who made it or where they are | Delay, coarsening, no identity on a report (section C) |
| Background-tracking privacy and battery | Route matching needs position while the phone is in a pocket | Opt-in, its own consent purpose, retention limits, a superseding ADR, lawyer review (section D) |
| Liability for reroute suggestions | Suggesting another way because of a report is advice the app gives | No automatic rerouting (ADR 0022); show the report, let the person decide (**to be verified by a lawyer**) |
| Brand dilution | Live local content makes a safety product look like a social app | Home stays the map; social posts last and only behind v7.4, section F |
| Scope sprawl for a solo developer | Four stages on top of v7.4's six steps and an unfinished MVP | Nothing here is scheduled; the MVP scope is unchanged; one stage at a time |

## H. Plan edits

These add to the tables in v7.1, section C, v7.2, section I, v7.3, section J and v7.4,
section I. Where they name the same section, this table wins.

| § | Change |
| --- | --- |
| §1 | The vision sentence of section A is a long-term direction. Public text follows the wording rule of section A. |
| §9 | Live community reports (stage 3) are a later, separate surface from the report flow of §9.2. The k-threshold and the publication gate are unchanged. |
| §18 | P013–P022 keep their scope. Nothing in this addendum is a prompt. |
| §22 | Risks, add: the eight rows of section G. |
