// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Why a token was rejected. Logged (WARNING, with request_id) so failures can be counted, but
 * never sent to the client: every AuthError becomes the same generic 401.
 */
export type AuthFailureReason =
  'missing' | 'malformed' | 'expired' | 'invalid' | 'wrong_provider' | 'no_phone';

/** The caller's credentials are missing or not acceptable. The client should get 401. */
export class AuthError extends Error {
  readonly reason: AuthFailureReason;

  constructor(reason: AuthFailureReason, options?: ErrorOptions) {
    // The message is the category only: never the token, a claim value or a jose message.
    super(`authentication failed: ${reason}`, options);
    this.name = 'AuthError';
    this.reason = reason;
  }
}

/**
 * The token could not be checked because Google's public keys could not be fetched (network
 * error, timeout, non-200 or malformed key set). Not the caller's fault: the client gets 503 and
 * retries with backoff.
 */
export class AuthUnavailableError extends Error {
  /** Name of the underlying error (e.g. `JWKSTimeout`, `TypeError`), safe to log. */
  readonly causeName: string;

  constructor(causeName: string) {
    super('token verification keys unavailable');
    this.name = 'AuthUnavailableError';
    this.causeName = causeName;
  }
}
