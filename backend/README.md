# backend/

TypeScript modular monolith for SafeRoute Kolkata (Plan v7 §4, §6). It contains:

- **saferoute-api**: Hono + `@hono/zod-openapi`. Every route declares its Zod schemas, and the
  OpenAPI 3.1 document is generated from them into `contracts/openapi.json`.
- **saferoute-worker**: pg-boss jobs (SOS actions on a dedicated queue, purge, H3 aggregation).
- Domain modules: auth, users, contacts, search, routing, safety, reports, sos, share, admin.
- Data: PostgreSQL + PostGIS through Drizzle ORM. Postgres is the only durable store in the MVP.

**Status:** empty. It is filled from **P002** (`feat/002-backend-skeleton`) onwards: P003 (database),
P004 (OpenAPI pipeline), P005 (Firebase auth), P012, P013, P015–P021.

## Quality gate (from P002 onwards)

```sh
pnpm typecheck && pnpm lint && pnpm test && pnpm build
```
