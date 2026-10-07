# backend/

TypeScript modular monolith for SafeRoute (Plan v7 §4, §6): the **saferoute-api** Hono
service on PostgreSQL + PostGIS via Drizzle ORM (P003), with a generated OpenAPI 3.1 contract
(P004, [`../contracts/`](../contracts/)) and Firebase ID-token authentication with `/v1/me`
(P005a/b, [ADR 0006](../docs/adr/0006-authentication-and-roles.md)). It ships as one container
image (P006a, [ADR 0007](../docs/adr/0007-gcp-staging-topology.md)). Later prompts add the Cloud
Run deploy workflow (P006b), the pg-boss worker and the domain modules listed in
[`src/modules/README.md`](src/modules/README.md).

## Requirements

- **Node.js 24** (Active LTS; the exact major is in [`.nvmrc`](.nvmrc)). Check with `node --version`.
- **pnpm 12.8.1** (pinned in `package.json` → `packageManager`).
- **Docker Desktop** (running) for the local database and the `db` tests.

`backend/` is a standalone pnpm package, not part of a root workspace. Run every command from
inside `backend/`.

## Run locally

```sh
cd backend
pnpm install
pnpm db:up               # PostGIS in Docker on 127.0.0.1:5433 (LOCAL ONLY credentials)
pnpm db:migrate          # apply the committed migrations
pnpm dev                 # http://localhost:8080/health and /health/ready, logs pretty-printed
```

`pnpm dev` also runs without a database: `/health/ready` then answers 503. Database workflow,
schema changes and the lng/lat convention: [`src/db/README.md`](src/db/README.md).

Production-style:

```sh
pnpm build               # compiles src/ → dist/
pnpm start               # node dist/server.js, JSON logs on stdout
```

Ctrl+C stops the server gracefully (in-flight requests finish, exit code 0).

### Sign-in locally (no emulator)

Protected routes need `FIREBASE_PROJECT_ID`. Put the **staging** project ID in `backend/.env`
(git-ignored; `pnpm dev` loads it). Without it, `/v1/*` answers 503 `auth_not_configured`.

There is **no dev login route, no Firebase emulator and no auth bypass** of any kind: the API only
accepts RS256 tokens signed by Google for that project (ADR 0006). Real tokens come from the
Android app (from P009). Until then, check the negative paths by hand:

```sh
curl -i http://localhost:8080/v1/me                                   # 401, WWW-Authenticate: Bearer
curl -i -H "Authorization: Bearer garbage" http://localhost:8080/v1/me # 401, same body
curl -i http://localhost:8080/health                                  # 200
```

The positive paths are covered by the automated tests: they sign tokens with an RSA key generated
per test run and give the **real** `FirebaseIdTokenVerifier` a local key resolver
([`test/auth/tokens.ts`](test/auth/tokens.ts)), with the fake project `demo-saferoute`.

Production-style:

```sh
pnpm build               # compiles src/ → dist/
pnpm start               # node dist/server.js, JSON logs on stdout
```

Ctrl+C stops the server gracefully (in-flight requests finish, exit code 0).

## Environment variables

All settings come from environment variables, validated once at startup by
[`src/config.ts`](src/config.ts). Invalid values stop the process with a message that names the
variable (never its value). Empty values count as unset. See [`.env.example`](.env.example).

| Name | Required (dev / test / production) | Secret? | Default | Where the value comes from when deployed |
| --- | --- | --- | --- | --- |
| `NODE_ENV` | no / no / yes (`production`) | no | `development` | Cloud Run env var (P006) |
| `PORT` | no / no / no | no | `8080` | set by Cloud Run |
| `LOG_LEVEL` | no / no / no | no | `info` | Cloud Run env var |
| `SERVICE_NAME` | no / no / no | no | `saferoute-api` | Cloud Run env var |
| `APP_VERSION` | no / no / no | no | `dev` | set by the deploy pipeline |
| `GIT_SHA` | no / no / no | no | `unknown` | set by the deploy pipeline |
| `DATABASE_URL` | no / no / **yes** | **yes** (contains the password; never logged) | none | Secret Manager → Cloud Run (wired in P006) |
| `DB_POOL_MAX` | no / no / no | no | `5` (1–20 per instance) | Cloud Run env var |
| `DB_STATEMENT_TIMEOUT_MS` | no / no / no | no | `10000` (100–300000) | Cloud Run env var |
| `DB_CONNECT_TIMEOUT_MS` | no / no / no | no | `5000` (100–60000) | Cloud Run env var |
| `FIREBASE_PROJECT_ID` | no / no / **yes** (`demo-` IDs rejected) | no, but kept out of tracked files | none (`/v1` then answers 503) | GitHub environment secret (so public logs mask it) → Cloud Run env var |
| `GEOCODING_API_KEY` | no / no / **yes** | **yes** (never logged, never in an error or in `/health`) | none (search then answers 503 `search_not_configured`) | Secret Manager `saferoute-staging-geocoding-key` → Cloud Run env var `GEOCODING_API_KEY`. A server-only key of the geocoding provider, separate from the app's map key |
| `GEOCODING_PROVIDER` | no / no / no | no | `geoapify` | A constant in `deploy-staging.yml` → Cloud Run env var. `geoapify` (chosen, ADR 0018) or `locationiq` (spare). Must match the key |
| `SEARCH_GLOBAL_DAILY_LIMIT` | no / no / no | no | `2500` (1–10000000) | Cloud Run env var. Provider calls per day for all users together; keep it under the provider plan's daily quota |
| `SEARCH_PROVIDER_TIMEOUT_MS` | no / no / no | no | `3000` (200–20000) | Cloud Run env var |

Other allowed values: `NODE_ENV` ∈ `development`, `test`, `production`; `LOG_LEVEL` ∈ `debug`,
`info`, `warn`, `error`; `DATABASE_URL` is a `postgres://` or `postgresql://` URL, either with a
host or in the Cloud SQL unix-socket form `postgresql://<user>:<password>@/<db>?host=/cloudsql/<connection name>`
(URL-encode the password); `FIREBASE_PROJECT_ID` matches `^[a-z][a-z0-9-]{4,28}[a-z0-9]$`;
`GEOCODING_API_KEY` is 8–200 printable characters without spaces.

The container image sets `NODE_ENV=production` and bakes `GIT_SHA` and `APP_VERSION` from the
`GIT_SHA` build argument; runtime values override them. The migration runner
(`node dist/db/migrate.js`) needs only `DATABASE_URL`: it does not apply the API's production
requirement for `FIREBASE_PROJECT_ID`.

No variable changes the token issuer, audience, algorithm or key URL, and none enables an emulator
or bypass (a test enforces this). `pnpm dev`, `pnpm db:migrate` and `pnpm admin:set-role` load
`.env.example`, then `.env` (git-ignored) if present.

## Layout

```text
src/
  server.ts            entry point: config → logger → runtime → HTTP server, graceful shutdown
  runtime.ts           createRuntime(config, logger): database pool + token verifier + app (no I/O)
  app.ts               createApp({ config, logger, readiness, verifier, db }): middleware and routes
  config.ts            parseConfig(env) with Zod
  types.ts             Hono context variables (requestId, logger)
  db/                  Drizzle client, schema, migration runner, PostGIS point type (see db/README.md)
  lib/logger.ts        pino JSON logs for Cloud Logging (severity, message, timestamp)
  lib/problem.ts       RFC 9457 problem+json responses and AppError
  middleware/          request-id, access-log
  routes/health.ts     GET /health (liveness, no dependencies)
  routes/ready.ts      GET /health/ready (readiness: SELECT 1 with a 2 s timeout)
  modules/auth/        Firebase ID-token verifier (jose), authenticate / requireUser / requireRole
  modules/users/       POST /v1/me/bootstrap, GET /v1/me
  modules/search/      POST /v1/search, the GeocoderProvider interface and the provider adapters
  lib/rate-limit.ts    token buckets in PostgreSQL (rate_limit_buckets)
  regions/defaults.ts  default search bias for the launch region
  scripts/set-role.ts  admin CLI: change a user's role (audited)
  modules/             more domain modules arrive with later prompts
scripts/container-smoke.mjs  builds the image and smoke-tests it with local Docker (plain Node)
scripts/smoke.mjs      the three deployment smoke checks against any running API (plain Node)
Dockerfile             one image for the API, the migration job and the admin job
drizzle/               generated SQL migrations (committed, never edited after merge)
test/                  Vitest "unit" project (no network)
test/db/               Vitest "db" project (Testcontainers PostGIS)
test/search-eval/      search-quality fixture and the `pnpm search:eval` harness (not run in CI)
```

## HTTP conventions

- **`X-Request-Id`**: echoed if the caller sends a valid one (`^[A-Za-z0-9._-]{8,64}$`),
  otherwise generated (UUID). It is on every response and every log line (`request_id`).
- **Routes** are declared with `createRoute` (`@hono/zod-openapi`), so they appear in
  `contracts/openapi.json` and their input is validated by a shared hook (ADR 0004). JSON bodies
  are camelCase; log fields stay snake_case.
- **Errors** are `application/problem+json`:
  `{ type, title, status, detail, code, requestId }` (+ `errors: [{path, code}]` for
  `validation_error`, never echoing submitted values). Clients switch on `code`, an open set
  listed in [`contracts/README.md`](../contracts/README.md). Throw `AppError(status, code,
  detail)` for expected failures; anything else becomes a generic 500 and is logged server-side.
- **Access log**: one line per request with `method`, route `path` pattern, `status`,
  `duration_ms`. Query strings, headers, bodies and client IPs are never logged (Plan v7 §12.2).
- **`GET /health`** (liveness): `200 {"status":"ok","service","version","uptimeSeconds"}`,
  `Cache-Control: no-store`. No I/O, so a database outage never restarts healthy instances.
- **Authentication** (ADR 0006): protected `/v1` routes need `Authorization: Bearer <Firebase ID
  token>` from phone sign-in. `401 unauthorized` (+ `WWW-Authenticate: Bearer`, same body for
  every reason) → refresh the token and retry once. `503 auth_unavailable` (Google's keys
  unreachable) → retry with backoff. `403 bootstrap_required` → call `POST /v1/me/bootstrap`.
  `403 account_deleted` / `forbidden` are final. Roles come from `users.role`, read on every
  request. Tokens, phone numbers and Firebase uids are never logged; the request logger gets
  `user_id` (internal UUID) once the user is known.
- **`GET /health/ready`** (readiness): `200 {"status":"ready","checks":{"database":"ok"}}` or 503
  problem+json `db_unavailable` / `db_not_configured`. Both are outside `/v1` because they are
  operational, not part of the app contract.

## Place search (since P011a)

`POST /v1/search` (a JSON body: `q`, optional `nearLatitude` + `nearLongitude`, `language`,
`limit`) forwards a search to a geocoding provider behind an adapter
([ADR 0018](../docs/adr/0018-search-and-geocoding.md)). The provider is chosen by name with
`GEOCODING_PROVIDER`; adding one means a file in `src/modules/search/providers/` and a name in
`GEOCODING_PROVIDERS` (`src/config.ts`), nothing in the endpoint.

- **Why POST:** Cloud Run's own request log records every URL with its query string. A search
  in the URL would be stored there, so the search travels in the body
  ([ADR 0019](../docs/adr/0019-privacy-in-urls.md)). The same rule holds for every endpoint: no
  user text, position, phone number or credential in a path or query; a contract test checks
  the parameter names.
- **Privacy:** the query, the coordinates and the results are never logged or stored. The log
  has one line per provider call with `outcome`, `latency_ms` and `result_count`. `near` is
  rounded to two decimals (about 1 km) on the server. The provider sees SafeRoute's server, not
  the user's IP address or token. Nothing is cached on the server.
- **Rate limits** (`SEARCH_LIMITS` in `src/modules/search/service.ts`, plus
  `SEARCH_GLOBAL_DAILY_LIMIT`): 30 searches at once then 1 per second per user; 1000 per user per
  day; a daily budget for all users together. To change a number, change it there, or set the
  variable for the shared budget. **Revisit the shared budget whenever the provider or its plan
  changes**: it must stay under the plan's daily quota, with room for the evaluation harness.
- **Errors:** 429 `rate_limited` and 503 `search_unavailable` carry `Retry-After` (seconds)
  when the wait is known. A key the provider refuses is logged as an error with
  `alert: geocoder_key_rejected` and answered as 503, never as 401 or 403.
- **Locally:** without `GEOCODING_API_KEY` the endpoint answers 503 `search_not_configured`. To
  try it, put `GEOCODING_PROVIDER` and `GEOCODING_API_KEY` in `backend/.env` (git-ignored).
- **Quality:** `pnpm search:eval` measures a provider against the committed fixture
  ([`test/search-eval/README.md`](test/search-eval/README.md)).

## Change a user's role (admin)

Roles (`user`, `moderator`, `admin`) are stored in `users.role` and checked on every request, so a
change applies to the user's next request. Use the audited script; never edit the row by hand:

```sh
pnpm admin:set-role --user-id <uuid> --role moderator --confirm          # local, via tsx
node dist/scripts/set-role.js --user-id <uuid> --role moderator --confirm   # compiled
```

- `--user-id` is the internal account id: the `id` field of `GET /v1/me` (sign in on the app, then
  read it from the response). It is not the Firebase uid.
- Without `--confirm` nothing changes. An unknown user exits 1. `--help` works without a database.
- It prints only the user id, `old → new` role and a masked database host, and writes an
  `audit_log` row (`actor_type 'system'`, action `user.role_changed`, metadata `{from, to}`).
- It uses only `DATABASE_URL` and production dependencies. Against **staging**, run it as the Cloud
  Run Job that P006 provides; never point a laptop at the staging database.

## Running in a container

The API, the migration job and the admin job run from one image built from
[`Dockerfile`](Dockerfile) (multi-stage, Node 24 slim pinned by digest, production dependencies
only, non-root). Docker Desktop must be running.

```sh
docker buildx build --load --build-arg GIT_SHA=$(git rev-parse HEAD) -t saferoute-api:local .
node scripts/container-smoke.mjs      # builds the image and checks it end to end
```

[`scripts/container-smoke.mjs`](scripts/container-smoke.mjs) needs only Node and Docker. It
starts a throwaway PostGIS container, runs the migration command twice (the second run applies
nothing), checks that production refuses a `demo-` Firebase project and a missing geocoding key,
runs the smoke checks below (plus `POST /v1/search` without a token → 401),
and verifies non-root, JSON logs and a SIGTERM shutdown within 10 s. CI runs it on every pull
request that touches `backend/` ([`container-ci`](../.github/workflows/container-ci.yml)).

| Command in the image | Purpose | Needs |
| --- | --- | --- |
| `node dist/server.js` (default) | The API | `DATABASE_URL`, `FIREBASE_PROJECT_ID`, `GEOCODING_API_KEY`, `GEOCODING_PROVIDER` |
| `node dist/db/migrate.js` | Apply migrations (Cloud Run Job) | `DATABASE_URL` |
| `node dist/scripts/set-role.js …` | Change a user's role (Cloud Run Job) | `DATABASE_URL` |

Nothing sensitive is in the image: no `.env`, no sources, no tests, no dev dependencies. Secrets
arrive as environment variables at runtime. Staging runs on Google Cloud; the one-time setup is
[`docs/runbooks/gcp-staging-setup.md`](../docs/runbooks/gcp-staging-setup.md). Never point a
laptop or a local container at the staging database.

## Deployment smoke checks (used by P006)

After each deploy, without any token:

1. `GET /health` → 200, and its `version` equals the deployed `APP_VERSION` (the git SHA: baked
   into the image from the `GIT_SHA` build argument, and set again by the deploy).
2. `GET /health/ready` → 200 `{"status":"ready","checks":{"database":"ok"}}`.
3. `GET /v1/me` without a token → 401 with `WWW-Authenticate: Bearer` and an `X-Request-Id`
   header. This proves auth is wired and `FIREBASE_PROJECT_ID` is set (otherwise: 503
   `auth_not_configured`) without needing a real token.

[`scripts/smoke.mjs`](scripts/smoke.mjs) runs these three checks and retries for up to two
minutes while a new revision warms up. It prints status codes, the version and a request id,
never the URL or a response body:

```sh
SMOKE_URL=<base url> node scripts/smoke.mjs --expect-version <git sha>   # exit 0 = passed
```

The [`deploy-staging`](../.github/workflows/deploy-staging.yml) workflow runs it against the
candidate revision before any traffic moves and again after promotion; a failure there sends
traffic back to the previous revision. How deploys, rollbacks and log queries work:
[`docs/runbooks/rollback-staging.md`](../docs/runbooks/rollback-staging.md) and
[`docs/runbooks/observability-staging.md`](../docs/runbooks/observability-staging.md).

Startup makes no network call (the pool connects lazily; Google's keys are fetched on the first
token), so a cold start never depends on Google being reachable
([`test/deploy-readiness.test.ts`](test/deploy-readiness.test.ts)). The API does need outbound
HTTPS to `www.googleapis.com` to verify tokens.

## Quality gate

```sh
pnpm typecheck && pnpm lint && pnpm format:check && pnpm test && pnpm build && pnpm db:check
pnpm openapi:check && pnpm openapi:lint   # after route changes: pnpm openapi:generate, commit
```

`pnpm test` = `pnpm test:unit` (no Docker) + `pnpm test:db` (needs Docker). After a change to the
Dockerfile, dependencies, startup or shutdown, also run `node scripts/container-smoke.mjs`.

CI runs the same steps in [`.github/workflows/backend-ci.yml`](../.github/workflows/backend-ci.yml)
on every pull request that touches `backend/`, including `pnpm openapi:lint`; the stale-spec check
runs as a unit test. The oasdiff breaking-change gate runs in
[`.github/workflows/contracts-ci.yml`](../.github/workflows/contracts-ci.yml).
