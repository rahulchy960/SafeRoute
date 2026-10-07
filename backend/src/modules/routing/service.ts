// SPDX-License-Identifier: AGPL-3.0-only
import { randomUUID } from 'node:crypto';
import type { Logger } from '../../lib/logger.js';
import { AppError } from '../../lib/problem.js';
import type { RateLimiter } from '../../lib/rate-limit.js';
import { distanceMeters, isInsideRoutingExtent } from '../../regions/routing-extent.js';
import { polyline6Bounds, type BoundingBox } from './polyline.js';
import { RoutingError, type RouteQuery, type RoutingMode, type RoutingProvider } from './types.js';

const SECONDS_PER_DAY = 86_400;

/**
 * Route rate limits (Plan v7 §6.2). All numbers live here and in ROUTING_GLOBAL_DAILY_LIMIT
 * (src/config.ts). A request must pass all three buckets, in this order, so a user who is over
 * their own limit never spends the shared budget.
 */
export const ROUTING_LIMITS = {
  /** 10 requests at once, then one every 10 seconds. */
  userBurst: { capacity: 10, refillPerSecond: 0.1 },
  /** 300 requests per user per day. */
  userDaily: { capacity: 300, refillPerSecond: 300 / SECONDS_PER_DAY },
} as const;

/**
 * Longest straight line between origin and destination, per mode (ADR 0020).
 * Walking: beyond 30 km the stock walking profile often finds no route (it does not use trunk
 * roads), and nobody walks it. Driving: longer than any line inside the covered area, so it only
 * bounds nonsense.
 */
export const MAX_STRAIGHT_LINE_METERS: Record<RoutingMode, number> = {
  walking: 30_000,
  driving: 1_000_000,
};

/** Seconds the app should wait before trying again after `routing_unavailable`. */
export const ROUTING_RETRY_AFTER_SECONDS = 10;

export interface RoutingDeps {
  provider: RoutingProvider;
  limiter: RateLimiter;
  /** Route requests per day for everyone together (ROUTING_GLOBAL_DAILY_LIMIT). */
  globalDailyLimit: number;
}

/** A 429 or 503 that tells the client how long to wait; the route turns it into `Retry-After`. */
export class RoutingRetryError extends AppError {
  readonly retryAfterSeconds: number;

  constructor(status: 429 | 503, code: string, detail: string, retryAfterSeconds: number) {
    super(status, code, detail);
    this.name = 'RoutingRetryError';
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

export interface RouteResult {
  id: string;
  distanceMeters: number;
  durationSeconds: number;
  geometry: { encoding: 'polyline6'; value: string };
  bbox: BoundingBox;
}

const UNAVAILABLE = 'Routes are temporarily unavailable. Try again shortly.';

async function enforceLimits({ limiter, globalDailyLimit }: RoutingDeps, userId: string) {
  const { userBurst, userDaily } = ROUTING_LIMITS;
  for (const [name, limit] of [
    ['burst', userBurst],
    ['daily', userDaily],
  ] as const) {
    const decision = await limiter.take(
      `routes:${name}:${userId}`,
      userId,
      limit.capacity,
      limit.refillPerSecond,
    );
    if (!decision.allowed) {
      throw new RoutingRetryError(
        429,
        'rate_limited',
        'Too many route requests. Wait a moment and try again.',
        decision.retryAfterSeconds,
      );
    }
  }
  const global = await limiter.take(
    'routes:global',
    null,
    globalDailyLimit,
    globalDailyLimit / SECONDS_PER_DAY,
  );
  if (!global.allowed) {
    throw new RoutingRetryError(503, 'routing_unavailable', UNAVAILABLE, global.retryAfterSeconds);
  }
}

/**
 * Checks the limits and the covered area, asks the routing engine once and writes one log line.
 *
 * NEVER log, store or cache the coordinates, the geometries or the routes: together they say
 * where a person is and means to go (Plan v7 §12.2). The log line has the outcome, the latency,
 * a count and the mode; `request_id` and `user_id` come from the request logger. The error
 * details never repeat a coordinate.
 */
export async function findRoutes(
  deps: RoutingDeps,
  log: Logger,
  userId: string,
  query: RouteQuery,
): Promise<RouteResult[]> {
  await enforceLimits(deps, userId);

  if (!isInsideRoutingExtent(query.origin) || !isInsideRoutingExtent(query.destination)) {
    // A normal answer for a place we do not cover yet, not a failure (addendum v7.2 §D).
    throw new AppError(422, 'outside_covered_area', "Routes aren't available here yet.");
  }
  if (distanceMeters(query.origin, query.destination) > MAX_STRAIGHT_LINE_METERS[query.mode]) {
    throw new AppError(422, 'route_too_long', 'These two places are too far apart for this mode.');
  }

  const started = performance.now();
  const line = (outcome: string, routeCount: number) => ({
    outcome,
    latency_ms: Math.round(performance.now() - started),
    route_count: routeCount,
    mode: query.mode,
  });
  try {
    const routes = await deps.provider.route(query);
    const results = routes.map((route): RouteResult => {
      const bbox = polyline6Bounds(route.geometry);
      if (bbox === undefined) throw new RoutingError('malformed');
      return {
        id: randomUUID(),
        distanceMeters: route.distanceMeters,
        durationSeconds: route.durationSeconds,
        geometry: { encoding: 'polyline6', value: route.geometry },
        bbox,
      };
    });
    log.info(line('ok', results.length), 'routing call');
    return results;
  } catch (err) {
    if (!(err instanceof RoutingError)) throw err;
    if (err.kind === 'no_route') {
      log.info(line(err.kind, 0), 'routing call');
      throw new AppError(404, 'no_route_found', 'No route was found between these two places.');
    }
    if (err.kind === 'not_routable') {
      log.info(line(err.kind, 0), 'routing call');
      throw new AppError(
        422,
        'location_not_routable',
        'One of the places is not near a road or path. Move the pin or pick another place.',
      );
    }
    if (err.kind === 'auth') {
      // Our service account or its permission is wrong. The user did nothing wrong: 503, never
      // 401 or 403 (which the app would read as "sign in again").
      log.error({ ...line(err.kind, 0), alert: 'routing_auth_failed' }, 'routing call');
    } else {
      // A timeout is expected on the first request after the service scaled to zero.
      log.warn(line(err.kind, 0), 'routing call');
    }
    throw new RoutingRetryError(
      503,
      'routing_unavailable',
      UNAVAILABLE,
      ROUTING_RETRY_AFTER_SECONDS,
    );
  }
}
