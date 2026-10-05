// SPDX-License-Identifier: AGPL-3.0-only
import { sql } from 'drizzle-orm';
import { check, pgTable, text, timestamp, uniqueIndex, uuid } from 'drizzle-orm/pg-core';

/**
 * One row per app account (Plan v7 §11). Created on first sign-in (P005).
 * PERSONAL DATA columns are marked below; P020 export/erasure must cover them.
 * `updated_at` is set by application code through Drizzle's `$onUpdate` (no trigger), so updates
 * must go through the Drizzle update builder; raw SQL updates must set it themselves.
 */
export const users = pgTable(
  'users',
  {
    id: uuid('id').primaryKey().defaultRandom(),
    /** PERSONAL DATA: Firebase Auth user id, links the account to its sign-in identity. */
    firebaseUid: text('firebase_uid').notNull().unique(),
    /** PERSONAL DATA: verified phone number in E.164 form (e.g. +91XXXXXXXXXX). */
    phoneE164: text('phone_e164'),
    /** PERSONAL DATA: name the user chose to show to their contacts. */
    displayName: text('display_name'),
    /** UI language: 'en' (English) or 'bn' (Bengali). */
    locale: text('locale').notNull().default('en'),
    /** Authorisation role; the source of truth, read on every request (ADR 0006). */
    role: text('role').notNull().default('user'),
    /**
     * PERSONAL DATA: when the user declared "I am 18 or older" (ADR 0010). No date of birth is
     * collected. NULL for accounts created before the age gate existed.
     */
    adultAttestedAt: timestamp('adult_attested_at', { withTimezone: true }),
    createdAt: timestamp('created_at', { withTimezone: true }).notNull().defaultNow(),
    updatedAt: timestamp('updated_at', { withTimezone: true })
      .notNull()
      .defaultNow()
      // SQL now(), not new Date(): created_at also comes from the database clock, and the app
      // clock can drift from it (seen with Docker Desktop), which would make updated_at < created_at.
      .$onUpdate(() => sql`now()`),
    /** Set when the user deletes their account; hard erasure follows in the P020 purge job. */
    deletedAt: timestamp('deleted_at', { withTimezone: true }),
  },
  (t) => [
    // NULLs are allowed many times; a phone number can belong to only one account.
    uniqueIndex('users_phone_e164_key')
      .on(t.phoneE164)
      .where(sql`${t.phoneE164} is not null`),
    check('users_locale_check', sql`${t.locale} in ('en', 'bn')`),
    check('users_role_check', sql`${t.role} in ('user', 'moderator', 'admin')`),
  ],
);
