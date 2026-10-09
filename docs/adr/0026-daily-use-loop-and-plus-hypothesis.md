# ADR 0026: Daily-use loop, SafeCircle Plus hypothesis, Safe Date mode and rejected ideas

- **Status:** Proposed
- **Date:** 2026-10-09
- **Prompt:** P012i
- **Plan refs:** Plan v7 §1, §3.3, §8; [addendum v7.7](../plan/addendum-v7.7.md); [addendum v7.4](../plan/addendum-v7.4.md) B, C, D, F, G; [addendum v7.6](../plan/addendum-v7.6.md); [ADR 0010](0010-adults-only-and-consent-records.md), [ADR 0011](0011-trusted-circle-principles.md), [ADR 0021](0021-post-mvp-product-direction.md)

No legal, tax or payments conclusion is drawn here. Every such point is **to be verified by a
lawyer** (and, for tax and payments, a chartered accountant). This ADR contains no price and
no revenue figure, on purpose.

## Context

- Rahul asked how SafeRoute can be used daily and how it can earn money.
- A safety app is opened rarely. [ADR 0021](0021-post-mvp-product-direction.md) answered that
  with "daily utility first" (map, search, routes, journeys) and left monetization as three
  lines: a Plus tier, Teams, and promoted listings at scale.
- Two further ideas came up: a tab of anonymous confessions that people pay to read, and a
  dating app.
- The journey engine and the Trusted Circle are designed on paper only. The Trusted Circle
  ([ADR 0011](0011-trusted-circle-principles.md)) is Proposed and waits for a lawyer.
- SafeRoute is not a registered company and cannot charge anyone.
- The app has no analytics. Nothing about use or willingness to pay has been measured.
- Nothing in this ADR is built or measured. It is **Proposed**: it records a direction and
  some refusals for Rahul to accept, change or reject. No code depends on it, and no prompt
  starts work on the strength of it.

## Decision

1. **The daily loop is passive.** Automatic "arrived" and "running late" notices between
   consenting adults, on journeys the traveller starts. The realistic target is use about once
   a week plus passive value every day, not daily opens.
2. **The traveller controls it**, as ADR 0011 requires: who sees what, for how long, with an
   instant exit; no hidden mode, no remote activation, no history browsing, nothing visible
   outside an active journey. **None of this can be bought, in any tier, by a person or an
   institution.**
3. **The circle is the paying unit, as a hypothesis.** The person who worries is usually the
   one who would pay.
4. **"SafeCircle Plus" is a hypothesis**: paid for things that cost money to run or go beyond
   the basics (more circle members, automatic arrival and late notices for saved places,
   server-sent SMS to contacts without the app, priority support).
5. **"Teams" is a hypothesis**: the same mechanism bought by an institution, with end-of-shift
   check-ins as its loop.
6. **Principles that are not hypotheses:** never paywall SOS, 112, basic live sharing or basic
   check-ins; no ads in safety flows; no selling or sharing of personal or location data;
   never charge to read other people's disclosures or safety reports.
7. **"Safe Date" is kept as a journey type**: no matching, no profiles, no chat; it works with
   any dating app. Wording says "safer" with a named feature, never "safe".
8. **A confession or anonymous-stories tab with a paid read is rejected.**
9. **A dating app is rejected.**
10. **Validate before building:** a second survey round, a manual pilot of 10 to 20 adult
    pairs, a "coming soon" interest check that takes no payment details, and 8 to 10 buyer
    interviews.
11. **Prices, revenue figures and benchmark numbers stay out of the repository and out of
    every public material.** They are kept in the private Notion backlog, marked as
    hypotheses.
12. **Gates, all of them, before any build:** a registered company; lawyer and
    chartered-accountant review; SafeCircle cleared under ADR 0011; server SMS before any
    SMS-based paid feature; a payment integration review; a Play billing policy review; the
    product guardrails of addendum v7.4, section D.

The details are in [addendum v7.7](../plan/addendum-v7.7.md).

## Alternatives considered

- **A confession tab that people pay to read.** It would bring daily visits. Rejected: it
  earns from vulnerable disclosures; it brings a duty of care for self-harm and abuse content,
  takedown and defamation exposure, and the risks of anonymity; and it accuses and names
  people, which the guardrails forbid.
- **A dating app.** A large market. Rejected: cold start against very large incumbents,
  limited identity verification, moderation around the clock, scams and minors, liability for
  any "safe dating" claim, and scope sprawl. The incumbents already offer photo verification,
  ID verification and date-plan sharing (addendum v7.7, section D).
- **Force daily opens** (streaks, daily prompts, a feed). Rejected: it works against the
  nature of a safety tool, and addendum v7.4 already puts a feed behind eight gates.
- **Ads.** Rejected in every safety flow by the guardrails; promoted listings stay as ADR 0021
  left them: only at scale and outside safety flows.
- **Charge the traveller per feature.** Simpler to build. Not chosen as the first hypothesis:
  the traveller is rarely the one who feels the need.
- **Sell location or journey data.** Never: the guardrails forbid it.
- **Do not record it.** Rejected: the refusals above would be argued again.

## Consequences

- The monetization lines of addendum v7.4, section G now have a shape that can be tested, and
  a testing order that starts with people, not with code.
- Five rules bind future work from today, through `CLAUDE.md` (addendum v7.7, sections C, D
  and E).
- A feature built for "the person who worries" is also attractive to a person who controls.
  That makes ADR 0011's principles more important, not less, and it is why they are excluded
  from every tier.
- Automatic arrival notices need background location, saved places on a server and a
  server-side timer. Each breaks a promise the app makes today (foreground only, nothing
  stored: ADR 0015, ADR 0022) and needs its own ADR, consent purpose and lawyer review.
- An interest check cannot count taps, because the app has no analytics. Adding any is a
  separate decision.
- A Proposed ADR can be read as a commitment. It is not one.
- **What would make us revisit this:** survey and pilot results; a lawyer's finding on the
  Trusted Circle, on subscriptions or on institutional use; company registration; evidence
  that travellers, not worriers, are the ones who would pay.

## References

- [Addendum v7.7](../plan/addendum-v7.7.md), sections A to I.
- [Addendum v7.4](../plan/addendum-v7.4.md), sections B, C, D and G.
- [ADR 0011](0011-trusted-circle-principles.md) and
  [ADR 0021](0021-post-mvp-product-direction.md), each with a note of 2026-10-09 that points
  here.
- Prompt log: [`docs/prompt-logs/012i-daily-use-and-monetization.md`](../prompt-logs/012i-daily-use-and-monetization.md).
