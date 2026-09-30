// SPDX-License-Identifier: AGPL-3.0-only
import {
  index,
  jsonb,
  pgTable,
  primaryKey,
  smallint,
  text,
  timestamp,
  uuid,
} from 'drizzle-orm/pg-core';
import { users } from './users.js';

/**
 * Stored responses for retried, state-changing calls that send an Idempotency-Key header
 * (Plan v7 §6.2: SOS, reports, share creation, location batches). Kept for 24 h (`expires_at`);
 * a purge job arrives in P020.
 *
 * `response_status` and `response_body` go beyond Plan v7 §11 on purpose: a hash alone can
 * detect a reused key with a different request but cannot replay the original response (ADR 0003).
 */
export const idempotencyKeys = pgTable(
  'idempotency_keys',
  {
    userId: uuid('user_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    /** Client-chosen key from the Idempotency-Key header. */
    key: text('key').notNull(),
    /** Route template, e.g. `POST /v1/sos`, so the same key on different routes never collides. */
    route: text('route').notNull(),
    /** Hash of the original request, to reject the same key reused with a different body. */
    responseHash: text('response_hash').notNull(),
    responseStatus: smallint('response_status').notNull(),
    responseBody: jsonb('response_body'),
    expiresAt: timestamp('expires_at', { withTimezone: true }).notNull(),
    createdAt: timestamp('created_at', { withTimezone: true }).notNull().defaultNow(),
  },
  (t) => [
    primaryKey({ name: 'idempotency_keys_pkey', columns: [t.userId, t.route, t.key] }),
    index('idempotency_keys_expires_at_idx').on(t.expiresAt),
  ],
);
