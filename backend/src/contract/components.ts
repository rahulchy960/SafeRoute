// SPDX-License-Identifier: AGPL-3.0-only
import { z, type OpenAPIHono } from '@hono/zod-openapi';
import type { AppEnv } from '../types.js';
import { ProblemDetailsSchema } from './problem.js';
import {
  ERROR_RESPONSES,
  HEADER_COMPONENTS,
  problemResponseComponent,
  type ErrorStatus,
} from './responses.js';

/** Same rule as Plan v7 §6.2: 16–64 URL-safe characters, e.g. a UUID. */
export const IDEMPOTENCY_KEY_PATTERN = /^[A-Za-z0-9._-]{16,64}$/;

/**
 * Header for retryable state-changing calls (SOS, share creation, location batches, reports).
 * Declared ahead of use: P015 (POST /v1/sos) is the first route to reference it, then P016 and
 * P017.
 */
export const IdempotencyKeyHeader = z
  .string()
  .min(16)
  .max(64)
  .regex(IDEMPOTENCY_KEY_PATTERN)
  .openapi({
    param: { name: 'Idempotency-Key', in: 'header', required: true },
    description:
      'Client-generated key (e.g. a UUID) for a retryable state-changing call. The server ' +
      'stores key → response for 24 h and replays the stored response for a repeat ' +
      '(Plan v7 §6.2).',
    examples: ['2b1f6c4e-8d3a-4f7b-9c21-5e0a7d4b3c19'],
  });

/**
 * Registers the reusable components on the app's OpenAPI registry, so they appear in the
 * generated spec even before any route references them (hence `no-unused-components` is off in
 * redocly.yaml).
 */
export function registerContractComponents(app: OpenAPIHono<AppEnv>): void {
  const registry = app.openAPIRegistry;

  registry.register('ProblemDetails', ProblemDetailsSchema);
  registry.registerParameter('IdempotencyKey', IdempotencyKeyHeader);

  for (const [name, { description, schema }] of Object.entries(HEADER_COMPONENTS)) {
    registry.registerComponent('headers', name, { description, schema: { ...schema } });
  }
  for (const [status, { name, description }] of Object.entries(ERROR_RESPONSES)) {
    registry.registerComponent(
      'responses',
      name,
      problemResponseComponent(description, Number(status) as ErrorStatus),
    );
  }

  // Referenced by every protected /v1 route (P005, ADR 0006). Authorization is always enforced
  // by server middleware (src/modules/auth/middleware.ts), not by the spec.
  registry.registerComponent('securitySchemes', 'firebaseBearer', {
    type: 'http',
    scheme: 'bearer',
    bearerFormat: 'JWT',
    description:
      'Firebase ID token from phone sign-in, sent as `Authorization: Bearer <token>`. ' +
      '401 → refresh the token and retry once; 503 `auth_unavailable` → retry with backoff.',
  });
}
