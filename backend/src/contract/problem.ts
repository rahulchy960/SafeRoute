// SPDX-License-Identifier: AGPL-3.0-only
import { z } from '@hono/zod-openapi';

/**
 * Error codes the API returns today. `code` stays an open string in the contract (not an enum):
 * adding a code is then a non-breaking change, and generated clients keep working when they meet
 * a code they don't know yet. Keep this list in sync with contracts/README.md.
 */
export const PROBLEM_CODES = [
  'validation_error',
  'unauthorized',
  'forbidden',
  'bootstrap_required',
  'account_deleted',
  'consent_required',
  'adult_required',
  'not_found',
  'conflict',
  'phone_already_registered',
  'account_deletion_required',
  'gone',
  'rate_limited',
  'internal_error',
  'db_unavailable',
  'db_not_configured',
  'auth_unavailable',
  'auth_not_configured',
  'search_unavailable',
  'search_not_configured',
  'outside_covered_area',
  'route_too_long',
  'location_not_routable',
  'no_route_found',
  'routing_unavailable',
  'routing_not_configured',
  'http_error',
] as const;

const codeDescription =
  'Stable, machine-readable error code; clients switch on this, not on `title` or `detail`. ' +
  'Open set: clients must tolerate unknown values. Currently defined: ' +
  PROBLEM_CODES.map((code) => `\`${code}\``).join(', ') +
  '.';

export const ValidationIssueSchema = z
  .object({
    path: z.string().openapi({
      description: 'Location of the invalid input, e.g. `body.contacts.0.name` or `query.limit`.',
      examples: ['body.name'],
    }),
    code: z.string().openapi({
      description: 'Why it is invalid (e.g. `invalid_type`, `too_small`). Open set.',
      examples: ['invalid_type'],
    }),
  })
  .openapi('ValidationIssue', {
    description: 'One invalid input. Never contains the submitted value.',
  });

/** RFC 9457 problem details plus the stable `code` (Plan v7 §6.2). */
export const ProblemDetailsSchema = z
  .object({
    type: z.string().openapi({
      description: 'Problem type URI. `about:blank` means `title` is the HTTP status phrase.',
      examples: ['about:blank'],
    }),
    title: z.string().openapi({ examples: ['Not Found'] }),
    status: z
      .number()
      .int()
      .min(400)
      .max(599)
      .openapi({ examples: [404] }),
    detail: z.string().openapi({
      description: 'Human-readable, generic explanation. Never contains personal data.',
      examples: ['No route matches this request.'],
    }),
    code: z.string().openapi({ description: codeDescription, examples: ['not_found'] }),
    requestId: z.string().openapi({
      description: 'Same value as the `X-Request-Id` response header; quote it in bug reports.',
      examples: ['3f2c1b9e-7a44-4c1e-9d2f-0b6a5e8c1d23'],
    }),
    errors: z.array(ValidationIssueSchema).optional().openapi({
      description: 'Present for `validation_error`: one entry per invalid input.',
    }),
  })
  .openapi('ProblemDetails', {
    description: 'Error body for every 4xx/5xx response (media type `application/problem+json`).',
  });

export type ProblemDetails = z.infer<typeof ProblemDetailsSchema>;
export type ValidationIssue = z.infer<typeof ValidationIssueSchema>;
