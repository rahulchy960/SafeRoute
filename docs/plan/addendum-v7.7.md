# SafeRoute Plan v7 — Addendum v7.7: daily-use loop, monetization hypotheses and rejected ideas

> Copyright (C) 2026 Rahul Chowdhury. Licensed under CC BY-NC-ND 4.0
> (`CC-BY-NC-ND-4.0`), like everything under `docs/`: see [`../LICENSE.md`](../LICENSE.md).

- **Date:** 2026-10-09 · **Prompt:** P012i · **Decision:**
  [ADR 0026](../adr/0026-daily-use-loop-and-plus-hypothesis.md) (Proposed)
- **This addendum supplements the earlier addenda.** It replaces nothing in them. The journey
  engine and the guardrails of [addendum v7.4](addendum-v7.4.md) (sections B, C, D and G),
  the Trusted Circle principles of
  [ADR 0011](../adr/0011-trusted-circle-principles.md), and the gates of
  [addendum v7.6](addendum-v7.6.md) all stay in force.
- **Reading order: PDF → [v7.1](addendum-v7.1.md) → [v7.2](addendum-v7.2.md) →
  [v7.3](addendum-v7.3.md) → v7.4 → v7.5 → v7.6 → v7.7.** Where they differ, the later
  document wins.
- The PDF and the texts of v7.1 to v7.6 are unchanged.
- A full **v8** will be written after the MVP and company registration.
- **What this is:** a record of how SafeRoute could be used often and how it could earn
  money, of the ideas that were rejected, and of how to test the rest without writing code.
- **What this is not:** a scope change, a schedule, a price list or a promise. The MVP
  prompts keep their scope. Nothing here is built or measured.
- **This file contains no price and no revenue figure, on purpose.** Prices and revenue
  figures are hypotheses. They are kept in the private Notion backlog, marked "hypothesis, not
  for public use", and they must not appear in any public material, this repository included.
- **No legal, tax or payments conclusion is drawn here.** Every such point is marked **to be
  verified by a lawyer** (and, for tax and payments, a chartered accountant).

## A. Status

- **Proposed.** Not accepted, not scheduled.
- **Post-MVP.** Nothing is built. Nothing starts before the gates in section H are met.
- The question behind it, from Rahul: how can SafeRoute be used daily, and how can it pay
  for itself?

## B. The daily loop

**A passive loop works better than a feature people have to open on purpose.** The loop is
two automatic notices between consenting adults, on journeys they make regularly:

- **"Arrived"**: the traveller reached the place they set out for.
- **"Running late"**: the arrival window has passed and the traveller has not arrived yet.

It needs things that do not exist yet: the journey engine ([addendum v7.4](addendum-v7.4.md),
sections B and C) and the Trusted Circle
([ADR 0011](../adr/0011-trusted-circle-principles.md), called SafeCircle in the product
notes). ADR 0011 stays Proposed until a lawyer has reviewed it; no code before that.

**Who it is for.** The person who gets value every day is often not the traveller. It is the
person who worries about them. That has two consequences, recorded as hypotheses in section C:
the worrier is usually the one willing to pay, and the unit that pays is the circle, not the
individual.

**What the traveller controls.** Everything:

- **who** sees a journey, chosen per person;
- **what** they see: a status ("on the way", "arrived", "running late"), or also the live
  position while the journey is active, as the traveller chose;
- **for how long**: a journey has an end, and a circle link has an expiry (ADR 0011);
- **an instant exit**: the traveller stops a journey or leaves a circle at once, and the
  other side is told.

**What the feature never has:**

- **No hidden mode.** While anything is shared, the traveller's phone shows it.
- **No remote activation.** Only the traveller starts a journey. Nobody can start, extend or
  force one from another phone.
- **No history browsing.** A circle member cannot look up where the traveller was yesterday,
  or last week.
- **Nothing outside an active journey.** The person who worries sees a status only while a
  journey is active. Between journeys they see nothing.

**False alarms are part of the design** (addendum v7.4, section B): a grace period after the
arrival window; the traveller is asked first; "I'm fine" in one tap stops the escalation. The
wording is "check in", never "alarm".

**The realistic target, stated plainly:** people will not open a safety app every day. Daily
use of such an app stays structurally low. The target is **use about once a week, plus passive
value every day** (a notice that arrives without anyone opening anything). The design aims at
that, and not at forcing people to open the app.

## C. Monetization hypotheses

Everything in this section is a **hypothesis**. None of it has been tested.

### Principles (not hypotheses; these hold whatever is tested)

- **Never paywall SOS, the 112 dialer, basic live sharing or basic check-ins.**
- **No ads in any safety flow.**
- **No selling and no sharing of personal data or location data.**
- **Never charge to read other people's disclosures or safety reports.**
- **Prices are hypotheses.** Indicative ranges to test are kept in the private Notion backlog
  only. No price is in this repository.
- **A registered company is needed before anything is charged.**
- **To be verified by a lawyer and a chartered accountant:** payments rules, tax, and
  consumer-protection rules for subscriptions (renewal, cancellation, refunds, disclosure).

### Hypothesis 1: "SafeCircle Plus" (the circle pays)

| | Free | Paid ("Plus") |
| --- | --- | --- |
| SOS, 112 dialer, notification shortcuts | Yes | Yes |
| Live sharing | Time-limited | Time-limited (the limit is a privacy rule, not a paywall) |
| Circle links | 1 to 2 | More members |
| Arrival | Manual "I've arrived" | Automatic arrival and late notices for saved places |
| Contacts without the app | Reached by SMS from the user's own phone (the device-first SOS design, Plan v7 §7) | SMS fallback sent by the server, once server SMS exists |
| Support | Standard | Priority |

- **Why SMS fallback is a natural boundary:** a server-sent SMS costs money for every message.
  It also needs a registered company and the sender registration that Indian telecom rules
  require (TRAI DLT; to be verified by a lawyer). It cannot exist before both.
- **What stays free is decided by the principles above**, not by this table. If a row of the
  table ever conflicts with a principle, the principle wins.
- **Options to test, not decisions:** UPI autopay; an annual plan.

### Hypothesis 2: "Teams" (an institution pays)

- The same mechanism, bought by an employer, a college, a hostel or an event organiser for
  its people.
- The daily loop there is a **check-in at the end of a shift** or on arrival home.
- **The same rules apply to an institution as to a person:** the traveller starts every
  journey; no hidden mode; no history browsing; no tracking outside an active journey. An
  institution that pays does not buy the right to watch. Whether an employer or a college may
  make such a tool a condition of work or residence is **to be verified by a lawyer**.

### Order of testing

1. Whether pairs of adults use a manual version at all (section F, step 2).
2. Whether the person who worries says they would pay (survey and pilot).
3. Whether institutions want it (buyer interviews).
4. Only then: a price test, after company registration and the reviews in section H.

### On benchmarks

A public benchmark exists for a large family-safety app (monthly users, paying circles,
revenue per paying circle, and the share of revenue from outside its home market). Its figures
are kept in the private Notion backlog with their source and date, to be verified before
anyone quotes them. Two things are recorded here without numbers:

- most of that app's subscription revenue comes from one high-income market;
- **revenue per user in India will be far lower. Its revenue assumptions must not be copied.**

## D. Rejected or deferred ideas

### A confession or anonymous-stories tab, with a subscription to read: rejected

- It would **earn money from vulnerable disclosures**. People would pay to read what others
  wrote at a bad moment.
- **Duty of care.** Such a feed receives posts about self-harm and abuse. Somebody has to
  respond, at any hour.
- **Intermediary and takedown duties**, and **defamation** (to be verified by a lawyer).
- **Anonymity cuts both ways:** it protects a writer and it shields an abuser or a liar.
- It **conflicts with the guardrails** against naming or accusing people: safety content
  describes incidents and conditions, never persons (Plan v7 §1; addendum v7.4, section D).
- **Possible duties to report child abuse** when a post discloses it (to be verified by a
  lawyer).
- It is user-generated content, which is behind every gate of addendum v7.4, section F.

**If it is ever revisited:** free to read; moderated; with NGO or counsellor partners; with
verified crisis helplines shown; and never monetized by reading. It would need its own ADR.

### A dating app: rejected

- **Cold start** against very large incumbents.
- **Identity verification:** what a private company in India may check, and against what, is
  limited (to be verified by a lawyer).
- **Moderation around the clock.**
- **Scams, and minors** getting in.
- **"Safe dating" claims create liability** and conflict with the claims rules: SafeRoute
  never calls a place, a route or a person safe.
- **Scope sprawl** for a solo developer who has not finished the MVP.

**What large dating apps already offer** (as found on 2026-10-09 through a web search; the
company pages were not all opened, and features differ by country and change often, so check
the source before relying on any of this):

| Feature | Seen at | Source |
| --- | --- | --- |
| Photo verification (video selfie compared with profile photos) | Tinder; Hinge ("Selfie Verified", later a required "Face Check") | [Tinder help centre](https://www.help.tinder.com/hc/en-us/articles/19868368795917-ID-Photo-Verification); [Hinge help centre](https://help.hinge.co/hc/en-us/articles/45715796564243) |
| ID verification (a government ID checked against the selfie) | Tinder; Bumble (reported launched in March 2025 in a number of countries, India among them) | Tinder help centre, as above; [TechCrunch, 17 March 2025](https://techcrunch.com/2025/03/17/bumble-heightens-safety-measures-with-new-id-verification-feature/); [Bumble's own post](https://bumble.com/the-buzz/bumble-dating-features) |
| Sharing the plan for a date with chosen contacts | Tinder "Share My Date" (reported April 2024); Bumble "Share Date" (reported March 2025) | Bumble's post and the TechCrunch article, as above |
| Emergency-assistance partnership | Match Group and Noonlight, announced for Tinder in the United States in January 2020. Whether it is still offered was **not confirmed** | [Tinder's announcement](https://blog.gotinder.com/tinder-introduces-safety-updates); [TechCrunch, 23 January 2020](https://techcrunch.com/2020/01/23/match-group-invests-in-noonlight-to-power-new-safety-features-in-tinder-and-other-dating-apps) |

- Hinge's own help page says that its verification "doesn't guarantee the identity or safety"
  of a user. The incumbents do not claim "safe" either.
- **"Safety rankings" of dating apps written by a competitor, or by a site that earns from
  referrals, are not reliable sources** and are not used here.

## E. Safe Date mode (kept from the dating idea)

A **journey type**, not a dating feature. Post-MVP, and only after the Trusted Circle is
cleared (ADR 0011).

- The traveller shares **who** they are meeting, **where** and **when**, with contacts they
  choose.
- **A check-in timer.** When it runs out, the traveller is asked first; only then the circle
  (addendum v7.4, section C).
- **Live sharing during the trip**, time-limited, visible on the traveller's phone.
- **One-tap SOS**, as everywhere in the app.
- **Suggested public meeting places** from map data, labelled "may be incomplete"
  (addendum v7.3, section I). A suggestion says that a place is public and on the map. It
  never says that a place is safe.
- **Adults only.**
- **No matching, no profiles, no chat.** It works with any dating app, and with none.
- What the traveller writes about the other person is that person's personal data. Where it
  is stored, who sees it and when it is deleted is **to be verified by a lawyer**.

**Wording rule.** Say **"safer"**, and name the specific feature that makes it so ("your
friend gets a message if you don't check in"). **Never "safe"** (Plan v7 §3.3 and the claims
rules).

A later partnership with dating apps is possible. It is **speculative**: nobody has been asked.

## F. Validation plan (no code)

1. **A second survey round**, adults only, with the questions below.
2. **A manual pilot**: 10 to 20 pairs of adults, for two weeks, after live sharing exists
   (P016). It measures weekly active pairs, how often "arrived" is used, and what people say
   they would pay.
3. **A "Plus" interest check** during the closed test: a row that is honestly labelled
   "coming soon" and collects **no payment details**. Counting taps on it needs analytics,
   and **the app has no analytics today**. Unless a later decision adds them (with a privacy
   design and consent), the interest check is done by survey instead.
4. **8 to 10 interviews with institutional buyers.**

**Success measures** (no target numbers are set here): weekly active pairs; the share of users
with at least one circle link; arrival-notice usage; stated willingness to pay; how many
interviews turn into a pilot.

### Survey questions (adults only)

1. Who would worry about you on your regular trips: a parent, a partner, a friend, nobody?
2. Would that person want an automatic "arrived" message?
3. Would you want them to have it?
4. Would they pay for it, and roughly how much per month? (Answer in ranges.)
5. Do you meet people from dating apps in person?
6. Did you tell anyone where you were going?
7. What did you do for safety?
8. Would a date-safety mode help?
9. Would you pay for it?
10. What would make you switch it off?

## G. Risks

| Risk | Note |
| --- | --- |
| Coercive monitoring and stalking misuse | The central risk. A feature for "the person who worries" is also a feature for a person who controls. ADR 0011's principles are the answer, and they are not for sale in any tier |
| False alarms | Grace periods, the traveller asked first, "I'm fine" in one tap |
| Privacy of saved places and journey data | Home and work say where a person sleeps and spends each weekday (addendum v7.4, section B) |
| Low willingness to pay in India | The reason the benchmark's assumptions must not be copied |
| Scope sprawl | One developer; the MVP is not finished |
| Moderation duties if social features are ever added | The reason both rejected ideas were rejected |
| Consumer-protection exposure from subscription practices | Renewal, cancellation and refund rules, to be verified by a lawyer |
| Overclaiming safety | "Safer" with a named feature, never "safe" |

## H. Gates before any build

All of these, not some:

1. A **registered company**.
2. **Lawyer and chartered-accountant review**: subscriptions, payments, tax, consent, the
   exclusion of minors, and the Trusted Circle design.
3. **SafeCircle cleared** as ADR 0011 requires.
4. **Server SMS** (TRAI DLT registration) before any paid feature that depends on SMS.
5. A **payment integration review**.
6. A **Play billing policy review**.
7. **The product guardrails apply** (addendum v7.4, section D).

## I. Plan edits

- No section of Plan v7 changes.
- [ADR 0026](../adr/0026-daily-use-loop-and-plus-hypothesis.md) records the decision as
  Proposed. ADR 0011 and ADR 0021 each gain a dated note that points to it; their texts are
  unchanged.
- Addendum v7.4, section G ("Monetization fit") stays as written. This addendum adds detail
  to its "Plus tier" and "Teams" lines and decides no price, limit or tier boundary either.
- `CLAUDE.md`, "Product guardrails", gains short rules from sections C, D and E.
