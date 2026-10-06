# SafeRoute Plan v7 — Addendum v7.3: collect statewide, publish by gate

> Copyright (C) 2026 Rahul Chowdhury. Licensed under CC BY-NC-ND 4.0
> (`CC-BY-NC-ND-4.0`), like everything under `docs/`: see [`../LICENSE.md`](../LICENSE.md).

- **Date:** 2026-10-07 · **Prompt:** P010d · **Decision:**
  [ADR 0017](../adr/0017-collect-statewide-publish-by-gate.md)
- **Where this addendum differs from Plan v7, [addendum v7.1](addendum-v7.1.md) and
  [addendum v7.2](addendum-v7.2.md), v7.3 wins.** Read the PDF first, then v7.1, v7.2 and this
  file.
- The PDF and the texts of v7.1 and v7.2 are unchanged.
- A full **v8** will be written after the MVP and company registration.
- **What changes:** v7.2 accepted reports only inside "active regions". Now reports are
  **accepted** anywhere inside West Bengal, and **publication** is gated region by region. The
  three coverage layers of v7.2, section B stand; only the rule for layer 3 changes.
- Nothing here is built or measured. Limits and boundaries marked "set in" or "defined in" are
  open until the prompt named next to them records them.
- Legal points are marked **to be verified by a lawyer**. This file draws no legal conclusion.

## A. Region statuses

These replace `active_reports`, `context_only` and `planned` from v7.2, section C. The term
"active region" is no longer used; say "published region".

| Status | Reports accepted | Shown publicly |
| --- | --- | --- |
| `context_only` | No | No |
| `collecting` | Yes, and moderated | **Never** |
| `published` | Yes, and moderated | Cells, once the k-threshold is met **and** the publication gate (section C) holds |

- **Initial configuration:** the whole of West Bengal is `collecting`.
- **No region is `published` at launch** unless the thresholds are met.
- The Kolkata metropolitan area is the **first candidate** for `published`.
- The rest of v7.2, section C stands: `regionCode`, a server-side versioned configuration file,
  no region names in identifiers.

## B. Acceptance rule

- A report is accepted only if its point is **inside the West Bengal boundary**.
  - The boundary's source, licence and version are recorded when it is defined in P017.
  - A point outside is rejected with a **typed error**. Never a silent drop.
- Everything else in Plan v7 §9.2 is unchanged:
  - fixed categories;
  - no free text shown publicly;
  - a publication delay of at least 24 h;
  - 1-hour time bands;
  - the pin moved by up to about 300 m;
  - per-user rate limits;
  - account age of at least 24 h.

## C. Publication gate for a region

A region is `published` only while all four hold:

1. **k-threshold:** at least 3 distinct reporters per cell in the window (unchanged).
2. **Moderation coverage:** at least one trained moderator plus a backup, able to review that
   region's reports within the SLA (48 h). Recorded in the region's configuration.
3. **Backlog** under the limit set in P018.
4. **Lawyer-reviewed wording is live** (**to be verified by a lawyer**).

A published region can be switched back to `collecting` without a release: configuration only.

## D. No-data rule

- **Absence of data never means "safe".**
- In unpublished areas the map shows "No community data here yet". This replaces v7.2's
  "Community reports aren't available here yet", which is no longer accurate: reports are
  accepted there.
- Route results state what share of the route length lies in published cells, and say that
  areas without data are unknown, not safe.
- No safety scores, rankings or "safe" labels (Plan v7 §3.3, unchanged).

## E. Cell resolution

- The urban default stays H3 resolution 9.
- Sparse or rural areas may use coarser parents (resolution 8 or 7), derived from the stored
  resolution-11 cells (Plan v7 §15.2).
- The rule and its thresholds are decided in P019 with real data, and reviewed by a lawyer for
  re-identification risk (**to be verified by a lawyer**).
- **Risk recorded now:** where few people live or pass, a cell with few reports can point to a
  person, a household or a single event, even above the k-threshold.

## F. Moderation capacity

- The daily per-user cap (3) stays.
- Add a **per-region intake cap** and a **backlog alarm**.
- Statewide central moderation is allowed: moderation is rule-checking.
- Local reviewers are recruited per region as volume grows.
- Moderator training notes and the two-person rule for bulk actions (Plan v7 §12.4) apply.

## G. Reporter experience

- The report screen explains, in plain words:
  - reports from any part of West Bengal are accepted;
  - they appear on the map only where enough reports exist.
- Never promise publication or a police response.
- Keep the official-path links (112, police portals).
- The text is reviewed by a lawyer before launch (**to be verified by a lawyer**).

## H. Official data

- Official data stays per jurisdiction and Kolkata-first: RTI per police commissionerate or
  district.
- Statewide official coverage is a long-running, separate effort (Plan v7 §9.1).

## I. Claims rule update

This extends v7.2, section J. The three layers still apply.

**Allowed wording, as an example:**

> Report unsafe spots anywhere in West Bengal. Community safety data appears where enough
> reports exist, starting in [list of published regions]. SOS and live sharing work wherever
> there is cellular signal and GPS.

- The list of published regions is the current one from the configuration. While it is empty,
  say that no area shows community data yet; do not name a region as if it were published.

**Not allowed:**

- "safety data across West Bengal", unless it is true;
- any "safe area" claim;
- unmeasured usage numbers.

`CLAUDE.md` "Coverage claims" and the `README.md` "Coverage" section follow this section.

## J. Plan edits

These add to the tables in v7.1, section C and v7.2, section I. Where they name the same
section, this table wins.

| § | Change |
| --- | --- |
| §9.2 | Acceptance and publication wording: accepted anywhere inside the West Bengal boundary (section B); published by region through the gate (section C). |
| §11 | Region configuration with the three statuses, and the `regionCode` column on reports (P017). |
| §3.4 | Launch criteria, add: the boundary check tested; the no-data wording verified on devices; a moderator rota covering the collecting regions; lawyer review of the reporter-facing text. |
| §22 | Risks, add: moderation load across the state; re-identification in sparse areas; an empty overlay misread as safety; the per-region official data effort; misleading funders or users by coverage wording. |
