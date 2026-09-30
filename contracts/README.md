# contracts/

The HTTP API contract between the backend and the Android app (Plan v7 §6, ADR 0001).

- `openapi.json`: the OpenAPI 3.1 document, **generated** from the backend's Zod schemas and
  committed. Never edit it by hand.
- The Kotlin client (openapi-generator: Kotlin, Retrofit2, kotlinx.serialization) is generated
  from this file in CI. The app never hand-writes request/response models.
- All paths are under `/v1`. CI runs a breaking-change diff (e.g. oasdiff). A breaking change needs
  an ADR plus either a new version path or a coordinated app release, because old app versions stay
  installed for months.

**Status:** empty. `openapi.json` and the generation/diff pipeline arrive in **P004**
(`feat/004-openapi-contract-pipeline`).

## Quality gate (from P004 onwards)

Regenerate `openapi.json`, commit the diff, and run the breaking-change check.
