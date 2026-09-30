# P003a: Database client, config, readiness, local DB and CI

| Field | Value |
| --- | --- |
| Prompt | P003 · DB foundation, **part a of 2** (split: see §1) |
| Milestone | M1 (depends on P002, P002a: merged) |
| Branch | `feat/003a-db-client-readiness` |
| PR title | `feat(db): Drizzle client, migration runner, readiness and local PostGIS [P003a]` |
| Notion | P003a row in the Prompt Log (the pre-created P003 row, renamed) |
| Date | 2026-10-01 |
| Plan refs | Plan v7 §4, §6.2, §12.2, §13.1, §13.2, §14.2, §17, §20 |

## 1. Objective

Give the backend its database plumbing: Drizzle ORM + `pg` on PostgreSQL/PostGIS, validated DB
config, a pooled client with safe error logging, a standalone migration runner with an advisory
lock (the future Cloud Run Job), migration `0000` (PostGIS extension), `GET /health/ready`, a local
PostGIS via Docker Compose, and CI checks for migrations.

**Why the split.** The full P003 came to about **2,000 changed lines excluding generated files**
(lockfile, migration metadata, diagram exports), against the ~800-line limit. The prompt says to
split in that case:

- **P003a (this PR):** client, config, readiness, Compose, CI. It also includes the migration
  runner and migration `0000`, so that CI's migration validation has a real migration to apply
  and this PR is testable on its own.
- **P003b (next PR, after this is merged):** the four tables and migration `0001`, the custom
  PostGIS point type, Testcontainers and all DB integration tests (incl. the PostGIS spike),
  ADR 0003, the CLAUDE.md "Database rules" pointer, `src/db/README.md` and the `003-data-model`
  diagram.

The complete, tested P003 was built first. P003b exists as a local commit that is rebased onto
`main` after this merge; branches are not stacked.

## 2. Context & prerequisites

- P002 (`4d751d4`) and P002a (`300a99b`) merged; Notion synced (P002a → Merged).
- **Docker:** the first preflight found Docker Desktop installed but not running, and the prompt
  stopped as instructed. After Rahul started it: client/server 28.3.3, Linux engine via Docker
  Desktop (WSL 2).
- Local Node 22.17.0; project targets Node 24 (P002).

## 3. Workflow executed

1. `/start-prompt`: synced main, confirmed merges, updated Notion, read Plan v7 §7.1, §10, §11,
   §14.2, §15.2.
2. **Image:** Docker Hub tags for `postgis/postgis` → `16-3.4` (PostgreSQL 16 + PostGIS 3.4, last
   updated 2024-10-14). Pinned by digest `sha256:44126d87…a5fa5`. `docker pull` + `postgres
   --version` → 16.4.
3. **Dependencies** (exact, all at least 2 days old per pnpm's release-age policy):
   drizzle-orm 0.45.3, pg **8.23.0** (8.23.1 was published the same day), drizzle-kit 0.31.11,
   @types/pg 8.23.1.
4. Config (R2), `db/client.ts` (R3), `routes/ready.ts` + app/server wiring (R6), unit tests.
5. `drizzle.config.ts`; `drizzle-kit generate --custom --name=postgis` → `0000_postgis.sql` with
   `CREATE EXTENSION IF NOT EXISTS postgis`.
6. `db/migrate.ts` (advisory lock, reports applied tags, CLI exit codes).
7. `docker-compose.yml`. The first `db:migrate` failed with "Connection terminated
   unexpectedly": the healthcheck passed during the image's first-start initialisation (see §9).
   Fixed with a TCP healthcheck; then fresh volume → run 1 applied, runs 2 and 3 no-op.
8. CI (R10), actionlint; the CI migration step simulated locally with `node dist/db/migrate.js`
   on a fresh database.
9. Full P003 built and tested (incl. P003b parts), measured, then split (§1).

## 4. Changes

| Area | Files |
| --- | --- |
| Config | `backend/src/config.ts`: `DATABASE_URL` (postgres URL; required only in production), `DB_POOL_MAX` 1–20 (default 5), `DB_STATEMENT_TIMEOUT_MS` 100–300000 (10000), `DB_CONNECT_TIMEOUT_MS` 100–60000 (5000) |
| DB | `backend/src/db/client.ts` (`createDb`, `poolConfig`, `safeDbError`, `databaseReadiness`), `backend/src/db/migrate.ts`, `backend/src/db/schema/index.ts` (empty barrel; tables in P003b), `backend/drizzle.config.ts`, `backend/drizzle/0000_postgis.sql` + `meta/` |
| HTTP | `backend/src/routes/ready.ts` (`GET /health/ready`), `backend/src/app.ts` (injected `readiness`), `backend/src/server.ts` (creates the pool only if `DATABASE_URL` is set; closes it after HTTP drains) |
| Local DB | `backend/docker-compose.yml` (pinned image, 127.0.0.1:5433, LOCAL ONLY credentials, named volume, TCP healthcheck), `backend/.env.example` |
| Tooling | `backend/package.json` (deps + `db:generate`, `db:check`, `db:migrate`, `db:up`, `db:down`), `pnpm-lock.yaml`, `pnpm-workspace.yaml` (comment), `.prettierignore` (`drizzle/`) |
| Tests | `backend/test/config.test.ts` (+DB cases), `backend/test/ready.test.ts` (new), `backend/test/helpers.ts` (`readiness` injection) |
| CI | `.github/workflows/backend-ci.yml`: PostGIS service; `db:check`; drift check; migration validation (fresh DB, then idempotent re-run) |
| Docs | `backend/README.md`, this log |

- **API:** `GET /health/ready` added, unversioned and outside `/v1` (operational; OpenAPI
  inclusion is decided in P004). `/health` unchanged. No `/v1` change.
- **DB:** migration `0000_postgis` (expand-only: creates the extension). No tables yet.

## 5. Diagram

No diagram in P003a. The required `003-data-model` diagram shows the tables, which arrive in
P003b. The CI change only adds steps to the existing backend job.

## 6. Quality gate & test results

In `backend/` (Node 22.17.0, Docker 28.3.3):

| Command | Result |
| --- | --- |
| `pnpm install --frozen-lockfile` | OK; lockfile passes supply-chain policies |
| `pnpm typecheck` | pass |
| `pnpm lint` | pass (0 problems) |
| `pnpm format:check` | pass |
| `pnpm test` | **47/47** (6 files): config 19, request-id 12, access-log 6, errors 4, ready 5, health 1 |
| `pnpm build` | pass |
| `pnpm db:check` | "Everything's fine" |
| `pnpm db:generate` (drift) | "No schema changes" |
| `node dist/db/migrate.js` on a fresh DB ×2 | run 1 `applied: ["0000_postgis"]`, run 2 `count: 0` |
| `pnpm audit --prod` | **No known vulnerabilities found** |
| actionlint 1.7.12 | clean |

New tests:

- **config:** `DATABASE_URL` optional in dev/test and required in production (the error names
  the variable); pool and timeout bounds (0, 21, 50 ms, 120 s rejected); wrong scheme and non-URL
  rejected; a rejected URL's password and host never appear in the error.
- **readiness (injected check):**
  - 200 `ready` with `no-store`;
  - 503 `db_unavailable` when the check throws, with nothing from the connection string in the
    body or logs, a WARNING line with `request_id`, and an access line with the route path;
  - 503 after the 2 s timeout (fake timers);
  - 503 `db_not_configured` without a database;
  - `/health` never calls the check.

**Manual checks (real PostGIS via Compose, `pnpm dev`):**

- `/health` 200 throughout.
- `/health/ready` 200 `{"status":"ready","checks":{"database":"ok"}}` → after `docker compose down`,
  503 `db_unavailable` problem+json → after `pnpm db:up`, 200 again without restarting the API.
- Logs: a pool error and a readiness warning, both without password or URL.

**Migration runner (compose DB):** fresh volume → run 1 applied both migrations of the full
branch, runs 2 and 3 were no-ops. Concurrent-run serialisation is verified by the P003b
integration tests (3 parallel runs → each migration applied once).

**Full P003 before the split** (for the record; re-run in P003b): 26/26 DB integration tests
passed against a Testcontainers PostGIS.

SOS failure matrix (Plan v7 §7.5): **not applicable**. No SOS, share or contacts code.

## 7. Decisions & ADRs

ADR 0003 (database conventions) comes with P003b, where the tables it governs are created.

- **`DATABASE_URL` is optional outside production.** `pnpm dev` and the unit tests run without a
  database, and `/health/ready` then says `db_not_configured`. In production a missing URL is a
  startup error.
- **Session settings via connection startup options:** `TimeZone=UTC` and `statement_timeout`
  are set when each pooled connection opens, and `application_name` = service name, which makes
  the connection visible in `pg_stat_activity`.
- **`safeDbError()`:** DB errors are logged as `{name, code, message}` with any `postgres://…`
  scrubbed, never the raw error object. A URL `TypeError` carries the full URL, password
  included, in its `input` property.
- **Migration runner:** one dedicated connection (not the API pool, no statement timeout).
  `pg_advisory_lock(hashtextextended('saferoute:migrations', 0))` serialises concurrent runs.
  Drizzle applies all pending migrations in one transaction. Applied tags are found by comparing
  Drizzle's `__drizzle_migrations.created_at` with the journal before and after. The runner is
  never called on API startup.
- **Readiness timeout = 2 s**, via `Promise.race`; a late rejection is swallowed so it can't
  become unhandled. Failures log at WARNING and the access line logs at ERROR (503).
- **Compose on port 5433**, so it can't clash with a native Postgres on 5432.
- **`pnpm dev` / `db:migrate` load `.env.example` then `.env`** (`--env-file-if-exists`), so the
  checklist `db:up → db:migrate → dev` works with no setup. `pnpm start` loads no env file
  (production-like).
- **pnpm guards kept on:** `pg` 8.23.0, not the same-day 8.23.1; no new install scripts allowed.
- **`drizzle/` excluded from Prettier:** generated migration files stay exactly as drizzle-kit
  writes them.

## 8. Security & privacy notes

- **No personal-data columns in P003a** (the tables come in P003b).
- `DATABASE_URL` is never logged, returned or printed. Config errors name the variable only,
  DB errors go through `safeDbError`, readiness bodies say only "The database is not reachable."
  Tests assert that neither the password nor the host appears in bodies or logs.
- Committed credentials are **throwaway, clearly labelled** values:
  - `local-only-password`, in `docker-compose.yml` and `.env.example`, reachable only on
    127.0.0.1;
  - `ci-only-password`, for the CI service container that lives for one job.

  gitleaks (tree) finds no leaks.
- All queries use Drizzle's `sql` template or constant SQL strings; no string concatenation of
  input.
- Public repo check: no real personal data, local paths or IDs added.

## 9. Known issues & risks

- **Postgres image first start:** the official image initialises on a Unix socket and then
  restarts. A socket-based `pg_isready` healthcheck reports healthy too early; use
  `-h 127.0.0.1`. The CI service uses the same TCP check.
- **Docker clock skew (found while building P003b):** the Docker Desktop VM clock ran about
  0.8 s ahead of Windows. P003b sets `updated_at` with SQL `now()` for this reason.
- P003b must be rebased and opened after this merge. Until then `main` has the migration runner
  but no tables.
- The API image (P006) must ship `backend/drizzle/` next to `dist/` for the migration job.

## 10. Follow-ups & prerequisites for next prompt

Recorded in Notion *Follow-ups*:

1. DB roles / least privilege, incl. the `export_readonly` role (P018/P020).
2. Cloud SQL: `postgis` must be allowed and the migration identity must be able to
   `CREATE EXTENSION` (P006).
3. PgBouncer / managed pooling when connections exceed 70% of the limit (Plan v7 §14.2).
4. Purge job for expired `idempotency_keys` (P020).
5. Decide the long-term `updated_at` mechanism (app `$onUpdate` vs trigger).
6. Docker/Windows notes: first-start healthcheck (TCP) and VM clock skew.
7. Ship `backend/drizzle/` in the API image; migration job command `node dist/db/migrate.js` (P006).

**Next:** P003b (`feat/003b-db-schema-migrations`), rebased onto `main` once this PR is merged.

## 11. How Rahul can verify

1. Read the PR diff and `backend/drizzle/0000_postgis.sql` (the migration is the source of truth).
2. With Docker running, in `backend/`: `pnpm install`, `pnpm db:up`, `pnpm db:migrate` (logs
   `applied: ["0000_postgis"]`), run `pnpm db:migrate` again (`database already up to date`),
   then `pnpm dev`.
3. Open <http://localhost:8080/health> (ok) and <http://localhost:8080/health/ready> (ready). Run
   `pnpm db:down`: `/health/ready` becomes a 503 problem+json while `/health` stays 200. Run
   `pnpm db:up` again: ready.
4. `pnpm test` → 47 passed.
5. CI green (`backend-ci` now also runs `db:check`, the drift check and migration validation).
   Squash and merge, then tell Claude Code to continue with P003b.

## 12. Learning notes

- **ORM and migrations:** an ORM (Drizzle) lets TypeScript describe tables and build typed
  queries. A **migration** is a numbered SQL file that moves the database from one version to the
  next. Files are applied in order, once each, and recorded in `drizzle.__drizzle_migrations`, so
  every environment reaches the same schema by replaying the same history.
- **Connection pool:** opening a Postgres connection is slow, so a pool keeps a few open and
  lends them to requests. On Cloud Run many instances each have their own pool against one
  Cloud SQL server with a hard connection limit, so each pool stays small (5).
- **Liveness vs readiness:** `/health` answers "is the process alive?" (never touches the
  database, so a DB outage doesn't restart healthy containers). `/health/ready` answers "can it
  serve database traffic right now?".
- **Advisory lock:** a named lock that Postgres holds for us. Two migration jobs started at once
  both ask for the same lock; the second waits, then finds nothing left to do.
- **Docker Compose:** a file describing containers to run locally. `pnpm db:up` starts PostGIS
  with a data volume that survives restarts; `docker compose down -v` deletes it.
