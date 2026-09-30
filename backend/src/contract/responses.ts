// SPDX-License-Identifier: AGPL-3.0-only

/** Media type of every error body (RFC 9457). */
export const PROBLEM_MEDIA_TYPE = 'application/problem+json';

interface HeaderComponent {
  description: string;
  schema: { type: 'string'; enum?: string[] };
}

/** Header components; responses reference them instead of repeating the definitions. */
export const HEADER_COMPONENTS: Record<'RequestId' | 'CacheControlNoStore', HeaderComponent> = {
  RequestId: {
    description:
      'Correlation ID for this request. Echoes a well-formed incoming `X-Request-Id` ' +
      '(8–64 characters of `A–Z a–z 0–9 . _ -`), otherwise a fresh UUID.',
    schema: { type: 'string' },
  },
  CacheControlNoStore: {
    description: 'Always `no-store`: probe results must never be cached.',
    schema: { type: 'string', enum: ['no-store'] },
  },
};

export const headerRef = (name: keyof typeof HEADER_COMPONENTS) => ({
  $ref: `#/components/headers/${name}`,
});

/** Shared error responses, keyed by status code. Routes pick the ones that can really happen. */
export const ERROR_RESPONSES = {
  400: { name: 'BadRequest', description: 'Invalid input (`validation_error`).' },
  401: { name: 'Unauthorized', description: 'Missing or invalid credentials (`unauthorized`).' },
  403: { name: 'Forbidden', description: 'Authenticated but not allowed (`forbidden`).' },
  404: { name: 'NotFound', description: 'No such route or resource (`not_found`).' },
  409: { name: 'Conflict', description: 'Conflicts with the current state (`conflict`).' },
  410: { name: 'Gone', description: 'The resource existed but has expired or ended (`gone`).' },
  429: { name: 'TooManyRequests', description: 'Rate limit exceeded (`rate_limited`).' },
  500: { name: 'InternalError', description: 'Unexpected server error (`internal_error`).' },
  503: {
    name: 'ServiceUnavailable',
    description: 'A dependency is unavailable (e.g. `db_unavailable`, `db_not_configured`).',
  },
} as const;

export type ErrorStatus = keyof typeof ERROR_RESPONSES;

/** A response component that returns ProblemDetails and documents `X-Request-Id`. */
export function problemResponseComponent(description: string) {
  return {
    description,
    headers: { 'X-Request-Id': headerRef('RequestId') },
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
