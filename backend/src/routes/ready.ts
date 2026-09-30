// SPDX-License-Identifier: AGPL-3.0-only
import { createRoute, OpenAPIHono, z } from '@hono/zod-openapi';
import { errorResponses, headerRef } from '../contract/responses.js';
import { safeDbError } from '../db/client.js';
import { problemResponse } from '../lib/problem.js';
import type { AppEnv } from '../types.js';

/** Resolves when the database answers; rejects otherwise. Injected so unit tests need no DB. */
export type ReadinessCheck = () => Promise<void>;

export const READINESS_TIMEOUT_MS = 2_000;

class ReadinessTimeoutError extends Error {
  constructor() {
    super(`readiness check exceeded ${String(READINESS_TIMEOUT_MS)} ms`);
    this.name = 'ReadinessTimeoutError';
  }
}

async function withTimeout(check: ReadinessCheck, timeoutMs: number): Promise<void> {
  let timer: NodeJS.Timeout | undefined;
  const pending = check();
  // If the timeout wins, the check may still reject later; swallow that so it is not unhandled.
  pending.catch(() => undefined);
  try {
    await Promise.race([
      pending,
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => {
          reject(new ReadinessTimeoutError());
        }, timeoutMs);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

export const ReadinessSchema = z
  .object({
    status: z.literal('ready'),
    checks: z.object({ database: z.literal('ok') }).openapi({
      description: 'One entry per dependency this instance needs to serve traffic.',
    }),
  })
  .openapi('Readiness');

export const readinessRoute = createRoute({
  method: 'get',
  path: '/health/ready',
  operationId: 'getReadiness',
  tags: ['operational'],
  summary: 'Readiness probe',
  description:
    'Answers "can this instance serve traffic that needs the database?". Returns 503 ' +
    '(`db_unavailable` or `db_not_configured`) when it cannot. Never includes connection details.',
  security: [],
  responses: {
    200: {
      description: 'The instance and its database are ready.',
      headers: {
        'Cache-Control': headerRef('CacheControlNoStore'),
        'X-Request-Id': headerRef('RequestId'),
      },
      content: { 'application/json': { schema: ReadinessSchema } },
    },
    ...errorResponses(500, 503),
  },
});

/**
 * Readiness: "can this instance serve traffic that needs the database?". Separate from GET
 * /health (liveness, no I/O) so a database outage never makes Cloud Run restart healthy
 * instances. Responses never include connection details.
 */
export function readyRoutes(check: ReadinessCheck | undefined, timeoutMs = READINESS_TIMEOUT_MS) {
  return new OpenAPIHono<AppEnv>().openapi(readinessRoute, async (c) => {
    c.header('Cache-Control', 'no-store');
    const id = c.get('requestId');

    if (check === undefined) {
      return problemResponse(
        c,
        503,
        'db_not_configured',
        'No database is configured for this instance.',
        id,
      );
    }

    try {
      await withTimeout(check, timeoutMs);
    } catch (err) {
      c.get('logger').warn(
        { db_error: safeDbError(err), timeout_ms: timeoutMs },
        'readiness check failed',
      );
      return problemResponse(c, 503, 'db_unavailable', 'The database is not reachable.', id);
    }
    return c.json({ status: 'ready' as const, checks: { database: 'ok' as const } }, 200);
  });
}
