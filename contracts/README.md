# contracts/

The HTTP API contract between the backend and the Android app (Plan v7 §6, ADR 0001,
[ADR 0004](../docs/adr/0004-api-contract-and-conventions.md)).

- **`openapi.json`**: the OpenAPI **3.1** document, **generated** from the backend's Zod route
  definitions (`backend/src/routes`, `backend/src/modules/*/routes.ts`) and committed. **Never
  edit it by hand.** Being generated JSON, it can't carry an SPDX comment; it is covered by
  `AGPL-3.0-only` through `COPYRIGHT.md` and `info.license`.
- The Kotlin client is generated from this file (P008). The app never hand-writes request or
  response models.

## Regenerate and check

Run inside `backend/` (no database or network needed):

```sh
pnpm openapi:generate   # rewrite contracts/openapi.json; commit the diff with your route change
pnpm openapi:check      # fails with "contracts/openapi.json is stale" if you forgot
pnpm openapi:lint       # Redocly lint (backend/redocly.yaml)
```

CI: `backend-ci` fails when the committed file is stale (a unit test compares it with a fresh
generation) or fails lint. `.github/workflows/contracts-ci.yml` runs the same checks, then compares
the PR's spec with the base branch using **oasdiff** and writes the changelog to the job summary.
A breaking change fails the job unless the PR has the label `breaking-api-change` **and** adds a
new ADR file under `docs/adr/`.

## How to read the spec

- `paths`: every endpoint with its `operationId` (the method name in the generated client),
  `tags`, `summary`, request schema and responses.
- `components.schemas`: shared data shapes (`ProblemDetails`, `Health`, ...).
- `components.responses`: shared error responses (`BadRequest`, `NotFound`, `InternalError`, ...).
- `components.securitySchemes.firebaseBearer`: every protected `/v1` operation lists it under
  `security` (since P005, [ADR 0006](../docs/adr/0006-authentication-and-roles.md)); `/health`
  and `/health/ready` are public by design (`security: []`).
- `components.parameters.IdempotencyKey` is declared ahead of use (first used in P015).
  `POST /v1/me/bootstrap` doesn't need it: it is naturally idempotent (keyed by the token's user).
- Consent (since P009a, [ADR 0010](../docs/adr/0010-adults-only-and-consent-records.md)):
  `POST /v1/me/bootstrap` takes a `consent` object (age declaration, notice version and locale,
  purposes) and needs it to create an account. `GET /v1/me/consents` returns the latest decision
  per purpose; `PUT /v1/me/consents/{purpose}` records a new one. `purpose` is an open string
  checked against a server allowlist (`backend/src/modules/consents/purposes.ts`), so a new
  purpose is not a contract change.
- Search (since P011a, [ADR 0018](../docs/adr/0018-search-and-geocoding.md)):
  `POST /v1/search` takes a JSON body (`SearchRequest`) with `q`, optional `nearLatitude` +
  `nearLongitude` (a bias, both or neither), `language` and `limit`, and returns
  `{ results: Place[], attribution }`. It is a POST because the search must not be in a URL
  (see "Privacy in URLs" below); it changes nothing and needs no `Idempotency-Key`. Until
  0.4.0 it was a GET with query parameters; that operation was removed in 0.5.0
  ([ADR 0019](../docs/adr/0019-privacy-in-urls.md)). Show
  `attribution` next to the results when it is not null. `Place.id` is opaque and `Place.kind` is
  an open string. Plan v7 §6.3 wrote the bias as one `near` parameter; the contract uses explicit
  `nearLatitude` / `nearLongitude` to follow the rule below. The contract names no provider.
- Paste the file into any OpenAPI viewer (e.g. editor.swagger.io) to browse it. It is public data.

## API contract rules (summary of ADR 0004)

- Product endpoints live under **`/v1`**. `/health` and `/health/ready` are unversioned
  operational probes.
- JSON property names are **camelCase**. IDs are UUID strings. Timestamps are RFC 3339 UTC with
  `Z`. Coordinates are explicit `latitude` / `longitude` numbers in WGS84 decimal degrees, never a
  bare pair; note that PostGIS stores (longitude, latitude).
- Enums are strings. **Clients must tolerate unknown values.**
- Retryable state-changing calls take an `Idempotency-Key` header. Protected routes use
  `firebaseBearer`.
- **Privacy in URLs** ([ADR 0019](../docs/adr/0019-privacy-in-urls.md)): the hosting platform
  logs every URL with its query string. No user-entered text, position, phone number, token or
  key may be a path or query parameter; use a body or a header. A contract test fails on a
  parameter name that looks like one of these unless its allowlist names an ADR.
- **City-neutral** (ADR 0005): no city or place names in paths, operationIds, schema names, tags,
  descriptions or examples. Future scoping by region is an optional `regionCode` field (additive,
  not added yet; [ADR 0013](../docs/adr/0013-regions-and-expansion.md)).
- **Versioning:** `info.version` is the contract version. Additive change → minor bump in the same
  PR. Breaking change → new ADR + new path version or coordinated app release. CI then needs
  the PR label `breaking-api-change` **and** a new file under `docs/adr/`. Old app versions stay
  installed for months (Plan v7 §6.2).

## Errors

Every 4xx/5xx body is `application/problem+json` (RFC 9457):

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "No route matches this request.",
  "code": "not_found",
  "requestId": "3f2c1b9e-7a44-4c1e-9d2f-0b6a5e8c1d23"
}
```

`requestId` equals the `X-Request-Id` response header. `validation_error` adds
`errors: [{ "path": "body.latitude", "code": "too_big" }]` and never echoes submitted values.
Clients switch on `code`, which is an **open set** (unknown codes must be handled generically).
Currently defined codes:

| `code` | Typical status | Meaning |
| --- | --- | --- |
| `validation_error` | 400 | Request failed schema validation; see `errors` |
| `unauthorized` | 401 | Missing or invalid credentials; same body for every reason, plus `WWW-Authenticate: Bearer`. Refresh the ID token and retry once |
| `forbidden` | 403 | Authenticated but the role is not allowed. Final |
| `bootstrap_required` | 403 | Signed in, but no account yet: call `POST /v1/me/bootstrap`, then retry |
| `account_deleted` | 403 | The account was deleted. Final |
| `consent_required` | 403 | Bootstrap of a new account without the `consent` object. Show the consent notice, then retry with it |
| `adult_required` | 403 | Bootstrap of a new account without `ageConfirmed: true`. SafeRoute is for adults (18+); nothing is stored |
| `not_found` | 404 | No such route or resource |
| `conflict` | 409 | Conflicts with the current state |
| `phone_already_registered` | 409 | Bootstrap: another account already holds this phone number |
| `account_deletion_required` | 409 | `account_core` consent can't be withdrawn on its own; the user must delete the account |
| `gone` | 410 | Existed but expired or ended (e.g. a finished share) |
| `rate_limited` | 429 | Too many requests. Wait for the `Retry-After` header (seconds) before trying again |
| `http_error` | 4xx | Framework-level rejection (e.g. malformed JSON, unsupported media type) |
| `internal_error` | 500 | Unexpected server error (details only in server logs) |
| `db_unavailable` | 503 | Readiness: the database is not reachable |
| `db_not_configured` | 503 | No database configured for this instance (readiness, `/v1` routes) |
| `auth_unavailable` | 503 | Google's token-signing keys can't be fetched right now; retry with backoff (not a sign-out) |
| `auth_not_configured` | 503 | This instance has no `FIREBASE_PROJECT_ID` (dev/test only) |
| `search_unavailable` | 503 | Search can't be answered right now (the geocoding provider failed, or the shared daily budget is used up). Retry later; honour `Retry-After` when it is sent. Never a reason to sign out |
| `search_not_configured` | 503 | This instance has no geocoding key (dev/test only) |

Keep this table in sync with `PROBLEM_CODES` in `backend/src/contract/problem.ts`.
