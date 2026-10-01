# P005a: Firebase ID-token verifier, auth middleware and database-authoritative roles

| Field | Value |
| --- | --- |
| Prompt | P005 · Firebase ID-token auth and `/v1/me`, **part a of 2** (part b: `/v1/me` endpoints, contract, role script, diagram) |
| Milestone | M1 (depends on P003a/b, P004a/b; P004b merged as `d5d3ed2`) |
| Branch | `feat/005a-auth-verifier` |
| PR title | `feat(auth): Firebase ID-token verifier, auth middleware and roles [P005a]` |
| Notion | P005a row in the Prompt Log |
| Date | 2026-10-01 |
| Plan refs | Plan v7 §3.2 (F-01), §6.2, §6.3, §11, §12.1, §12.4, §13.1, §17, §20 |

## 1. Objective

Authenticate API callers with Firebase ID tokens from phone sign-in:

- verify tokens with `jose` against Google's public keys (no credentials, no emulator);
- provide `authenticate`, `requireUser` and `requireRole` middleware for later routes;
- keep roles authoritative in `users.role` (ADR 0006).

Everything is tested offline with locally signed tokens.

**Why the split.** The complete P005 came to about 2,570 changed lines, excluding the lockfile,
`openapi.json` and diagram exports. The guideline is ~800. The prompt says to split into P005a
(config, verifier, middleware, roles) and P005b (users module, endpoints, contract, script,
diagram), so it was split there.

P005a is still about 1,400 lines: ~480 production code, ~730 tests (the required token matrix)
and ~200 docs. Rahul chose to ship it as is rather than separate code from its tests.

## 2. Context & prerequisites

- P004b merged (PR #7, squash `d5d3ed2`, 2026-09-30 22:57 UTC). Clean tree, hooks active, no open
  PRs.
- The full P005 was implemented and passed the whole gate (191 tests, oasdiff non-breaking) on the
  local branch `wip/005-full` (never pushed). P005a was ported file by file with
  `git checkout wip/005-full -- <paths>`, then trimmed. The P005b parts stay on the wip branch for
  the next prompt.
- Rahul's Firebase **staging** project exists (Phone provider enabled). Its ID lives only in
  `backend/.env` (git-ignored) and was never read. Tests use the fake project `demo-saferoute`.
- No Android app yet: the real end-to-end check (phone OTP on a device → API) is part of P009.

## 3. Workflow executed

1. `/start-prompt`: the first preflight stopped on a modified `backend/.env.example`. Rahul
   resolved it, then: `main` = `d5d3ed2`, P004b merged. Notion: P004b → Merged
   (`d5d3ed2`, 2026-10-01); P005 → In progress with the 12 headings.
2. **R0 spike** (throwaway script in the session scratchpad, not committed):
   - Firebase's "Verify ID tokens" guide confirms: `alg` RS256 with a `kid`,
     `iss = https://securetoken.google.com/<projectId>`, `aud = <projectId>`, `exp` in the
     future, `iat` and `auth_time` in the past, `sub` non-empty. The guide lists the **X.509**
     key URL (`https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com`).
   - The **JWK URL** is confirmed by the issuer's own discovery document,
     `https://securetoken.google.com/demo-saferoute/.well-known/openid-configuration`:
     `jwks_uri = https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com`,
     `id_token_signing_alg_values_supported = ["RS256"]`.
   - Node `fetch` from Rahul's PC: JWK URL → 200, 4 keys, all `kty RSA`, `alg RS256`,
     `use sig`, each with a `kid`. The **same `kid` set** as the X.509 URL.
     `Cache-Control: public, max-age=23823, must-revalidate, no-transform` (~6.6 h; the X.509 URL
     gave `max-age=23261`).
   - Phone claim shape (Firebase Security Rules token reference): `firebase.sign_in_provider`
     ∈ {custom, password, **phone**, anonymous, google.com, …}; top-level `phone_number` when
     present. It matches the prompt.
   - `jose` **6.2.12** (latest). Checked in its source:
     - `createRemoteJWKSet` options `timeoutDuration` (default 5000), `cooldownDuration` (30000)
       and `cacheMaxAge` (600000); the cooldown counts from the last *successful* fetch;
     - `jwtVerify` options `issuer`, `audience`, `algorithms`, `clockTolerance` and
       `requiredClaims`; `iat` is checked against the clock only when `maxTokenAge` is set;
     - error classes (with `code`): `JWTExpired` (`ERR_JWT_EXPIRED`),
       `JWTClaimValidationFailed`, `JWSSignatureVerificationFailed`, `JWKSNoMatchingKey`,
       `JWKSMultipleMatchingKeys`, `JWKSTimeout`, `JWKSInvalid`, `JOSEAlgNotAllowed`,
       `JOSENotSupported`, `JWSInvalid` and `JWTInvalid`;
     - the base `JOSEError` (`ERR_JOSE_GENERIC`) is thrown only for a non-200 or non-JSON key-set
       response; a network error is re-thrown as is (a `TypeError`);
     - `alg` is checked against `algorithms` **before** the key resolver runs, so `none` and
       HS256 tokens never reach the key set;
     - `customFetch` is exported, which lets tests count real fetches.
3. Implemented config (R2), the verifier (R3), middleware and roles (R4, R5), then the full
   P005 (users module, endpoints, contract, script, README, ADR, diagram). Ran the full gate
   plus oasdiff (non-breaking) and a production-only install check.
4. Measured the diff (2,570 lines) and saved everything to `wip/005-full`. Created
   `feat/005a-auth-verifier` from `main` and ported the P005a files. Trimmed `users/service.ts`
   to the lookup and rewrote the middleware tests to use test-only routes (no `/v1/me` yet).
   Adapted the README, ADR 0006 and the modules README.
5. Full gate on P005a (§6), this log, `/ship-prompt`.

## 4. Changes

| Area | Files | What |
| --- | --- | --- |
| Dependency | `backend/package.json`, `pnpm-lock.yaml` | `jose` **6.2.12** (exact pin), runtime, MIT, no dependencies of its own |
| Config (R2) | `src/config.ts` | `FIREBASE_PROJECT_ID` (`^[a-z][a-z0-9-]{4,28}[a-z0-9]$`): required in production, `demo-` rejected in production, optional in dev/test. No variable for the JWKS URL, issuer, audience or algorithm; no emulator/bypass |
| Verifier (R3) | `src/modules/auth/{verifier,firebase-verifier,errors}.ts` | `TokenVerifier` interface, `VerifiedIdentity`, `AuthError` (reasons missing/malformed/expired/invalid/wrong_provider/no_phone), `AuthUnavailableError`, `FirebaseIdTokenVerifier` |
| Middleware (R4) | `src/modules/auth/middleware.ts` | `authenticate`, `requireUser` (DB lookup on every request), `requireRole` |
| Roles (R5) | `src/modules/auth/roles.ts` | admin ⊇ moderator ⊇ user; unknown values fail every check |
| Users lookup | `src/modules/users/service.ts` | `findUserByFirebaseUid` (the only piece of the users module P005a needs) |
| Tests | `test/auth/{tokens,verifier.test,middleware.test}.ts`, `test/config.test.ts`, `test/db/auth.test.ts` | §6 |
| Docs | `docs/adr/0006-authentication-and-roles.md`, `docs/adr/README.md`, `CLAUDE.md` (Auth rules), `backend/README.md` (environment-variable table, auth conventions), `src/modules/README.md`, comment on `users.role` | |

Verifier details:

- The default key resolver is `createRemoteJWKSet(FIREBASE_JWKS_URL, {timeoutDuration: 3000,
  cooldownDuration: 30000, cacheMaxAge: 600000})`. Keys are fetched lazily on the first token,
  so startup makes no network call. One shared instance is intended (the wiring is in P005b).
- Checks run in this order:
  1. length ≤ 4096 (before parsing);
  2. the protected header has a `kid`;
  3. `jwtVerify` with `algorithms ['RS256']`, issuer, audience, `clockTolerance` 10 s and
     `requiredClaims` sub/iat/exp/auth_time;
  4. `sub` 1–128 characters; `iat` and `auth_time` ≤ now + 10 s; provider `phone`; `phone_number`
     matches `^\+[1-9][0-9]{6,14}$`.
- Error classification:
  - Signature, claim, format, disallowed-alg and unknown-`kid` errors → `AuthError` (401).
  - Non-JOSE errors (network), `JWKSTimeout`, `JWKSInvalid` and the base `JOSEError` (non-200 or
    non-JSON key-set response) → `AuthUnavailableError` (503).
  - No jose message or payload is kept, because they can contain claim values.
- Duplicate `Authorization` headers arrive joined by `", "` (checked in `@hono/node-server`).
  The bearer pattern `^Bearer ([^\s,]+)$` (case-insensitive) rejects them.

**API contract:** unchanged in P005a (`contracts/openapi.json` untouched, `info.version` stays
0.1.0). The middleware already answers with codes that P005b adds to the contract:
`bootstrap_required`, `account_deleted`, `auth_unavailable` and `auth_not_configured`. No
production route uses the middleware yet. **Migrations:** none.

## 5. Diagram

The P005 auth-flow diagram (`docs/diagrams/005-auth-flow.*`, 14 nodes) is already drawn on
`wip/005-full`. It ships with **P005b** as the prompt's split assigns, because it shows the
`/v1/me` endpoints. No diagram in this PR.

## 6. Quality gate & test results

Run in `backend/` on `feat/005a-auth-verifier` (Windows, Node 24, pnpm 12.8.1, Docker 28.3.3):

| Command | Result |
| --- | --- |
| `pnpm typecheck` | pass |
| `pnpm lint` | pass |
| `pnpm format:check` | pass |
| `pnpm test` | pass: **165 tests, 15 files** (unit 135, db 30) |
| `pnpm build` | pass |
| `pnpm db:check` | pass (no schema change, no migration) |
| `pnpm openapi:check` / `pnpm openapi:lint` | pass (spec unchanged) |
| `pnpm audit --prod` | **No known vulnerabilities found** |

New and changed tests (97 in the touched files):

- **Verifier** (35, no network, RSA key pair generated per run, real `FirebaseIdTokenVerifier`):
  - Accepts a valid phone token (uid, phone, provider); the 10 s tolerance; a 128-character
    `sub`.
  - Rejects as `AuthError`:
    - expired;
    - wrong issuer (×2), wrong audience;
    - another key with the same `kid`, unknown `kid`, missing `kid`;
    - `alg none` (emulator-style), HS256;
    - provider password / google.com / anonymous, no `firebase` claim;
    - missing or malformed phone (×2);
    - `sub` empty / >128 / missing, missing `auth_time`, `auth_time` or `iat` in the future;
    - >4096 characters (resolver never called), empty, garbage, `a.b.c`, two segments.
  - `AuthUnavailableError`: resolver network error; real `createRemoteJWKSet` timeout
    (`JWKSTimeout`), HTTP 500, malformed JWKS (`JWKSInvalid`).
  - **Cooldown**: real `createRemoteJWKSet` with a counting `customFetch` and a faked clock.
    20 forged `kid`s → still 1 fetch; after 31 s, 5 forged → exactly 1 more; valid tokens
    served from the cache.
- **Config** (+13, now 32): required only in production; `demo-` rejected in production and allowed in
  dev/test; 7 invalid formats; messages name the variable, never the value;
  `FIREBASE_AUTH_EMULATOR_HOST`, `FIREBASE_JWKS_URL` and `AUTH_BYPASS` are ignored; no file under
  `src/` mentions `EMULATOR_HOST` or `AUTH_BYPASS`.
- **Middleware** (26, unit, on the real app with test-only routes):
  - Missing header, wrong scheme, `Bearer` alone, empty token, two spaces, garbage and duplicate
    headers → identical 401 body, `WWW-Authenticate: Bearer`, one WARNING with exactly
    `auth_failure` + `request_id`, and the token never in the logs.
  - Scheme case-insensitive; no verifier → 503 `auth_not_configured`; keys unavailable → 503
    `auth_unavailable` (no `WWW-Authenticate`, WARNING with `cause`); unexpected error → 500.
  - Access-log rules from P002 hold; the uid and phone never reach the logs or responses.
  - `requireRole` matrix: 4 roles (including an unknown one) × 3 requirements.
- **DB** (4, Testcontainers PostGIS): `requireUser`:
  - no row → 403 `bootstrap_required`;
  - soft-deleted → 403 `account_deleted`;
  - sets `{userId, role, locale}` and `user_id` in the access log (no uid, phone or token).

  `requireRole` uses `users.role` and ignores a `role: admin` token claim. Promote and demote
  apply on the next request.

**SOS failure matrix (Plan v7 §7.5):** not applicable. No SOS, live-location or contacts code.

## 7. Decisions & ADRs

- **ADR 0006** (Accepted): phone-only sign-in; verification with `jose` instead of
  `firebase-admin`; no credentials; no emulator or bypass path; **database-authoritative roles,
  a deliberate deviation from Plan v7 §12.4 ("custom claims")** for immediate revocation, no
  Admin credentials and a single source of truth; bootstrap as an idempotent upsert (P005b);
  `firebase_uid` never exposed; revocation checks deferred; the error codes and client behaviour;
  the project ID treated as configuration, not a secret.
- JWK URL instead of the X.509 URL from the guide: `jose` consumes JWK Sets directly. The issuer's
  discovery document publishes the JWK URL, and both serve the same keys (spike).
- Cache max age 10 min (prompt value) instead of Google's `max-age` (~6.6 h). Refetching every
  10 min per instance is cheap. A new `kid` is picked up at most 30 s after first use.
- `requireUser` returns 503 `db_not_configured` when no database is configured, matching the
  readiness probe's code.
- `users.role` comment updated from "mirrored from Firebase custom claims" to "source of truth"
  (a TypeScript comment only; no migration).

## 8. Security & privacy notes

- No personal data is newly stored in P005a. `phone_e164` gets populated by bootstrap in P005b,
  and P009 must show and record DPDP consent before calling it.
- Tokens, the Authorization header, phone numbers and Firebase uids are never logged or returned.
  Logs carry only the failure reason category and the internal `user_id`. Tests assert this.
- RS256 only; `none` and HS256 are rejected before key lookup; issuer, audience and expiry are
  enforced; the length cap is checked before parsing; there's no bypass or emulator switch
  (a test scans `src/`).
- Test fixtures: fake project `demo-saferoute`, numbers from `+910000000001`, uids
  `test-uid-*`. RSA keys are generated per run and never committed. The HS256 test secret is
  labelled as not real.
- Public repo check: no real project ID, secrets, personal data or local paths in the diff. The
  JWKS URL and issuer are public Google endpoints.
- `pnpm audit --prod`: no known vulnerabilities. `jose` is MIT, compatible with AGPL-3.0-only.

## 9. Known issues & risks

- **PR size** ~1,400 lines (see §1), mostly tests.
- If Google's key endpoint is unreachable **before** the first successful fetch, each protected
  request tries again, deduplicated, with a 3 s timeout, and answers 503. jose's cooldown only
  applies after a success. Acceptable, because clients back off on 503. Watch the
  `auth_unavailable` rate.
- `backend/.env.example` gets `FIREBASE_PROJECT_ID=demo-saferoute`. The `.env*` deny rule stops
  Claude Code from editing it, so Rahul adds it by hand (see the commit).

## 10. Follow-ups & prerequisites for next prompt

Next: **P005b** (`feat/005b-me-endpoints`), porting from `wip/005-full`:

- users module: `POST /v1/me/bootstrap`, `GET /v1/me`;
- wiring: `createApp({verifier, db})` and `src/runtime.ts`;
- the contract: codes, `me` tag, `WWW-Authenticate` on 401, version 0.1.0 → 0.2.0, oasdiff
  non-breaking;
- the `set-role` script;
- README sign-in, admin and smoke-check sections;
- the deploy-readiness test;
- diagram `005-auth-flow`.

Follow-ups recorded in Notion:

- P009: show and record DPDP consent **before** bootstrap; run the real-device check against
  staging.
- Add `firebase-admin` only for FCM push in the worker (Cloud Run service account, no key files).
- Devices/FCM registration endpoint `PUT /v1/devices/{installationId}`.
- Rate limiting for invalid-token floods.
- App Check in monitor mode.
- Revocation checks for moderator/admin (P018) and SOS-critical routes.
- Mirror the role to a custom claim only if the moderation web app needs it.
- P006: Cloud Run egress must allow `www.googleapis.com` (don't force all egress through a VPC
  without Cloud NAT); `DATABASE_URL` format for Cloud SQL.
- Index review of `users.firebase_uid` lookups under load.
- Dashboard signals (Plan v7 §14.5): auth-failure rate by reason, count of 503
  `auth_unavailable`.

**Note for P006:** the API needs outbound HTTPS to `www.googleapis.com`. Startup makes no call
there (keys are fetched lazily), so a cold start never depends on Google.

## 11. How Rahul can verify

1. Read the diff, especially `src/modules/auth/` and ADR 0006. There's no `openapi.json` change
   in this part.
2. With Docker running: `cd backend`, `pnpm install`, `pnpm test`. Expect 165 passing tests
   (unit 135, db 30).
3. `pnpm build`, then in a shell **without real secrets**:
   - `$env:NODE_ENV="production"; $env:DATABASE_URL="postgres://u:p@127.0.0.1:1/x"; node dist/server.js`
     → refuses: `FIREBASE_PROJECT_ID: required when NODE_ENV=production`;
   - add `$env:FIREBASE_PROJECT_ID="demo-saferoute"` → refuses: `demo- project ID is not allowed`.

   Then remove the variables again.
4. Read the README "Environment variables" table.
5. Confirm CI is green; squash and merge; delete the branch.

The `curl` checks against `/v1/me` come with P005b.

## 12. Learning notes

- **ID token / JWT.** A JSON Web Token has three base64url parts: header (algorithm, key id
  `kid`), payload (claims such as `sub`, `exp` and `phone_number`) and signature. Firebase signs
  the token after the phone OTP succeeds. **Verifying** means checking four things:
  - the signature matches one of Google's public keys, so nobody could have forged it;
  - the issuer is our Firebase project;
  - the audience is our project ID, so it was minted for us;
  - it hasn't expired.

  <https://firebase.google.com/docs/auth/admin/verify-id-tokens>
- **Public-key verification (JWKS) and key rotation.** Google signs with private keys and
  publishes the matching public keys as a JSON Web Key Set. Anyone can verify; only Google can
  sign. Google rotates the keys every few hours, so the verifier caches the set and refetches when
  a token names an unknown `kid`, at most once per 30 s so forged tokens can't flood Google.
- **Authentication vs authorization.** Authentication = *who are you?* (the token proves the
  phone sign-in). Authorization = *what may you do?* (the role in our database). They're separate
  steps: `authenticate`/`requireUser`, then `requireRole`.
- **Why roles are checked in the database.** A token's claims are frozen until the app refreshes
  it (up to ~1 h). Reading `users.role` on every request makes demoting a moderator take effect
  on the next request, and the API needs no Firebase admin credentials to set claims.
- **Idempotent upsert** (used by bootstrap in P005b). "Insert if missing, otherwise return
  what's there": calling it twice, or ten times at once, gives the same single row. That makes
  it safe for the app to retry after a network error.
- **Why a project ID is configuration, not a secret.** It's inside every ID token and inside the
  Android app, so hiding it protects nothing. Security comes from Google's signature, not from
  the ID being unknown. It stays out of this public repo only because it contains a personal
  handle.
