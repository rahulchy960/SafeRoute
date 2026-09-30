# P004a: OpenAPI contract (Zod routes, generator, committed spec, conventions)

| Field | Value |
| --- | --- |
| Prompt | P004 · OpenAPI contract pipeline, **part a of 2** (part b: contract breaking-change CI, `feat/004b-contract-breaking-ci`) |
| Milestone | M1 (depends on P003c, merged as `b0ddcaa`, PR #5) |
| Branch | `feat/004a-openapi-contract` |
| PR title | `feat(contracts): Zod→OpenAPI 3.1 contract, generator and committed spec [P004a]` |
| Notion | P004a row in the Prompt Log |
| Date | 2026-10-01 |
| Plan refs | Plan v7 §6.1–§6.3, §12.2, §13.2, §14.5, §17, §20 |

## 1. Objective

Make the backend's HTTP contract machine-readable and enforced:

- routes declared with Zod via `@hono/zod-openapi`;
- an OpenAPI 3.1 document generated deterministically and committed as `contracts/openapi.json`;
- CI fails when the file is stale or doesn't lint;
- the API conventions (naming, errors, idempotency header, auth scheme) fixed while no client
  exists.

The oasdiff breaking-change gate (`contracts-ci`) is **P004b**.

**Split:** the full P004 came to ~1,720 changed lines (excluding the lockfile, spec and diagram
exports), against a ~800 limit. Rahul chose to split. P004a (this PR, 1,106 lines) is the
contract itself. P004b is the `contracts-ci` workflow, the oasdiff fixtures and self-test, and the
pipeline diagram. P004b is built and tested on the local branch `wip/004-full` (never pushed) and
starts after this PR is merged.

## 2. Context & prerequisites

- P003c merged (PR #5, squash `b0ddcaa`, 2026-09-30 22:14 UTC). No open PRs. Docker running.
- Existing routes: `GET /health`, `GET /health/ready`. Errors were problem+json with
  `request_id`.
- Rahul's decisions:
  - JSON is camelCase, so the error body uses `requestId`.
  - Asked in this session: rename `/health`'s `uptime_s` to **`uptimeSeconds`** now, so the
    first committed contract is fully camelCase.
  - Split into P004a/P004b.

## 3. Workflow executed

1. `/start-prompt`: `git pull --ff-only` → `b0ddcaa`, clean, hooks active. Notion: P003c →
   Merged (SHA, date), P004 → In progress (row later renamed to P004a; P004b row created as
   Planned).
2. **R0 spike (oasdiff on 3.1).** I downloaded **oasdiff v1.32.1** (released 2026-09-15). The
   Windows and Linux amd64 tarballs were verified against the release's `checksums.txt`
   (`sha256sum --check --strict`: OK). Linux sha256:
   `7c8939fc49b75ee11fec66a5b83b37a2fca6aee109fed85013b1ba2ac2a1ee7f`. I ran it on two 3.1
   fixtures using `type: ["string","null"]`, `examples` and `$ref` components:
   - `oasdiff breaking base additive --fail-on ERR` → exit 0 ("No breaking changes").
   - `oasdiff breaking base breaking --fail-on ERR` → exit 1, 4 errors:
     `new-required-request-property`, `request-property-became-not-nullable` (3.1 null-union
     understood), `response-required-property-removed`, `api-path-removed-without-deprecation`.
   - `oasdiff changelog` → correct text and markdown output.
   - **Result: 3.1 works. No downgrade to 3.0.**
3. **Dependencies (R1):**
   - `@hono/zod-openapi` **1.6.3** (runtime, MIT). Its peers `zod ^4.0.0` and `hono >=4.10.0` are
     satisfied by the repo's zod 4.6.5 and hono 4.13.10, so **zod was not changed**. Transitive:
     `@asteasolutions/zod-to-openapi` 9.1.0, `@hono/zod-validator`, `openapi3-ts` (all MIT).
   - `@redocly/cli` **2.54.3** (dev, MIT). The latest release, 2.57.0 (published the day before),
     only installed by adding a `minimumReleaseAgeExclude` entry to `pnpm-workspace.yaml`. I
     reverted that and pinned 2.54.3, which passes the release-age policy unchanged.
   - `pnpm audit --prod`: **No known vulnerabilities found.** The full audit has 1 moderate
     advisory (esbuild ≤0.24.2 via `drizzle-kit` → `@esbuild-kit`, dev only). It predates P004
     and is recorded as a follow-up.
4. Built `backend/src/contract/*`, refactored the routes and app, renamed the fields, and added
   the tests (§4, §6).
5. Generated `contracts/openapi.json`, read it through (two paths, components, info), and ran
   Redocly lint. The only finding is covered in §7 (fixed with a targeted ignore, not a muted
   rule). I also proved each lint rule fires on a deliberately broken copy.
6. Wrote ADR 0004, `contracts/README.md`, and updated CLAUDE.md, the PR template, and the
   backend and modules READMEs. Created the `breaking-api-change` label (R8), since it's cheap
   and P004b uses it.
7. Built and tested `contracts-ci.yml`, the fixtures and the diagram (actionlint 1.7.12: OK; all
   gate branches simulated locally). Measured the size, stopped, and asked Rahul. Split, moving
   the P004b files to `wip/004-full`.
8. P004a only: `backend-ci` now also runs `pnpm openapi:check` + `pnpm openapi:lint` and
   triggers on `contracts/**`, so the committed spec is enforced before P004b lands. Full gate
   re-run, then this log and `/ship-prompt`.

## 4. Changes

| Area | Files |
| --- | --- |
| Contract building blocks | `backend/src/contract/`: `info.ts` (title, contract version `0.1.0`, description, license, relative server, tag), `problem.ts` (`ProblemDetails`, `ValidationIssue`, `PROBLEM_CODES`), `responses.ts` (shared 400/401/403/404/409/410/429/500/503 problem+json responses, `X-Request-Id` / `Cache-Control` header components), `components.ts` (registers the components, `IdempotencyKey` header parameter, `firebaseBearer` security scheme), `validation.ts` (default hook), `openapi.ts` (`buildOpenApiDocument()` with dummy deps, `serializeOpenApiDocument()`), `generate.ts` (write / `--check`) |
| Routes / app | `src/app.ts` (`OpenAPIHono` + `defaultHook` + components), `src/routes/health.ts` and `ready.ts` (`createRoute`, operationIds `getHealth` / `getReadiness`, tag `operational`, `security: []`, typed 200 + shared errors), `src/lib/problem.ts` (`requestId`, optional `errors`) |
| Scripts / lint | `backend/package.json` (`openapi:generate`, `openapi:check`, `openapi:lint`; deps), `pnpm-lock.yaml`, `backend/redocly.yaml`, `backend/.redocly.lint-ignore.yaml` |
| Contract | `contracts/openapi.json` (generated), `contracts/README.md` (conventions, error codes, regenerate, how to read) |
| Tests | new `test/contract.test.ts`, `test/validation.test.ts`; updated `errors`, `health`, `ready` tests for the renames |
| CI | `.github/workflows/backend-ci.yml`: `contracts/**` path filter + contract check/lint step |
| Docs | ADR 0004 (+ index), CLAUDE.md (API contract rules, contracts gate row, SPDX exemption), PR template hint, `backend/README.md`, `src/modules/README.md`, `.gitattributes` (LF for the spec) |

**API contract changes:** new committed `contracts/openapi.json` (first version, `0.1.0`).
Intentional behaviour changes, with no clients affected: the problem+json field `request_id` →
**`requestId`**, and the `/health` field `uptime_s` → **`uptimeSeconds`**. Log fields stay
snake_case (`request_id`), and the `X-Request-Id` header is unchanged. No DB changes, no
migrations.

## 5. Diagram

Deferred to **P004b** with the CI pipeline it mostly shows: `docs/diagrams/004-openapi-contract-pipeline.json`
(11 nodes) and its exports are already generated and checked on `wip/004-full`. P004a changes no
user flow, schema or infrastructure. The `backend-ci` change is two steps.

## 6. Quality gate & test results

Run in `backend/` on the P004a tree (Docker up):

| Command | Result |
| --- | --- |
| `pnpm install --frozen-lockfile` | Pass |
| `pnpm typecheck` · `pnpm lint` · `pnpm format:check` · `pnpm build` · `pnpm db:check` | Pass |
| `pnpm test` (unit + db) | **12 files, 87/87 passed** (unit 61 = 47 existing + 14 new; db 26) |
| `pnpm openapi:check` | Pass. It also fails correctly: after editing a route summary it printed `contracts/openapi.json is stale: run pnpm openapi:generate and commit` and exited 1 |
| `pnpm openapi:lint` (Redocly 2.54.3) | Valid, 0 errors/warnings; 2 problems explicitly ignored (§7) |
| `pnpm audit --prod` | No known vulnerabilities |
| markdownlint-cli2 | 0 issues. JSON validity: 20/20 |
| actionlint 1.7.12 | OK (`backend-ci.yml`; shellcheck not installed locally) |

New tests:

- **Determinism:** two builds give identical bytes, LF only, with a trailing newline.
- **Committed spec:** `contracts/openapi.json` equals a fresh generation.
- **Spec shape:** OpenAPI 3.1.x, semver `info.version`, no `contact`, license, `servers: [{url: "/"}]`,
  paths exactly the allowlist `['/health', '/health/ready']`.
- **Components:** ProblemDetails, IdempotencyKey, firebaseBearer and the shared error responses
  exist.
- **Operations:** each has a unique lowerCamel operationId, a tag and a summary, and every
  ≥400 response is only `application/problem+json`.
- **Leak and neutrality scans:** no `http(s)://`, emails or local paths, and no "kolkata"
  (case-insensitive).
- **No undocumented routes:** the app's registered routes equal the spec's operations (only
  `ALL` middleware entries are excluded).
- **Validation hook:** a test-only route gets an invalid body → 400 `validation_error`,
  `errors: [{path: 'body.latitude', code: 'too_big'}, {path: 'body.longitude', code: 'invalid_type'}, {path: 'body.phone', code: 'invalid_format'}]`.
  The body parses with `ProblemDetailsSchema`, and none of the submitted values appear in the
  response or the logs. A valid body gets 204.
- **requestId:** the generated `requestId` equals the `X-Request-Id` header, and there is no
  `request_id` in the body.
- **`openapi:check`:** exit 0 on a fresh copy, exit 1 with the documented message on an edited
  copy, exit 1 when the file is missing.

SOS failure matrix (Plan v7 §7.5): **not applicable**. No SOS, share or contacts code.

## 7. Decisions & ADRs

- **ADR 0004** (Accepted): contract-first via Zod, OpenAPI 3.1, the conventions table
  (`/v1`, camelCase JSON with snake_case DB/logs, UUIDs, RFC 3339 UTC, explicit
  `latitude`/`longitude`, string enums with tolerant clients, problem+json with an open-string
  `code`, `Idempotency-Key`, `firebaseBearer`, city-neutral with a future optional `cityCode`),
  semver contract version, the breaking-change policy with the label + ADR escape hatch, and no
  runtime docs endpoint.
- **`uptime_s` → `uptimeSeconds`**: Rahul's decision in this session. It is a second intentional
  rename besides `requestId`.
- **`http_error` added to the documented codes.** P002 already returns it for Hono
  `HTTPException`s under 500 (e.g. malformed JSON, 415). Leaving it out would have made the list
  wrong.
- **Probes declare `security: []`** (explicitly public), so later routes can't inherit auth by
  accident.
- **Redocly `operation-4xx-response`** warned on the two probes. They take no input, so no 4xx
  can come from them, and declaring one would make the contract lie. I added a targeted entry
  in `.redocly.lint-ignore.yaml` for exactly those two operations, with the reason. The rule
  stays active for everything else.
- **Custom lint rules** (every operation has a tag; 5xx use problem+json) plus the built-ins for
  operationId, uniqueness, summary, tag definitions and 4xx problem+json.
  `no-unused-components` is off because the shared components are declared ahead of use. Every
  rule was shown to fire on a broken copy.
- **`backend-ci`** runs `openapi:check` and `openapi:lint` and now triggers on `contracts/**`,
  so a hand edit of the spec can't slip through before P004b.
- `@redocly/cli` is pinned below the newest release to respect pnpm's release-age policy, rather
  than adding an exclusion.

## 8. Security & privacy notes

- The spec contains no hostnames, emails, tokens, project IDs, personal data or place names.
  Tests enforce this.
- Validation errors return only `{path, code}`. Zod's `message` and `input` are dropped, so
  locations, tokens or phone numbers are never echoed (Plan v7 §12.2). `detail` texts are
  generic.
- The contract is public. It shows endpoint shapes but no secrets, and authorization is enforced
  by server middleware regardless of what the spec says.
- Supply chain: exact version pins, lockfile committed, oasdiff checksum pinned (used in P004b),
  `@redocly/cli` is dev-only, and the release-age policy was kept.

## 9. Known issues & risks

- The oasdiff breaking-change gate isn't active until P004b merges. Until then, reviewers should
  read the `openapi.json` diff by hand.
- The ADR 0005 body says "0004 stays free". It was true when written; ADR 0004 now exists.
  Accepted ADR text is left unchanged.
- `src/routes/health.ts` predates the SPDX rule and still has no header (existing file; not
  mass-edited).
- `dist/` now includes `contract/generate.js`. It's harmless and is never run in production.

## 10. Follow-ups & prerequisites for next prompt

Recorded in Notion *Follow-ups* (source P004a):

1. P005 must reference `firebaseBearer` on every protected `/v1` route, use `unauthorized` /
   `forbidden` codes, and bump `info.version` (minor).
2. P008: generate the Kotlin client from `contracts/openapi.json`, with a CI drift check for the
   generated client. If the generator can't handle 3.1, revisit per ADR 0004.
3. Make `contracts-ci` a required check (needs an always-run job so unrelated PRs aren't blocked).
4. Consider a docs UI (Swagger/Redoc) in staging only, never production.
5. Dev-only esbuild advisory via `drizzle-kit` (GHSA-67mh-4wv8-2f99): update when drizzle-kit
   drops `@esbuild-kit`.

Already open, so no new rows: P017 `cityCode` on reports (P003c), and the optional SPDX header
check (P002a).

**Next:** **P004b** (`feat/004b-contract-breaking-ci`): `contracts-ci.yml`, fixtures + self-test,
diagram, from `wip/004-full`. Then **P005** (`feat/005-firebase-auth-api`), which needs Rahul's
Firebase staging project.

## 11. How Rahul can verify

1. Read the PR diff and `contracts/openapi.json`. It should be short: two paths plus components.
2. In `backend/`: `pnpm install`, then `pnpm openapi:generate`, then `git status` should show no
   change.
3. Edit a route description (e.g. `summary` in `src/routes/health.ts`), run
   `pnpm openapi:check` and confirm it fails with the "stale" message. Then undo the edit.
4. Paste `contracts/openapi.json` into <https://editor.swagger.io> (public data) and look at
   `getHealth`, `getReadiness` and the `ProblemDetails` schema.
5. `pnpm dev`, then open `/health` (you should see `uptimeSeconds`) and `/nope` (the 404 body
   should use `requestId`).
6. Once `repo-checks` and `backend-ci` are green, squash and merge and delete the branch. Then
   run P004b.

## 12. Learning notes

- **What OpenAPI is.** A standard, machine-readable description of an HTTP API: every path,
  method, input, response and error shape, in one JSON/YAML file. Here the file is *generated*
  from the same Zod schemas the server validates with, so it can't drift from the code. See
  <https://spec.openapis.org/oas/v3.1.0>.
- **Why a contract beats hand-written models.** If the Android app hand-wrote its Kotlin data
  classes, every backend change would have to be copied by hand, and a typo (`requestID` vs
  `requestId`) would only show up at runtime on a phone. With a contract, both sides share one
  source of truth.
- **Code generation.** A tool (openapi-generator, P008) reads `openapi.json` and *writes* the
  Kotlin client for you: data classes, Retrofit interfaces and enums. When the contract changes,
  you regenerate instead of editing. See <https://openapi-generator.tech/docs/generators/kotlin>.
- **Breaking changes, two examples.** (1) Renaming a response field, say `uptimeSeconds` →
  `uptime`: an installed app still reads `uptimeSeconds`, finds nothing, and crashes or shows
  wrong data. (2) Adding a *required* request field: old apps don't send it, so every request
  they make now gets a 400. Adding an *optional* field or a new endpoint is not breaking.
- **Why versioned paths matter.** People don't update apps quickly. An app released today may
  still be installed in six months. If `/v1/sos` changed shape, those phones would break, maybe
  mid-emergency. So a breaking change goes to a new path (`/v2/sos`) while `/v1` keeps working,
  or waits for a coordinated forced update.
- **Semantic versioning for an API.** `MAJOR.MINOR.PATCH`: patch = wording/description only,
  minor = something added (backwards compatible), major = something broke. Our `info.version`
  (`0.1.0`) is the *contract* version, separate from the app build number. See <https://semver.org>.
