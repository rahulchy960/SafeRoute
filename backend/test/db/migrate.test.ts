// SPDX-License-Identifier: AGPL-3.0-only
import pg from 'pg';
import { afterAll, describe, expect, it } from 'vitest';
import { runMigrations } from '../../src/db/migrate.js';
import { createEmptyDatabase, databaseUrl } from './helpers.js';

const ALL = [
  '0000_postgis',
  '0001_identity_support_tables',
  '0002_consent_records_adult_attestation',
];

async function query<T extends pg.QueryResultRow>(url: string, text: string): Promise<T[]> {
  const client = new pg.Client({ connectionString: url });
  await client.connect();
  try {
    return (await client.query<T>(text)).rows;
  } finally {
    await client.end();
  }
}

describe('runMigrations', () => {
  const cleanups: (() => Promise<void>)[] = [];
  afterAll(async () => {
    for (const drop of cleanups) await drop();
  });

  it('applies all migrations to an empty database, then is a no-op', async () => {
    const { url, drop } = await createEmptyDatabase();
    cleanups.push(drop);

    expect(await runMigrations({ databaseUrl: url })).toEqual(ALL);
    expect(await runMigrations({ databaseUrl: url })).toEqual([]);

    const rows = await query<{ n: string }>(
      url,
      'select count(*)::text as n from drizzle.__drizzle_migrations',
    );
    expect(rows[0]?.n).toBe(String(ALL.length));
  });

  it('serialises concurrent runs with the advisory lock (each migration applied once)', async () => {
    const { url, drop } = await createEmptyDatabase();
    cleanups.push(drop);

    const results = await Promise.all([
      runMigrations({ databaseUrl: url }),
      runMigrations({ databaseUrl: url }),
      runMigrations({ databaseUrl: url }),
    ]);
    expect(results.flat().sort()).toEqual(ALL);
    expect(results.filter((applied) => applied.length === 0)).toHaveLength(2);
  });

  it('left the shared test database migrated, with PostGIS installed and UTC sessions', async () => {
    const rows = await query<{ version: string }>(
      databaseUrl(),
      'select postgis_version() as version',
    );
    expect(rows[0]?.version).toMatch(/^3\.4 /);

    const tables = await query<{ tablename: string }>(
      databaseUrl(),
      `select tablename from pg_tables where schemaname = 'public' order by tablename`,
    );
    expect(tables.map((t) => t.tablename)).toEqual([
      'audit_log',
      'consent_records',
      'devices',
      'idempotency_keys',
      'spatial_ref_sys',
      'users',
    ]);
  });
});
