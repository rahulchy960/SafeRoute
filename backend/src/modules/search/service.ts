// SPDX-License-Identifier: AGPL-3.0-only
import type { Logger } from '../../lib/logger.js';
import { AppError } from '../../lib/problem.js';
import type { RateLimiter } from '../../lib/rate-limit.js';
import {
  searchLocalFirst,
  WidePassRefused,
  type LocalFirstOptions,
  type LocalFirstOutcome,
} from './local-first.js';
import {
  GeocoderError,
  type GeocoderProvider,
  type GeocoderQuery,
  type PlaceResult,
} from './types.js';

const SECONDS_PER_DAY = 86_400;

/**
 * Search rate limits (Plan v7 §6.2, ADR 0018). All numbers live here and in
 * SEARCH_GLOBAL_DAILY_LIMIT (src/config.ts); change them in one of those two places and nowhere
 * else. A request must pass all three buckets, in this order, so a user who is over their own
 * limit never spends the shared budget.
 */
export const SEARCH_LIMITS = {
  /** Typing: 30 searches at once, then one per second. */
  userBurst: { capacity: 30, refillPerSecond: 1 },
  /** 1000 searches per user per day. */
  userDaily: { capacity: 1000, refillPerSecond: 1000 / SECONDS_PER_DAY },
} as const;

export interface SearchDeps {
  geocoder: GeocoderProvider;
  limiter: RateLimiter;
  /** Provider calls per day for everyone together (SEARCH_GLOBAL_DAILY_LIMIT). */
  globalDailyLimit: number;
  /** Set when the app sent an area: nearby places first, then a wide pass if needed. */
  localFirst: LocalFirstOptions | undefined;
}

/** Thrown for 429 and for 503 with a wait; the route turns `retryAfterSeconds` into a header. */
export class SearchLimitError extends AppError {
  readonly retryAfterSeconds: number;

  constructor(status: 429 | 503, code: string, detail: string, retryAfterSeconds: number) {
    super(status, code, detail);
    this.name = 'SearchLimitError';
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

const UNAVAILABLE = 'Search is temporarily unavailable. Try again shortly.';

async function enforceLimits({ limiter, globalDailyLimit }: SearchDeps, userId: string) {
  const { userBurst, userDaily } = SEARCH_LIMITS;
  for (const [name, limit] of [
    ['burst', userBurst],
    ['daily', userDaily],
  ] as const) {
    const decision = await limiter.take(
      `search:${name}:${userId}`,
      userId,
      limit.capacity,
      limit.refillPerSecond,
    );
    if (!decision.allowed) {
      throw new SearchLimitError(
        429,
        'rate_limited',
        'Too many searches. Wait a moment and try again.',
        decision.retryAfterSeconds,
      );
    }
  }
  // One hot row for every instance: fine at pilot volume, revisit at Stage 1 (ADR 0018).
  const global = await limiter.take(
    'search:global',
    null,
    globalDailyLimit,
    globalDailyLimit / SECONDS_PER_DAY,
  );
  if (!global.allowed) {
    throw new SearchLimitError(503, 'search_unavailable', UNAVAILABLE, global.retryAfterSeconds);
  }
}

/**
 * Checks the limits, calls the provider and writes one log line.
 *
 * With `deps.localFirst` there can be two provider calls (ADR 0018, "Local ranking"). EACH call
 * spends the user's limits and the shared daily budget, so a search can cost two of each. When
 * a limit refuses the second call, the nearby results are returned without it.
 *
 * NEVER log the query, the coordinates, the distances or the results: together they say where a
 * person is or means to go (Plan v7 §12.2). The log line has the outcome, the latency and
 * counts, nothing else; `request_id` and `user_id` come from the request logger.
 */
export async function searchPlaces(
  deps: SearchDeps,
  log: Logger,
  userId: string,
  query: GeocoderQuery,
): Promise<PlaceResult[]> {
  await enforceLimits(deps, userId);

  const started = performance.now();
  const latency = () => Math.round(performance.now() - started);
  try {
    const found: LocalFirstOutcome =
      deps.localFirst === undefined
        ? {
            results: await deps.geocoder.search(query),
            localCount: 0,
            providerCalls: 1,
            widePass: 'off',
          }
        : await searchLocalFirst(deps.geocoder, query, deps.localFirst, async () => {
            try {
              await enforceLimits(deps, userId);
            } catch (err) {
              throw err instanceof SearchLimitError ? new WidePassRefused(err) : err;
            }
          });
    log.info(
      {
        outcome: 'ok',
        latency_ms: latency(),
        result_count: found.results.length,
        local_count: found.localCount,
        provider_calls: found.providerCalls,
        wide_pass: found.widePass,
      },
      'geocoder call',
    );
    return found.results;
  } catch (err) {
    if (!(err instanceof GeocoderError)) throw err;
    const fields = { outcome: err.kind, latency_ms: latency(), result_count: 0 };
    if (err.kind === 'auth') {
      // Our key is wrong, inactive or over plan. The user did nothing wrong, so they get a 503,
      // never a 401 or 403 (which the app would read as "sign in again").
      log.error({ ...fields, alert: 'geocoder_key_rejected' }, 'geocoder call');
    } else {
      log.warn(fields, 'geocoder call');
    }
    if (err.kind === 'rate_limited') {
      throw new SearchLimitError(
        503,
        'search_unavailable',
        UNAVAILABLE,
        err.retryAfterSeconds ?? 60,
      );
    }
    throw new AppError(503, 'search_unavailable', UNAVAILABLE);
  }
}
