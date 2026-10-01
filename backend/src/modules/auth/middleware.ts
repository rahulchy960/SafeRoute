// SPDX-License-Identifier: AGPL-3.0-only
import type { Context } from 'hono';
import { createMiddleware } from 'hono/factory';
import type { Db } from '../../db/client.js';
import { problemResponse } from '../../lib/problem.js';
import type { Logger } from '../../lib/logger.js';
import type { AppEnv } from '../../types.js';
import { findUserByFirebaseUid, type UserRecord } from '../users/service.js';
import { AuthError, AuthUnavailableError, type AuthFailureReason } from './errors.js';
import { hasRole, type Role } from './roles.js';
import type { TokenVerifier, VerifiedIdentity } from './verifier.js';

/** The signed-in caller's account, loaded from the database on every request (ADR 0006). */
export interface CurrentUser {
  /** Internal users.id (UUID). The only user identifier that may appear in logs. */
  userId: string;
  /** Authoritative role from users.role, never from token claims. */
  role: string;
  locale: string;
}

export interface AuthEnv {
  Variables: AppEnv['Variables'] & { identity: VerifiedIdentity };
}

export interface UserEnv {
  Variables: AuthEnv['Variables'] & {
    currentUser: CurrentUser;
    /** The full row requireUser loaded, so handlers don't query it again. */
    userRecord: UserRecord;
  };
}

export interface AuthDeps {
  /** Undefined when FIREBASE_PROJECT_ID is not set (dev/test only): protected routes answer 503. */
  verifier: TokenVerifier | undefined;
  /** Undefined when DATABASE_URL is not set (dev/test only): user routes answer 503. */
  db: Db | undefined;
}

/**
 * Exactly one `Bearer <token>` credential: scheme case-insensitive, one space, no whitespace or
 * comma in the token. Repeated Authorization headers arrive joined by ", " and fail the pattern;
 * a JWT never contains a comma.
 */
const BEARER_PATTERN = /^Bearer ([^\s,]+)$/i;

/** What the helpers below need from a request, read once from the typed middleware context. */
interface RequestScope {
  c: Context;
  log: Logger;
  requestId: string;
}

/**
 * Every 401 has the same body whatever the reason, so a client (or an attacker) learns nothing
 * about why a token failed. The reason goes to the server log only.
 */
function unauthorized({ c, log, requestId }: RequestScope, reason: AuthFailureReason): Response {
  log.warn({ auth_failure: reason }, 'authentication failed');
  c.header('WWW-Authenticate', 'Bearer');
  return problemResponse(c, 401, 'unauthorized', 'Missing or invalid credentials.', requestId);
}

/**
 * Verifies the bearer token. Returns the identity, or the error response to send.
 * NEVER logs the token, the Authorization header, the phone number or the Firebase uid.
 */
async function verifyRequest(
  scope: RequestScope,
  verifier: TokenVerifier | undefined,
): Promise<VerifiedIdentity | Response> {
  const { c, log, requestId } = scope;
  if (verifier === undefined) {
    return problemResponse(
      c,
      503,
      'auth_not_configured',
      'Sign-in is not configured on this instance.',
      requestId,
    );
  }

  const header = c.req.header('authorization');
  if (header === undefined) return unauthorized(scope, 'missing');
  const token = BEARER_PATTERN.exec(header)?.[1];
  if (token === undefined) return unauthorized(scope, 'malformed');

  try {
    return await verifier.verify(token);
  } catch (err) {
    if (err instanceof AuthError) return unauthorized(scope, err.reason);
    if (err instanceof AuthUnavailableError) {
      log.warn(
        { auth_failure: 'unavailable', cause: err.causeName },
        'token verification keys unavailable',
      );
      // 503, not 401: the token may be fine, so the client should retry rather than sign out.
      return problemResponse(
        c,
        503,
        'auth_unavailable',
        'Sign-in cannot be checked right now. Try again shortly.',
        requestId,
      );
    }
    throw err;
  }
}

/**
 * Requires a valid Firebase ID token and puts the identity in `c.get('identity')`. Does not need
 * a user row: used by POST /v1/me/bootstrap, which creates it.
 */
export function authenticate(verifier: TokenVerifier | undefined) {
  return createMiddleware<AuthEnv>(async (c, next) => {
    const scope = { c, log: c.get('logger'), requestId: c.get('requestId') };
    const result = await verifyRequest(scope, verifier);
    if (result instanceof Response) return result;
    c.set('identity', result);
    await next();
  });
}

/**
 * `authenticate` + the caller's user row, looked up by firebase_uid on every request (no cache),
 * so a role change or account deletion takes effect on the next request.
 * - no row → 403 `bootstrap_required` (call POST /v1/me/bootstrap first);
 * - soft-deleted → 403 `account_deleted`.
 * Sets `c.get('currentUser')` and adds `user_id` (internal UUID) to the request logger.
 */
export function requireUser({ verifier, db }: AuthDeps) {
  return createMiddleware<UserEnv>(async (c, next) => {
    const scope = { c, log: c.get('logger'), requestId: c.get('requestId') };
    const result = await verifyRequest(scope, verifier);
    if (result instanceof Response) return result;
    c.set('identity', result);

    const id = c.get('requestId');
    if (db === undefined) {
      return problemResponse(c, 503, 'db_not_configured', 'No database is configured.', id);
    }
    const user = await findUserByFirebaseUid(db, result.firebaseUid);
    if (user === undefined) {
      return problemResponse(
        c,
        403,
        'bootstrap_required',
        'No account exists for this sign-in yet. Call POST /v1/me/bootstrap first.',
        id,
      );
    }
    if (user.deletedAt !== null) {
      return problemResponse(c, 403, 'account_deleted', 'This account has been deleted.', id);
    }

    c.set('currentUser', { userId: user.id, role: user.role, locale: user.locale });
    c.set('userRecord', user);
    c.set('logger', c.get('logger').child({ user_id: user.id }));
    await next();
  });
}

/**
 * Allows the request only if the caller's database role includes `required`
 * (admin ⊇ moderator ⊇ user). Must run after `requireUser`. Failure → 403 `forbidden`.
 */
export function requireRole(required: Role) {
  return createMiddleware<UserEnv>(async (c, next) => {
    if (!hasRole(c.get('currentUser').role, required)) {
      return problemResponse(
        c,
        403,
        'forbidden',
        'You are not allowed to do this.',
        c.get('requestId'),
      );
    }
    await next();
  });
}
