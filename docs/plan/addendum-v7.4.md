# SafeRoute Plan v7 — Addendum v7.4: post-MVP product direction and guardrails

> Copyright (C) 2026 Rahul Chowdhury. Licensed under CC BY-NC-ND 4.0
> (`CC-BY-NC-ND-4.0`), like everything under `docs/`: see [`../LICENSE.md`](../LICENSE.md).

- **Date:** 2026-10-08 · **Prompt:** P012d · **Decision:**
  [ADR 0021](../adr/0021-post-mvp-product-direction.md)
- **Where this addendum differs from Plan v7, [addendum v7.1](addendum-v7.1.md),
  [addendum v7.2](addendum-v7.2.md) and [addendum v7.3](addendum-v7.3.md), v7.4 wins.** Read the
  PDF first, then v7.1, v7.2, v7.3 and this file.
- The PDF and the texts of v7.1, v7.2 and v7.3 are unchanged.
- A full **v8** will be written after the MVP and company registration.
- **What this is:** Rahul shared a product idea document (daily commute, SafeCircle,
  SafeExplore, a nearby feed, a community feed, a weekend mode, monetization). This addendum
  records what was decided about it: the order after the MVP, one shared mechanism (the journey
  engine), and guardrails for every future feature.
- **What this is not:** a scope change. The MVP prompts (P013–P022) keep their scope. Nothing
  here is built, measured or scheduled with dates.
- **Names.** "SafeCircle" is the idea document's name for the feature that
  [ADR 0011](../adr/0011-trusted-circle-principles.md) calls Trusted Circle. They are the same
  feature; ADR 0011 governs it. "Safe Commute", "SafeExplore", "Journeys", "Plus" and "Teams" are
  working names, not decided product names.
- Legal points are marked **to be verified by a lawyer**. This file draws no legal conclusion.

## A. Direction and sequence

**Direction: daily utility first, safety throughout.** People open a navigation app every day
and a safety app almost never. SafeRoute earns its place through everyday use (map, search,
routes, journeys), and the safety tools are present in every one of those surfaces, not in a
separate corner.

The order after the MVP. A later step does not start before the earlier ones it depends on, and
each step is built by its own prompts:

| Step | What | Depends on / gate |
| --- | --- | --- |
| 1. MVP+ | Police numbers per jurisdiction; one-tap WhatsApp share; SMS location check-ins; nearby essentials from map data (hospitals, police stations, late-night pharmacies and petrol pumps) | The MVP. Police numbers only when verified (addendum v7.2, section F). Nearby essentials follow section E |
| 2. Journeys v1 | The journey engine (section B) for one traveller: Safe Commute | Routing and follow-me (P012); live sharing (P016); lawyer review of saved places and check-ins (**to be verified by a lawyer**) |
| 3. SafeCircle | Journeys and sharing with a circle of trusted adults | ADR 0011, which stays Proposed until a lawyer has reviewed it; no code before that |
| 4. Company-gated channels | Server-sent SMS, automatic WhatsApp messages, automated voice calls | A registered company; the sender registrations each channel requires (**to be verified by a lawyer**) |
| 5. Broader Explore | Discovery beyond essentials, with curated content | A places or events source that passes the terms check (section E); someone to curate |
| 6. Community feed and weekend mode | User posts; leisure-oriented discovery | Only with traction, a moderation team and legal review: every gate in section F |

"Traction" in step 6 is not defined here. It needs a measured number, recorded in the plan,
before anyone argues that the gate is met.

## B. Journey engine

One mechanism serves Safe Commute (a traveller alone) and SafeCircle (a traveller who shares
with a circle). It is designed once, in Journeys v1, so that the circle is added on top and not
beside it.

**A journey is:**

- an origin and a destination;
- an **expected arrival window, set by the traveller** (never by anyone else, and never
  inferred silently);
- an **arrival check**: automatic detection near the destination, or the traveller taps
  "I've arrived";
- a **missed-arrival prompt, to the traveller first**. Only then, and only if the journey is
  shared, to circle members (section C).

**Rules:**

- **A journey is started by the traveller, on their own phone.** Nobody else can start, extend
  or force one. This is ADR 0011's "no remote activation", applied to journeys.
- **No continuous background tracking beyond the user-started journey.** Tracking begins when
  the traveller starts a journey and ends when it ends. There is no always-on mode.
- **Saved places are stored on the device only, at first.** Home and work are sensitive: they
  say where a person sleeps and where they are every weekday. Syncing them to the server needs
  its own consent purpose (ADR 0010), encryption and a retention design, each decided in the
  prompt that builds it (**to be verified by a lawyer**).
- **False-alarm design is part of the feature, not a later fix:**
  - a grace period after the arrival window ends;
  - escalation in steps, each one visible to the traveller before it happens;
  - a one-tap "I'm fine" that stops the escalation.
- A journey is not an SOS. The SOS button and the 112 dialer stay available during a journey
  and work as they do everywhere else (device-first, Plan v7 §7).

**Open, decided in the prompts that build it:** how arrival is detected and at what distance;
the length of the grace period; what happens when the phone has no signal at the destination;
which Android permissions a journey needs (today the app is foreground-only, ADR 0015; a
journey that survives the screen turning off needs a foreground service, its own permission
prompt and a changed location disclosure).

## C. Missed-arrival alerts

- **A server-side timer is needed.** The phone may be dead, switched off or without signal at
  the moment the arrival window ends; a timer that lives only on the phone would stay silent
  exactly then. This means the server learns that a journey exists and when it should end.
  What it stores, for how long, and under which consent purpose is decided in the prompt that
  builds it (**to be verified by a lawyer**).
- **Order of channels:**
  1. a prompt to the traveller;
  2. push notifications to circle members who have the app;
  3. SMS to others, **only after server-sent SMS exists**. That needs a registered company and
     the sender registration that Indian telecom rules require (TRAI DLT; **to be verified by a
     lawyer**), so it belongs to step 4 of section A.
- **Never alert the circle without a prior prompt to the traveller.** The one exception: the
  traveller opted in to automatic escalation for that journey, beforehand, knowing what it
  does.
- **Wording: "check in", not "alarm".** A missed arrival usually means a late bus or a flat
  battery. The message to a circle member says that the traveller has not confirmed arrival
  and suggests checking in with them. It never says or implies that something has happened.
- An alert is not a call for help. Every alert surface keeps the one-tap 112 dialer, and no
  text suggests that SafeRoute contacts the police or anyone else by itself.

## D. Guardrails (every future feature)

These apply to every feature after the MVP, including those not listed in this addendum. Each
restates or extends a rule that already exists.

**Safety information:**

- **No safety score, risk label ("Low risk", "High risk"), ranking or "safe" claim** about a
  place, a route or a neighbourhood. This extends Plan v7 §3.3 and addendum v7.3, section D to
  discovery, journeys and feeds.
- **Show counts and facts, with their period and source** (Plan v7 §3.3, §10). A number of
  reports in a named category, over a stated period, from a stated source is a fact; an
  adjective about the area is a label.
- **No traffic claims without a licensed data source.** Durations are estimates without
  traffic (ADR 0020) until such a source exists and its terms are recorded.

**Money and data:**

- **No ads or promotions in any safety flow**: SOS, the 112 surfaces, live sharing, check-ins,
  journeys, missed-arrival alerts, reports and the safety layer.
- **No targeting by location history.**
- **No selling or sharing of personal data.**
- **Never paywall SOS, 112, basic live sharing or basic check-ins.**

**Existing decisions that stay in force:**

- City-neutral naming ([ADR 0005](../adr/0005-product-name-and-multi-city-readiness.md)).
- Adults only ([ADR 0010](../adr/0010-adults-only-and-consent-records.md)); consent per purpose
  and just-in-time.
- SafeCircle follows [ADR 0011](../adr/0011-trusted-circle-principles.md): mutual, visible,
  time-limited, instant leave, no hidden mode, no remote activation.
- The coverage claims rule (addenda v7.2 and v7.3) applies to every new surface.

A feature that cannot be built inside these guardrails needs a new ADR that says which one it
changes and why, before any code.

## E. Discovery data rules

- **Nearby essentials use open map data, with attribution.** The source, its licence and the
  attribution text are recorded when the feature is built.
- **Open map data is incomplete, especially around small towns**, and opening hours are often
  missing or stale. A listing is "from map data", never a promise that a place is open, staffed
  or able to help. "Late-night" is shown only where the data carries hours, with that caveat.
- A police station on the map is a place, not a number to call: phone numbers follow the
  verified-source rule (addendum v7.2, section F), and unknown means 112 only.
- **Any places or events source needs a terms check before its adapter is written**: caching,
  attribution, commercial use, and any clause about safety or emergency use. The finding is
  recorded in an ADR in our own words, as ADR 0018 did for geocoding.
- **No user-generated content before the community-feed gates** (section F). Until then,
  discovery shows map data and curated content only: no user reviews, ratings, photos or
  comments.
- Discovery is described with the three coverage layers. It is navigation content, not safety
  data, and it never implies that a listed place or its surroundings are safe.

## F. Community feed gates

**All of these are required before any build.** Not before launch: before the first line of
feed code.

1. **A registered company.**
2. **Named moderators with backups.** The names are kept outside this public repository.
3. **Moderation tooling and an SLA.**
4. **Google Play user-generated-content policy compliance:** in-app reporting, blocking and
   moderation.
5. **Lawyer review** of defamation, privacy, minors and intermediary obligations (**to be
   verified by a lawyer**).
6. **A photo handling design:** metadata stripping, and how faces and number plates are
   handled.
7. **Safety-data separation: posts never become safety facts.** A post does not feed the
   safety layer, a cell count or the exposure metric. Safety data comes only from the report
   flow of Plan v7 §9.2 and its moderation.
8. **Abuse and brigading controls.**

Weekend mode waits for the same gates wherever it shows user content.

## G. Monetization fit

- **Plus tier:** for features that cost money to run, such as extra circle members and
  automated channels (server SMS, WhatsApp, voice). It never contains anything on the
  never-paywall list in section D.
- **B2B "Teams":** kept in separate notes, outside this addendum.
- **Promoted listings:** only at scale, and outside every safety flow. A promoted listing is
  labelled as promoted and never affects safety information or route choice.
- **Every claim about revenue stays out of public materials until measured.** No projections,
  prices or conversion figures in the README, the pitch, the website or the store listing.
- No price, limit or tier boundary is decided here. Payments, consumer-protection wording and
  tax are **to be verified by a lawyer** after company registration.

## H. Risks

These add to Plan v7 §22 and the risk lists in addenda v7.1 to v7.3.

| Risk | What it means | What reduces it (not removes it) |
| --- | --- | --- |
| Coercive monitoring through SafeCircle | A partner or relative pressures someone to share journeys | ADR 0011 as written; journeys only the traveller starts; lawyer review before any code |
| False alarms | A late bus alarms a family; repeated alarms get ignored | Traveller prompted first; grace period; steps; "I'm fine"; "check in" wording |
| Privacy of saved places | Home and work reveal a person's routine | Device-only at first; sync needs consent, encryption and retention |
| Scope sprawl for a solo developer | Six steps on top of an unfinished MVP | The sequence in section A; MVP scope unchanged; one step at a time |
| Empty discovery content | Thin map data makes discovery look broken, most of all outside cities | Essentials only at first; honest "from map data" wording; contributions to open map data |
| Moderation load | A feed adds content review to report moderation | Section F: no feed without named moderators, tooling and an SLA |
| Brand dilution | A leisure feed makes a safety product look like a social app | Daily utility first, safety throughout; no ads in safety flows; feed last |
| Misleading "score" wording | A count, a badge or a colour is read as a safety rating | Section D; counts with period and source; no labels |

## I. Plan edits

These add to the tables in v7.1, section C, v7.2, section I and v7.3, section J. Where they name
the same section, this table wins.

| § | Change |
| --- | --- |
| §3.3 | The "no scores, rankings or safe labels" rule extends to places, routes and neighbourhoods in every future feature, and adds risk labels and unlicensed traffic claims (section D). |
| §8 | Journeys (section B) are a later addition to live sharing by link, not a replacement. |
| §18 | P013–P022 keep their scope. P013–P016 leave room for circle consent purposes and journeys: a note for their authors, no scope change. |
| §22 | Risks, add: the eight rows of section H. |
