# backend/

TypeScript modular monolith for SafeRoute Kolkata (Plan v7 §4, §6): the **saferoute-api** Hono
service. Later prompts add the database (P003), the OpenAPI contract (P004), Firebase auth (P005),
Cloud Run deployment (P006), the pg-boss worker and the domain modules listed in
[`src/modules/README.md`](src/modules/README.md).

## Requirements

- **Node.js 24** (Active LTS; the exact major is in [`.nvmrc`](.nvmrc)). Check with `node --version`.
- **pnpm 12.8.1** (pinned in `package.json` → `packageManager`).

`backend/` is a standalone pnpm package, not part of a root workspace. Run every command from
inside `backend/`.

## Run locally

```sh
cd backend
pnpm install
pnpm dev                 # http://localhost:8080/health, logs pretty-printed
```

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

## Layout

```text
src/
  server.ts            entry point: config → logger → app → HTTP server, graceful shutdown
  app.ts               createApp({ config, logger }): middleware order and error handlers
  config.ts            parseConfig(env) with Zod
  types.ts             Hono context variables (requestId, logger)
  lib/logger.ts        pino JSON logs for Cloud Logging (severity, message, timestamp)
  lib/problem.ts       RFC 9457 problem+json responses and AppError
  middleware/          request-id, access-log
  routes/health.ts     GET /health (liveness, no dependencies)
  modules/             domain modules (added by later prompts)
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
- **`GET /health`**: `200 {"status":"ok","service","version","uptime_s"}`, `Cache-Control: no-store`.
  It is outside `/v1` because it is operational, not part of the app contract.

## Quality gate

```sh
pnpm typecheck && pnpm lint && pnpm format:check && pnpm test && pnpm build
```

CI runs the same steps in [`.github/workflows/backend-ci.yml`](../.github/workflows/backend-ci.yml)
on every pull request that touches `backend/`.
