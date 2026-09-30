import { createRoute, OpenAPIHono, z } from '@hono/zod-openapi';
import type { Config } from '../config.js';
import { errorResponses, headerRef } from '../contract/responses.js';
import type { AppEnv } from '../types.js';

export const HealthSchema = z
  .object({
    status: z.literal('ok'),
    service: z.string().openapi({ examples: ['saferoute-api'] }),
    version: z.string().openapi({
      description: 'Deployed build version of the service (not the contract version).',
      examples: ['0.1.0'],
    }),
    uptimeSeconds: z
      .number()
      .int()
      .min(0)
      .openapi({
        description: 'Seconds since this instance started.',
        examples: [42],
      }),
  })
  .openapi('Health');

export const healthRoute = createRoute({
  method: 'get',
  path: '/health',
  operationId: 'getHealth',
  tags: ['operational'],
  summary: 'Liveness probe',
  description:
    'Answers "is this process up and serving HTTP?". Does no I/O (no database, no provider), ' +
    'so a slow dependency never makes the platform restart a healthy instance.',
  security: [],
  responses: {
    200: {
      description: 'The process is up.',
      headers: {
        'Cache-Control': headerRef('CacheControlNoStore'),
        'X-Request-Id': headerRef('RequestId'),
      },
      content: { 'application/json': { schema: HealthSchema } },
    },
    ...errorResponses(500),
  },
});

/**
 * Liveness: answers "is this process up and serving HTTP?". It must stay fast and must not call
 * the database or any provider, otherwise a slow dependency would make Cloud Run restart healthy
 * instances. Readiness (database check) is GET /health/ready in ready.ts.
 */
export function healthRoutes(config: Pick<Config, 'SERVICE_NAME' | 'APP_VERSION'>) {
  return new OpenAPIHono<AppEnv>().openapi(healthRoute, (c) => {
    c.header('Cache-Control', 'no-store');
    return c.json(
      {
        status: 'ok' as const,
        service: config.SERVICE_NAME,
        version: config.APP_VERSION,
        uptimeSeconds: Math.floor(process.uptime()),
      },
      200,
    );
  });
}
