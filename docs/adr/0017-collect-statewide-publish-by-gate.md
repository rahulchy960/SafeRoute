# ADR 0017: Collect reports statewide, publish by gate

- **Status:** Accepted
- **Date:** 2026-10-07
- **Prompt:** P010d
- **Plan refs:** Plan v7 §3.3, §3.4, §9.1, §9.2, §11, §12.4, §15.2, §22; [addendum v7.2](../plan/addendum-v7.2.md); [addendum v7.3](../plan/addendum-v7.3.md); [ADR 0013](0013-regions-and-expansion.md), [ADR 0016](0016-statewide-coverage-layers.md)

## Context

- [ADR 0016](0016-statewide-coverage-layers.md) made West Bengal the launch geography and
  limited safety data to "active regions": reports were accepted only there, starting with the
  Kolkata metropolitan area.
- Rahul wants community safety data across West Bengal.
- Accepting a report and showing it are two different acts. A report that is never shown harms
  nobody's picture of an area; a thin overlay shown too early does.
- A region that accepts no reports can never reach the k-threshold (at least 3 distinct
  reporters per cell), so under ADR 0016 a region could not show that it was ready.
- Every accepted report must be moderated. That load now comes from the whole state.
- In sparse areas a cell with few reports can point to a person or a single event.
- Nothing is built or measured yet: no boundary, no moderators, no report volumes.

## Decision

1. **Reports are accepted anywhere inside the West Bengal boundary.** A point outside is
   rejected with a typed error, never dropped silently. The boundary's source, licence and
   version are recorded when it is defined in P017. The rest of Plan v7 §9.2 is unchanged.
2. **Region statuses** replace `active_reports`, `context_only` and `planned`:
   - `context_only`: reports not accepted;
   - `collecting`: reports accepted and moderated, never shown publicly;
   - `published`: cells shown once the k-threshold is met and the gate below holds.
3. **Initial configuration:** the whole of West Bengal is `collecting`. No region is
   `published` at launch unless the thresholds are met. The Kolkata metropolitan area is the
   first candidate.
4. **Publication gate for a region**, all four:
   1. k-threshold: at least 3 distinct reporters per cell in the window (unchanged);
   2. moderation coverage: at least one trained moderator plus a backup, able to review the
      region's reports within the 48 h SLA, recorded in the region's configuration;
   3. backlog under the limit set in P018;
   4. lawyer-reviewed wording is live.
5. **A published region can be switched back to `collecting` by configuration**, without a
   release.
6. **No-data rule.** Absence of data never means "safe". Unpublished areas show "No community
   data here yet". Route results state what share of the route length lies in published cells
   and that areas without data are unknown, not safe. No safety scores, rankings or "safe"
   labels.
7. **Cell resolution.** H3 resolution 9 stays the urban default. Coarser parents (8 or 7) may
   be used in sparse areas; the rule is decided in P019 with real data and a lawyer's review of
   the re-identification risk.
8. **Claims.** Public wording says that reports are accepted anywhere in West Bengal and that
   community safety data appears where enough reports exist, with the current list of published
   regions. It never says "safety data across West Bengal" unless that is true.
9. **No code, contract, migration or workflow changes now.** The scopes of P017, P018 and P019
   change as addendum v7.3 describes.

## Alternatives considered

- **Collect only in Kolkata (ADR 0016 as written).** Least moderation load and the simplest
  message. Rejected: reports from elsewhere would be turned away, and no other region could
  ever gather the reports it needs to pass the k-threshold.
- **Publish statewide immediately.** One simple message. Rejected: most cells would be empty or
  under the k-threshold, an empty overlay reads as "nothing happens here", and there are no
  moderators or reviewed wording for it. Sparse cells also raise the re-identification risk.

## Consequences

- Easier: a region proves its readiness with its own data. Going back from `published` to
  `collecting` is a configuration change.
- Harder: moderation load arrives from the whole state before anything is published there. A
  per-region intake cap and a backlog alarm are needed (P017, P018).
- Reporters send reports that may never be shown. The report screen must say so plainly and
  must never promise publication or a police response.
- Collected, unpublished reports are still personal data held by the service. Their retention
  and the consent wording belong to P017 and P020 (**to be verified by a lawyer**).
- "Active region" in ADR 0016, addendum v7.2 and the P010c records now reads "published
  region". ADR 0016's three layers and its claims rule stay in force; only "reports are
  accepted only inside active regions" is replaced.
- New risks, recorded in addendum v7.3, section J: moderation load across the state;
  re-identification in sparse areas; an empty overlay misread as safety; the per-region
  official data effort; misleading funders or users by coverage wording.
- Follow-ups: lawyer review of the re-identification risk and the reporter-facing text; the
  West Bengal boundary source (P017); recruiting and training moderators and a backup; a
  coverage-wording review for pitch materials.
- Revisit when P018 has set the backlog limit, and when the first region is proposed for
  `published`.

## References

- [Addendum v7.3](../plan/addendum-v7.3.md); [addendum v7.2](../plan/addendum-v7.2.md);
  Plan v7 §9.2, §12.4, §15.2.
- [ADR 0013](0013-regions-and-expansion.md), [ADR 0016](0016-statewide-coverage-layers.md).
- Prompt log: [`docs/prompt-logs/010d-statewide-collection-model.md`](../prompt-logs/010d-statewide-collection-model.md).
