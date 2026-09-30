# ADR 0005: Product name "SafeRoute" and multi-city readiness

- **Status:** Accepted
- **Date:** 2026-10-01
- **Prompt:** P003c
- **Plan refs:** Plan v7 §1, §14.2 (Stage 3), §15.1, §17, §20

## Context

- The project started as "SafeRoute Kolkata", the name used in Plan v7, the first prompt logs and
  ADRs 0001–0002. The GitHub repository is already named `SafeRoute`.
- More cities are planned after the Kolkata launch. A city in the product name would make
  every later city look like a secondary add-on and would need another rename.
- Nothing has been published under the old name yet: no Play listing, and the Android package
  name is still undecided (P007, Plan v7 §15.1). Renaming now costs only wording changes.
- Plan v7 §14.2 puts multi-city support in Stage 3. The MVP stays Kolkata-only.
- The P003c prompt assigned this ADR the number 0005; 0004 stays free for the next decision.

## Decision

- We will call the product **"SafeRoute"** in all current docs, governance files, package
  metadata, the GitHub description and the Notion workspace ("SafeRoute — Engineering").
- We will reserve both "SafeRoute" and the former name "SafeRoute Kolkata" in
  [`TRADEMARKS.md`](../../TRADEMARKS.md). Forks may use neither.
- We will keep historical records unchanged: the Plan v7 PDF (CC BY-NC-ND, not re-exported), past
  prompt logs and the bodies of accepted ADRs. ADRs that use the old name get a one-line note.
- **City-neutral naming rule:** city names don't appear in code identifiers, API paths,
  operationIds, schema/table/column names, package names or Gradle/module names.
  City-specific facts live in configuration, data or documentation. "Kolkata" stays wherever it is
  a fact: launch city, pilot areas, OSRM/tiles extract, Durga Puja load planning, `Asia/Kolkata`
  conversions, Bengali UI copy, test landmarks. The rule is in `CLAUDE.md` → "Naming rules".
- We will **not** build multi-city support now: no city columns, no city config, no city
  selector. It stays deferred to Plan v7 §14.2 Stage 3.

### What a future city will need (not implemented)

1. A **city code** on incident reports and official/imported data (e.g. `cityCode`, default
   `kol`), added when the reports table is designed (P017) or in an expand migration later.
2. A **routing graph and tile extent** per city: an OSRM extract, tile coverage and a geocoding
   bounding box.
3. **Moderation coverage** per city: moderators who know the area, queue routing and SLAs.
4. **Consent and language copy** per city: onboarding/consent text and local languages next to
   English (Bengali for Kolkata).
5. **Police and data sources** per city: police-station and helpline data, official datasets and
   their licenses and refresh jobs.

## Alternatives considered

- **Keep "SafeRoute Kolkata".** Accurate for the MVP, but it needs a second rename (and
  store-listing churn) as soon as a second city arrives. Rejected.
- **Rename later, just before a second city.** By then the package name, Play listing, Firebase
  project and user-facing copy would carry the old name, which makes it more expensive and
  confusing. Rejected.
- **Build multi-city plumbing now (city columns, config).** Speculative work with no second city
  to test it against, against Plan v7 §14.2 staging. Rejected; the list above records what it will
  need.

## Consequences

- Easier: adding a city later needs no rename, and identifiers never embed a city.
- Harder: reviewers must enforce the naming rule. Kolkata-specific facts must be kept visibly
  factual (the README "Coverage" section says where SafeRoute works today).
- The Plan v7 PDF still says "SafeRoute Kolkata" until Rahul publishes a Plan v8 (follow-up).
- Revisit when a second city is scheduled (Plan v7 §14.2 Stage 3).

## References

- Plan v7 §1 (product scope), §14.2 (scaling stages), §15.1 (package name), §17, §20
- [`TRADEMARKS.md`](../../TRADEMARKS.md), [`README.md`](../../README.md) → "Coverage"
- Prompt log: [`docs/prompt-logs/003c-rebrand-saferoute.md`](../prompt-logs/003c-rebrand-saferoute.md)
