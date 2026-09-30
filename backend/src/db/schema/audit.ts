// SPDX-License-Identifier: AGPL-3.0-only
import { sql } from 'drizzle-orm';
import { bigint, check, index, jsonb, pgTable, text, timestamp, uuid } from 'drizzle-orm/pg-core';

/**
 * Append-only record of security- and moderation-relevant actions (Plan v7 §11).
 *
 * RULE (also a table comment in migration 0001): `metadata` must never contain precise locations,
 * tokens, phone numbers or message text (Plan v7 §11, §12.2).
 * `actor_user_id` has no foreign key on purpose: audit rows must survive account deletion.
 */
export const auditLog = pgTable(
  'audit_log',
  {
    id: bigint('id', { mode: 'number' }).primaryKey().generatedAlwaysAsIdentity(),
    actorUserId: uuid('actor_user_id'),
    actorType: text('actor_type').notNull(),
    /** Verb in snake_case, e.g. `report_approved`, `account_deleted`. */
    action: text('action').notNull(),
    /** Affected table or domain object, e.g. `report`, `user`. */
    entity: text('entity').notNull(),
    entityId: text('entity_id'),
    metadata: jsonb('metadata').$type<Record<string, unknown>>().notNull().default({}),
    at: timestamp('at', { withTimezone: true }).notNull().defaultNow(),
  },
  (t) => [
    index('audit_log_entity_idx').on(t.entity, t.entityId),
    index('audit_log_at_idx').on(t.at),
    check(
      'audit_log_actor_type_check',
      sql`${t.actorType} in ('user', 'moderator', 'admin', 'system')`,
    ),
  ],
);
