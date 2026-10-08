// SPDX-License-Identifier: AGPL-3.0-only
import { randomBytes } from 'node:crypto';
import { drizzle } from 'drizzle-orm/node-postgres';
import pg from 'pg';
import { inject } from 'vitest';
import * as schema from '../../src/db/schema/index.js';

export function databaseUrl(): string {
  return inject('databaseUrl');
}

/** A pool + typed Drizzle client on the shared, migrated test database. */
export function connect() {
  const pool = new pg.Pool({ connectionString: databaseUrl(), max: 4 });
  return { pool, db: drizzle(pool, { schema }) };
}

/** Empties every application table between tests; keeps the schema and migration history. */
export async function truncateAll(pool: pg.Pool): Promise<void> {
  await pool.query(
    'truncate table audit_log, consent_records, contact_optout_tokens, emergency_contacts, idempotency_keys, rate_limit_buckets, devices, users restart identity cascade',
  );
}

/**
 * Creates an empty database in the same container (for migration tests) and returns its URL and
 * a drop function.
 */
export async function createEmptyDatabase(): Promise<{ url: string; drop: () => Promise<void> }> {
  const name = `migrate_${randomBytes(4).toString('hex')}`;
  const admin = new pg.Client({ connectionString: databaseUrl() });
  await admin.connect();
  await admin.query(`create database ${admin.escapeIdentifier(name)}`);
  await admin.end();

  const url = new URL(databaseUrl());
  url.pathname = `/${name}`;
  return {
    url: url.toString(),
    drop: async () => {
      const client = new pg.Client({ connectionString: databaseUrl() });
      await client.connect();
      await client.query(`drop database if exists ${client.escapeIdentifier(name)} with (force)`);
      await client.end();
    },
  };
}
