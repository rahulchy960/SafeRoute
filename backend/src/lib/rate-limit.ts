// SPDX-License-Identifier: AGPL-3.0-only
import { sql } from 'drizzle-orm';
import type { Db } from '../db/client.js';

export interface RateLimitDecision {
  allowed: boolean;
  /** Whole seconds until one token is available again; 0 when allowed. */
  retryAfterSeconds: number;
}

export interface RateLimiter {
  /**
   * Takes one token from the bucket `key`, creating it full on first use.
   * `userId` is stored for the cascade on account deletion; pass null for a shared bucket.
   */
  take(
    key: string,
    userId: string | null,
    capacity: number,
    refillPerSecond: number,
  ): Promise<RateLimitDecision>;
}

/**
 * Token bucket in PostgreSQL (Plan v7 §6.2, ADR 0018). A bucket holds up to `capacity` tokens and
 * gains `refillPerSecond` tokens per second; each request takes one.
 *
 * One statement decides and updates, so it is correct with parallel requests and with any number
 * of API instances:
 * - INSERT creates the bucket with `capacity - 1` tokens;
 * - on conflict the row is locked, the refill since `refilled_at` is added (capped at capacity),
 *   and one token is taken ONLY IF at least one is there (`WHERE` on the DO UPDATE);
 * - a row comes back only when a token was taken. No row means "denied", and nothing was written.
 *
 * Time is the database's `now()`, never the app's clock: instances may disagree with each other,
 * the database does not disagree with itself. A statement that waited for the row lock can have an
 * older `now()` than the stored `refilled_at`; `greatest(...)` keeps time from running backwards.
 */
export function createRateLimiter(db: Db): RateLimiter {
  return {
    async take(key, userId, capacity, refillPerSecond) {
      if (!(capacity >= 1) || !(refillPerSecond > 0)) {
        throw new RangeError('rate limit capacity must be >= 1 and refill must be > 0');
      }
      // Tokens the bucket holds right now: stored tokens plus the refill since then.
      const available = sql`least(${capacity}::double precision,
        b.tokens + greatest(0, extract(epoch from (now() - b.refilled_at))) * ${refillPerSecond}::double precision)`;

      const taken = await db.execute(sql`
        insert into rate_limit_buckets as b (key, user_id, tokens, refilled_at, updated_at)
        values (${key}, ${userId}, ${capacity}::double precision - 1, now(), now())
        on conflict (key) do update
          set tokens = ${available} - 1,
              refilled_at = greatest(b.refilled_at, now()),
              updated_at = now()
          where ${available} >= 1
        returning b.key`);
      if (taken.rows.length > 0) return { allowed: true, retryAfterSeconds: 0 };

      // Denied. Read how long until one token is back; informational, so no lock is needed.
      const waiting = await db.execute<{ wait: number | null }>(sql`
        select (1 - ${available}) / ${refillPerSecond}::double precision as wait
          from rate_limit_buckets b where b.key = ${key}`);
      const wait = waiting.rows[0]?.wait ?? 1;
      return { allowed: false, retryAfterSeconds: Math.max(1, Math.ceil(wait)) };
    },
  };
}
