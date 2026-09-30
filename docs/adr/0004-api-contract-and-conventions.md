# ADR 0004: API contract (Zod → OpenAPI 3.1) and API conventions

- **Status:** Accepted
- **Date:** 2026-10-01
- **Prompt:** P004
- **Plan refs:** Plan v7 §6.1–§6.3, §12.2, §13.2, §14.5, §17, §20

## Context

- ADR 0001 chose a native Kotlin app whose client is **generated** from an OpenAPI 3.1 contract
  (no tRPC, no hand-written models). P008 will generate that client, so the contract has to exist,
  be correct and change only on purpose.
- Old app versions stay installed for months (Plan v7 §6.2). Once the app ships, a breaking
  change to a live endpoint breaks users who haven't updated, including during an SOS.
- No client exists yet, so the conventions below can still be fixed without cost. P002 used
  snake_case `request_id` in error bodies, and `/health` returned `uptime_s`.
- The product is city-neutral (ADR 0005). The repository is public, so the contract is public
  too.

## Decision

### Contract-first from Zod

- Every route is declared with `createRoute` from `@hono/zod-openapi` on an `OpenAPIHono`
  router. Its Zod schemas validate requests at runtime **and** generate the spec, so the two
  can't drift apart. A test fails if a registered route is missing from the spec, or the spec has
  a path the app doesn't serve.
- `pnpm openapi:generate` builds the app with dummy dependencies (no database, no network) and
  writes **`contracts/openapi.json`** (OpenAPI **3.1**) deterministically. The file is committed and
  never edited by hand.
- CI fails when the committed file is stale (`pnpm openapi:check`, also a unit test), when it
  fails Redocly lint (`pnpm openapi:lint`), or when oasdiff finds a breaking change that hasn't been
  approved (below). `backend-ci` runs the first two from P004a; the `contracts-ci` workflow with
  the oasdiff gate arrives in P004b (the prompt was split for size).
- OpenAPI 3.1, not 3.0. The P004 spike showed oasdiff 1.32.1 handles 3.1: `type: [..., "null"]`,
  `examples` and `$ref` components, including nullable → not-nullable detection.

### Conventions

| Topic | Rule |
| --- | --- |
| Paths | Product endpoints under **`/v1`**. Operational probes (`/health`, `/health/ready`) stay unversioned and are not for app features. |
| Naming | JSON property names **camelCase** (decided by Rahul). The database and log fields stay **snake_case** (`request_id` in logs, `user_id` in tables). The `X-Request-Id` header is unchanged. operationIds are lowerCamelCase verbs (`getHealth`). |
| IDs | UUID strings. |
| Time | RFC 3339 timestamps in UTC with `Z` (e.g. `2026-10-01T08:30:00Z`). |
| Coordinates | Explicit `latitude` / `longitude` decimal-degree numbers (WGS84), never a bare `[a, b]` pair. PostGIS stores points as (longitude, latitude), so conversions happen in one place. |
| Enums | Strings. Clients must tolerate unknown values, so adding a value is not breaking. |
| Errors | RFC 9457 `application/problem+json` with `type`, `title`, `status`, `detail`, `code`, `requestId`, and optional `errors: [{path, code}]` for `validation_error`. `code` is an **open string**, not an enum: adding a code is non-breaking and generated clients don't crash on a new one. Validation errors never echo submitted values (Plan v7 §12.2). |
| Idempotency | Retryable state-changing calls take the `Idempotency-Key` header (component `IdempotencyKey`, 16–64 `[A-Za-z0-9._-]`); the server stores key → response for 24 h. First used in P015. |
| Auth | Security scheme `firebaseBearer` (HTTP bearer, Firebase ID token). Protected routes reference it from P005. The spec *documents* the boundary; server middleware *enforces* it. |
| City-neutral | No city or place names in paths, operationIds, schema names, tags, descriptions or examples (ADR 0005). When multi-city arrives, scoping is an **optional** `cityCode` field, which is additive and non-breaking. It is not added now. |
| Hosts | `servers: [{url: "/"}]`. No real hostnames, emails or personal data in the spec. |

### Versioning and breaking changes

- `info.version` is the **contract** version (semver), never a git SHA or build number. It is
  bumped in the PR that changes the spec: **minor** for additive changes (new endpoint, new
  optional field, new enum value or error code), **patch** for description-only changes.
- A **breaking** change (removing or renaming an endpoint or response field, adding a required
  request field, tightening a type, removing nullability) needs **a new ADR** and **either a new
  path version (`/v2/...`) or a coordinated app release** that drops support for old versions.
  In CI it passes only when the PR has the label `breaking-api-change` **and** adds a new file
  under `docs/adr/`.
- The P004 renames (`request_id` → `requestId` in error bodies, `uptime_s` → `uptimeSeconds` in
  `/health`) are intentional. No client exists, and this is the first committed contract.

### No docs endpoint

The API serves **no** Swagger UI, Redoc or `/openapi.json` route. That keeps the production
surface small, and the spec is readable in the repository anyway. A staging-only docs UI is a
follow-up.

## Alternatives considered

- **Hand-written OpenAPI YAML.** It drifts from the code unless every change is made twice.
  Rejected.
- **tRPC / TypeScript-only types.** No Kotlin client. Rejected in ADR 0001.
- **OpenAPI 3.0.3 output.** Better support in older tools, but 3.1 is plain JSON Schema, and the
  spike showed the differ handles it. Revisit only if P008's generator can't handle 3.1.
- **`code` as a closed enum.** Nicer generated types, but each new code would be a breaking change
  and old apps could fail to parse errors. Rejected.
- **snake_case JSON.** Matches the database, but Kotlin/JSON conventions are camelCase and the
  database shape shouldn't leak into the contract. Rejected by Rahul.
- **Making contracts-ci a required check now.** Path-filtered workflows don't report on PRs that
  don't touch those paths, so a required check would block unrelated PRs. It stays a follow-up
  (e.g. an always-run job that no-ops).

## Consequences

- Easier: the app client (P008) is generated, validation errors are uniform, reviewers see
  contract changes as a JSON diff plus an oasdiff changelog in the job summary, and per-route
  dashboards can key off `operationId` (Plan v7 §14.5).
- Harder: every route must go through `createRoute`. The spec file must be regenerated and
  committed with each API change. Breaking changes need the label + ADR ceremony.
- Risks: oasdiff false negatives on exotic schema changes, mitigated by review of the changelog
  summary. The contract is public: it shows endpoint shapes but no secrets, and authorization is
  enforced server-side regardless.

## References

- Plan v7 §6.1–§6.3 (API design), §12.2 (no personal data in logs/errors), §14.5
- ADR 0001 (Kotlin + OpenAPI), ADR 0003 (database conventions), ADR 0005 (naming)
- [`contracts/README.md`](../../contracts/README.md), `backend/src/contract/`,
  `.github/workflows/contracts-ci.yml` (P004b)
- Prompt log: [`docs/prompt-logs/004-openapi-contract-pipeline.md`](../prompt-logs/004-openapi-contract-pipeline.md)
