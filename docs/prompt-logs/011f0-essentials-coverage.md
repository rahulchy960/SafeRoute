# P011f0: record the OpenStreetMap coverage finding and the essentials plan (docs only)

| Field | Value |
| --- | --- |
| Prompt | P011 · part f, a docs-only note written after P011f1 and P011f2 |
| Milestone | M4 |
| Branch | `docs/011f0-essentials-coverage` |
| PR title | `docs(plan): record OpenStreetMap coverage finding and essentials plan [P011f0]` |
| Notion | [P011f0 row in the Prompt Log](https://app.notion.com/p/3f3073707720810e9ff9e2d79ce06f9e) |
| Date | 2026-10-09 |
| Plan refs | Addenda v7.2 F, v7.3 I, v7.4 D; ADR 0018 |

## 1. Objective

Record what Rahul found when he looked at OpenStreetMap for one small town, and the rule and
the ideas that follow from it. No code, contract or workflow change.

## 2. Context & prerequisites

- P011f2 is merged (PR #45, `a596c12`). No open pull requests, clean tree, hooks active.
- P011f1 had left the cause of the missing search results open on purpose.

## 3. Workflow executed

1. Main synced, PR #45 confirmed merged and set to Merged in Notion, branch created.
2. Docs edited; three rows added to the Notion Ideas backlog.
3. markdownlint and gitleaks; commit, push, pull request.

## 4. Changes

| File | What |
| --- | --- |
| `docs/plan/addendum-v7.3.md`, section I | The finding and the "may be incomplete" rule for lists of essentials |
| `CLAUDE.md`, "Coverage claims" | The same rule, short |
| `docs/prompt-logs/011f1-category-search-backend.md` | A dated note: the finding and what it means for that prompt's evidence |
| `docs/prompt-logs/011f2-category-chips.md` | A short dated note that points to it |

The finding, as reported: one hospital, one railway station and one petrol pump without a
brand name were mapped; no police station, bus stop, post office, bank, ATM or pharmacy;
roads and buildings were well mapped. One town, one person, one day: not a measurement.

Notion Ideas backlog, three new rows:

| Idea | Stage | Depends on |
| --- | --- | --- |
| Curated essentials dataset: police stations and hospitals verified per region, with source, date and verifier; extends the police-numbers table | MVP+ | Terms check of any official source |
| OpenStreetMap mapping effort for small towns | Research | (none recorded) |
| Compare POI coverage across regions | Research | (none recorded) |

The prompt gave a stage only for the first idea. "Research" for the other two is my choice;
change it in Notion if it is wrong.

## 5. Diagram

No diagram needed: no flow, state machine, schema, API, infrastructure or pipeline changed.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| markdownlint (Docker, v0.18.1) | 0 errors |
| gitleaks (Docker, v8.30.1) | no leaks |
| Other gates | not applicable: only Markdown files changed |

## 7. Decisions & ADRs

No ADR. The rule is a wording rule and lives in the addendum and in `CLAUDE.md`. A curated
dataset, if it is ever built, needs its own ADR and a terms check of its source first.

## 8. Security & privacy notes

- The town is not named. The finding describes public map data, not a person or a place of a
  person.
- Public repo check: no personal data, local paths or non-public ids added.

## 9. Known issues & risks

- The finding is one observation. It must not be quoted as a coverage figure.
- No screen shows a list of essentials yet, so the new rule has nothing to test today. The
  first prompt that builds such a list must apply it.
- `README.md` was not changed: its coverage row already says that local shops and small
  businesses are incomplete, and it does not mention essentials.
- ADR 0018 was not changed: the prompt did not list it. Its evaluation note is still to come
  with the measured tables.

## 10. Follow-ups & prerequisites for next prompt

- The three ideas above are in the Ideas backlog.
- Still open from P011f1 and P011f2: the evaluation run, the dictionary review, the phone
  checks, the Bengali reviews.
- Next prompt: P013 (emergency contacts).

## 11. How Rahul can verify

1. Read the new paragraph in `docs/plan/addendum-v7.3.md`, section I, and the bullet in
   `CLAUDE.md` "Coverage claims": is the finding stated as you saw it?
2. Check the three rows in the Notion Ideas backlog, especially the stage of the last two.
3. CI: `repo-checks` green.

## 12. Learning notes

No Android or code concepts in this prompt.
