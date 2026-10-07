# ADR 0021: Post-MVP product direction, journey engine and guardrails

- **Status:** Accepted
- **Date:** 2026-10-08
- **Prompt:** P012d
- **Plan refs:** Plan v7 §1, §3.3, §7, §8, §9.2, §10, §18, §22; [addendum v7.4](../plan/addendum-v7.4.md); [ADR 0005](0005-product-name-and-multi-city-readiness.md), [ADR 0010](0010-adults-only-and-consent-records.md), [ADR 0011](0011-trusted-circle-principles.md), [ADR 0017](0017-collect-statewide-publish-by-gate.md)

## Context

- Rahul shared a product idea document: a daily commute feature, SafeCircle, SafeExplore, a
  nearby feed, a community feed, a weekend mode and monetization.
- A safety app that is opened only in an emergency is rarely installed and rarely remembered.
  A navigation app is opened every day.
- The ideas pull in different directions. Some are everyday utility built on what the MVP
  already has (routes, live sharing). Others are a social product with its own legal and
  moderation load.
- One developer builds SafeRoute, and the MVP is not finished (P013–P022 remain).
- Several ideas, built carelessly, break rules the project already has: a "risk level" for a
  route is a safety score (Plan v7 §3.3); a circle that watches commutes is the monitoring tool
  ADR 0011 was written to prevent.
- Nothing in this ADR is built or measured. No dates, prices or usage numbers are decided.

## Decision

1. **Direction: daily utility first, safety throughout.** Everyday features bring people back;
   the safety tools are present in each of them.
2. **Sequence after the MVP** (addendum v7.4, section A):
   1. MVP+: police numbers per jurisdiction, one-tap WhatsApp share, SMS location check-ins,
      nearby essentials from map data;
   2. Journeys v1;
   3. SafeCircle, under [ADR 0011](0011-trusted-circle-principles.md) and only after its lawyer
      review;
   4. company-gated channels: server SMS, WhatsApp, voice;
   5. broader Explore with curated content;
   6. a community feed and weekend mode, only with traction, a moderation team and legal
      review.
3. **One journey engine** for Safe Commute and SafeCircle (addendum v7.4, sections B and C):
   - a journey is an origin, a destination, an expected arrival window set by the traveller,
     an arrival check, and a missed-arrival prompt that goes to the traveller first;
   - only the traveller starts a journey; nobody else can start or force one;
   - no continuous background tracking beyond the user-started journey;
   - saved places stay on the device at first; syncing needs a consent purpose, encryption and
     a retention design;
   - missed-arrival alerts need a server-side timer; the circle is alerted only after a prompt
     to the traveller, unless the traveller opted in to automatic escalation; app users are
     reached by push first, others by SMS only once server SMS exists;
   - false alarms are designed for: a grace period, escalation in steps, a one-tap "I'm fine",
     and "check in" wording instead of "alarm".
4. **Guardrails for every future feature** (addendum v7.4, section D):
   - no safety score, risk label, ranking or "safe" claim about places, routes or
     neighbourhoods; counts and facts with their period and source;
   - no traffic claims without a licensed data source;
   - no ads or promotions in any safety flow; no targeting by location history; no selling or
     sharing of personal data;
   - never paywall SOS, 112, basic live sharing or basic check-ins;
   - city-neutral naming (ADR 0005), adults only (ADR 0010), SafeCircle under ADR 0011.
5. **Discovery data** (section E): nearby essentials from open map data with attribution; a
   terms check before any places or events source; no user-generated content before the feed
   gates.
6. **Community feed gates** (section F), all required before any build: a registered company;
   named moderators with backups; moderation tooling and an SLA; Play UGC policy compliance;
   lawyer review; a photo handling design; posts never become safety facts; abuse and brigading
   controls.
7. **Monetization** (section G): a Plus tier only for features that cost money to run; B2B
   Teams in separate notes; promoted listings only at scale and outside safety flows; no
   revenue claim in public materials until measured.
8. **No code, contract, migration, workflow or dependency changes now.** The MVP prompts keep
   their scope.

## Alternatives considered

- **Build the social feed first.** A feed could bring daily visits sooner than commute
  features. Rejected: it needs moderators, tooling, a registered company and legal review that
  do not exist; an empty feed is worse than none; it would compete with the unfinished MVP for
  one developer's time; and a safety product that opens on a social feed is a different product.
- **Safety scores on places** (a rating or a "Low/High risk" label per place, route or
  neighbourhood). Easy to read and what the idea document sketched in places. Rejected: Plan v7
  §3.3 already forbids scores, rankings and "safe" labels; a score built on sparse community
  data is wrong more often than it is right; absence of data would read as "safe"; and a label
  on a neighbourhood describes the people who live there, which Plan v7 §1 rules out.
- **Separate mechanisms for commute and circle.** Faster to ship the first one. Rejected: two
  timers, two alert paths and two sets of consent would diverge, and the circle would be bolted
  on beside the rules that protect the traveller.
- **Do nothing until the MVP ships.** Keeps focus. Rejected in part: nothing is built now, but
  recording the guardrails now stops P013–P016 from closing doors (consent purposes, journey
  state) or from adopting wording that has to be withdrawn later.

## Consequences

- Easier: every later idea is checked against one list (section D) and one order (section A).
  Journeys v1 can be designed knowing the circle comes next.
- Harder: several attractive features wait a long time. The community feed may never be built
  if its gates are not met; that is an accepted outcome.
- The journey engine sends a journey's existence and end time to the server. That is new
  personal data, with a new consent purpose and a retention rule to decide when it is built
  (**to be verified by a lawyer**).
- A journey that continues with the screen off needs a foreground service and a changed
  location disclosure. Today's policy is foreground only (ADR 0015); the prompt that builds
  journeys must amend it with its own ADR.
- [ADR 0011](0011-trusted-circle-principles.md) stays Proposed and its text is unchanged. It
  gains a dated note pointing here.
- `CLAUDE.md` gains a "Product guardrails" section with the section D rules.
- New risks, recorded in addendum v7.4, section H: coercive monitoring through SafeCircle;
  false alarms; privacy of saved places; scope sprawl for a solo developer; empty discovery
  content; moderation load; brand dilution; misleading "score" wording.
- Follow-ups: lawyer review of saved places, missed-arrival alerts and SafeCircle; an
  evaluation of places and events data sources; a note for the authors of P013–P016 to leave
  room for circle consent purposes and journeys (no scope change).
- Revisit when the MVP has shipped, and again before each step of the sequence starts.

## References

- [Addendum v7.4](../plan/addendum-v7.4.md); Plan v7 §3.3, §8, §10.
- [ADR 0010](0010-adults-only-and-consent-records.md), [ADR 0011](0011-trusted-circle-principles.md),
  [ADR 0015](0015-map-stack-and-location-policy.md), [ADR 0018](0018-search-and-geocoding.md).
- Prompt log: [`docs/prompt-logs/012d-post-mvp-product-direction.md`](../prompt-logs/012d-post-mvp-product-direction.md).
