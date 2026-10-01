// SPDX-License-Identifier: AGPL-3.0-only
import {
  createRemoteJWKSet,
  decodeProtectedHeader,
  errors,
  jwtVerify,
  type JWTPayload,
  type JWTVerifyGetKey,
} from 'jose';
import { AuthError, AuthUnavailableError } from './errors.js';
import type { TokenVerifier, VerifiedIdentity } from './verifier.js';

/**
 * Google's public keys for Firebase ID tokens, as a JWK Set. Published as `jwks_uri` in the
 * issuer's discovery document (https://securetoken.google.com/<projectId>/.well-known/openid-configuration);
 * same key IDs as the X.509 URL in Firebase's "Verify ID tokens" guide (checked in the P005 spike).
 * A constant on purpose: no environment variable can point verification at other keys.
 */
export const FIREBASE_JWKS_URL =
  'https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com';

export const FIREBASE_ISSUER_PREFIX = 'https://securetoken.google.com/';

/**
 * Key-set cache settings for jose's createRemoteJWKSet:
 * - timeoutDuration: give up on a key fetch after 3 s (the request then gets 503, never hangs);
 * - cooldownDuration: a token with an unknown `kid` triggers at most one refetch per 30 s, so
 *   forged tokens cannot make every request call Google;
 * - cacheMaxAge: refresh the keys every 10 min (Google rotates them every few hours).
 * Keys are fetched lazily on the first token, so startup never depends on Google being reachable.
 */
export const REMOTE_JWKS_OPTIONS = {
  timeoutDuration: 3_000,
  cooldownDuration: 30_000,
  cacheMaxAge: 600_000,
} as const;

/** Longer than any real Firebase ID token (~1 kB); rejected before any parsing. */
export const MAX_TOKEN_LENGTH = 4096;
export const CLOCK_TOLERANCE_SECONDS = 10;
export const E164_PATTERN = /^\+[1-9][0-9]{6,14}$/;
const MAX_UID_LENGTH = 128;

export interface FirebaseIdTokenVerifierOptions {
  projectId: string;
  /** Where signing keys come from. Default: Google's remote JWK Set. Tests inject local keys. */
  keyResolver?: JWTVerifyGetKey;
}

/**
 * Verifies Firebase ID tokens (phone sign-in only) with jose against Google's public keys.
 * Needs only the project ID: no credentials, no Admin SDK, no emulator (ADR 0006).
 * Create one instance at startup and share it, so the key cache is shared.
 */
export class FirebaseIdTokenVerifier implements TokenVerifier {
  readonly #projectId: string;
  readonly #issuer: string;
  readonly #keys: JWTVerifyGetKey;

  constructor({ projectId, keyResolver }: FirebaseIdTokenVerifierOptions) {
    this.#projectId = projectId;
    this.#issuer = `${FIREBASE_ISSUER_PREFIX}${projectId}`;
    this.#keys = keyResolver ?? createRemoteJWKSet(new URL(FIREBASE_JWKS_URL), REMOTE_JWKS_OPTIONS);
  }

  async verify(idToken: string): Promise<VerifiedIdentity> {
    if (idToken.length === 0 || idToken.length > MAX_TOKEN_LENGTH) {
      throw new AuthError('malformed');
    }
    // Firebase always sets `kid`. Without it, every key in the set would be a candidate.
    let kid: unknown;
    try {
      kid = decodeProtectedHeader(idToken).kid;
    } catch {
      throw new AuthError('malformed');
    }
    if (typeof kid !== 'string' || kid.length === 0) throw new AuthError('malformed');

    let payload: JWTPayload;
    try {
      ({ payload } = await jwtVerify(idToken, this.#keys, {
        algorithms: ['RS256'],
        issuer: this.#issuer,
        audience: this.#projectId,
        clockTolerance: CLOCK_TOLERANCE_SECONDS,
        requiredClaims: ['sub', 'iat', 'exp', 'auth_time'],
      }));
    } catch (err) {
      throw classifyJoseError(err);
    }
    return toIdentity(payload);
  }
}

/** Firebase-specific checks jose does not do; the signature, iss, aud and exp are already valid. */
function toIdentity(payload: JWTPayload): VerifiedIdentity {
  const nowSeconds = Date.now() / 1000 + CLOCK_TOLERANCE_SECONDS;
  const { sub, iat, exp } = payload;
  const authTime = payload.auth_time;

  if (typeof sub !== 'string' || sub.length === 0 || sub.length > MAX_UID_LENGTH) {
    throw new AuthError('invalid');
  }
  if (typeof iat !== 'number' || typeof exp !== 'number' || typeof authTime !== 'number') {
    throw new AuthError('invalid');
  }
  if (iat > nowSeconds || authTime > nowSeconds) throw new AuthError('invalid');

  const firebase = payload.firebase as { sign_in_provider?: unknown } | undefined;
  if (firebase?.sign_in_provider !== 'phone') throw new AuthError('wrong_provider');

  const phone = payload.phone_number;
  if (typeof phone !== 'string' || !E164_PATTERN.test(phone)) throw new AuthError('no_phone');

  return {
    firebaseUid: sub,
    phoneE164: phone,
    signInProvider: 'phone',
    issuedAt: new Date(iat * 1000),
    expiresAt: new Date(exp * 1000),
  };
}

/**
 * Token problems (bad format, signature, claims, disallowed alg such as `none` or HS256, unknown
 * `kid`) → AuthError, the client's fault. Key-set problems (network error, timeout, non-200 or
 * unparsable response, malformed set) → AuthUnavailableError, so the client retries.
 * No jose message or payload is kept: they can contain claim values such as the phone number.
 */
export function classifyJoseError(err: unknown): AuthError | AuthUnavailableError {
  if (err instanceof AuthError) return err;
  if (!(err instanceof errors.JOSEError)) {
    return new AuthUnavailableError(err instanceof Error ? err.name : 'UnknownError');
  }
  if (
    err instanceof errors.JWKSTimeout ||
    err instanceof errors.JWKSInvalid ||
    // jose throws the base class only for a key-set HTTP response that is not 200 or not JSON.
    err.code === errors.JOSEError.code
  ) {
    return new AuthUnavailableError(err.name);
  }
  if (err instanceof errors.JWTExpired) return new AuthError('expired');
  if (err instanceof errors.JWSInvalid || err instanceof errors.JWTInvalid) {
    return new AuthError('malformed');
  }
  return new AuthError('invalid');
}
