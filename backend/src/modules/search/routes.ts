// SPDX-License-Identifier: AGPL-3.0-only
import { createRoute, OpenAPIHono } from '@hono/zod-openapi';
import { errorResponses, headerRef } from '../../contract/responses.js';
import { problemResponse } from '../../lib/problem.js';
import { createRateLimiter } from '../../lib/rate-limit.js';
import { LAUNCH_REGION_CENTER } from '../../regions/defaults.js';
import type { AppEnv } from '../../types.js';
import { requireUser, type AuthDeps } from '../auth/middleware.js';
import { coarsen, normalizeQuery } from './normalize.js';
import { SearchRequestSchema, SearchResultsSchema } from './schema.js';
import { SearchLimitError, searchPlaces } from './service.js';
import type { GeocoderProvider } from './types.js';

export const searchPlacesRoute = createRoute({
  method: 'post',
  path: '/v1/search',
  operationId: 'searchPlaces',
  tags: ['search'],
  summary: 'Search for places by name',
  description:
    'Forwards the text to a geocoding provider and returns matching places, best match first. ' +
    'Results are biased towards `nearLatitude`/`nearLongitude` (or a default area) but not ' +
    'limited to it. The search travels in the request body, never in the URL, and is not ' +
    'stored or logged. Calling it again with the same body is safe (it changes nothing), so ' +
    'no `Idempotency-Key` is needed. Errors: `rate_limited` (429) and ' +
    '`search_unavailable` (503) may carry a `Retry-After` header in seconds; ' +
    '`search_not_configured` (503) means this instance has no geocoding key.',
  security: [{ firebaseBearer: [] }],
  request: {
    body: { required: true, content: { 'application/json': { schema: SearchRequestSchema } } },
  },
  responses: {
    200: {
      description: 'Matching places.',
      headers: {
        'Cache-Control': headerRef('CacheControlNoStore'),
        'X-Request-Id': headerRef('RequestId'),
      },
      content: { 'application/json': { schema: SearchResultsSchema } },
    },
    ...errorResponses(400, 401, 403, 429, 500, 503),
  },
});

export interface SearchRouteDeps extends AuthDeps {
  /** Undefined when GEOCODING_API_KEY is not set (dev/test only): the route answers 503. */
  geocoder: GeocoderProvider | undefined;
  globalDailyLimit: number;
}

export function searchRoutes(deps: SearchRouteDeps) {
  const { db, geocoder, globalDailyLimit } = deps;
  const limiter = db === undefined ? undefined : createRateLimiter(db);

  return new OpenAPIHono<AppEnv>().openapi(
    { ...searchPlacesRoute, middleware: requireUser(deps) },
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
      if (geocoder === undefined) {
        return problemResponse(
          c,
          503,
          'search_not_configured',
          'Search is not configured on this instance.',
          requestId,
        );
      }

      const input = c.req.valid('json');
      // The schema already accepted `q`; normalising again yields the value to send on.
      const query = normalizeQuery(input.q) ?? '';
      const near =
        input.nearLatitude !== undefined && input.nearLongitude !== undefined
          ? { latitude: input.nearLatitude, longitude: input.nearLongitude }
          : LAUNCH_REGION_CENTER;

      try {
        const results = await searchPlaces(
          { geocoder, limiter, globalDailyLimit },
          c.get('logger'),
          c.get('currentUser').userId,
          {
            query,
            nearLatitude: coarsen(near.latitude),
            nearLongitude: coarsen(near.longitude),
            language: input.language,
            limit: input.limit,
          },
        );
        return c.json({ results, attribution: geocoder.attribution }, 200);
      } catch (err) {
        if (err instanceof SearchLimitError) {
          c.header('Retry-After', String(err.retryAfterSeconds));
          return problemResponse(c, err.status, err.code, err.detail, requestId);
        }
        throw err;
      }
    },
  );
}
