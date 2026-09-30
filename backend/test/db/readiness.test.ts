// SPDX-License-Identifier: AGPL-3.0-only
import { afterAll, describe, expect, it } from 'vitest';
import { createApp } from '../../src/app.js';
import { parseConfig } from '../../src/config.js';
import { createDb, databaseReadiness } from '../../src/db/client.js';
import { createLogger } from '../../src/lib/logger.js';
import { databaseUrl } from './helpers.js';

describe('GET /health/ready against a real database', () => {
  const lines: string[] = [];
  const config = parseConfig({ NODE_ENV: 'test', DATABASE_URL: databaseUrl() });
  const logger = createLogger(config, { write: (chunk: string) => void lines.push(chunk) });
  const handle = createDb({ ...config, DATABASE_URL: databaseUrl() }, logger);
  const app = createApp({ config, logger, readiness: databaseReadiness(handle.db) });

  afterAll(async () => {
    await handle.close();
  });

  it('returns 200 ready while the database answers', async () => {
    const res = await app.request('/health/ready');
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ status: 'ready', checks: { database: 'ok' } });
  });

  it('opens pooled connections in UTC with the configured statement timeout', async () => {
    const tz = await handle.pool.query<{ tz: string; st: string }>(
      'select current_setting($1) as tz, current_setting($2) as st',
      ['TimeZone', 'statement_timeout'],
    );
    expect(tz.rows[0]).toEqual({ tz: 'UTC', st: '10s' });
  });

  it('returns 503 db_unavailable after the pool is closed, without leaking the URL', async () => {
    await handle.close();
    const res = await app.request('/health/ready');
    expect(res.status).toBe(503);
    const body = await res.text();
    expect(JSON.parse(body)).toMatchObject({ code: 'db_unavailable' });

    const password = new URL(databaseUrl()).password;
    for (const text of [body, lines.join('')]) {
      expect(text).not.toContain(password);
      expect(text).not.toContain(databaseUrl());
    }
  });
});
