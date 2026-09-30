# backend/

TypeScript modular monolith for SafeRoute Kolkata (Plan v7 §4, §6): the **saferoute-api** Hono
service on PostgreSQL + PostGIS via Drizzle ORM (P003). Later prompts add the OpenAPI contract (P004), Firebase auth (P005),
Cloud Run deployment (P006), the pg-boss worker and the domain modules listed in
[`src/modules/README.md`](src/modules/README.md).

## Requirements

- **Node.js 24** (Active LTS; the exact major is in [`.nvmrc`](.nvmrc)). Check with `node --version`.
- **pnpm 12.8.1** (pinned in `package.json` → `packageManager`).
- **Docker Desktop** (running) for the local database.

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

`pnpm dev` also runs without a database: `/health/ready` then answers 503. `pnpm db:down` stops
the database (data is kept); `docker compose down -v` resets it. Tables, schema changes and the
database test suite arrive in P003b.

Production-style:

```sh
pnpm build               # compiles src/ → dist/
pnpm start               # node dist/server.js, JSON logs on stdout
```

Ctrl+C stops the server gracefully (in-flight requests finish, exit code 0).

## Configuration

All settings come from environment variables, validated once at startup by
[`src/config.ts`](src/config.ts). Invalid values stop the process with a message that names the
variable (never its value). See [`.env.example`](.env.example).

| Variable | Default | Allowed |
| --- | --- | --- |
| `NODE_ENV` | `development` | `development`, `test`, `production` |
| `PORT` | `8080` | 1–65535 (Cloud Run sets it) |
| `LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |
| `SERVICE_NAME` | `saferoute-api` | any short string |
| `APP_VERSION` | `dev` | set by CI/CD |
| `GIT_SHA` | `unknown` | set by CI/CD |
| `DATABASE_URL` | none (required in production) | `postgres://` or `postgresql://` URL; contains a password, never logged |
| `DB_POOL_MAX` | `5` | 1–20 connections per instance |
| `DB_STATEMENT_TIMEOUT_MS` | `10000` | 100–300000 |
| `DB_CONNECT_TIMEOUT_MS` | `5000` | 100–60000 |

`pnpm dev` and `pnpm db:migrate` load `.env.example`, then `.env` (git-ignored) if present.

## Layout

```text
src/
  server.ts            entry point: config → logger → app → HTTP server, graceful shutdown
  app.ts               createApp({ config, logger }): middleware order and error handlers
  config.ts            parseConfig(env) with Zod
  types.ts             Hono context variables (requestId, logger)
  db/                  Drizzle client (client.ts), migration runner (migrate.ts), schema/ (tables from P003b)
  lib/logger.ts        pino JSON logs for Cloud Logging (severity, message, timestamp)
  lib/problem.ts       RFC 9457 problem+json responses and AppError
  middleware/          request-id, access-log
  routes/health.ts     GET /health (liveness, no dependencies)
  routes/ready.ts      GET /health/ready (readiness: SELECT 1 with a 2 s timeout)
  modules/             domain modules (added by later prompts)
drizzle/               generated SQL migrations (committed, never edited after merge)
test/                  Vitest tests (app.request(), no network)
```

## HTTP conventions

- **`X-Request-Id`**: echoed if the caller sends a valid one (`^[A-Za-z0-9._-]{8,64}$`),
  otherwise generated (UUID). It is on every response and every log line (`request_id`).
- **Errors** are `application/problem+json`:
  `{ type, title, status, detail, code, request_id }`. Clients switch on `code`
  (`not_found`, `internal_error`, `validation_error`, …). Throw `AppError(status, code, detail)`
  for expected failures; anything else becomes a generic 500 and is logged server-side.
- **Access log**: one line per request with `method`, route `path` pattern, `status`,
  `duration_ms`. Query strings, headers, bodies and client IPs are never logged (Plan v7 §12.2).
- **`GET /health`** (liveness): `200 {"status":"ok","service","version","uptime_s"}`,
  `Cache-Control: no-store`. No I/O, so a database outage never restarts healthy instances.
- **`GET /health/ready`** (readiness): `200 {"status":"ready","checks":{"database":"ok"}}` or 503
  problem+json `db_unavailable` / `db_not_configured`. Both are outside `/v1` because they are
  operational, not part of the app contract.

## Quality gate

```sh
pnpm typecheck && pnpm lint && pnpm format:check && pnpm test && pnpm build && pnpm db:check
```

CI runs the same steps in [`.github/workflows/backend-ci.yml`](../.github/workflows/backend-ci.yml)
on every pull request that touches `backend/`.
