// SPDX-License-Identifier: AGPL-3.0-only
import { sql } from 'drizzle-orm';
import {
  check,
  customType,
  index,
  pgTable,
  text,
  timestamp,
  unique,
  uuid,
} from 'drizzle-orm/pg-core';
import { users } from './users.js';

/** PostgreSQL `bytea`, read and written as a Node Buffer. */
const bytea = customType<{ data: Buffer; driverData: Buffer }>({
  dataType: () => 'bytea',
});

/**
 * A user's emergency contacts (Plan v7 §11, ADR 0024). At most 5 rows without `deleted_at` per
 * user; the limit is enforced in src/modules/contacts/service.ts under a lock on the user's row.
 *
 * PERSONAL DATA OF PEOPLE WHO ARE NOT USERS: `name` and `phone_e164`. P020 export must include
 * them and erasure removes them with the account (ON DELETE CASCADE). They never go into logs,
 * errors or `audit_log.metadata`.
 *
 * `opted_out_at` is set by the contact through the public opt-out page; such a contact must never
 * be alerted. Deleting an opted-out contact keeps the row with `deleted_at` set (a tombstone), so
 * the same number cannot be added again by this user. Every other delete is a hard delete.
 *
 * `has_app_user_id` stays NULL and is never exposed: whether a number belongs to a registered
 * user is decided in P015.
 */
export const emergencyContacts = pgTable(
  'emergency_contacts',
  {
    id: uuid('id').primaryKey().defaultRandom(),
    userId: uuid('user_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    /** PERSONAL DATA: the name the user gave this contact. */
    name: text('name').notNull(),
    /** PERSONAL DATA: the contact's phone number in E.164 form. */
    phoneE164: text('phone_e164').notNull(),
    hasAppUserId: uuid('has_app_user_id').references(() => users.id, { onDelete: 'set null' }),
    /** When the user confirmed that the invite SMS was sent from their own phone. */
    invitedAt: timestamp('invited_at', { withTimezone: true }),
    optedOutAt: timestamp('opted_out_at', { withTimezone: true }),
    deletedAt: timestamp('deleted_at', { withTimezone: true }),
    createdAt: timestamp('created_at', { withTimezone: true }).notNull().defaultNow(),
    updatedAt: timestamp('updated_at', { withTimezone: true })
      .notNull()
      .defaultNow()
      .$onUpdate(() => sql`now()`),
  },
  (t) => [
    unique('emergency_contacts_user_phone_key').on(t.userId, t.phoneE164),
    check('emergency_contacts_name_check', sql`char_length(${t.name}) between 1 and 80`),
    check('emergency_contacts_phone_e164_check', sql`${t.phoneE164} ~ '^\\+[1-9][0-9]{6,14}$'`),
  ],
);

/**
 * Opt-out links of a contact (ADR 0024). Only the SHA-256 of a token is stored: the plaintext is
 * returned once by POST /v1/contacts/{id}/invite and exists afterwards only in the SMS the user
 * sent. A contact can have several valid tokens (one per invite). Rows go away with the contact.
 */
export const contactOptoutTokens = pgTable(
  'contact_optout_tokens',
  {
    tokenHash: bytea('token_hash').primaryKey(),
    contactId: uuid('contact_id')
      .notNull()
      .references(() => emergencyContacts.id, { onDelete: 'cascade' }),
    createdAt: timestamp('created_at', { withTimezone: true }).notNull().defaultNow(),
  },
  (t) => [index('contact_optout_tokens_contact_id_idx').on(t.contactId)],
);
