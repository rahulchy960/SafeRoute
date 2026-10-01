# P005b: `/v1/me` bootstrap and profile, contract 0.2.0, role script and auth-flow diagram

| Field | Value |
| --- | --- |
| Prompt | P005 · Firebase ID-token auth and `/v1/me`, **part b of 2** (part a: [`005a-auth-verifier.md`](005a-auth-verifier.md), PR #8) |
| Milestone | M1 (depends on P005a, merged as `98a2923`) |
| Branch | `feat/005b-me-endpoints` |
| PR title | `feat(users): /v1/me bootstrap and profile, contract 0.2.0, role script and auth diagram [P005b]` |
| Notion | P005b row in the Prompt Log |
| Date | 2026-10-01 |
| Plan refs | Plan v7 §3.2 (F-01), §6.2, §6.3, §11, §12.1, §12.4, §13.1, §17, §20 |

## 1. Objective

Finish P005 with the items the split assigned to part b:

- the users module: `POST /v1/me/bootstrap` and `GET /v1/me` (R6);
- wiring the shared verifier and database into the app (R10);
- the contract change: two paths, new codes, version 0.2.0 (R9);
- the `set-role` admin script (R7);
- local-workflow and deployment-readiness docs and tests (R8, R13);
- the auth-flow diagram (§14).

Nothing beyond the original P005 scope was added.

## 2. Context & prerequisites

- P005a merged (PR #8, squash `98a2923`, 2026-10-01 00:05 UTC). `main` has the verifier,
  middleware, roles, config and ADR 0006.
- Everything here was built and tested before the split on the local branch `wip/005-full`
  (never pushed). See the P005a log §3 for the R0 spike (JWK URL confirmed via the issuer's
  discovery document, `Cache-Control: public, max-age=23823, …`, `jose` 6.2.12).
- It was ported with `git checkout wip/005-full -- <paths>`. Files that P005a had already
  changed on `main` (READMEs, ADR, auth tests, `.env.example`) were merged by hand or left as on
  `main`.

## 3. Workflow executed

1. `/start-prompt`:
   - `main` = `98a2923`, clean tree, hooks active, P005a merged, no open PRs;
   - Notion: P005a → Merged (`98a2923`); ADR 0006's link → `main`; P005b → In progress.
2. `git diff --stat main wip/005-full` to list what was left. Then `git switch -c
   feat/005b-me-endpoints` and ported:
   - the users module, `runtime.ts`, `app.ts`, `server.ts`;
   - the contract sources and `contracts/`;
   - the script and its tests, the users/deploy-readiness tests, `test/helpers.ts`;
   - the diagram files.
3. Merged the README sections by hand: local sign-in, admin script, smoke checks, layout,
   intro. Removed the `requireUser + requireRole` block from `test/db/users.test.ts`, because
   P005a's `test/db/auth.test.ts` already covers it.
4. Added one CI step to `backend-ci` (after Build): `node dist/scripts/set-role.js --help`. This
   proves the compiled script runs with plain `node` (R7).
5. Ran the full gate, oasdiff, the diagrams check, markdownlint, actionlint and gitleaks (§6).
   Then this log and `/ship-prompt`.

## 4. Changes

| Area | Files | What |
| --- | --- | --- |
| Users module (R6) | `src/modules/users/{routes,schema,service}.ts` | `POST /v1/me/bootstrap` (`bootstrapMe`, `authenticate`), `GET /v1/me` (`getMe`, `requireUser`); `Me` and `BootstrapMeRequest` schemas; `bootstrapUser` (idempotent upsert + audit row in one transaction); `toMe` |
| Wiring (R10) | `src/app.ts`, `src/runtime.ts`, `src/server.ts` | `createApp({config, logger, readiness, verifier, db})`; `createRuntime(config, logger)` builds the pool, the **single shared** `FirebaseIdTokenVerifier` and the app with no I/O. Middleware order is unchanged; `/health` and `/health/ready` are unchanged |
| Contract (R9) | `src/contract/{info,problem,responses,components}.ts`, `contracts/openapi.json`, `contracts/README.md` | Version **0.1.0 → 0.2.0**, `me` tag. Codes `bootstrap_required`, `account_deleted`, `phone_already_registered`, `auth_unavailable`, `auth_not_configured`. `WWW-Authenticate` header on the shared 401. Shared 401/403/409/503 descriptions say what the client should do. `firebaseBearer` description |
| Script (R7) | `src/scripts/set-role.ts`, `package.json` (`admin:set-role`) | `--user-id --role --confirm`; `--help` without a database; audited `user.role_changed`; masked host; exit 0/1/2 |
| CI (R11) | `.github/workflows/backend-ci.yml` | One step: run the compiled script's `--help`. No new job, no secrets, no Google access |
| Docs (R8, R13) | `backend/README.md`, `src/modules/README.md` | Local sign-in without an emulator (curl negative checks); admin role section; **Deployment smoke checks (used by P006)**; layout |
| Tests | `test/db/users.test.ts`, `test/db/set-role.test.ts`, `test/set-role.test.ts`, `test/deploy-readiness.test.ts`, `test/contract.test.ts`, `test/helpers.ts` | §6 |
| Diagram | `docs/diagrams/005-auth-flow.{json,excalidraw,svg,png}` | §5 |

**Bootstrap** runs as one transaction:

1. `INSERT … ON CONFLICT DO NOTHING RETURNING` with **no conflict target**, so every unique
   index is an arbiter;
2. if a row came back, write the `user.created` audit row (metadata `{}`) → **201**;
3. otherwise read the row by `firebase_uid`:
   - found and not deleted → **200**, returned unchanged (body ignored);
   - found and soft-deleted → **403 `account_deleted`**;
   - not found → another account holds the phone → **409 `phone_already_registered`**, nothing
     written.

With a single conflict target, a concurrent duplicate can raise a unique violation on the
*other* unique index (the phone index). Omitting the target avoids that. `phone_e164` comes from
the verified token; the body may only set `locale` (`en`/`bn`, default `en`) and `displayName`
(trimmed, 1–80 characters, no control characters). Unknown properties are ignored. No
`Idempotency-Key`: the call is naturally idempotent (comment in `routes.ts`).

**`GET /v1/me`** returns `id, phoneE164, displayName, locale, role, createdAt`. Never
`firebase_uid`, `deleted_at` or `updated_at`. Both endpoints send `Cache-Control: no-store`.

**API contract diff:** `contracts/openapi.json` +241/−8. oasdiff against `main`: **2 changes,
0 errors, 0 warnings**, both `endpoint-added` (`GET /v1/me`, `POST /v1/me/bootstrap`). Not
breaking. No "kolkata". **Migrations:** none.

## 5. Diagram

[`docs/diagrams/005-auth-flow.svg`](../diagrams/005-auth-flow.svg) (14 nodes, 13 edges):

- Android app → Firebase phone/OTP → ID token → `authenticate`;
- `jose` checks using Google's JWKS (cached, refetch on unknown `kid`);
- branches: 401, 503 `auth_unavailable`, bootstrap upsert, `requireUser` (403 codes,
  `requireRole`);
- PostgreSQL users + audit_log;
- a note: "No emulator or bypass".

The PNG was checked for legibility: no arrows run through labels after reordering the bottom
layer.

## 6. Quality gate & test results

Run in `backend/` on `feat/005b-me-endpoints` (Windows, Node 24, pnpm 12.8.1, Docker 28.3.3):

| Command | Result |
| --- | --- |
| `pnpm typecheck` · `lint` · `format:check` | pass |
| `pnpm test` | pass: **195 tests, 19 files** (unit 147, db 48) |
| `pnpm build` · `node dist/scripts/set-role.js --help` | pass |
| `pnpm db:check` | pass (no migration) |
| `pnpm openapi:check` · `pnpm openapi:lint` | pass |
| oasdiff 1.32.1 `breaking --fail-on ERR` vs `main` | no breaking changes (2 × `endpoint-added`) |
| `tools/diagrams`: `pnpm check` | all 7 diagrams up to date |
| markdownlint-cli2 (repo config) · actionlint 1.7.12 | 0 errors · clean |
| `pnpm audit --prod` | no known vulnerabilities |

Production-only check (on `wip/005-full`, same code): `pnpm install --prod --frozen-lockfile`
in a scratch copy of `dist/` + manifests installed 7 packages. `node dist/scripts/set-role.js
--help` → 0, and without `--confirm` → 2.

New and changed tests:

- **`/v1/me`, DB (15)**:
  - first bootstrap → 201 with exactly the 6 `Me` fields, the phone from the token, one audit
    row (`user.created`, `actor_user_id` = id, metadata `{}`) and an INFO `user created` log with
    `user_id` only (no phone or uid in the logs);
  - a repeat call → 200, identical, `updated_at` unchanged, still one audit row (body
    locale/name ignored);
  - a `phoneE164` in the body is ignored;
  - **10 parallel bootstraps → nine 200s and one 201, one row, one audit row**;
  - soft-deleted → 403 `account_deleted` (bootstrap and GET);
  - phone held by another uid → 409, nothing written, number not echoed;
  - 6 invalid inputs (empty / blank / 81 characters / newline / NUL name, locale `fr`) → 400
    `validation_error` with the right path and no echoed values;
  - 80 characters with whitespace → stored trimmed;
  - `GET /v1/me`: 403 `bootstrap_required` before bootstrap, then 200 equal to the bootstrap
    body with no hidden fields, `no-store`, access log carries `user_id`;
  - a DB role change shows on the next request.
- **set-role** (9 unit + 3 DB):
  - `--help` without a database; refuses without `--confirm` and never connects; 5 bad-argument
    forms → exit 2; a missing or wrong `DATABASE_URL` is never echoed; host masking (IP, DNS,
    socket);
  - DB: role changed + one `system` audit row `{from, to}`, output is exactly id/roles/masked
    host; the same role → no-op, no audit; an unknown user → exit 1, nothing changed.
- **Deploy readiness (1):** with a production config (fake values) `createRuntime` builds the
  app. `GET /health` → 200 with `version`; `GET /v1/me` → 401 + `WWW-Authenticate` +
  `X-Request-Id`. `fetch` is never called and the pool has 0 connections.
- **Contract (13, +2):**
  - every non-public operation requires `firebaseBearer` and references the shared 401;
  - the new paths, operationIds, response codes and `Me` fields;
  - no `firebaseUid`/`deletedAt` in the spec; 401 documents `WWW-Authenticate`;
  - the paths allowlist is extended with the two paths.

Manual checks (compiled server, fake `FIREBASE_PROJECT_ID=demo-saferoute`, port 18080, run on
`wip/005-full`):

- `GET /v1/me` with no header → 401, `www-authenticate: Bearer`, `x-request-id`;
- `Bearer garbage` → 401 with the same body;
- `/health` → 200;
- server log reasons `missing` and `malformed`, with no token text;
- production with no project ID, or with `demo-saferoute` → exit 1 with the expected message.

**SOS failure matrix (Plan v7 §7.5):** not applicable. No SOS, live-location or contacts code.

## 7. Decisions & ADRs

- ADR 0006 (accepted in P005a) covers this part. Nothing new.
- `ON CONFLICT DO NOTHING` without a target, rather than `(firebase_uid)`, so concurrent
  bootstraps can't fail on the phone index (see §4).
- `createRuntime` (new `src/runtime.ts`) holds the startup wiring, so the deploy-readiness test
  exercises the same code as `server.ts`.
- `GET /v1/me` reuses the row `requireUser` loaded (`userRecord` in the context): one query per
  request.
- `users/schema.ts` holds the module's Zod API schemas. The Drizzle tables stay in
  `src/db/schema/`, where drizzle-kit reads them. The modules README layout line was updated to
  match.
- `set-role` reads only `DATABASE_URL`, not the full config. It must run as a Cloud Run Job
  without `FIREBASE_PROJECT_ID`, which production config would require. Setting the current role
  again is a no-op without an audit row.

## 8. Security & privacy notes

- **New personal data written:**
  - `users.phone_e164`, from the verified token;
  - `users.display_name`, user-provided and optional.

  Both are already marked PERSONAL DATA in the schema; P020 covers export and erasure.
  **P009 must show and record the DPDP consent notice before calling bootstrap** (Notion
  follow-up).
- The phone number is never taken from the body. `firebase_uid` is never returned. Validation
  errors never echo values. A 409 doesn't reveal the number. Audit metadata is `{}` or
  `{from, to}`.
- Logs: `user created` and the access log carry `user_id` (internal UUID) only. Tests assert
  that the phone, uid and token are absent.
- The `set-role` output has no phone, name or uid, and only a masked host. It refuses without
  `--confirm`.
- No new secrets; the CI step needs none. The public repo check passed: fake numbers
  (`+9100000000xx`), fake project `demo-saferoute`, the placeholder `Sample Name`, no local
  paths. The spec contains no hosts or URLs.

## 9. Known issues & risks

- **PR size ~1,200 lines** excluding generated files (lockfile, `openapi.json`, diagram
  exports). About half is tests. It's the second half of the prescribed split.
- If a user's phone number changes in Firebase, bootstrap returns the account unchanged and
  `GET /v1/me` shows the old number. There's no `PATCH /v1/me` (non-goal); it needs a
  re-verification design later.
- Staging can't be exercised end to end until P009 (no real tokens). The positive paths rely on
  locally signed tokens through the real verifier.

## 10. Follow-ups & prerequisites for next prompt

- New in Notion: "Phone-number change in Firebase is not reflected in users.phone_e164"
  (Low). All other P005 follow-ups were recorded in P005a.
- The P004a follow-up "P005: reference firebaseBearer on protected /v1 routes" → **Done** when
  this PR merges.

**Next prompt: P006** (`ci/006-gcp-staging-deploy`). Inputs only Rahul can provide:

1. A GCP **staging** project (ID kept as `<GCP_PROJECT_ID>` in the repo) with **billing**
   enabled, plus budget alerts.
2. The **region**: Plan v7 §13.1 says `asia-south1`. Confirm the availability of Cloud Run,
   Cloud SQL and Artifact Registry there.
3. An **Artifact Registry** Docker repository (name, region).
4. **Cloud SQL** for PostgreSQL + PostGIS sizing for staging: tier (e.g. shared-core), storage,
   private IP vs connector, backups/PITR. Allow the `postgis` extension (P003a follow-up).
5. **Workload Identity Federation**, which needs Rahul's `gcloud` session:
   - a workload identity pool and an OIDC provider for `token.actions.githubusercontent.com`,
     restricted to `rahulchy960/SafeRoute`;
   - deploy and migration service accounts with least-privilege roles;
   - `roles/iam.workloadIdentityUser` bindings.
6. A **GitHub environment** `staging` with the variables `GCP_PROJECT_ID`, region, WIF provider
   and service-account emails, and `FIREBASE_PROJECT_ID` (the staging Firebase project ID).
7. `DATABASE_URL` in **Secret Manager**.

Also for P006: Cloud Run egress must reach `www.googleapis.com`; run the README "Deployment smoke
checks"; run migrations and `set-role` as Cloud Run Jobs.

## 11. How Rahul can verify

1. Read the PR diff, especially `src/modules/users/`, and the `openapi.json` diff: two new paths,
   `info.version` 0.2.0, new codes in the `ProblemDetails.code` description, `WWW-Authenticate`
   on `Unauthorized`.
2. With Docker running: `cd backend`, `pnpm install`, `pnpm db:up`, `pnpm db:migrate`,
   `pnpm test` → 195 passed (unit 147, db 48).
3. Make sure `backend/.env` has `FIREBASE_PROJECT_ID`, run `pnpm dev`, then:
   - `curl -i http://localhost:8080/v1/me` → 401 with `WWW-Authenticate: Bearer`;
   - `curl -i -H "Authorization: Bearer garbage" http://localhost:8080/v1/me` → 401, same body
     apart from `requestId`;
   - `curl -i http://localhost:8080/health` → 200.
4. In a new shell without secrets: set `NODE_ENV=production` with no `FIREBASE_PROJECT_ID`, then
   with `FIREBASE_PROJECT_ID=demo-saferoute` (plus a fake `DATABASE_URL`). `node dist/server.js`
   refuses to start both times.
5. `pnpm admin:set-role --help`; read the README "Environment variables", "Change a user's role"
   and "Deployment smoke checks".
6. Confirm CI is green, then squash and merge, and delete the branch.

## 12. Learning notes

- **Idempotent upsert, concretely.** `INSERT … ON CONFLICT DO NOTHING` asks Postgres to skip the
  insert if a unique index already has the value. If ten requests arrive at once, Postgres lets
  one insert and makes the other nine wait, then do nothing. Each then reads the existing row.
  The app can retry bootstrap freely: the answer is always "your one account".
- **201 vs 200.** 201 Created tells the client a new resource was made, here on the first
  sign-in. 200 OK means "here is what already existed". The Android app can treat both the same.
- **Why the phone comes from the token.** Anything in a request body is whatever the client
  chose to send. The token's `phone_number` was verified by Firebase's OTP and signed by Google,
  so only it is trusted.
- **Bearer tokens and `WWW-Authenticate`.** `Authorization: Bearer <token>` means "whoever holds
  this token is me". A 401 answers with `WWW-Authenticate: Bearer`, the standard way of saying
  "send a valid bearer token". The app reacts by refreshing its Firebase token and retrying once.
- **Cold start.** Cloud Run starts containers on demand. If startup waited for Google or the
  database, a slow dependency would make the service fail to start. Here both connect lazily on
  first use.
