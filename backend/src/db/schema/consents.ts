// SPDX-License-Identifier: AGPL-3.0-only
import { sql } from 'drizzle-orm';
import { check, index, pgTable, text, timestamp, uuid } from 'drizzle-orm/pg-core';
import { users } from './users.js';

/**
 * History of a user's consent decisions, one row per decision (ADR 0010, Plan v7 §12.1).
 *
 * APPEND-ONLY: rows are inserted, never updated. The current state of a purpose is its newest
 * row (`decided_at`), so the history shows what the user agreed to, under which notice version,
 * and when they withdrew.
 *
 * PERSONAL DATA: the whole table describes a person's choices. P020 export must include it and
 * erasure removes it with the user (ON DELETE CASCADE); how long a record must be kept after
 * that is decided in P020 with legal input.
 *
 * `purpose` is an open string checked by shape only. Which purposes exist is decided by the
 * server allowlist (src/modules/consents/purposes.ts), so a new purpose needs no migration.
 */
export const consentRecords = pgTable(
  'consent_records',
  {
    id: uuid('id').primaryKey().defaultRandom(),
    userId: uuid('user_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    purpose: text('purpose').notNull(),
    status: text('status').notNull(),
    /** Version of the notice text the user saw (e.g. `2026-10-v1`). */
    noticeVersion: text('notice_version').notNull(),
    /** Language the notice was shown in. */
    noticeLocale: text('notice_locale').notNull(),
    decidedAt: timestamp('decided_at', { withTimezone: true }).notNull().defaultNow(),
  },
  (t) => [
    // nullsFirst() makes drizzle emit plain `DESC` ordering (PostgreSQL's default for DESC), so
    // `ORDER BY decided_at DESC` matches the index.
    index('consent_records_user_purpose_decided_idx').on(
      t.userId,
      t.purpose,
      t.decidedAt.desc().nullsFirst(),
    ),
    check('consent_records_purpose_check', sql`${t.purpose} ~ '^[a-z][a-z0-9_]{2,40}$'`),
    check('consent_records_status_check', sql`${t.status} in ('granted', 'withdrawn')`),
    check('consent_records_notice_locale_check', sql`${t.noticeLocale} in ('en', 'bn')`),
  ],
);
