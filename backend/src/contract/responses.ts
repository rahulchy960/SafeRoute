// SPDX-License-Identifier: AGPL-3.0-only

/** Media type of every error body (RFC 9457). */
export const PROBLEM_MEDIA_TYPE = 'application/problem+json';

interface HeaderComponent {
  description: string;
  schema: { type: 'string'; enum?: string[] };
}

/** Header components; responses reference them instead of repeating the definitions. */
export const HEADER_COMPONENTS: Record<
  'RequestId' | 'CacheControlNoStore' | 'WwwAuthenticate',
  HeaderComponent
> = {
  RequestId: {
    description:
      'Correlation ID for this request. Echoes a well-formed incoming `X-Request-Id` ' +
      '(8–64 characters of `A–Z a–z 0–9 . _ -`), otherwise a fresh UUID.',
    schema: { type: 'string' },
  },
  CacheControlNoStore: {
    description: 'Always `no-store`: the response must never be cached.',
    schema: { type: 'string', enum: ['no-store'] },
  },
  WwwAuthenticate: {
    description: 'Always `Bearer`: send a Firebase ID token as `Authorization: Bearer <token>`.',
    schema: { type: 'string', enum: ['Bearer'] },
  },
};

export const headerRef = (name: keyof typeof HEADER_COMPONENTS) => ({
  $ref: `#/components/headers/${name}`,
});

/** Shared error responses, keyed by status code. Routes pick the ones that can really happen. */
export const ERROR_RESPONSES = {
  400: { name: 'BadRequest', description: 'Invalid input (`validation_error`).' },
  401: {
    name: 'Unauthorized',
    description:
      'Missing or invalid credentials (`unauthorized`). Refresh the ID token and retry once.',
  },
  403: {
    name: 'Forbidden',
    description:
      'Authenticated but not allowed: `forbidden`, `bootstrap_required`, `account_deleted`, ' +
      '`consent_required` or `adult_required`. Final: retrying the same request does not help.',
  },
  404: { name: 'NotFound', description: 'No such route or resource (`not_found`).' },
  409: {
    name: 'Conflict',
    description:
      'Conflicts with the current state (`conflict`, `phone_already_registered`, ' +
      '`account_deletion_required`).',
  },
  410: { name: 'Gone', description: 'The resource existed but has expired or ended (`gone`).' },
  429: { name: 'TooManyRequests', description: 'Rate limit exceeded (`rate_limited`).' },
  500: { name: 'InternalError', description: 'Unexpected server error (`internal_error`).' },
  503: {
    name: 'ServiceUnavailable',
    description:
      'A dependency is unavailable (e.g. `db_unavailable`, `db_not_configured`, ' +
      '`auth_unavailable`, `auth_not_configured`). Retry with backoff.',
  },
} as const;

export type ErrorStatus = keyof typeof ERROR_RESPONSES;

/**
 * A response component that returns ProblemDetails and documents `X-Request-Id` (and, for 401,
 * `WWW-Authenticate`).
 */
export function problemResponseComponent(description: string, status?: ErrorStatus) {
  return {
    description,
    headers: {
      'X-Request-Id': headerRef('RequestId'),
      ...(status === 401 ? { 'WWW-Authenticate': headerRef('WwwAuthenticate') } : {}),
    },
    content: {
      [PROBLEM_MEDIA_TYPE]: { schema: { $ref: '#/components/schemas/ProblemDetails' } },
    },
  };
}

/** Route-level `responses` entries that point at the shared error components. */
export function errorResponses<const S extends ErrorStatus>(...statuses: S[]) {
  return Object.fromEntries(
    statuses.map((status) => [
      status,
      { $ref: `#/components/responses/${ERROR_RESPONSES[status].name}` },
    ]),
  ) as Record<S, { $ref: string }>;
}
