# P012d: post-MVP product direction and guardrails

| Field | Value |
| --- | --- |
| Prompt | P012 · a docs-only part (P012a–a3 graphs and services, P012b routing API, P012c1 directions, **P012d product direction**). P012c2 (follow-me) is still open |
| Milestone | M4 (depends on P012c1, merged as `f593b4a`) |
| Branch | `docs/012d-post-mvp-product-direction` |
| PR title | `docs(plan): addendum v7.4 post-MVP product direction and guardrails [P012d]` |
| Notion | [P012d row in the Prompt Log](https://app.notion.com/p/3f2073707720812685e3dbf68677428b) |
| Date | 2026-10-08 |
| Plan refs | Plan v7 §1, §3.3, §7, §8, §9.2, §10, §18, §22; addenda v7.1 to v7.3; ADRs 0005, 0010, 0011, 0015, 0017, 0018 |

> **Documents only.** No application code, contract, migration, workflow or dependency changed.
> **Nothing in this prompt is built, measured or scheduled.** No journey, circle, feed, tier or
> price exists. The MVP prompts (P013–P022) keep their scope.

## 1. Objective

Rahul shared a product idea document: daily commute, SafeCircle, SafeExplore, a nearby feed, a
community feed, a weekend mode and monetization. Record what was decided about it: the order
after the MVP, one journey engine for commute and circle, guardrails for every future feature,
and the gates a community feed must pass before any build.

## 2. Context & prerequisites

- P012c1 merged (PR #33, `f593b4a`). No open pull requests. Clean tree, hooks active.
- The idea document itself is not in the repository and was not read here. This prompt records
  the decisions as listed in the prompt text.
- The Plan PDF cannot be read on this machine. Plan v7 §3.3 and §10 are cited as the prompt
  cites them, not re-read.
- ADR 0011 (Trusted Circle) is Proposed and stays so.

## 3. Workflow executed

1. `/start-prompt`: `git switch main`, `git pull --ff-only`, clean tree, `core.hooksPath` is
   `.githooks`; PR #33 confirmed merged with `gh pr list`; Notion P012c1 set to Merged; branch
   `docs/012d-post-mvp-product-direction`; Notion page created.
2. Addendum v7.4, the reading order, `CLAUDE.md` "Product guardrails" (`616316d`).
3. ADR 0021, the note on ADR 0011, the ADR index (`4c0db3b`).
4. Notion: Ideas backlog, follow-ups, ADR row (section 10).
5. `/ship-prompt`: quality gate, this log, push, pull request, Notion.

## 4. Changes

| File | What |
| --- | --- |
| `docs/plan/addendum-v7.4.md` | new: A direction and sequence · B journey engine · C missed-arrival alerts · D guardrails · E discovery data rules · F community feed gates · G monetization fit · H risks · I plan edits |
| `docs/plan/README.md` | reading order PDF → v7.1 → v7.2 → v7.3 → v7.4 |
| `docs/adr/0021-post-mvp-product-direction.md` | new, Accepted |
| `docs/adr/0011-trusted-circle-principles.md` | one dated note at the top; body unchanged |
| `docs/adr/README.md` | index row for ADR 0021 |
| `CLAUDE.md` | new "Product guardrails" section; reading order and repo map name v7.4. **Golden rules unchanged** |
| `docs/prompt-logs/012d-post-mvp-product-direction.md` | this log |

Not changed: the plan PDF, addenda v7.1 to v7.3, `README.md`, any code, `contracts/`,
migrations, workflows, dependencies.

**`README.md`:** checked, no contradiction found. It already says the app shows no safety
score, ranking or "safe" label, and it mentions no circle, feed, tier or revenue. Its plan
paragraph lists the addenda up to v7.3; that is incomplete, not contradictory, and the prompt
asked for no change in that case.

## 5. Diagram

No diagram needed (as the prompt says). A journey is a state machine (planned, under way,
arrived, missed, checked in); its diagram belongs to the prompt that builds Journeys v1.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| markdownlint-cli2 v0.18.1 over `**/*.md` | 92 files, 0 errors |
| JSON validity (`*.json`, `*.excalidraw`, tracked) | 59/59 valid; no JSON file changed |
| Relative links in the seven changed or new markdown files | 108 links, 0 broken |
| gitleaks 8.30.1, full history | no leaks |
| Forbidden files tracked | none |
| `git diff --stat main...HEAD` | only `docs/` and `CLAUDE.md` |
| Search of the diff for local paths, e-mail addresses, project ids and keys | nothing found |

Backend, contracts, container, deploy workflow, infra scripts, Android: not touched, gates not
applicable.

**SOS failure matrix (Plan v7 §7.5):** not applicable; no SOS, live-location or contacts code.

## 7. Decisions & ADRs

[ADR 0021](../adr/0021-post-mvp-product-direction.md), Accepted: daily utility first, safety
throughout; the six-step sequence; one journey engine; guardrails; feed gates. Rejected: build
the social feed first; safety scores on places.

Choices made while writing, for Rahul to confirm. Each goes beyond the prompt's wording:

- **"SafeCircle" and "Trusted Circle" are treated as one feature.** The addendum and the note
  on ADR 0011 say so. The consent purpose name `trusted_circle` in ADR 0011 is unchanged.
- **Feature names are called working names** ("Safe Commute", "SafeExplore", "Journeys",
  "Plus", "Teams"), since no naming or trademark decision was recorded.
- **"Traction" is left undefined on purpose.** The addendum says it needs a measured number
  recorded in the plan before the gate can be argued.
- **Two rejected alternatives were added to ADR 0021** beyond the two the prompt named:
  separate mechanisms for commute and circle, and doing nothing until the MVP ships.
- **Consequences the prompt did not name:**
  - the server-side timer means the server learns that a journey exists and when it should
    end, which is new personal data needing a consent purpose and a retention rule;
  - a journey that continues with the screen off needs a foreground service and a changed
    location disclosure, so the prompt that builds it must amend ADR 0015.
- **Section E additions:** listings are "from map data" and never promise that a place is
  open; a police station on the map is not a phone number (the verified-source rule of
  addendum v7.2, section F still decides numbers).
- **Section F:** moderators' names are kept outside this public repository (as the P010d log
  already asked for the regional roster).
- **Section G additions:** a promoted listing is labelled and never affects safety information
  or route choice; no price or tier boundary is decided.
- **Addendum section I (plan edits)** was added to match earlier addenda. It edits §3.3, §8,
  §18 and §22 only.
- **`CLAUDE.md` "Product guardrails"** carries the section D rules plus four short lines from
  sections B, E, F and G (journeys, data-source terms check, no user content before the gates,
  no revenue claims), because those are the rules a later prompt is most likely to break.

## 8. Security & privacy notes

- No personal data, keys, project ids, service URLs or local paths in the diff.
- **Saved places are sensitive.** Home and work reveal a routine. Decision: device only at
  first; syncing needs a consent purpose, encryption and retention (**to be verified by a
  lawyer**).
- **The journey timer is new server-side personal data** (section 7). Nothing is stored today.
- **Coercive monitoring** stays the main risk of any circle feature. ADR 0011's rules and
  "only the traveller starts a journey" reduce it; they don't remove it.
- **Moderator names** never go in the repository.
- Legal statements are drafts marked "to be verified by a lawyer". No legal conclusion is
  drawn; TRAI DLT and the Play user-generated-content policy are named as things to check, not
  described.

## 9. Known issues & risks

- **Nothing is built or measured.** Arrival detection, the grace period, the timer's storage,
  prices, limits and "traction" are all "not recorded".
- **"Done in P011e" could not be verified.** Rahul asked for the backlog idea "Nearby-first
  search ranking" to be marked as done in P011e. On `main` there is no
  `docs/prompt-logs/011e-*.md` and no `[P011e]` commit, and the Prompt Log has no P011e row.
  The idea is recorded with that statement and a follow-up asks Rahul to confirm.
- **Safe Commute is staged "MVP+" in the backlog** (as the prompt says: "MVP+ next"), while the
  addendum puts Journeys v1 as step 2, after the MVP+ items. The backlog note says so.
- **Four addenda now amend one PDF.** A reader must follow PDF → v7.1 → v7.2 → v7.3 → v7.4; v8
  is the fix and comes after the MVP and company registration.
- **`README.md` names the addenda only up to v7.3** (section 4).
- The risks added to the plan are in addendum v7.4, section H.
- Unchanged from P012c1: nothing in P012 has run on a phone, and the staging routing numbers
  are not recorded.

## 10. Follow-ups & prerequisites for next prompt

Notion *Follow-ups*, new (source P012d):

- Lawyer review: saved places, missed-arrival alerts and SafeCircle (High).
- Places and events data source evaluation (terms check).
- P013–P016 prompts: leave room for circle consent purposes and journeys (note only, no scope
  change).
- Confirm P011e (nearby-first search ranking): no log, commit or Prompt Log row found (Low).

Notion *Ideas backlog*:

- New: Safe Commute (MVP+); Journey engine (Journeys); Missed-arrival alerts (Journeys);
  Nearby essentials (MVP+); SafeExplore broad (Later); Nearby Feed (Later); Community Feed
  (Blocked: the gates of section F); Weekend Mode (Later); Promoted listings (Later, at scale);
  Plus tier (After company, a stage chosen here for Rahul to confirm); Stops along a route
  (Journeys; check whether the router offers alternatives with waypoints); Nearby-first search
  ranking (stage empty, see section 9); Improve open map data around small towns (Ongoing;
  owner: Rahul and local contributors).
- Updated: "Trusted Circle" is now "SafeCircle (Trusted Circle)", After company, blocker:
  lawyer review, depends on the journey engine.
- **Schema change:** the Stage property gained two options, "Journeys" and "Ongoing", because
  the stages Rahul named did not exist.

Notion *Architecture Decisions*: ADR 0021 added.

**Next: P012c2** (`feat/012c2-follow-me`), unchanged from the P012c1 log. Then P013.

## 11. How Rahul can verify

1. Read [`docs/plan/addendum-v7.4.md`](../plan/addendum-v7.4.md) and
   [ADR 0021](../adr/0021-post-mvp-product-direction.md). Check the choices in section 7.
2. Read the "Product guardrails" section of [`CLAUDE.md`](../../CLAUDE.md) and the note at the
   top of [ADR 0011](../adr/0011-trusted-circle-principles.md).
3. Confirm the Notion Ideas backlog (13 new rows, one updated, two new Stage options) and the
   four follow-ups. Answer the P011e question.
4. CI green on the pull request, then squash and merge.

## 12. Learning notes

No Android or Kotlin concept was used in this prompt. Three ideas that later Android prompts
will meet:

- **Why a timer on the phone is not enough.** Android stops apps that are not in use, and a
  phone can run out of battery. An alert that must fire when the phone is silent has to be
  scheduled somewhere that keeps running: the server. The phone tells the server "expect me by
  this time", and the server acts if it hears nothing. The same idea is behind the SOS server
  orchestration in P015.
- **Foreground service.** An Android app normally gets the location only while it is on the
  screen (today's policy, ADR 0015). To keep working with the screen off, an app starts a
  *foreground service*: a piece of the app that runs with a permanent, visible notification, so
  the user always knows it is active. A journey would need one, which is why it needs its own
  permission prompt and disclosure.
  [Foreground services](https://developer.android.com/develop/background-work/services/fgs)
- **Guardrail versus feature flag.** A feature flag turns a feature on or off. A guardrail is a
  rule about what no feature may do, written down before the features exist, so that each new
  prompt is checked against it and not the other way round.
