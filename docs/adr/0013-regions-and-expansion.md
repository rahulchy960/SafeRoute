# ADR 0013: Regions and expansion

- **Status:** Accepted
- **Date:** 2026-10-06
- **Prompt:** P009a
- **Plan refs:** Plan v7 §10, §14, §14.2 (Stage 3); [addendum v7.1](../plan/addendum-v7.1.md) D, E

> Note (2026-10-07, P010c): West Bengal is the launch geography; see
> [ADR 0016](0016-statewide-coverage-layers.md).
>
> Note (2026-10-07, P010d): reports are collected statewide and published region by region
> through a gate; see [ADR 0017](0017-collect-statewide-publish-by-gate.md).

## Context

- [ADR 0005](0005-product-name-and-multi-city-readiness.md) and
  [ADR 0004](0004-api-contract-and-conventions.md) planned for more *cities* and named the
  future field `cityCode`.
- The roadmap now expands region by region: nearby districts, then larger cities, then the rest
  of West Bengal (addendum v7.1, D). A district or a state-level area is not a city.
- Renaming a field after it exists in the contract or the database is a breaking change. Naming
  it correctly now costs nothing: nothing has been built.
- ADR number 0012 is reserved for the Firebase configuration decision in P009b.

## Decision

1. **The term is "region".** A region is a city, a district or a state-level area.
2. **The future optional field is `regionCode`, not `cityCode`,** in the contract and on
   reports. This supersedes the `cityCode` wording in ADR 0004 and ADR 0005 and in the P017
   follow-up. **No code, contract or migration changes now.**
3. **Per-region configuration will be designed later.** It will cover: bounds, routing graph,
   tile extent, police data and numbers, languages, moderators, civic handles
   ([ADR 0014](0014-civic-reports-ask-govt.md)) and consent copy.
4. **Readiness checklist for opening a region:**
   - verified police numbers;
   - local moderators;
   - enough report density, or the map-context fallback below;
   - languages;
   - a capacity model for the larger extent;
   - a lawyer check.
5. **Rural sparse-data rule.** Where there are too few reports, show map context instead of an
   empty overlay. Keep the k-threshold: it is never lowered to make a region look covered.
6. **Plan v7 §14's capacity model is Kolkata-only.** It must be redone in v8 before the first
   expansion.
7. The city-neutral naming rule of ADR 0005 is unchanged and applies to regions too: no region
   names in identifiers, paths or schema names.

## Alternatives considered

- **Keep `cityCode`.** Already written in two ADRs, but wrong for districts and state-level
  areas, and a rename later would be breaking. Rejected.
- **A hierarchy now (state → district → city).** More expressive, but speculative with one
  launch city. Rejected; a flat code can be mapped to a hierarchy in configuration later.
- **Add the field now with a default.** Rejected for the same reason as in ADR 0005: there is
  no second region to test it against.

## Consequences

- P017 designs reports with `regionCode` in mind (follow-up updated).
- ADR 0004 and ADR 0005 each get a one-line note pointing here; their bodies stay as written.
- New risks recorded in the addendum: sparse data in rural regions, per-region police data
  quality, moderator capacity per region.
- Revisit when the first region after Kolkata is scheduled.

## References

- Plan v7 §10, §14; [addendum v7.1](../plan/addendum-v7.1.md).
- [ADR 0004](0004-api-contract-and-conventions.md),
  [ADR 0005](0005-product-name-and-multi-city-readiness.md).
- Prompt log: [`docs/prompt-logs/009a-consent-backend-plan-addendum.md`](../prompt-logs/009a-consent-backend-plan-addendum.md).
