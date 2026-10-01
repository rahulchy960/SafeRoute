# ADR 0006: Authentication with Firebase ID tokens (jose) and database-authoritative roles

- **Status:** Accepted
- **Date:** 2026-10-01
- **Prompt:** P005 (split: P005a verifier, middleware and roles; P005b `/v1/me` endpoints, contract and role script)
- **Plan refs:** Plan v7 §3.2 (F-01), §6.2, §6.3, §11, §12.1, §12.4, §13.1

## Context

- Users sign in with **phone number + OTP through Firebase Authentication** (Plan v7 §3.2). The
  API must check who is calling on every protected route. The client is never the security
  boundary (Plan v7 §6.2).
- A Firebase ID token is an RS256-signed JWT. Firebase's "Verify ID tokens" guide lists the
  checks: `alg` RS256 with a `kid` of one of Google's public keys, `iss` =
  `https://securetoken.google.com/<projectId>`, `aud` = `<projectId>`, `exp` in the future,
  `iat` and `auth_time` in the past, and `sub` (the uid) a non-empty string. Phone sign-in adds
  `firebase.sign_in_provider = "phone"` and a top-level `phone_number`.
- Verifying needs only the project ID and Google's **public** keys. The issuer's discovery
  document publishes them as a JWK Set at
  `https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com`,
  with the same key IDs as the X.509 URL in Firebase's guide (checked in the P005 spike).
- Plan v7 §12.4 says roles (user, moderator, admin) come "via custom claims checked
  server-side". Custom claims can only be set with Firebase Admin credentials. They reach the API
  only when the client refreshes its token, which takes up to about 1 h.
- The users table (P003) already has `role` with a check constraint, and `audit_log` exists.
- The repository is public. The Firebase project ID is not a secret: it is inside every token and
  inside the Android app. It contains a personal handle, though, so it stays out of tracked files.

## Decision

1. **Phone sign-in only.** A token whose `firebase.sign_in_provider` is not `phone`, or that has
   no valid E.164 `phone_number`, is rejected.
2. **We verify tokens with `jose`**, not `firebase-admin`. `FirebaseIdTokenVerifier`
   (`backend/src/modules/auth/`) accepts RS256 only and requires a `kid`. It checks issuer,
   audience and expiry with a 10 s clock tolerance, plus `sub` (1–128 characters), `iat` and
   `auth_time` not in the future, provider `phone` and the phone format. Tokens longer than
   4096 characters are rejected before parsing. Keys come from Google's JWK Set through
   `createRemoteJWKSet`: a 3 s timeout, a 30 s cooldown before an unknown `kid` may trigger a
   refetch, and a 10 min cache. They are fetched lazily, so startup makes no network call.
3. **No credentials and no bypass.** The API needs no service account for authentication. The
   JWKS URL, issuer, audience and algorithm are code constants. The only setting is
   `FIREBASE_PROJECT_ID`: required in production, and `demo-` projects are rejected there.
   There is no emulator switch, no dev login route and no bypass flag. Unsigned (`alg: none`)
   emulator tokens and HS256 tokens are rejected. Tests sign tokens with a throwaway RSA key and
   inject a local key resolver into the real verifier.
4. **Roles are authoritative in the database** (`users.role`). `requireUser` loads them on every
   request, with no cache. `requireRole` applies admin ⊇ moderator ⊇ user. Token claims are
   never used for authorization. **This deviates from Plan v7 §12.4 ("custom claims")**, because:
   - revocation is immediate, while claims lag until the next token refresh (up to about 1 h);
   - the API needs no Firebase Admin credentials;
   - there is a single source of truth.

   Roles change only through an audited admin script (`audit_log` action `user.role_changed`,
   P005b), never by editing rows by hand.
5. **Bootstrap is an idempotent upsert** (implemented in P005b). `POST /v1/me/bootstrap` inserts the user with
   `ON CONFLICT DO NOTHING` (every unique index is an arbiter). `phone_e164` comes from the
   verified token, never from the body, and the insert writes `user.created` to `audit_log` in
   the same transaction. A repeat call returns the existing account unchanged. A phone number
   that another account holds → 409 `phone_already_registered`.
6. **`firebase_uid` is never exposed** by the API and never logged. Neither are tokens, the
   Authorization header or phone numbers. Logs carry the failure *reason category* and the
   internal `user_id` only.
7. **Revocation checks are deliberately not implemented now.** Tokens live at most 1 h, and a
   role change or account deletion already takes effect on the next request through the database
   lookup. Revisit for moderator/admin routes (P018) and SOS-critical routes.
8. **Error codes and client behaviour:**

   | Response | Meaning | Client behaviour |
   | --- | --- | --- |
   | 401 `unauthorized` + `WWW-Authenticate: Bearer` | Missing or invalid token (same body for every reason) | Refresh the ID token and retry once; then sign in again |
   | 503 `auth_unavailable` | Google's keys unreachable | Retry with backoff. Not a sign-out |
   | 503 `auth_not_configured` | No project ID (dev/test only) | Treat like any 503 |
   | 403 `bootstrap_required` | Signed in, no account yet | Call `POST /v1/me/bootstrap`, then retry |
   | 403 `account_deleted` / `forbidden` | Deleted account / role not allowed | Final: don't retry |

## Alternatives considered

- **`firebase-admin` `verifyIdToken`.** Well-known, and it can also check revocation. But it is
  a large dependency (slower Cloud Run cold starts), and the Firebase Auth emulator trusts
  unsigned tokens when `FIREBASE_AUTH_EMULATOR_HOST` is set: one stray environment variable
  would disable verification. We will add `firebase-admin` later only where it is needed (FCM
  push in the worker, using the Cloud Run service account, no key files).
- **Roles from custom claims (Plan v7 §12.4 as written).** Rejected: it needs Admin credentials
  in the API, and revoking a moderator would lag by up to an hour. If the moderation web app
  (P018) ever needs a claim, it will mirror the database role, and the database stays
  authoritative.
- **Firebase Auth emulator for tests.** Rejected: it needs Java and firebase-tools, its tokens
  are unsigned, and it adds a bypass path. Locally signed tokens exercise the real verification
  code with no extra tooling.
- **An environment variable for the JWKS URL or issuer.** Rejected: it would be a way to point
  verification at attacker-controlled keys, and nothing needs it.

## Consequences

- The API has no Firebase credentials and needs outbound HTTPS to `www.googleapis.com` (P006:
  don't force all egress through a VPC without Cloud NAT). If Google's keys are unreachable
  before the first successful fetch, protected routes answer 503 until the next attempt succeeds.
- Every authenticated request does one indexed lookup on `users.firebase_uid` (unique index).
  Review under load.
- The client (P009) must refresh tokens on 401, and must show and record the DPDP consent notice
  **before** calling bootstrap, which stores the phone number (Plan v7 §12.1).
- A role change applies on the next request; there are no tokens to revoke for authorization.
- Follow-ups: revocation checks for moderator/admin and SOS routes; rate limiting of
  invalid-token floods; App Check in monitor mode; a devices/FCM endpoint; `firebase-admin` only
  in the push worker.

## References

- Plan v7 §3.2, §6.2, §6.3, §11, §12.1, §12.4, §13.1.
- Firebase: "Verify ID tokens using a third-party JWT library"
  (<https://firebase.google.com/docs/auth/admin/verify-id-tokens>).
- `jose` (MIT): <https://github.com/panva/jose>.
- [ADR 0003](0003-database-conventions-and-migrations.md) (audit rules),
  [ADR 0004](0004-api-contract-and-conventions.md) (contract and error conventions).
- Prompt log: [`docs/prompt-logs/005a-auth-verifier.md`](../prompt-logs/005a-auth-verifier.md)
  (P005b adds its own).
