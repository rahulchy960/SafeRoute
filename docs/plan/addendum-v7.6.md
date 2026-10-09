# SafeRoute Plan v7 — Addendum v7.6: credits and verified place contributions (concept)

> Copyright (C) 2026 Rahul Chowdhury. Licensed under CC BY-NC-ND 4.0
> (`CC-BY-NC-ND-4.0`), like everything under `docs/`: see [`../LICENSE.md`](../LICENSE.md).

- **Date:** 2026-10-09 · **Prompt:** P012h · **Decision:**
  [ADR 0025](../adr/0025-credits-and-place-contributions.md) (Proposed)
- **This addendum supplements the earlier addenda.** It replaces nothing in them: the
  guardrails of [addendum v7.4](addendum-v7.4.md), section D, the community-feed gates of v7.4,
  section F, and the staged path of [addendum v7.5](addendum-v7.5.md) all stay in force.
- **Reading order: PDF → [v7.1](addendum-v7.1.md) → [v7.2](addendum-v7.2.md) →
  [v7.3](addendum-v7.3.md) → v7.4 → v7.5 → v7.6.** Where they differ, the later document wins.
- The PDF and the texts of v7.1 to v7.5 are unchanged.
- A full **v8** will be written after the MVP and company registration.
- **What this is:** a record of an idea and of the decisions already taken about it, so that
  nobody has to argue them again.
- **What this is not:** a scope change, a schedule or a promise. The MVP prompts keep their
  scope. Nothing here is built, measured, priced or dated. No number in this file is a
  measurement, and none may be quoted in public material.
- **No legal, tax or payments conclusion is drawn here.** Every such point is marked **to be
  verified by a lawyer and a chartered accountant**.

## A. Status and scope

- **Proposed.** Not accepted, not scheduled.
- **Post-MVP and post-company.** Nothing starts before the gates in section H are met.
- **Not part of the closed test.** No tester is offered credits, points, vouchers or prizes
  through the app.
- The idea, in Rahul's words: people who add a place that is missing from the map earn
  credits that can later be exchanged for vouchers.
- The idea exists because the map has gaps. On the map of one small town there were roads
  and buildings but no police station, bus stop, post office, bank, ATM or pharmacy
  ([addendum v7.3](addendum-v7.3.md), section I).

## B. Contribution types

| # | Contribution | Credits |
| --- | --- | --- |
| 1 | **Missing place**: a place that exists and is not on the map | Eligible, under sections C to H |
| 2 | **Still open / closed / moved**: confirming or correcting a place that is on the map | Eligible later, after type 1 has run and been measured |
| 3 | **Safety incident reports** (Plan v7 §9.2) | **Never** |

**Safety data is never gamified.** A safety incident report earns no credits, no points, no
badge that has a value, and no entry in a contest. This is decided, not open.

Why:

- A reward for a report is a reason to invent one, or to make a small thing sound large.
- A reward for being where something happens is a reason to go there, or to stay.
- The safety layer is only worth showing if its reports were made for no gain. Paying for
  them would spoil the one thing it depends on.

## C. Evidence for a missing place

A missing-place contribution is eligible only with **a photo captured inside the app's own
camera flow**.

- **No gallery and no file import.** The app offers no way to attach an existing picture.
- With the photo the app records the **device location and its accuracy at the moment of
  capture**, and a **timestamp**.
- The contributor must be **18 or older**, like every user
  ([ADR 0010](../adr/0010-adults-only-and-consent-records.md)).

Rules for the photo:

- It shows the **sign or the frontage** of the place.
- **No people, no faces, no number plates, no interiors of private property.**
- **Never taken while moving in traffic**, on foot or in a vehicle.
- Before upload, on the device and if feasible: faces and number plates are blurred, and
  device identifiers are removed from the photo's metadata. **Whether this is feasible is not
  known. It is an open question to be researched** (section H, and the follow-ups of P012h).

What this proves, and what it does not:

- It makes **copying from another map harder**: a place has to be visited and photographed.
- It does **not prove that the name is correct**. A person still has to read the sign.
- It does **not stop GPS spoofing**. A false location can be fed to a phone.

## D. Verification and anti-fraud

These are design requirements, to be researched. None is designed or built.

- **No real-time verification by people nearby.** Asking nearby users to confirm something at
  once would conflict with the delays that protect a reporter (Plan v7 §9.2), and it would
  tell people where another user is.
- **Independent confirmation** within a time window, by one of:
  - at least one other contributor's own in-app photo of the same place, or
  - a moderator's review.
- **Duplicate detection**: image hashes, and existing places nearby.
- **Mock-location and device-integrity signals.**
- **Plausibility checks**: speed, and the distance between one submission and the next.
- **Caps** per user, per day and per month.
- **Trust scores** for contributors. (A score about a contributor's record, never about a
  place: [addendum v7.4](addendum-v7.4.md), section D, forbids scores for places.)
- **Sampled audits** by a moderator.
- **A payout delay**, so that fraud can be caught before anything is redeemed.
- **Accounts at least 7 days old.**
- **One account per verified phone number.**

## E. Data, licensing and privacy

- **A separate consent purpose**, for example `place_contributions`: asked just in time,
  revocable, on the pattern of [ADR 0010](../adr/0010-adults-only-and-consent-records.md).
  Never bundled with another purpose.
- **The contributor's identity is stored apart from the place record.** A place on the map
  does not say who added it.
- **That a contributor was at a place at a time is personal data**, and it is treated as such.
- **Photo retention**: the proposal is to delete the original photo after verification and
  keep the derived place record. The limit is to be decided with a lawyer.
- **Encryption at rest** for photos and contributor records.
- **Access and erasure** under the DPDP Act for everything a contributor supplied (to be
  verified by a lawyer).
- **A contributor licence**: the contributor grants SafeRoute a licence for the photos and the
  place data. The wording is to be written by a lawyer.
- **OpenStreetMap**: a contribution goes to OpenStreetMap only under OpenStreetMap's own
  contributor terms, and only from the contributor's own photos or observations.
- **Never from Google Maps or any other protected source.** Not the name, not the position,
  not the opening hours, not a photo.
- **Store disclosures**: Play's Data safety form and the camera-permission disclosure change
  when this exists.
- **A third-party reward partner**, if there is one, is named in the privacy policy and in
  the consent notice.

## F. Rewards

- **A points ledger**: append-only and auditable.
- **Points have no cash value**, and may be changed or may expire. The terms are to be
  written by a lawyer.
- **Recognition first, vouchers later.** The first stage is badges and levels with no
  monetary value. Vouchers come later, if at all.
- **Redemption through a reward partner, as vouchers.** Not cash, and not a wallet transfer.
- **The rate is not decided.** An indicative idea of 10,000 points for ₹200 was mentioned. It
  is an **unvalidated proposal**. It has not been costed, tested or reviewed, and **it must
  not appear in any public material**: not the pitch, the website, the store listing, the
  README or the app.
- **Funding first.** A source of money (sponsors, grants or the company's budget) exists
  before any redemption is offered.
- **Written terms** before launch: eligibility, expiry, how a dispute is handled, and a
  grievance contact.
- **No purchase is required** to earn or to redeem.
- **No reward for risky behaviour.**
- **Payout details are additional personal data**, with their own retention and consent.
- **To be verified by a lawyer and a chartered accountant:** GST, tax deduction at source,
  the tax position of a user who receives a reward, payments rules, and the rules for prize
  promotions. This addendum says nothing about what those rules are.

## G. Product path

| Option | What | Credits | When |
| --- | --- | --- | --- |
| **A** | A guide that leads the user to the OpenStreetMap editor to add the place there | None | Planned as P011g; not built at the time of writing |
| **B** | In-app submission as an OpenStreetMap note, or through the user's own OpenStreetMap login | None | After the MVP core |
| **C** | SafeRoute's own place database | Required for credits | Only after the gates in section H |

- **Credits need option C.** Verification and attribution have to happen inside SafeRoute;
  an edit made in someone else's editor cannot be verified or credited by SafeRoute.
- **A mapping contest, by hand, without the app**, may run as a pilot: friends or students
  add places to OpenStreetMap, Rahul judges, the prizes are small, and **the no-copying rule
  is written into the entry rules**. Whether such a contest needs anything legally is to be
  verified by a lawyer and a chartered accountant before it runs.

## H. Gates before any build

All of these, not some:

1. A **registered company**.
2. **Lawyer and chartered-accountant review** of rewards, tax, payments, consent, photo
   handling and the terms.
3. A **reward partner** and **funding**.
4. The **option C pipeline**, with moderation tools and the capacity to use them.
5. The **fraud design validated on a small pilot**.
6. A **privacy review of the photo pipeline**, including the answer to the open question in
   section C.
7. A **Play policy review**: user-generated content and the camera permission.
8. **The product guardrails apply** ([addendum v7.4](addendum-v7.4.md), section D): no
   paywall on safety features, no ads in safety flows, no targeting by location history, no
   selling of personal data, adults only.

A gate that is "nearly met" is not met.

## I. Rejected alternatives

| Alternative | Why not |
| --- | --- |
| Credits for safety reports | Section B: it pays for invented or exaggerated incidents and for risky behaviour, and spoils the safety layer |
| Gallery uploads | A picture from a gallery can come from anywhere, another map included; the capture location and time would mean nothing |
| Real-time verification by nearby users | It conflicts with the reporter-protecting delays (Plan v7 §9.2) and reveals where people are |
| Cash payouts at launch | Payments, tax and fraud exposure before anything is proven; vouchers through a partner come first, and cash needs its own ADR and legal review |
| Public leaderboards before fraud controls exist | A ranking is a reward too, and it invites farming before there is a way to catch it |

## J. Risks

| Risk | Note |
| --- | --- |
| Copying from protected sources to earn rewards | The in-app photo makes it harder, not impossible |
| GPS-spoofing farms | Section C says plainly that the photo does not stop this |
| Photos of people or private property | Rules, on-device blurring if feasible, moderation |
| Contributor tracking, and identifying a reporter | A contribution records where a person was; kept apart from the place, with a retention limit |
| Legal and tax exposure | Nothing is decided here; lawyer and chartered accountant first |
| Cost of fraud losses and of unredeemed obligations | Points that were promised are owed; a payout delay and caps limit it |
| Moderation load | One developer; option C needs moderators before it opens |
| Brand dilution | A safety app that pays for map edits can look like a rewards app |
| Scope sprawl for a solo developer | The gates exist to stop this from starting early |
| Users taking risks to earn rewards | No reward for risky behaviour; never a photo taken in traffic |

## K. Plan edits

- No section of Plan v7 changes. This addendum adds a concept that Plan v7 does not have.
- [ADR 0025](../adr/0025-credits-and-place-contributions.md) records the decision as Proposed.
- `CLAUDE.md`, "Product guardrails", gains four short rules from sections B, C and F.
