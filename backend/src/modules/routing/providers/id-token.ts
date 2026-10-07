// SPDX-License-Identifier: AGPL-3.0-only
import { RoutingError } from '../types.js';

/** Where a Cloud Run instance asks for tokens of its own service account. Not configurable. */
export const METADATA_IDENTITY_URL =
  'http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/identity';

/** A token is replaced this long before it expires. */
const REFRESH_BEFORE_EXPIRY_MS = 5 * 60_000;
const METADATA_TIMEOUT_MS = 3000;

export interface IdTokenSource {
  /** A Google-signed ID token for `audience`. Throws `RoutingError('auth')` when none is available. */
  get(audience: string): Promise<string>;
}

export interface IdTokenSourceOptions {
  /** Tests only: a fake metadata server. Never read from the environment. */
  identityUrl?: string;
  fetchImpl?: typeof fetch;
  now?: () => number;
}

/** Expiry in milliseconds from the token's payload. Not a verification: the receiver verifies. */
function expiryOf(token: string): number | undefined {
  try {
    const payload = JSON.parse(
      Buffer.from(token.split('.')[1] ?? '', 'base64url').toString('utf8'),
    ) as { exp?: unknown };
    return typeof payload.exp === 'number' ? payload.exp * 1000 : undefined;
  } catch {
    return undefined;
  }
}

/**
 * ID tokens from the metadata server, with no library (ADR 0020). The API's service account
 * holds `roles/run.invoker` on each OSRM service; the token says "I am that account, and this
 * token is meant for <audience>". Cloud Run checks both.
 *
 * - `audience` must be the service URL exactly as Cloud Run reports it: no trailing slash, no
 *   path. src/config.ts normalises it once.
 * - One token per audience is cached until five minutes before it expires; parallel requests
 *   share one fetch.
 * - Any failure is `RoutingError('auth')`. The token never appears in an error or a log.
 */
export function createMetadataIdTokenSource(options: IdTokenSourceOptions = {}): IdTokenSource {
  const identityUrl = options.identityUrl ?? METADATA_IDENTITY_URL;
  const doFetch = options.fetchImpl ?? fetch;
  const now = options.now ?? Date.now;
  const cache = new Map<string, { token: string; expiresAt: number }>();
  const pending = new Map<string, Promise<string>>();

  async function fetchToken(audience: string): Promise<string> {
    try {
      const url = new URL(identityUrl);
      url.searchParams.set('audience', audience);
      const response = await doFetch(url, {
        headers: { 'Metadata-Flavor': 'Google' },
        redirect: 'error',
        signal: AbortSignal.timeout(METADATA_TIMEOUT_MS),
      });
      const token = (await response.text()).trim();
      const expiresAt = expiryOf(token);
      if (response.status !== 200 || expiresAt === undefined || expiresAt <= now()) {
        throw new RoutingError('auth');
      }
      cache.set(audience, { token, expiresAt });
      return token;
    } catch {
      // The original error can quote the URL, which holds the audience (a service address).
      throw new RoutingError('auth');
    }
  }

  return {
    get(audience) {
      const cached = cache.get(audience);
      if (cached !== undefined && cached.expiresAt - now() > REFRESH_BEFORE_EXPIRY_MS) {
        return Promise.resolve(cached.token);
      }
      let inFlight = pending.get(audience);
      if (inFlight === undefined) {
        inFlight = fetchToken(audience).finally(() => pending.delete(audience));
        pending.set(audience, inFlight);
      }
      return inFlight;
    },
  };
}
