// SPDX-License-Identifier: AGPL-3.0-only
import { doublePrecision, index, pgTable, text, timestamp, uuid } from 'drizzle-orm/pg-core';
import { users } from './users.js';

/**
 * Token buckets for per-route rate limits (Plan v7 §6.2, ADR 0018). One row per bucket, updated
 * in place by src/lib/rate-limit.ts: the table grows with the number of users, not of requests.
 *
 * NO PERSONAL DATA except `user_id`. `key` names the bucket (e.g. `search:burst:<user id>`); it
 * never holds a query, a location, a phone number or an IP address.
 *
 * `user_id` is NULL for buckets shared by everyone (the provider budget). A user's buckets go
 * away with the user (ON DELETE CASCADE). Stale buckets are purged in P020.
 */
export const rateLimitBuckets = pgTable(
  'rate_limit_buckets',
  {
    key: text('key').primaryKey(),
    userId: uuid('user_id').references(() => users.id, { onDelete: 'cascade' }),
    /** Tokens left at `refilled_at`. The refill since then is computed when the bucket is read. */
    tokens: doublePrecision('tokens').notNull(),
    refilledAt: timestamp('refilled_at', { withTimezone: true }).notNull(),
    updatedAt: timestamp('updated_at', { withTimezone: true }).notNull().defaultNow(),
  },
  (t) => [index('rate_limit_buckets_user_id_idx').on(t.userId)],
);
