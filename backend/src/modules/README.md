# src/modules/

Domain modules of the modular monolith (Plan v7 §4). Each module arrives with the prompt that
needs it; there is no placeholder code here.

**Rule: each module owns its routes, services and schema.** Other modules call its exported
service functions, never its tables or internal files directly. A module can be extracted into its
own service later only if measurements justify it (Plan v7 §14.6).

| Module | Owns | Planned in |
| --- | --- | --- |
| `auth` | Firebase ID-token verification (jose), `authenticate` / `requireUser` / `requireRole`; roles from `users.role` (ADR 0006) | P005a |
| `users` | `/v1/me` bootstrap (with the age declaration) and profile; deletion and export | P005b, P009a, P020 |
| `consents` | `/v1/me/consents`: per-purpose consent history and the purpose allowlist (ADR 0010) | P009a |
| `contacts` | emergency contacts, contact opt-out | P013 |
| `search` | `GET /v1/search`: place search behind the `GeocoderProvider` adapter, query normalisation, search rate limits (ADR 0018) | P011a |
| `routing` | route alternatives behind the OSRM adapter | P012 |
| `safety` | H3 safety cells, areas and the route exposure metric | P019 |
| `reports` | community reports and their lifecycle | P017 |
| `sos` | server-side SOS sessions, actions and the SOS job queue | P015 |
| `share` | live-location share sessions and the public viewer | P016 |
| `admin` | moderation decisions, audit log, export | P018 |

**Rule: every route is declared through `createRoute` (`@hono/zod-openapi`) on an `OpenAPIHono`
router**, so it appears in `contracts/openapi.json` and its input is validated by the shared
hook. A test fails if a registered route is missing from the spec (ADR 0004). Name paths,
operationIds and schemas without city names (ADR 0005).

Expected layout once a module exists:

```text
modules/<name>/
  routes.ts     createRoute definitions + Zod request/response schemas (OpenAPI, P004)
  service.ts    business logic; the only entry point other modules may call
  schema.ts     Zod API schemas of the module (the Drizzle tables live in src/db/schema/)
  *.test.ts
```
