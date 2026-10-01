# backend/

TypeScript modular monolith for SafeRoute (Plan v7 §4, §6): the **saferoute-api** Hono
service on PostgreSQL + PostGIS via Drizzle ORM (P003), with a generated OpenAPI 3.1 contract
(P004, [`../contracts/`](../contracts/)) and Firebase ID-token verification with auth middleware
(P005a, [ADR 0006](../docs/adr/0006-authentication-and-roles.md)). Later prompts add the `/v1/me`
endpoints (P005b), Cloud Run deployment (P006), the pg-boss worker and the domain modules listed
in [`src/modules/README.md`](src/modules/README.md).

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
| `FIREBASE_PROJECT_ID` | no / no / **yes** (`demo-` IDs rejected) | no, but kept out of tracked files | none (protected routes then answer 503) | GitHub environment variable → Cloud Run env var |

Other allowed values: `NODE_ENV` ∈ `development`, `test`, `production`; `LOG_LEVEL` ∈ `debug`,
`info`, `warn`, `error`; `DATABASE_URL` is a `postgres://` or `postgresql://` URL;
`FIREBASE_PROJECT_ID` matches `^[a-z][a-z0-9-]{4,28}[a-z0-9]$`.

No variable changes the token issuer, audience, algorithm or key URL, and none enables an emulator
or bypass (a test enforces this). `pnpm dev` and `pnpm db:migrate` load `.env.example`, then `.env` (git-ignored) if
present.

## Layout

```text
src/
  server.ts            entry point: config → logger → app → HTTP server, graceful shutdown
  app.ts               createApp({ config, logger }): middleware order and error handlers
  config.ts            parseConfig(env) with Zod
  types.ts             Hono context variables (requestId, logger)
  db/                  Drizzle client, schema, migration runner, PostGIS point type (see db/README.md)
  lib/logger.ts        pino JSON logs for Cloud Logging (severity, message, timestamp)
  lib/problem.ts       RFC 9457 problem+json responses and AppError
  middleware/          request-id, access-log
  routes/health.ts     GET /health (liveness, no dependencies)
  routes/ready.ts      GET /health/ready (readiness: SELECT 1 with a 2 s timeout)
  modules/auth/        Firebase ID-token verifier (jose), authenticate / requireUser / requireRole
  modules/users/       user lookup for requireUser (endpoints follow in P005b)
  modules/             more domain modules arrive with later prompts
drizzle/               generated SQL migrations (committed, never edited after merge)
test/                  Vitest "unit" project (no network)
test/db/               Vitest "db" project (Testcontainers PostGIS)
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
  unreachable) → retry with backoff. `403 bootstrap_required` → call `POST /v1/me/bootstrap` (P005b).
  `403 account_deleted` / `forbidden` are final. Roles come from `users.role`, read on every
  request. Tokens, phone numbers and Firebase uids are never logged; the request logger gets
  `user_id` (internal UUID) once the user is known.
- **`GET /health/ready`** (readiness): `200 {"status":"ready","checks":{"database":"ok"}}` or 503
  problem+json `db_unavailable` / `db_not_configured`. Both are outside `/v1` because they are
  operational, not part of the app contract.

## Quality gate

```sh
pnpm typecheck && pnpm lint && pnpm format:check && pnpm test && pnpm build && pnpm db:check
pnpm openapi:check && pnpm openapi:lint   # after route changes: pnpm openapi:generate, commit
```

`pnpm test` = `pnpm test:unit` (no Docker) + `pnpm test:db` (needs Docker).

CI runs the same steps in [`.github/workflows/backend-ci.yml`](../.github/workflows/backend-ci.yml)
on every pull request that touches `backend/`, including `pnpm openapi:lint`; the stale-spec check
runs as a unit test. The oasdiff breaking-change gate runs in
[`.github/workflows/contracts-ci.yml`](../.github/workflows/contracts-ci.yml).

Auth tests need no network and no Firebase project: they sign tokens with an RSA key generated per
test run and give the **real** `FirebaseIdTokenVerifier` a local key resolver
([`test/auth/tokens.ts`](test/auth/tokens.ts)), with the fake project `demo-saferoute`. There is no
emulator, dev login or bypass (ADR 0006).
