# src/db/

PostgreSQL + PostGIS access through Drizzle ORM (Plan v7 §4, §6.2, §11). Rules for tables, columns
and migrations are in [ADR 0003](../../../docs/adr/0003-database-conventions-and-migrations.md).
Read it before adding a table.

| File | Purpose |
| --- | --- |
| `client.ts` | `createDb(config, logger)` → `{ db, pool, close() }`: small pg pool, UTC sessions, statement timeout, safe error logging; `databaseReadiness()` for GET /health/ready |
| `migrate.ts` | Standalone migration runner (`pnpm db:migrate`, `node dist/db/migrate.js`), with an advisory lock; the Cloud Run Job command in P006 |
| `types.ts` | `geometryPoint`: Drizzle column type for PostGIS `geometry(Point,4326)`, values `{ lng, lat }` |
| `schema/` | One file per table group, re-exported by `schema/index.ts` (read by drizzle-kit) |
| `../../drizzle/` | Generated SQL migrations + drizzle-kit metadata, committed, never edited after merge |

## Local workflow

Docker Desktop must be running.

```sh
pnpm db:up          # start PostGIS in Docker (127.0.0.1:5433, LOCAL ONLY credentials)
pnpm db:migrate     # apply committed migrations (safe to repeat)
pnpm dev            # API; GET /health/ready reports whether the database answers
pnpm db:down        # stop the container (data kept in the named volume)
```

`pnpm dev` and `pnpm db:migrate` read `DATABASE_URL` from `.env.example`, and from `.env` if you
create one. **Reset the dev database:** `docker compose down -v` deletes the volume, then run
`pnpm db:up` and `pnpm db:migrate` again.

## Changing the schema

1. Edit or add a file in `schema/` (and export it from `schema/index.ts`).
2. `pnpm db:generate` writes the next `drizzle/NNNN_<name>.sql`. **Read the SQL**: it is the source
   of truth that runs in production.
3. Anything Drizzle can't express (e.g. `COMMENT ON`), add by hand to that new file **before it is
   merged**. Never edit a migration after merge; write a new one.
4. `pnpm db:check`, then `pnpm test` (the db tests apply every migration to a fresh PostGIS).
5. Expand only: new tables and nullable or defaulted columns. Destructive steps need an ADR
   (ADR 0003).

CI fails if `pnpm db:generate` produces changes, i.e. the schema was edited without committing
its migration.

## Tests

- `pnpm test:unit` needs nothing.
- `pnpm test:db` starts one PostGIS container with Testcontainers, runs `runMigrations()` (the
  production code path), and gives the tests its URL. It **fails** without Docker; it never skips.
- `pnpm test` runs both. Tests use only obviously fake data (`test-uid-1`, `+910000000001`).

## Coordinates: longitude first

- **Storage:** `geometry(Point, 4326)`. SRID **4326** is WGS 84 longitude/latitude in degrees,
  the system GPS and map SDKs use.
- **Order is (longitude, latitude)**, i.e. (x, y): `ST_MakePoint(lng, lat)`, GeoJSON
  `[lng, lat]`. Many apps show "lat, lng", so a swap is the most common bug. Kolkata is about
  `lng 88.36, lat 22.57`. Swapped, it lands in the Arabian Sea. `geometryPoint` uses `{ lng, lat }`
  field names instead of x/y so a swap is visible in code review.
- **Distances in metres:** degrees aren't metres. Either
  - cast to **geography** for "within N metres" checks:
    `ST_DWithin(a::geography, b::geography, 500)`, or
  - transform to a **projected** SRID for areas and buffers: Kolkata is in **UTM zone 45N,
    EPSG:32645** (metres), e.g. the 75 m route buffer of Plan v7 §10:
    `ST_Buffer(ST_Transform(route, 32645), 75)`.
- **Indexes:** spatial columns get a GiST index (`USING gist`).
- **Queries:** spatial SQL is written with Drizzle's `sql` template, which sends every value as a
  bound parameter. Never build SQL by string concatenation.

The PostGIS spike in `test/db/postgis-spike.test.ts` shows each of these against a real database.

## Logging and secrets

`DATABASE_URL` contains a password. It is never logged, returned or printed: errors go through
`safeDbError()`, which keeps only the error name, code and a message with any `postgres://…`
removed. Migration runs log only migration names.
