// SPDX-License-Identifier: AGPL-3.0-only
import { sql } from 'drizzle-orm';
import { drizzle, type NodePgDatabase } from 'drizzle-orm/node-postgres';
import pg from 'pg';
import type { Config } from '../config.js';
import type { Logger } from '../lib/logger.js';
import * as schema from './schema/index.js';

export type Db = NodePgDatabase<typeof schema>;

export type DbConfig = Pick<
  Config,
  'SERVICE_NAME' | 'DB_POOL_MAX' | 'DB_STATEMENT_TIMEOUT_MS' | 'DB_CONNECT_TIMEOUT_MS'
> & { DATABASE_URL: string };

export interface DbHandle {
  db: Db;
  pool: pg.Pool;
  /** Ends the pool once; later calls are no-ops. Call after the HTTP server has drained. */
  close: () => Promise<void>;
}

/**
 * Connection settings sent to Postgres when each pooled connection opens:
 * - `TimeZone=UTC`: `now()` and timestamptz text output are in UTC. Asia/Kolkata conversion
 *   happens only when data is read or exported (ADR 0003).
 * - `statement_timeout`: a stuck query is cancelled by the server instead of holding a
 *   connection from this small pool indefinitely.
 */
export function poolConfig(config: DbConfig): pg.PoolConfig {
  return {
    connectionString: config.DATABASE_URL,
    max: config.DB_POOL_MAX,
    connectionTimeoutMillis: config.DB_CONNECT_TIMEOUT_MS,
    statement_timeout: config.DB_STATEMENT_TIMEOUT_MS,
    options: '-c TimeZone=UTC',
    application_name: config.SERVICE_NAME,
  };
}

/**
 * Error fields that are safe to log. pg and URL errors can carry the connection string (e.g. a
 * TypeError's `input`), which contains the password, so only name, code and a scrubbed message
 * are kept.
 */
export function safeDbError(err: unknown): { name: string; code?: string; message: string } {
  if (!(err instanceof Error)) return { name: 'UnknownError', message: 'non-Error value thrown' };
  const code = (err as { code?: unknown }).code;
  return {
    name: err.name,
    ...(typeof code === 'string' ? { code } : {}),
    message: err.message.replace(/postgres(?:ql)?:\/\/\S+/gi, '<database-url>'),
  };
}

export function createDb(config: DbConfig, logger: Logger): DbHandle {
  const pool = new pg.Pool(poolConfig(config));
  // Idle connections can fail (database restart, network drop). Without this listener pg would
  // emit an unhandled 'error' event and crash the process; the pool replaces the connection.
  pool.on('error', (err) => {
    logger.error({ db_error: safeDbError(err) }, 'database pool error');
  });

  let closed = false;
  return {
    db: drizzle(pool, { schema }),
    pool,
    close: async () => {
      if (closed) return;
      closed = true;
      await pool.end();
    },
  };
}

/** Readiness probe for GET /health/ready: one round trip, no table access. */
export function databaseReadiness(db: Db): () => Promise<void> {
  return async () => {
    await db.execute(sql`select 1`);
  };
}
