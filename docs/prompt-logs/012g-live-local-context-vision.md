# P012g: live local context, vision and staged path (docs only)

| Field | Value |
| --- | --- |
| Prompt | P012g (docs only) |
| Milestone | M4 |
| Branch | `docs/012g-live-local-context-vision` |
| PR title | `docs(plan): addendum v7.5 live local context vision and staged path [P012g]` |
| Notion | [P012g row in the Prompt Log](https://app.notion.com/p/3f30737077208181bf54d111a10c7b1c) |
| Date | 2026-10-09 |
| Plan refs | Plan v7 §1, §3.3, §9.2, §22; addenda v7.2 H and J, v7.3 D and I, v7.4 D to F; ADRs 0010, 0015, 0021, 0022 |

> **Docs only.** No code, contract, migration, workflow or dependency changed. Nothing in
> this prompt is built, measured or scheduled.
>
> **One part is missing on purpose.** The prompt asked for "research questions for the next
> survey round (listed above in the chat)". No such list was in the session, in the
> repository or in Notion. Rahul was asked and chose to have it marked "not recorded". The
> addendum says so in section F; nothing was invented in its place.

## 1. Objective

Record a long-term direction ("Google Maps tells you where to go. SafeRoute tells you what
nearby people report along the way"), the stages by which the product could get there, the
guardrail conflicts and architecture changes that stand in the way, and the rule that public
text describes only what is live.

## 2. Context & prerequisites

- P011f0 is merged (PR #46, `3b26c6f`). No open pull requests, clean tree, hooks active.
- Addendum v7.4 and ADR 0021 already set the order after the MVP and put the community feed
  behind eight gates. This prompt adds to them and loosens none of them.
- The Plan PDF cannot be read on this machine; the prompt text and the addenda are the
  specification.

## 3. Workflow executed

1. Preflight: main synced, PR #46 confirmed merged and set to Merged in Notion, branch.
2. Read addendum v7.4, ADR 0021, the ADR template and the places that state the reading
   order. Looked for the survey questions in the session, the plan and the Notion Ideas
   backlog; not found; asked Rahul.
3. Wrote addendum v7.5 and ADR 0023; updated the reading order and the indexes.
4. Notion: Prompt Log row, ADR row, five ideas, four follow-ups.
5. Checks, commit, push, pull request.

Commands: markdownlint and gitleaks through Docker; a relative-link check over the changed
files with a small Node script; `git diff --stat main`.

## 4. Changes

| File | What |
| --- | --- |
| `docs/plan/addendum-v7.5.md` (new) | Sections A to H: vision and wording rule; the four stages; conflicts to resolve before stage 3; architecture implications; cold start; research questions (not recorded) and success measures; risks; plan edits |
| `docs/adr/0023-live-local-context-staged-path.md` (new) | **Proposed.** The map stays Home; the bottom sheet evolves in four stages; what triggers each; a feed as Home now is rejected |
| `docs/adr/README.md` | Index row for ADR 0023 |
| `docs/plan/README.md` | v7.5 and the new reading order |
| `CLAUDE.md` | Reading order (PDF → v7.1 → … → v7.5), repo map, and a short section "Live local context" |

The stages, as recorded:

| Stage | What | Starts when |
| --- | --- | --- |
| 1 | Nearby panel in the Home bottom sheet: essentials, labelled "may be incomplete" | After the MVP (MVP+) |
| 2 | Curated alerts posted by staff from official or verified sources | A company; each source's terms checked; a named person who posts |
| 3 | Community reports with expiry, confirmations, abuse controls, reporter-protecting delays | Section C answered in an ADR; lawyer review; minimum density in the region; live moderation |
| 4 | Social posts | Every gate of addendum v7.4, section F |

Not changed: `README.md` (it makes no claim that v7.5 affects), every file outside `docs/`
except `CLAUDE.md`, the PDF, and the texts of addenda v7.1 to v7.4.

## 5. Diagram

No diagram needed: no flow, state machine, schema, API, infrastructure or pipeline changed.
The stages are a table in the addendum.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| markdownlint (Docker, v0.18.1) | 0 errors in 107 files |
| Relative links (and heading anchors) in the six new or changed files | 108 links, 0 broken |
| gitleaks (Docker, v8.30.1) | no leaks |
| `git diff --stat main` | Markdown files only |
| Other gates | not applicable: no code, contract, diagram or workflow changed |

Failure matrix (Plan v7 §7.5): not applicable.

## 7. Decisions & ADRs

- **[ADR 0023](../adr/0023-live-local-context-staged-path.md), Proposed.** It is Rahul's to
  accept. Until then no prompt starts a stage because of it.
- Mine to make while writing, all of them easy to change:
  - **Route-affected alerts are not a stage.** The prompt lists four stages and treats
    route matching under architecture; the ADR says they are blocked on their own design.
  - **Stage 3 is described as separate from the report flow of Plan v7 §9.2**, with the
    relation between the two left open and the k-threshold untouched.
  - **Naming another company's product in advertising** is marked as a question for the
    lawyer, and the vision sentence is kept to internal and pitch material until then.
  - **"Minimum viable density" has no number.** It is written as "not recorded", to be set
    from beta data per region.
  - **Success measures have no targets**, for the same reason.
  - The triggers of each stage in the ADR are derived from the prompt's Notion stages
    ("After company", "After lawyer review and density") and from addendum v7.4.
- Notion stages: the backlog has no "After lawyer review and density" or "Now". Those two
  rows are "Blocked" (with that blocker) and "Ongoing"; each row says what the prompt called
  it. No option was added to the database.

## 8. Security & privacy notes

- No personal data, no secret, no non-public id. The follow-up about named people (a person
  who posts alerts, moderators) keeps names outside the repository, as addendum v7.4 does.
- The addendum records, and does not weaken, the current promise that routes and positions
  stay in memory on the phone. It says what would have to happen before that changes.
- No legal conclusion is drawn; legal points carry "to be verified by a lawyer".
- Public repo check: nothing added that the public repository rules forbid.

## 9. Known issues & risks

- **Section F has no research questions.** They must come from Rahul (follow-up).
- The addendum names another company's product in the vision sentence, in a public
  repository. It is a statement of direction in a plan, not advertising; whether it may be
  used in a store listing or an advertisement is for the lawyer.
- ADR 0023 is Proposed. A reader could take the stages for a commitment; the addendum, the
  ADR and `CLAUDE.md` each say that they are not.
- The new `CLAUDE.md` section adds rules for features that do not exist. They have nothing
  to test today.
- The ADR link in the Notion Architecture Decisions row points at `main` and works only
  after the merge.

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P012g):

- Lawyer review: live reports, defamation, intermediary duties, background location (and
  naming another product in advertising; liability for reroute suggestions).
- Official-source terms for curated alerts.
- Weather and traffic data source evaluation.
- Survey research questions for addendum v7.5, section F: Rahul to supply the list.

In the Ideas backlog: Nearby panel (essentials) [MVP+]; curated alerts [After company];
community live reports with TTL [Blocked: lawyer review and density]; route-affected alerts
[Blocked: background location and consent design]; second survey round questions [Ongoing].

Next prompt: P013 (emergency contacts). Nothing here changes its scope.

## 11. How Rahul can verify

1. Read `docs/plan/addendum-v7.5.md`. Check that section A reads as a direction and not as a
   promise, and that the stages in section B are in the order you meant.
2. Read `docs/adr/0023-live-local-context-staged-path.md`. If you agree, say so and a later
   prompt sets it to Accepted; if not, say what to change.
3. Section F: paste the survey questions when you have them.
4. Notion: the ADR row (Proposed), five new ideas, four follow-ups.
5. CI: `repo-checks` green; the pull request's "Files changed" shows Markdown only.

## 12. Learning notes

No Android or code concepts in this prompt. One process note:

- **Proposed against Accepted.** An ADR marked Proposed records a decision that is offered,
  not taken. Code and prompts follow Accepted ADRs; a Proposed one is a document to argue
  with.
