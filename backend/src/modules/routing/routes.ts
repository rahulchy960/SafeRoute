// SPDX-License-Identifier: AGPL-3.0-only
import { createRoute, OpenAPIHono } from '@hono/zod-openapi';
import { errorResponses, headerRef } from '../../contract/responses.js';
import { problemResponse } from '../../lib/problem.js';
import { createRateLimiter } from '../../lib/rate-limit.js';
import type { AppEnv } from '../../types.js';
import { requireUser, type AuthDeps } from '../auth/middleware.js';
import { RouteRequestSchema, RoutesSchema } from './schema.js';
import { findRoutes, RoutingRetryError } from './service.js';
import type { RoutingProvider } from './types.js';

export const createRoutesRoute = createRoute({
  method: 'post',
  path: '/v1/routes',
  operationId: 'createRoutes',
  tags: ['routing'],
  summary: 'Find routes between two points',
  description:
    'Returns the fastest route and up to two alternatives for walking or driving. The points ' +
    'travel in the request body, never in the URL, and neither they nor the routes are stored ' +
    'or logged. Calling it again with the same body is safe (it changes nothing), so no ' +
    '`Idempotency-Key` is needed. Routes carry no safety information.\n\n' +
    'Answers that are not failures of the service: `outside_covered_area` (422) when a point ' +
    'lies outside the area routes exist for; `route_too_long` (422) when the points are too ' +
    'far apart for the mode; `location_not_routable` (422) when a point is not near a road or ' +
    'path; `no_route_found` (404) when no way connects them.\n\n' +
    '`routing_unavailable` (503) comes with `Retry-After`. The routing service can be asleep: ' +
    'the first request after a quiet period may get this answer while it starts, and the same ' +
    'request succeeds a little later. The server makes one attempt and does not retry; the ' +
    'client does. `rate_limited` (429) also carries `Retry-After`. `routing_not_configured` ' +
    '(503) means this instance has no routing service.',
  security: [{ firebaseBearer: [] }],
  request: {
    body: { required: true, content: { 'application/json': { schema: RouteRequestSchema } } },
  },
  responses: {
    200: {
      description: 'One to three routes, fastest first.',
      headers: {
        'Cache-Control': headerRef('CacheControlNoStore'),
        'X-Request-Id': headerRef('RequestId'),
      },
      content: { 'application/json': { schema: RoutesSchema } },
    },
    ...errorResponses(400, 401, 403, 404, 422, 429, 500, 503),
  },
});

export interface RoutingRouteDeps extends AuthDeps {
  /** Undefined when the OSRM URLs are not set (dev/test only): the route answers 503. */
  routing: RoutingProvider | undefined;
  globalDailyLimit: number;
}

export function routingRoutes(deps: RoutingRouteDeps) {
  const { db, routing, globalDailyLimit } = deps;
  const limiter = db === undefined ? undefined : createRateLimiter(db);

  return new OpenAPIHono<AppEnv>().openapi(
    { ...createRoutesRoute, middleware: requireUser(deps) },
    async (c) => {
      c.header('Cache-Control', 'no-store');
      const requestId = c.get('requestId');
      if (limiter === undefined) {
        return problemResponse(
          c,
          503,
          'db_not_configured',
          'No database is configured.',
          requestId,
        );
      }
      if (routing === undefined) {
        return problemResponse(
          c,
          503,
          'routing_not_configured',
          'Routing is not configured on this instance.',
          requestId,
        );
      }

      // `departAt` is validated by the schema and not used yet (the exposure metric, P019).
      const { origin, destination, mode } = c.req.valid('json');
      try {
        const routes = await findRoutes(
          { provider: routing, limiter, globalDailyLimit },
          c.get('logger'),
          c.get('currentUser').userId,
          { origin, destination, mode },
        );
        return c.json({ routes, attribution: routing.attribution }, 200);
      } catch (err) {
        if (err instanceof RoutingRetryError) {
          c.header('Retry-After', String(err.retryAfterSeconds));
          return problemResponse(c, err.status, err.code, err.detail, requestId);
        }
        throw err;
      }
    },
  );
}
