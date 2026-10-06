# P010c: statewide coverage plan (addendum v7.2) and P010 log revisions

| Field | Value |
| --- | --- |
| Prompt | P010 · a third, docs-only part (P010a map, P010b location, **P010c coverage plan**) |
| Milestone | M3 (depends on P010b, merged as `2e08043`) |
| Branch | `docs/010c-statewide-coverage-plan` |
| PR title | `docs(plan): addendum v7.2 statewide coverage tiers and P010 log revisions [P010c]` |
| Notion | [P010c row in the Prompt Log](https://app.notion.com/p/3f1073707720818d88e6ee3fbf07184f) |
| Date | 2026-10-07 |
| Plan refs | Plan v7 §1, §3.2–3.4, §9, §12, §13–14, §21–22; addendum v7.1; ADRs 0005, 0013, 0015 |

> **Documents only.** No application code, contract, migration, workflow or dependency changed.
> **Nothing in this prompt is measured.** The addendum records a decision and the rules for
> describing it; routing extents, search hit rates, signal gaps and the pilot boundary are all
> "not recorded" until the prompts named for them.

## 1. Objective

Rahul decided that the launch geography is the whole of West Bengal, with Kolkata as the first
pilot area for community safety data. Record that as three coverage layers, with the rules that
keep every public claim honest, and update the scopes of the next prompts. Also add the pending
Revision sections to the two P010 logs.

## 2. Context & prerequisites

- P010b merged (PR #23, `2e08043`). No open pull requests. Clean tree, hooks active.
- Plan v7 and addendum v7.1 describe a Kolkata launch with expansion region by region
  (ADR 0013).
- Input C1 from Rahul, 2026-10-07: the P010 phone checks were run on **one device, Android
  17**. The device model was not given.
- The Plan PDF cannot be read on this machine; the plan references quoted in the prompt were
  used.

## 3. Workflow executed

1. `/start-prompt`: `git switch main`, `git pull --ff-only`, clean tree, `core.hooksPath` is
   `.githooks`; PR #23 confirmed merged with `gh pr list`; Notion P010b was already Merged;
   branch `docs/010c-statewide-coverage-plan`; Notion page created.
2. C2: Revision sections in the P010a and P010b logs (`98c21e5`).
3. C3: `docs/plan/addendum-v7.2.md` and the reading order in `docs/plan/README.md` (`beb8b7f`).
4. C4: ADR 0016, the note on ADR 0013, the ADR index (`3bc21cf`).
5. C5: `CLAUDE.md` "Coverage claims" and the `README.md` "Coverage" section (`92f7d18`).
6. C6: Notion scope notes, Ideas backlog, follow-ups, ADR row (section 10).
7. `/ship-prompt`: quality gate, this log, push, pull request, Notion.

## 4. Changes

| File | What |
| --- | --- |
| `docs/plan/addendum-v7.2.md` | new: A launch geography · B coverage layers · C region model · D routing (P012) · E search (P011) · F police numbers · G languages · H research · I plan edits · J claims rule · K risks |
| `docs/plan/README.md` | reading order PDF → v7.1 → v7.2 |
| `docs/adr/0016-statewide-coverage-layers.md` | new, Accepted |
| `docs/adr/0013-regions-and-expansion.md` | one dated note at the top; body unchanged |
| `docs/adr/README.md` | index row for ADR 0016 |
| `CLAUDE.md` | new "Coverage claims" section; plan addendum reading order; the project summary and the "Kolkata" line of the naming rules say pilot area, not launch city; repo map line. **Golden rules unchanged** |
| `README.md` | "Coverage" rewritten as the three layers; plan links include both addenda and ADR 0016 |
| `docs/prompt-logs/010a-map-maptiler.md`, `010b-location-permission.md` | Revision section appended to each |
| `docs/prompt-logs/010c-statewide-coverage-plan.md` | this log |

Not changed: `docs/plan/SafeRoute_Plan_v7_MVP.pdf`, `docs/plan/addendum-v7.1.md`, any code,
`contracts/`, migrations, workflows, dependencies.

**Size:** about 550 changed lines, all documents.

## 5. Diagram

No diagram needed. No flow, state machine, schema, API sequence, infrastructure or pipeline
changed. The regions configuration gets its diagram when it is built (P017).

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| markdownlint-cli2 v0.18.1 over `**/*.md` | 0 errors |
| JSON validity (`*.json`, `*.excalidraw`, tracked) | 45/45 valid; no JSON file changed |
| Relative links in the ten changed or new markdown files | 121 links, 0 broken |
| gitleaks 8.30.1, full history | no leaks |
| Forbidden files tracked | none |
| `git diff --stat main...HEAD` | only `docs/`, `CLAUDE.md` and `README.md` |
| Search of the diff for local paths, e-mail addresses, project ids and keys | nothing found |

Backend, contracts, container, deploy workflow, infra scripts, Android: not touched, gates not
applicable.

**SOS failure matrix (Plan v7 §7.5):** not applicable; no SOS, live-location or contacts code.
The addendum adds a launch criterion that SOS and live share are tested in weak-signal areas;
that test belongs to P014–P016.

## 7. Decisions & ADRs

[ADR 0016](../adr/0016-statewide-coverage-layers.md), Accepted: West Bengal is the launch
geography; coverage is always three layers; safety data only in active regions; the claims
rule. ADR 0013 stays in force and points to it.

Wording choices made while writing, for Rahul to confirm:

- **README: "Active regions today: none."** The addendum names the Kolkata metropolitan area
  as the *initial* active region, but its boundary is not defined and no regions configuration
  exists, so today nothing is active. The README says so and names the pilot as planned.
- **ADR 0016 lists a third rejected alternative**, lowering the k-threshold outside the pilot
  area. It restates rule 5 of ADR 0013; the prompt named two alternatives.
- **The reasons given for rejecting a Kolkata-only launch** (the core tools do not depend on
  local data) are this log's reading of the decision, not a quotation.
- **Addendum section I** says which lines of v7.1 it replaces (§14 wording, roadmap item 7), so
  that v7.1's text can stay untouched.

## 8. Security & privacy notes

- No personal data, keys, project ids, service URLs or local paths in the diff.
- The device record is a count and an Android version only; no model, serial or owner.
- The search fixture planned for P011 must contain public places only (addendum E).
- Police numbers: never show an unverified number (addendum F).
- Legal statements are drafts marked "to be verified by a lawyer". No legal conclusion is
  drawn.

## 9. Known issues & risks

- **Nothing is measured.** Whether routing can cover the whole state within the 2 s p95 and an
  acceptable cost is unknown until P012.
- **Search quality outside Kolkata is unknown** until the P011 evaluation; its threshold is not
  set.
- **The pilot boundary is not defined** (P017).
- **Rural signal gaps are not documented yet**; no field test has been done.
- **M3 device shortfall:** one device (Android 17) against the three the M3 exit asks for.
- **The first survey is small and skewed**; statewide demand is not shown.
- The risks added to the plan are in addendum v7.2, section K.
- `CLAUDE.md` now carries the claims rule, but existing UI strings and docs were not audited
  against it in this prompt (no code change allowed here).

## 10. Follow-ups & prerequisites for next prompt

Notion *Follow-ups*, new (source P010c):

- Lawyer review of coverage and advertising wording (High).
- Define the Kolkata metropolitan boundary (P017).
- Document rural signal gaps for the core safety tools.

Updated:

- "Record the device count and Android versions for M3": **stays open**, now with one device on
  Android 17 recorded and two more needed.
- "P017 to include regionCode": now also the regions configuration.
- "Plan v8: redo the capacity model": for the larger extent.
- "Publish Plan v8": wording for the West Bengal launch.

Already closed before this prompt: "Confirm on a device what P010a could not run".

Notion *Prompt Log* scope notes added to P011 (statewide search and the evaluation fixture),
P012 (West Bengal extract, measurements, outside-area result), P017 (`regionCode`, regions
configuration, reports only in active regions) and P019 (overlay and exposure only in active
regions).

Notion *Ideas backlog*: police numbers per jurisdiction and Hindi (now an MVP+ candidate)
updated; "Regional expansion" reworded to "Open more active regions"; new entries for the
second survey round, Nepali evaluation, the regions configuration and the claims review.

Notion *Architecture Decisions*: ADR 0016 added.

**Next: P011** (`feat/011-place-search`). Not started. Inputs Rahul must prepare: a server-side
geocoding key in Secret Manager for staging (never in the app, never shown to Claude Code), the
provider choice and its terms, and a view on the search hit-rate threshold.

## 11. How Rahul can verify

1. Read [`docs/plan/addendum-v7.2.md`](../plan/addendum-v7.2.md) and
   [ADR 0016](../adr/0016-statewide-coverage-layers.md). Check the four wording choices in
   section 7.
2. Check the "Coverage" section of [`README.md`](../../README.md), in particular "Active
   regions today: none".
3. Confirm the scope notes on the Notion pages of P011, P012, P017 and P019, and the Ideas
   backlog changes.
4. CI green on the pull request, then squash and merge.

## 12. Learning notes

No Android or Kotlin concept was used in this prompt. Two documentation ideas instead:

- **An addendum instead of an edit.** The plan PDF is a fixed, licensed record. Changes are
  written as dated addenda that say exactly which lines they replace, so a reader can always
  see what was believed when. The order of reading (PDF, v7.1, v7.2) is the order of authority.
- **Layers instead of one word.** "Coverage" can mean that an app opens, that it can route, or
  that it has local data. Naming the layer each time is what stops an empty map from being read
  as a safe one.
