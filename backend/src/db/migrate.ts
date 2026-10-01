// SPDX-License-Identifier: AGPL-3.0-only
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { drizzle } from 'drizzle-orm/node-postgres';
import { migrate } from 'drizzle-orm/node-postgres/migrator';
import pg from 'pg';
import { ConfigError, parseJobConfig } from '../config.js';
import { createLogger, type Logger } from '../lib/logger.js';
import { safeDbError } from './client.js';

/** backend/drizzle, resolved from both src/db (tsx) and dist/db (node). The API image must ship it. */
export const MIGRATIONS_FOLDER = fileURLToPath(new URL('../../drizzle', import.meta.url));

/**
 * Session-level advisory lock key shared by every migration run. A second runner blocks on
 * pg_advisory_lock until the first finishes, then finds nothing to apply.
 */
const LOCK_SQL = `select pg_advisory_lock(hashtextextended('saferoute:migrations', 0))`;
const UNLOCK_SQL = `select pg_advisory_unlock(hashtextextended('saferoute:migrations', 0))`;

interface JournalEntry {
  tag: string;
  when: number;
}

function readJournal(folder: string): JournalEntry[] {
  const raw = readFileSync(join(folder, 'meta', '_journal.json'), 'utf8');
  return (JSON.parse(raw) as { entries: JournalEntry[] }).entries;
}

/** Timestamps (journal `when`) of applied migrations, as recorded by Drizzle. */
async function appliedTimestamps(client: pg.Client): Promise<Set<number>> {
  const exists = await client.query<{ t: string | null }>(
    `select to_regclass('drizzle.__drizzle_migrations')::text as t`,
  );
  if (exists.rows[0]?.t == null) return new Set();
  const rows = await client.query<{ created_at: string }>(
    'select created_at from drizzle.__drizzle_migrations',
  );
  return new Set(rows.rows.map((row) => Number(row.created_at)));
}

export interface RunMigrationsOptions {
  databaseUrl: string;
  migrationsFolder?: string;
}

/**
 * Applies committed migrations in order and returns the tags that ran this time ([] when already
 * up to date). Drizzle applies all pending files in one transaction, so a failure leaves the
 * database unchanged. Uses one dedicated connection (not the API pool) with no statement timeout.
 */
export async function runMigrations({
  databaseUrl,
  migrationsFolder = MIGRATIONS_FOLDER,
}: RunMigrationsOptions): Promise<string[]> {
  const client = new pg.Client({
    connectionString: databaseUrl,
    options: '-c TimeZone=UTC',
    application_name: 'saferoute-migrate',
  });
  await client.connect();
  try {
    await client.query(LOCK_SQL);
    try {
      const before = await appliedTimestamps(client);
      await migrate(drizzle(client), { migrationsFolder });
      const after = await appliedTimestamps(client);
      return readJournal(migrationsFolder)
        .filter((entry) => after.has(entry.when) && !before.has(entry.when))
        .map((entry) => entry.tag);
    } finally {
      await client.query(UNLOCK_SQL);
    }
  } finally {
    await client.end();
  }
}

/**
 * CLI entry: `pnpm db:migrate` (tsx) or `node dist/db/migrate.js`, the command of the Cloud Run
 * migration job in P006. Never called on API startup. Exit 0 on success, 1 on any failure.
 */
async function main(): Promise<void> {
  let logger: Logger | undefined;
  try {
    // Job config: the migration job gets DATABASE_URL only, not the API's FIREBASE_PROJECT_ID.
    const config = parseJobConfig(process.env);
    logger = createLogger(config);
    if (config.DATABASE_URL === undefined) {
      logger.error('DATABASE_URL is not set; nothing to migrate');
      process.exitCode = 1;
      return;
    }
    const applied = await runMigrations({ databaseUrl: config.DATABASE_URL });
    logger.info(
      { applied, count: applied.length },
      applied.length > 0 ? 'migrations applied' : 'database already up to date',
    );
  } catch (err) {
    if (err instanceof ConfigError) {
      process.stderr.write(`${err.message}\n`);
    } else if (logger) {
      logger.error({ db_error: safeDbError(err) }, 'migration failed');
    } else {
      process.stderr.write('migration failed\n');
    }
    process.exitCode = 1;
  }
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
