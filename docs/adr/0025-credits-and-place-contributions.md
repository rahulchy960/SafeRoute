# ADR 0025: Credits and verified place contributions (concept)

- **Status:** Proposed
- **Date:** 2026-10-09
- **Prompt:** P012h
- **Plan refs:** Plan v7 §9.2; [addendum v7.6](../plan/addendum-v7.6.md); [addendum v7.4](../plan/addendum-v7.4.md) D, F; [addendum v7.3](../plan/addendum-v7.3.md) I; [ADR 0010](0010-adults-only-and-consent-records.md), [ADR 0018](0018-search-and-geocoding.md), [ADR 0021](0021-post-mvp-product-direction.md)

No legal, tax or payments conclusion is drawn here. Every such point is **to be verified by a
lawyer and a chartered accountant**.

## Context

- The map has gaps. In one small town it showed roads and buildings but no police station,
  bus stop, post office, bank, ATM or pharmacy (addendum v7.3, section I). The map data is
  community-maintained (OpenStreetMap, [ADR 0018](0018-search-and-geocoding.md)).
- Rahul wants to reward people who fill such gaps: credits for adding a missing place, later
  exchangeable for vouchers. His first idea also rewarded safety reports.
- A reward changes why people contribute. For a map that can be managed; for safety reports it
  cannot.
- A reward that has value brings fraud (copied data, false locations), tax and payments
  questions, and personal data that the app does not hold today (photos, where a person was,
  payout details).
- SafeRoute is built by one developer, is not a registered company yet, and the MVP is not
  finished.
- Nothing in this ADR is built or measured. It is **Proposed**: it records a concept and the
  decisions already taken about it, for Rahul to accept, change or reject. No code depends on
  it, and no prompt starts work on the strength of it.

## Decision

1. **Record the concept, build nothing.** It is post-MVP and post-company, and blocked by the
   gates in point 6.
2. **Three contribution types, treated differently:**
   - **Missing place:** eligible for credits.
   - **Still open / closed / moved** for an existing place: eligible later.
   - **Safety incident reports: never.** No credits, points, badges with value or contests.
     Safety data is never gamified.
3. **The evidence rule.** A missing-place contribution counts only with a photo captured in
   the app's own camera flow, with the device location, its accuracy and a timestamp from the
   moment of capture. No gallery and no file import. The photo shows the sign or the frontage;
   no people, faces, number plates or private interiors; never taken while moving in traffic.
4. **The verification principle.** A contribution is confirmed independently, by another
   contributor's own in-app photo or by a moderator, within a time window. There is no
   real-time verification by people nearby.
5. **Rewards:** recognition first (badges and levels with no monetary value), vouchers through
   a reward partner later. Points have no cash value. No cash and no wallet transfer without a
   further ADR and legal review. No rate is decided, and no rate appears in public material.
6. **Gates, all of them, before any build:** a registered company; lawyer and
   chartered-accountant review; a reward partner and funding; SafeRoute's own place pipeline
   with moderation tools and capacity; the fraud design validated on a small pilot; a privacy
   review of the photo pipeline; a Play policy review; the product guardrails of addendum
   v7.4, section D.
7. **Credits need SafeRoute's own place database** (option C of the addendum), because
   verification and attribution have to happen inside SafeRoute. Guiding users to the
   OpenStreetMap editor (option A) and in-app OpenStreetMap notes (option B) carry no credits.
8. **Sources.** A contribution comes from the contributor's own photo or observation. Never
   from Google Maps or any other protected source. Anything sent on to OpenStreetMap goes
   under OpenStreetMap's contributor terms.

The details are in [addendum v7.6](../plan/addendum-v7.6.md).

## Alternatives considered

- **Credits for safety reports.** It would bring more reports. Rejected: it pays for invented
  or exaggerated incidents and for going towards danger, and a paid report cannot be trusted.
- **Gallery uploads.** Easier for the contributor. Rejected: the picture can come from
  anywhere, another map included, and the capture place and time would mean nothing.
- **Real-time verification by nearby users.** Fast. Rejected: it conflicts with the delays
  that protect a reporter (Plan v7 §9.2) and shows where people are.
- **Cash payouts at launch.** The strongest incentive. Rejected: payments, tax and fraud
  exposure before anything is proven.
- **Public leaderboards before fraud controls exist.** Cheap recognition. Rejected: a ranking
  is a reward, and it invites farming before there is a way to catch it.
- **Do not record it at all.** Less to read. Rejected: the decisions above were made in
  discussion and would be made again, differently, without a record.

## Consequences

- The idea has a place to live that is not the roadmap. It cannot be mistaken for scope.
- Three rules bind future work from today, through `CLAUDE.md`: no rewards of any kind for
  safety reports; no gallery uploads for contributions; no cash rewards without an ADR and
  legal review.
- If the concept is ever built, the app gains a camera permission, a photo pipeline, a new
  consent purpose, a ledger, moderation work and a commercial partner. Each needs its own ADR.
- A Proposed ADR can be read as a commitment. It is not one; the addendum and `CLAUDE.md` say
  so as well.
- **Open questions** (follow-ups of P012h): whether faces and number plates can be blurred and
  metadata stripped on the device; what can be done against GPS spoofing, and what Play
  Integrity signals offer; which reward partners exist and what they cost; the wording of the
  contributor licence; how long a photo is kept; and everything under tax and payments.
- **What would make us revisit this:** company registration; a funded pilot; a lawyer's or a
  chartered accountant's finding that rules any part of it out; evidence from option A or B
  that people add places without a reward.

## References

- [Addendum v7.6](../plan/addendum-v7.6.md), sections A to J.
- [Addendum v7.4](../plan/addendum-v7.4.md), section D (guardrails) and section F
  (community-feed gates).
- Prompt log: [`docs/prompt-logs/012h-credits-concept.md`](../prompt-logs/012h-credits-concept.md).
- No ADR about "Add a missing place" (P011g) existed when this was written, so none carries a
  pointer to this one.
