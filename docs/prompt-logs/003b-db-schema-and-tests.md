# P003b: Database schema, first tables, PostGIS spike and integration tests

| Field | Value |
| --- | --- |
| Prompt | P003 · DB foundation, **part b of 2** (part a: [`003a-db-client-readiness.md`](003a-db-client-readiness.md), PR #3) |
| Milestone | M1 (depends on P003a, merged as `794db1a`) |
| Branch | `feat/003b-db-schema-and-tests` |
| PR title | `feat(db): users, devices, idempotency and audit tables with PostGIS tests [P003b]` |
| Notion | P003b row in the Prompt Log |
| Date | 2026-10-01 |
| Plan refs | Plan v7 §4, §6.2, §7.1, §10, §11, §12.2, §13.2, §15.2, §17, §20 |

## 1. Objective

Complete P003 on top of P003a's client and migration runner:

- the first tables: `users`, `devices`, `idempotency_keys`, `audit_log` (migration `0001`, with
  CHECK constraints);
- a Drizzle column type for PostGIS points;
- integration tests against a real PostGIS started by Testcontainers, including a PostGIS
  de-risking spike;
- DB tests in CI;
- ADR 0003 (database conventions and migration policy);
- the `003-data-model` diagram.

## 2. Context & prerequisites

- P003a merged (PR #3, squash `794db1a`, 2026-09-30 20:16 UTC). `main` was identical to the
  P003a branch tip.
- The work was built and tested before the split (`wip/003-full`), kept as two local P003b
  commits, and **ported by cherry-pick onto fresh `main`** (no merge of the wip branch). The
  resulting tree is byte-identical to the one tested earlier.
- Docker Desktop running (28.3.3); dev database from P003a's Compose file.
- Rahul's P003b instruction: branch `feat/003b-db-schema-and-tests`; scope schema, `0001`,
  Testcontainers tests, spike, CI drift check, ADR 0003, diagram, log, Notion.

## 3. Workflow executed

1. `/start-prompt`: `git pull --ff-only` → `794db1a`; P003a merged; no open PRs; Docker up.
   Notion: P003a → Merged, P003b → In progress.
2. `git switch -c feat/003b-db-schema-and-tests main`, then `git cherry-pick` the two local
   P003b commits (no conflicts).
3. **How the work was built** (in `wip/003-full`, before the split):
   - schema files → `drizzle-kit generate --name=identity_support_tables`;
   - checked the SQL: Drizzle expressed all CHECKs, the partial unique index, the identity
     column, the cascades and the composite PK;
   - added by hand, before the first commit: the SPDX header, the `audit_log` table comment and
     `PERSONAL DATA` column comments (Drizzle can't express `COMMENT ON`);
   - `db:generate` → "No schema changes".
4. Testcontainers global setup + DB tests; two bugs found and fixed (§9); diagram generated and
   checked visually (one layout pass to stop an arrow crossing a box).
5. Full gate re-run on the ported branch (§6); this log; `/ship-prompt`.

## 4. Changes

| Area | Files |
| --- | --- |
| Schema | `backend/src/db/schema/{users,devices,idempotency,audit}.ts`, `schema/index.ts` (barrel) |
| Types | `backend/src/db/types.ts`: `geometryPoint` (PostGIS `geometry(Point,4326)` ↔ `{ lng, lat }`), `parseEwkbPoint`, `SRID_WGS84` |
| Migration | `backend/drizzle/0001_identity_support_tables.sql` + `meta/0001_snapshot.json`, `_journal.json` |
| Tests | `backend/vitest.config.ts` (projects `unit` / `db`), `test/db/{global-setup,helpers,image}.ts`, `test/db/{migrate,schema,postgis-spike,readiness}.test.ts` |
| Tooling | `backend/package.json` (testcontainers + @testcontainers/postgresql 12.2.0; scripts `test:unit`, `test:db`), lockfile, `pnpm-workspace.yaml` (install scripts of ssh2, cpu-features, protobufjs denied) |
| CI | `.github/workflows/backend-ci.yml`: the test step now runs unit + db (Testcontainers uses the runner's Docker). `db:check`, the drift check and migration validation came with P003a. |
| Docs | `docs/adr/0003-database-conventions-and-migrations.md` + ADR index, `backend/src/db/README.md`, `CLAUDE.md` ("Database rules" pointer), `backend/README.md`, `backend/docker-compose.yml` (comment) |
| Diagram | `docs/diagrams/003-data-model.{json,excalidraw,svg,png}` |

### Migration 0001 (expand-only: new objects only)

| Table | Columns / constraints |
| --- | --- |
| `users` | `id uuid PK DEFAULT gen_random_uuid()`, `firebase_uid text NOT NULL UNIQUE`, `phone_e164 text` (unique index `WHERE phone_e164 IS NOT NULL`), `display_name text`, `locale text NOT NULL DEFAULT 'en'` CHECK in (`en`,`bn`), `role text NOT NULL DEFAULT 'user'` CHECK in (`user`,`moderator`,`admin`), `created_at`/`updated_at timestamptz NOT NULL DEFAULT now()`, `deleted_at timestamptz` |
| `devices` | `installation_id text PK`, `user_id uuid NOT NULL → users ON DELETE CASCADE` (indexed), `fcm_token`, `app_version`, `last_seen_at`, `created_at` |
| `idempotency_keys` | PK `(user_id, route, key)`, `user_id → users ON DELETE CASCADE`, `response_hash text NOT NULL`, **`response_status smallint NOT NULL`, `response_body jsonb`** (addition, see §7), `expires_at timestamptz NOT NULL` (indexed), `created_at` |
| `audit_log` | `id bigint GENERATED ALWAYS AS IDENTITY PK`, `actor_user_id uuid` (**no FK**), `actor_type` CHECK in (`user`,`moderator`,`admin`,`system`), `action`, `entity`, `entity_id`, `metadata jsonb NOT NULL DEFAULT '{}'`, `at timestamptz`; indexes `(entity, entity_id)` and `(at)`; table comment with the no-precise-location rule |

- **API:** no change. **Breaking:** no.
- **Size:** about 1,080 changed lines excluding generated files before this log, of which about
  470 are integration tests. That's over the ~800 guideline; it's the scope Rahul set for P003b
  after the split.

## 5. Diagram

[`docs/diagrams/003-data-model.svg`](../diagrams/003-data-model.svg) (+ `.png`, `.excalidraw`,
`.json`) shows the full MVP data model of Plan v7 §11 as 14 nodes in three lanes (SOS & live
sharing · identity & operations · reports & safety data), with foreign-key edges parent → child.

- **Orange** = built in P003: `users`, `devices`, `idempotency_keys`, `audit_log` (its actor edge
  is labelled "no FK").
- **Grey** = planned, each labelled with the prompt that adds it (Plan v7 §18):
  `emergency_contacts` P013, `sos_sessions`/`sos_actions`/`outbox` P015,
  `share_sessions`/`share_points` P016, `reports` P017, `report_decisions` P018,
  `official_aggregates + data_sources/areas/police_stations` and `route_metrics_cache` P019.

`tools/diagrams` `pnpm check`: all 5 diagrams up to date.

## 6. Quality gate & test results

In `backend/` (Node 22.17.0, Docker 28.3.3), on the ported branch:

| Command | Result |
| --- | --- |
| `pnpm install --frozen-lockfile` | OK |
| `pnpm typecheck` · `lint` · `format:check` | pass · pass · pass |
| `pnpm test:unit` | **47/47** |
| `pnpm test:db` | **26/26** (4 files: schema 14, postgis-spike 6, migrate 3, readiness 3) |
| `pnpm test` | **73/73** (10 files) |
| `pnpm build` | pass |
| `pnpm db:check` | "Everything's fine" |
| `pnpm db:generate` + `git status` (drift) | no changes |
| `node dist/db/migrate.js` on a fresh DB ×2 | `["0000_postgis","0001_identity_support_tables"]`, then `count: 0` |
| `pnpm db:migrate` on the dev DB | up to date |
| `pnpm audit --prod` | **No known vulnerabilities found** |
| Repo-wide | markdownlint 0 issues · gitleaks no leaks · actionlint clean · diagrams up to date |

**Integration tests (real PostGIS 16.4 / 3.4 via Testcontainers):**

- **Migrations:**
  - an empty database gets both, and a second run is a no-op;
  - **3 concurrent runs** apply each migration exactly once (advisory lock);
  - PostGIS 3.4 installed and the expected tables present.
- **users:**
  - defaults applied (uuid id, `en`, `user`);
  - a duplicate `firebase_uid` → 23505;
  - many NULL phones allowed, but a duplicate phone → 23505;
  - `locale`/`role` CHECKs → 23514;
  - `updated_at` advances on Drizzle updates.
- **devices:** FK → 23503; the `installation_id` PK rejects duplicates; deleting the user
  cascades.
- **idempotency_keys:**
  - PK `(user, route, key)` rejects duplicates;
  - `response_status`/`response_body` round-trip;
  - cascade on user delete;
  - `expires_at` index exists.
- **audit_log:**
  - identity ids 1, 2;
  - `metadata` defaults to `{}`;
  - rows survive deleting the actor;
  - the `actor_type` CHECK rejects unknown values;
  - the table comment carries the rule.
- **Personal-data markers:** exactly `devices.fcm_token`, `users.display_name`,
  `users.firebase_uid`, `users.phone_e164` carry `PERSONAL DATA` column comments.
- **PostGIS spike (R7)**, on a `TEMPORARY` table with the custom type and a GiST index:
  - lng/lat stored as x/y and read back through the type (SRID 4326);
  - EWKB parsing in both byte orders;
  - `ST_DWithin(::geography, 500 m)` finds the ~300 m point and not the 4.5 km one;
  - a 75 m `ST_Buffer` in **EPSG:32645 (UTM 45N)** intersects only the point ~40 m from the
    line, with an area of about 1.8e5 m² as expected;
  - GiST index present; no `spike%` objects left in `public`.
- **Readiness (real DB):** 200 ready; pooled connections report `TimeZone=UTC` and
  `statement_timeout=10s`; after closing the pool → 503 `db_unavailable`, with neither the
  password nor the URL in the body or logs.
- **No Docker:** with `DOCKER_HOST` pointed at a dead port, the db project fails with "The db
  test project needs Docker … Start Docker Desktop", exit 1. It never skips.

SOS failure matrix (Plan v7 §7.5): **not applicable**. No SOS, share or contacts code.

## 7. Decisions & ADRs

**ADR 0003 (Accepted), database conventions and migration policy:**

- UUID PKs via `gen_random_uuid()`; client-generated UUID v7 reserved for SOS ids (Plan v7 §7.1).
- `timestamptz` everywhere with UTC sessions; Asia/Kolkata only at read or export time.
- snake_case in SQL, camelCase in Drizzle; enumerations as `text` + CHECK in the migration.
- Soft delete only for `users.deleted_at` (hard erasure in P020).
- Store raw facts, not conclusions (Plan v7 §15.2).
- Personal-data columns marked in both the schema and the database.
- `audit_log.metadata` and logs never hold precise locations, tokens, phone numbers or message
  text.
- Forward-only migrations that are never edited after merge; expand → deploy → migrate/backfill →
  contract.
- A destructive migration needs its own ADR and must stay out of SOS-critical releases.
- Migrations run only via `migrate.ts`, never on API startup.

Other decisions:

- **`idempotency_keys` adds `response_status` and `response_body`** to the Plan v7 §11 columns: a
  hash alone can't replay the stored response that Plan v7 §6.2 requires. Recorded in ADR 0003.
- **`updated_at` = application code** (Drizzle `$onUpdate`) using SQL **`now()`**, not a trigger
  and not `new Date()`. The Docker VM clock ran about 0.8 s ahead of Windows, which made
  `updated_at < created_at` with the app clock. Raw SQL updates must set `updated_at` themselves.
- **Custom `geometryPoint` type returning `{ lng, lat }`** instead of Drizzle's built-in
  `geometry(mode: 'xy')` returning `{ x, y }`. The field names make a lat/lng swap visible in
  review. Writes go through `ST_SetSRID(ST_MakePoint(lng, lat), 4326)` as bound parameters.
- **Test isolation:** one container for the db project; files run sequentially;
  `TRUNCATE … RESTART IDENTITY CASCADE` before each schema test. Migration tests use their own
  freshly created databases in the same container.
- **Unique phone** is a partial unique index (`WHERE phone_e164 IS NOT NULL`), which states
  the rule explicitly.
- **Testcontainers install scripts denied:** ssh2 and cpu-features (optional native speed-ups)
  and protobufjs (version check) aren't needed to talk to Docker.

## 8. Security & privacy notes

**New personal-data columns** (marked in the schema files and with `COMMENT ON COLUMN … 'PERSONAL
DATA …'` for P020 export and erasure):

| Column | What it is |
| --- | --- |
| `users.firebase_uid` | Firebase Auth user id |
| `users.phone_e164` | verified phone number |
| `users.display_name` | display name |
| `devices.fcm_token` | push token |

- The `audit_log` rule (no precise location, tokens, phones or message text) is in the table
  comment, ADR 0003 and the schema file; `actor_user_id` has no FK so audit rows survive deletion.
- Tests use obviously fake data only (`test-uid-1`, `+910000000001`, public Kolkata landmarks).
  The Testcontainers password `test-only-password` belongs to a throwaway container.
- All queries use Drizzle builders or the `sql` template (bound parameters). Test-only
  `CREATE/DROP DATABASE` names are random hex and escaped with `escapeIdentifier`.
- No secrets; gitleaks clean. Public repo check: no real personal data, local paths or IDs.

## 9. Known issues & risks

- **Bugs found while building, both fixed:**
  - `updated_at` used the app clock (see §7);
  - a catalogue query through `information_schema` failed because Postgres evaluated a
    `::regclass` cast before the schema filter, so the test now uses `pg_catalog`.
- **Size:** over the ~800-line guideline (see §4).
- **Diagram legend:** the generator's legend says "External" for the grey boxes; the subtitle
  explains that grey means planned.
- DB tests add about 25 s locally (the container start dominates); CI time grows accordingly.

## 10. Follow-ups & prerequisites for next prompt

Already recorded in Notion *Follow-ups* during P003a: DB roles, the Cloud SQL `postgis`
permission, shipping `drizzle/` in the P006 image, the PgBouncer trigger, the idempotency purge
job, the `updated_at` mechanism, and the Docker/Windows notes. New in P003b:

1. Consider a trigger or lint rule so raw-SQL updates can't forget `updated_at` (tie-in with the
   existing `updated_at` follow-up; no new row).
2. `CREATE INDEX CONCURRENTLY` can't run in Drizzle's single migration transaction. It needs a
   plan when the first large-table index appears (noted in ADR 0003).

**Next prompt:** P004 (`feat/004-openapi-contract-pipeline`). Decide there whether `/health` and
`/health/ready` appear in `openapi.json`.

## 11. How Rahul can verify

1. Read the PR diff and **`backend/drizzle/0001_identity_support_tables.sql`**, the source of
   truth. `0000_postgis.sql` was reviewed in P003a.
2. With Docker running, in `backend/`: `pnpm install`, `pnpm db:up`, `pnpm db:migrate` (applies
   `0001` on your dev DB), `pnpm dev`. Open <http://localhost:8080/health> (ok) and
   `/health/ready` (ready). Run `pnpm db:down`: `/health/ready` is 503 while `/health` stays 200.
3. Open [`docs/diagrams/003-data-model.svg`](../diagrams/003-data-model.svg).
4. `pnpm test` → 73 passed (unit 47 + db 26). `pnpm test:unit` also works without Docker.
5. CI green (`backend-ci` now runs the DB tests), then squash and merge and delete the branch.

## 12. Learning notes

- **ORM and migrations:** Drizzle describes each table in TypeScript. `pnpm db:generate` compares
  it with the last snapshot and writes the SQL needed (a migration). Migrations are applied in
  order, once each, and never edited after merge, so every database reaches the same state.
- **Geometry vs geography, SRID 4326, longitude first:** a *geometry* is a shape on a flat
  plane; a *geography* is on the Earth's sphere. SRID 4326 means "WGS 84 longitude/latitude in
  degrees", the numbers a phone's GPS gives. PostGIS, GeoJSON and `ST_MakePoint` all expect
  **(longitude, latitude)** = (x, y), unlike the "lat, lng" people usually say. For metres, cast
  to geography (`ST_DWithin(...::geography, 500)`) or project to a metric system such as UTM zone
  45N (EPSG:32645) for Kolkata.
- **Connection pool on Cloud Run:** each instance keeps a few open connections (5). Many
  instances share one Cloud SQL server with a hard connection limit, so pools stay small.
- **Testcontainers:** a library that starts real Docker containers from tests. Here it starts the
  same PostGIS image as production and development, runs the real migrations and throws the
  container away afterwards, so tests hit a real database, not a mock.
- **Expand → contract:** change a schema in safe steps: add new things first (expand), deploy code
  that uses them, backfill data, and remove old things (contract) only once no running app
  version needs them. Old Android versions stay installed for months, so renames and drops take
  several releases.
