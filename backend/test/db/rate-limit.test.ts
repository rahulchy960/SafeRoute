// SPDX-License-Identifier: AGPL-3.0-only
import { eq } from 'drizzle-orm';
import { afterAll, beforeEach, describe, expect, it } from 'vitest';
import { rateLimitBuckets, users } from '../../src/db/schema/index.js';
import { createRateLimiter } from '../../src/lib/rate-limit.js';
import { connect, truncateAll } from './helpers.js';

const { pool, db } = connect();
const limiter = createRateLimiter(db);

beforeEach(async () => {
  await truncateAll(pool);
});
afterAll(async () => {
  await pool.end();
});

async function createUser(n: number): Promise<string> {
  const [row] = await db
    .insert(users)
    .values({ firebaseUid: `test-uid-${String(n)}` })
    .returning();
  if (!row) throw new Error('insert returned no row');
  return row.id;
}

/** Moves a bucket's clock back, as if `seconds` had passed. Uses database time only. */
async function elapse(key: string, seconds: number): Promise<void> {
  await pool.query(
    `update rate_limit_buckets set refilled_at = refilled_at - make_interval(secs => $2) where key = $1`,
    [key, seconds],
  );
}

async function takeMany(key: string, count: number, capacity: number, refill: number) {
  const decisions = [];
  for (let i = 0; i < count; i += 1)
    decisions.push(await limiter.take(key, null, capacity, refill));
  return decisions;
}

describe('token bucket (src/lib/rate-limit.ts)', () => {
  it('allows a burst up to the capacity, then denies with a Retry-After', async () => {
    const decisions = await takeMany('test:burst', 6, 5, 0.1);
    expect(decisions.map((d) => d.allowed)).toEqual([true, true, true, true, true, false]);
    expect(decisions.slice(0, 5).every((d) => d.retryAfterSeconds === 0)).toBe(true);
    // One token at 0.1 per second is 10 s away (a little less by the time we ask).
    expect(decisions[5]?.retryAfterSeconds).toBeGreaterThanOrEqual(9);
    expect(decisions[5]?.retryAfterSeconds).toBeLessThanOrEqual(10);
  });

  it('refills over time and never above the capacity', async () => {
    await takeMany('test:refill', 5, 5, 1);
    expect((await limiter.take('test:refill', null, 5, 1)).allowed).toBe(false);

    await elapse('test:refill', 2);
    const afterTwoSeconds = await takeMany('test:refill', 3, 5, 1);
    expect(afterTwoSeconds.map((d) => d.allowed)).toEqual([true, true, false]);

    await elapse('test:refill', 3600);
    const afterAnHour = await takeMany('test:refill', 6, 5, 1);
    expect(afterAnHour.map((d) => d.allowed)).toEqual([true, true, true, true, true, false]);
  });

  it('keeps one row per key however many requests arrive', async () => {
    await takeMany('test:rows', 20, 3, 0.01);
    const rows = await db.select().from(rateLimitBuckets);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ key: 'test:rows', userId: null });
    expect(rows[0]?.tokens).toBeGreaterThanOrEqual(0);
    expect(rows[0]?.tokens).toBeLessThan(1);
  });

  it('parallel requests never take more than the capacity', async () => {
    const decisions = await Promise.all(
      Array.from({ length: 40 }, () => limiter.take('test:parallel', null, 10, 0.001)),
    );
    expect(decisions.filter((d) => d.allowed)).toHaveLength(10);
    expect(await db.select().from(rateLimitBuckets)).toHaveLength(1);
  });

  it('parallel requests on a bucket that already exists never exceed what is left', async () => {
    await takeMany('test:parallel-existing', 6, 10, 0.001);
    const decisions = await Promise.all(
      Array.from({ length: 30 }, () => limiter.take('test:parallel-existing', null, 10, 0.001)),
    );
    expect(decisions.filter((d) => d.allowed)).toHaveLength(4);
  });

  it('isolates keys from each other', async () => {
    await takeMany('test:a', 2, 2, 0.001);
    expect((await limiter.take('test:a', null, 2, 0.001)).allowed).toBe(false);
    expect((await limiter.take('test:b', null, 2, 0.001)).allowed).toBe(true);
  });

  it('a daily bucket lets one more through after a day divided by the capacity', async () => {
    const perSecond = 4 / 86_400;
    await takeMany('test:daily', 4, 4, perSecond);
    const denied = await limiter.take('test:daily', null, 4, perSecond);
    expect(denied.allowed).toBe(false);
    // 86 400 s / 4 tokens = 21 600 s for one token.
    expect(denied.retryAfterSeconds).toBeGreaterThan(21_590);
    expect(denied.retryAfterSeconds).toBeLessThanOrEqual(21_600);

    await elapse('test:daily', 21_600);
    expect((await limiter.take('test:daily', null, 4, perSecond)).allowed).toBe(true);
    expect((await limiter.take('test:daily', null, 4, perSecond)).allowed).toBe(false);
  });

  it('uses the database clock, so a clock set in the future does not refill early', async () => {
    await takeMany('test:clock', 2, 2, 1);
    // A statement that waited for a lock can see a `refilled_at` later than its own now().
    await elapse('test:clock', -30);
    expect((await limiter.take('test:clock', null, 2, 1)).allowed).toBe(false);
  });

  it("stores the user id and removes a user's buckets with the user", async () => {
    const alice = await createUser(1);
    const bob = await createUser(2);
    await limiter.take(`test:user:${alice}`, alice, 5, 1);
    await limiter.take(`test:user:${bob}`, bob, 5, 1);
    await limiter.take('test:shared', null, 5, 1);

    await db.delete(users).where(eq(users.id, alice));
    const left = await db.select().from(rateLimitBuckets);
    expect(left.map((row) => row.key).sort()).toEqual(['test:shared', `test:user:${bob}`].sort());
  });

  it('rejects a bucket that could never be used', async () => {
    await expect(limiter.take('test:bad', null, 0, 1)).rejects.toThrow(RangeError);
    await expect(limiter.take('test:bad', null, 5, 0)).rejects.toThrow(RangeError);
    expect(await db.select().from(rateLimitBuckets)).toHaveLength(0);
  });

  it('has the user_id index and a table comment that says "no personal data"', async () => {
    const index = await pool.query<{ indexdef: string }>(
      `select indexdef from pg_indexes where indexname = 'rate_limit_buckets_user_id_idx'`,
    );
    expect(index.rows[0]?.indexdef).toContain('(user_id)');
    const comment = await pool.query<{ comment: string }>(
      `select obj_description('rate_limit_buckets'::regclass, 'pg_class') as comment`,
    );
    expect(comment.rows[0]?.comment).toContain('No personal data except user_id');
  });
});
