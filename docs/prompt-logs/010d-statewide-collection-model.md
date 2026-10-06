# P010d: statewide report collection with threshold-gated publication

| Field | Value |
| --- | --- |
| Prompt | P010 · a fourth, docs-only part (P010a map, P010b location, P010c coverage plan, **P010d collection model**) |
| Milestone | M3 (depends on P010c, merged as `70a9213`) |
| Branch | `docs/010d-statewide-collection-model` |
| PR title | `docs(plan): statewide report collection with threshold-gated publication [P010d]` |
| Notion | [P010d row in the Prompt Log](https://app.notion.com/p/3f107370772081bcb67dec5199eb9109) |
| Date | 2026-10-07 |
| Plan refs | Plan v7 §3.3, §3.4, §9.1, §9.2, §11, §12.4, §15.2, §22; addenda v7.1, v7.2; ADRs 0013, 0016 |

> **Documents only.** No application code, contract, migration, workflow or dependency changed.
> **Nothing in this prompt is built or measured.** No boundary is chosen, no moderator is
> recruited, no backlog limit or intake cap is set, and no region is published.

## 1. Objective

Rahul wants community safety data across West Bengal. Replace the "reports only in active
regions" rule of P010c with: reports are **accepted** anywhere inside West Bengal; **publication**
is gated by a data threshold and moderation capacity, region by region.

## 2. Context & prerequisites

- P010c merged (PR #24, `70a9213`). No open pull requests. Clean tree, hooks active.
- Addendum v7.2 and ADR 0016 (P010c) limited reports to "active regions", starting with the
  Kolkata metropolitan area.
- The Plan PDF cannot be read on this machine. The facts cited from Plan v7 §9.2, §12.4 and
  §15.2 (publication delay, time bands, pin offset, daily cap of 3, two-person rule, stored
  resolution-11 cells) are taken as quoted in the prompt, not re-read from the PDF.

## 3. Workflow executed

1. `/start-prompt`: `git switch main`, `git pull --ff-only`, clean tree, `core.hooksPath` is
   `.githooks`; PR #24 confirmed merged with `gh pr list`; Notion P010c set to Merged; branch
   `docs/010d-statewide-collection-model`; Notion page created.
2. D1: `docs/plan/addendum-v7.3.md`, the reading order, `CLAUDE.md` "Coverage claims" and the
   `README.md` "Coverage" section (`fd06333`).
3. D2: ADR 0017, the notes on ADR 0016 and ADR 0013, the ADR index (`9d20a9d`).
4. D3: Notion scope notes, follow-ups, ADR row (section 10).
5. `/ship-prompt`: quality gate, this log, push, pull request, Notion.

## 4. Changes

| File | What |
| --- | --- |
| `docs/plan/addendum-v7.3.md` | new: A region statuses · B acceptance rule · C publication gate · D no-data rule · E cell resolution · F moderation capacity · G reporter experience · H official data · I claims rule update · J plan edits |
| `docs/plan/README.md` | reading order PDF → v7.1 → v7.2 → v7.3 |
| `docs/adr/0017-collect-statewide-publish-by-gate.md` | new, Accepted |
| `docs/adr/0016-statewide-coverage-layers.md`, `0013-regions-and-expansion.md` | one dated note at the top of each; bodies unchanged |
| `docs/adr/README.md` | index row for ADR 0017 |
| `CLAUDE.md` | "Coverage claims": accepted anywhere in West Bengal, shown only in published regions; the three statuses; the allowed wording; reporter-facing text; plan addendum reading order. **Golden rules unchanged** |
| `README.md` | "Coverage": layer 3 and the bullets under the table rewritten; "Published regions today: none"; plan links include v7.3 and ADR 0017 |
| `docs/prompt-logs/010d-statewide-collection-model.md` | this log |

Not changed: the plan PDF, `addendum-v7.1.md`, `addendum-v7.2.md`, the P010c log, any code,
`contracts/`, migrations, workflows, dependencies.

**Size:** about 470 changed lines, all documents.

## 5. Diagram

No diagram needed (as the prompt says). The region statuses form a small state machine
(`context_only`, `collecting`, `published`); its diagram belongs to P017, where the
configuration is built.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| markdownlint-cli2 v0.18.1 over `**/*.md` | 75 files, 0 errors |
| JSON validity (`*.json`, `*.excalidraw`, tracked) | 45/45 valid; no JSON file changed |
| Relative links in the nine changed or new markdown files | 136 links, 0 broken |
| gitleaks 8.30.1, full history | no leaks |
| Forbidden files tracked | none |
| `git diff --stat main...HEAD` | only `docs/`, `CLAUDE.md` and `README.md` |
| Search of the diff for local paths, e-mail addresses, project ids and keys | nothing found |

Backend, contracts, container, deploy workflow, infra scripts, Android: not touched, gates not
applicable.

**SOS failure matrix (Plan v7 §7.5):** not applicable; no SOS, live-location or contacts code.

## 7. Decisions & ADRs

[ADR 0017](../adr/0017-collect-statewide-publish-by-gate.md), Accepted: accept reports
statewide; statuses `context_only`, `collecting`, `published`; a four-part publication gate;
publication reversible by configuration. It replaces one rule of ADR 0016; ADR 0016's three
layers and claims rule stay in force.

Wording choices made while writing, for Rahul to confirm:

- **"Active region" is retired.** v7.3, `CLAUDE.md` and the README say "published region". The
  P010c documents keep the old term and are read through the notes that point to ADR 0017.
- **The no-data text changed.** "No community data here yet" replaces v7.2's "Community reports
  aren't available here yet", because reports are now accepted in those areas.
- **README: "Published regions today: none."** Nothing is built, so nothing is published.
- **Empty list in the allowed wording.** The example names "[list of published regions]". The
  addendum and `CLAUDE.md` add: while the list is empty, say that no area shows community data
  yet. This is this log's addition; the prompt did not cover the empty case.
- **ADR 0017 adds a consequence the prompt did not name:** collected but unpublished reports are
  still personal data held by the service, so their retention and the consent wording must be
  settled in P017 and P020, with a lawyer.
- **The re-identification risk in addendum E** is described in one sentence written here (a
  sparse cell can point to a person, a household or a single event). The prompt asked only that
  the risk be recorded.

## 8. Security & privacy notes

- No personal data, keys, project ids, service URLs or local paths in the diff.
- **Moderator roster.** The gate asks for the moderators of a region to be "recorded in the
  region's configuration". The repository is public: a configuration file committed here must
  not contain names or contact details of moderators. P017/P018 must keep the roster outside
  the repository, or record only a count or role ids. Raised as a known issue below.
- **More personal data is collected than is shown.** Under this model the service holds reports
  from the whole state that are never published. Retention, consent wording and access rules
  for them belong to P017 and P020 (**to be verified by a lawyer**).
- **Re-identification in sparse areas** is recorded as a risk (addendum E) and has a High
  follow-up.
- Legal statements are drafts marked "to be verified by a lawyer". No legal conclusion is
  drawn.

## 9. Known issues & risks

- **Nothing is built or measured.** The boundary, the backlog limit, the per-region intake cap
  and the cell-resolution rule are all "not recorded".
- **Moderation load arrives statewide before any region is published**, and nobody is
  recruited or trained yet.
- **Where the moderator roster lives is undecided** (section 8).
- **Reporters may send reports that are never shown.** The report-screen wording is not
  written; it must not promise publication or a police response.
- **An empty overlay can still be misread as safety**, whatever the wording says.
- **Three addenda now amend one PDF.** A reader must follow PDF → v7.1 → v7.2 → v7.3; v8 is the
  fix and comes after the MVP.
- M3 device shortfall from P010c is unchanged: one device (Android 17) against three.
- The risks added to the plan are in addendum v7.3, section J.

## 10. Follow-ups & prerequisites for next prompt

Notion *Follow-ups*, new (source P010d):

- Lawyer review of the re-identification risk and the reporter-facing text (High).
- Define the West Bengal boundary source (P017).
- Recruit and train moderators and a backup (High).
- Coverage-wording review for pitch materials.

Already listed, not duplicated: the second survey round from several districts (Ideas backlog,
P010c).

Updated: "Define the Kolkata metropolitan boundary (P017)" (now the first candidate for
`published`); "P017 to include regionCode" (the three statuses, boundary check, intake cap).

Notion *Prompt Log* scope notes:

- P017: `regionCode`, regions configuration with the three statuses, boundary check, intake
  cap, reporter explanation.
- P018: backlog limit and alarm, per-region moderation roster, the "published" gate checklist.
- P019: published-only overlay, no-data wording, route data-share statement, adaptive cell
  resolution with a re-identification review.

Notion *Ideas backlog*: "Regions configuration" and "Publish more regions" reworded for the new
statuses. Notion *Architecture Decisions*: ADR 0017 added.

**Next: P011** (`feat/011-place-search`). Not started. Its inputs are unchanged from the P010c
log: a server-side geocoding key in Secret Manager for staging, the provider choice and its
terms, and a view on the search hit-rate threshold.

## 11. How Rahul can verify

1. Read [`docs/plan/addendum-v7.3.md`](../plan/addendum-v7.3.md) and
   [ADR 0017](../adr/0017-collect-statewide-publish-by-gate.md). Check the wording choices in
   section 7 and the roster question in section 8.
2. Check the "Coverage" section of [`README.md`](../../README.md) and the "Coverage claims"
   section of [`CLAUDE.md`](../../CLAUDE.md).
3. Confirm the scope notes on the Notion pages of P017, P018 and P019.
4. CI green on the pull request, then squash and merge.

## 12. Learning notes

No Android or Kotlin concept was used in this prompt. Two ideas from the data model instead:

- **Collecting and publishing are separate switches.** A system can take data in long before
  it shows any of it. Keeping the two apart lets a region gather enough reports to be judged,
  and lets publication be turned off again without touching the app.
- **k-threshold and re-identification.** Showing a cell only when at least 3 different people
  reported there hides any single reporter in a crowd. In a village the crowd is small, so the
  same rule protects less; larger cells put more people in each one. That trade between detail
  and privacy is what P019 has to settle with real data.
