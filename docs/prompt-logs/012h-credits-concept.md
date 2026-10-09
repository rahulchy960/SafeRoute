# P012h: Credits and verified place contributions (concept, docs only)

| Field | Value |
| --- | --- |
| Prompt | P012h (docs only; follows P013b2 in time, numbered with the P012 planning prompts) |
| Milestone | M4 planning (depends on P013b2, merged as `fe7f621`) |
| Branch | `docs/012h-credits-concept` |
| PR title | `docs(plan): record SafeRoute credits and verified place contributions concept [P012h]` |
| Notion | [P012h row in the Prompt Log](https://app.notion.com/p/3f4073707720811da698def0487fdf34) |
| Date | 2026-10-09 |
| Plan refs | Plan v7 §9.2; addenda v7.3 (I), v7.4 (D, F), v7.5; ADRs 0010, 0018, 0021 |

> **Docs only.** No code, contract, migration, workflow or dependency changes. Nothing is
> built, measured, priced or scheduled.
>
> **No legal, tax or payments conclusion is drawn.** Every such point is marked "to be
> verified by a lawyer and a chartered accountant". The indicative rate from the discussion
> (10,000 points for ₹200) is recorded as an unvalidated proposal that must not appear in any
> public material; this repository is public, so the addendum says so next to the number.
>
> **Two things in the prompt did not match what exists:** there is no ADR about "Add a
> missing place" (P011g) and no P011g prompt log, so no pointer note was added anywhere; and
> the Notion Ideas backlog has no stage "Now" (section 9).

## 1. Objective

Record the reward idea, the decisions already taken about it and the gates before any build,
so that it has a place that is not the roadmap, and so that three rules bind future work.

## 2. Context & prerequisites

- Preflight: `main` synced at `fe7f621`; PR #50 (P013b2) merged; no open pull requests.
- The next free addendum number is v7.6; the next free ADR number is 0025.
- The Plan PDF cannot be read on this machine. Plan v7 §9.2 is cited as the prompt cites it
  (safety incident reports, and the delays that protect a reporter); its text was not read.
- The decisions recorded were given in the prompt as already taken: never credits for safety
  reports; an in-app geotagged photo, no gallery; independent confirmation, not real-time
  neighbours; nothing built now.

## 3. Workflow executed

1. `/start-prompt`: sync, PR #50 merged, Notion (P013b2 set to Merged), branch.
2. Read addenda v7.4 and v7.5 and ADR 0023 for form; looked for a P011g ADR (none).
3. Wrote addendum v7.6 and ADR 0025; updated the reading order and the ADR index; added the
   rules to `CLAUDE.md`.
4. Notion: ADR row, five ideas, six follow-ups.
5. Checks; `/ship-prompt`.

## 4. Changes

- `docs/plan/addendum-v7.6.md` (new): A status and scope; B contribution types; C evidence;
  D verification and anti-fraud; E data, licensing and privacy; F rewards; G product path;
  H gates; I rejected alternatives; J risks; K plan edits (a short closing section, as in the
  earlier addenda; the prompt listed A to J).
- `docs/adr/0025-credits-and-place-contributions.md` (new, **Proposed**); ADR index row.
- `docs/plan/README.md` and `CLAUDE.md`: reading order PDF → v7.1 → … → v7.6.
- `CLAUDE.md`, "Product guardrails": no credits, points, rewards, badges with value or
  contests for incident or safety reports; no gallery or file uploads for a contribution; no
  cash rewards without an ADR and a legal review, and no rate in public text; a place
  contribution follows the evidence and licence rules of the addendum.

## 5. Diagram

No diagram needed. No flow, state machine, schema, API sequence, infrastructure or CI
pipeline changes.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| markdownlint (Docker, same config as CI) | 0 errors in 115 files |
| Relative links in every Markdown file | 619 checked; 0 broken in the files of this prompt; 1 broken link that was there before (below) |
| gitleaks on the commit | no leaks found |
| `git diff --stat main` | Markdown only: `CLAUDE.md` and files under `docs/` |

The one broken link is older than this prompt and is not fixed here (golden rule 4):
`docs/adr/0004-api-contract-and-conventions.md` points to
`../prompt-logs/004-openapi-contract-pipeline.md`, which does not exist under that name. It
is a Notion follow-up.

No failure-matrix rows apply: no SOS, live-location or contacts code is touched.

## 7. Decisions & ADRs

ADR 0025, **Proposed**: record the concept and build nothing; three contribution types with
safety reports never eligible; the in-app photo as evidence; independent confirmation;
recognition first and vouchers later, no cash without a further ADR; eight gates; credits need
SafeRoute's own place database; own photos and observations only.

Where the text goes beyond the prompt's wording, as a clarification and not a new decision:

- "Trust scores" are about a contributor's record, never about a place (addendum v7.4,
  section D forbids scores for places).
- A manual mapping contest is itself marked "to be verified by a lawyer and a chartered
  accountant" before it runs, because it has prizes.
- "A gate that is nearly met is not met."

## 8. Security & privacy notes

- No personal data in the change.
- The addendum names the personal data the concept would create (photos, where a contributor
  was and when, payout details) and requires a separate consent purpose, separation of the
  contributor from the place record, a retention limit and encryption at rest. None of it
  exists.
- It states what an in-app photo does not prove (the name; a true location), so that it is
  not mistaken for a fraud control.
- A points-to-money figure is in a public repository. It is labelled unvalidated and barred
  from public material in the same sentence. If Rahul would rather not have the figure in the
  repository at all, it can be removed before the merge.

## 9. Known issues & risks

- **ADR 0025 is Proposed** and could be read as a commitment. The addendum, the ADR and
  `CLAUDE.md` each say that nothing is built and no prompt starts it.
- **"Mapping contest pilot" is filed under stage "Research"**, not "Now": the Ideas backlog
  has no "Now" option, and adding options is Rahul's call. The note on the item says so.
- **Option A is described as "planned as P011g; not built"** because the repository has no
  P011g.
- Plan v7 §9.2 was not read (see section 2).
- The three `CLAUDE.md` rules apply from this merge, although the concept is only Proposed:
  they are prohibitions, which cost nothing to keep.

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P012h):

- Lawyer and chartered-accountant review of rewards, tax and payments.
- Research: on-device face and plate blurring and metadata stripping.
- Research: GPS-spoofing defences and Play Integrity signals.
- Reward-partner options and costs.
- Contributor licence wording.
- Photo retention decision.
- Broken link in ADR 0004 to the P004 prompt log (found by this prompt's link check).

In the Ideas backlog: SafeRoute credits (recognition first, vouchers later) [After company];
in-app geotagged place photo (evidence) [After company]; own place database (Option C)
[After company]; place confirmation (open/closed/moved) [After company]; mapping contest
pilot (manual, no app) [Research; owner Rahul; see section 9].

Next prompt: P014 (device-first SOS). Nothing here changes its scope. Still owed by Rahul from
P013: the phone checks of P013b2 and the staging checks of P013a.

## 11. How Rahul can verify

1. Read `docs/plan/addendum-v7.6.md`. Check that sections B, C and D say what was decided,
   and that section F treats the rate the way you want (or ask for the figure to be removed).
2. Read `docs/adr/0025-credits-and-place-contributions.md`. It stays Proposed until you say
   otherwise.
3. Read the four new rules in `CLAUDE.md`, "Product guardrails".
4. Notion: the ADR row (Proposed), five ideas, six follow-ups. Move "Mapping contest pilot"
   to the stage you want.
5. CI: `repo-checks` green; "Files changed" shows Markdown only. Squash and merge.

## 12. Learning notes

No Android or code concepts in this prompt. Two notes on the subject:

- **Why an in-app camera and not a gallery.** A picture chosen from the gallery is only a
  file: it can be a download or a screenshot of another map, and its place and time can be
  edited. A picture taken inside the app comes with the phone's position and clock at that
  moment. It is stronger evidence, not proof: the position can still be faked.
- **Incentives change data.** Paying for a contribution gets more contributions and worse
  ones, in proportion to how hard they are to check. A place can be checked by going there;
  an incident usually cannot. That is the whole reason safety reports are excluded.
