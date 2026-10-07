// SPDX-License-Identifier: AGPL-3.0-only
import pg from 'pg';
import { describe, expect, it } from 'vitest';
import { ConfigError, parseConfig, parsePostgresUrl } from '../src/config.js';
import { poolConfig, safeDbError } from '../src/db/client.js';
import { createLogger } from '../src/lib/logger.js';
import { maskDatabaseHost } from '../src/scripts/set-role.js';

/**
 * P006 R0: on Cloud Run the database is reached through the Cloud SQL connector's unix socket
 * (ADR 0007), so DATABASE_URL has no host in the authority and names the socket directory in the
 * `host` query parameter. All values here are fake.
 */
const SOCKET_DIR = '/cloudsql/demo:asia-south1:db';
const SOCKET_URL = `postgresql://u:p%40ss@/db?host=${SOCKET_DIR}`;
const PASSWORD_FORMS = ['p%40ss', 'p@ss'];

function expectNoPassword(text: string): void {
  for (const form of PASSWORD_FORMS) expect(text).not.toContain(form);
}

describe('Cloud SQL unix-socket DATABASE_URL', () => {
  it('is accepted by the config, in production too', () => {
    expect(parseConfig({ DATABASE_URL: SOCKET_URL }).DATABASE_URL).toBe(SOCKET_URL);
    expect(
      parseConfig({
        NODE_ENV: 'production',
        DATABASE_URL: SOCKET_URL,
        FIREBASE_PROJECT_ID: 'example-staging-1',
        GEOCODING_API_KEY: 'fake-geocoding-key-for-tests',
        GEOCODING_PROVIDER: 'geoapify',
        OSRM_WALKING_URL: 'https://osrm-walking.example.invalid',
        OSRM_DRIVING_URL: 'https://osrm-driving.example.invalid',
      }).DATABASE_URL,
    ).toBe(SOCKET_URL);
  });

  it('reaches node-postgres as a socket host with the decoded password', () => {
    const config = parseConfig({ DATABASE_URL: SOCKET_URL });
    const options = poolConfig({ ...config, DATABASE_URL: SOCKET_URL });
    // Constructing a client parses the connection string; nothing connects until connect().
    const client = new pg.Client(options);
    expect(client.host).toBe(SOCKET_DIR);
    expect(client.user).toBe('u');
    expect(client.database).toBe('db');
    expect(client.password).toBe('p@ss');
  });

  it('reports the host as a socket and never as the placeholder', () => {
    expect(parsePostgresUrl(SOCKET_URL)).toEqual({ hostname: '', port: '' });
    expect(parsePostgresUrl('postgres://u:p@127.0.0.1:5433/db')).toEqual({
      hostname: '127.0.0.1',
      port: '5433',
    });
    expect(maskDatabaseHost(SOCKET_URL)).toBe('(socket)');
  });

  it.each([
    ['no host parameter', 'postgresql://u:p%40ss@/db'],
    ['a relative host parameter', 'postgresql://u:p%40ss@/db?host=cloudsql/demo'],
    ['another scheme', `mysql://u:p%40ss@/db?host=${SOCKET_DIR}`],
  ])('rejects a host-less URL with %s, without echoing it', (_name, value) => {
    expect(parsePostgresUrl(value)).toBeUndefined();
    let error: unknown;
    try {
      parseConfig({ DATABASE_URL: value });
    } catch (err) {
      error = err;
    }
    expect(error).toBeInstanceOf(ConfigError);
    const message = (error as ConfigError).message;
    expect(message).toBe(
      'Invalid configuration:\n  - DATABASE_URL: must be a postgres:// or postgresql:// URL',
    );
    expectNoPassword(message);
  });

  it('keeps the password out of the error and the log when the socket is unreachable', async () => {
    const lines: string[] = [];
    const config = parseConfig({ DATABASE_URL: SOCKET_URL, DB_CONNECT_TIMEOUT_MS: '2000' });
    const logger = createLogger(config, { write: (line) => lines.push(line) });
    // The socket directory does not exist on this machine, so connecting fails locally (no network).
    const client = new pg.Client(poolConfig({ ...config, DATABASE_URL: SOCKET_URL }));
    client.on('error', () => undefined);

    const err: unknown = await client.connect().then(
      () => undefined,
      (reason: unknown) => reason,
    );
    await client.end().catch(() => undefined);

    expect(err).toBeInstanceOf(Error);
    logger.error({ db_error: safeDbError(err) }, 'database unreachable');
    expect(lines).toHaveLength(1);
    expectNoPassword(lines.join('\n'));
    expectNoPassword(JSON.stringify(safeDbError(err)));
    expectNoPassword((err as Error).message);
    expectNoPassword((err as Error).stack ?? '');
  });
});
