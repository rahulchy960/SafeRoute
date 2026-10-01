// SPDX-License-Identifier: AGPL-3.0-only
import { describe, expect, it } from 'vitest';
import { maskDatabaseHost, runSetRole, USAGE } from '../src/scripts/set-role.js';

function capture() {
  const out: string[] = [];
  const err: string[] = [];
  return { io: { out: (l: string) => out.push(l), err: (l: string) => err.push(l) }, out, err };
}

const USER_ID = '00000000-0000-4000-8000-000000000001';

describe('set-role script (no database)', () => {
  it('--help works without DATABASE_URL', async () => {
    const { io, out } = capture();
    expect(await runSetRole(['--help'], {}, io)).toBe(0);
    expect(out).toEqual([USAGE]);
  });

  it('refuses without --confirm and never connects', async () => {
    const { io, err } = capture();
    // An unreachable URL: if the script tried to connect, the test would fail with a DB error.
    const env = { DATABASE_URL: 'postgres://u:secret-pw@203.0.113.1:1/db' };
    expect(await runSetRole(['--user-id', USER_ID, '--role', 'moderator'], env, io)).toBe(2);
    expect(err.join('\n')).toContain('--confirm');
  });

  it.each([
    [['--user-id', 'not-a-uuid', '--role', 'admin', '--confirm']],
    [['--user-id', USER_ID, '--role', 'superuser', '--confirm']],
    [['--role', 'admin', '--confirm']],
    [['--user-id', USER_ID, '--confirm']],
    [['--user-id', USER_ID, '--role', 'admin', '--confirm', '--force']],
  ])('rejects bad arguments %j with exit code 2', async (argv) => {
    const { io, err } = capture();
    expect(await runSetRole(argv, {}, io)).toBe(2);
    expect(err).toEqual([USAGE]);
  });

  it('fails without DATABASE_URL, and never echoes a bad one', async () => {
    for (const env of [{}, { DATABASE_URL: 'mysql://u:secret-pw@db.example/x' }]) {
      const { io, err } = capture();
      expect(
        await runSetRole(['--user-id', USER_ID, '--role', 'admin', '--confirm'], env, io),
      ).toBe(1);
      expect(err.join('\n')).not.toContain('secret-pw');
    }
  });

  it('masks the database host', () => {
    expect(maskDatabaseHost('postgres://u:p@127.0.0.1:5433/db')).toBe('127***:5433');
    expect(maskDatabaseHost('postgres://u:p@db.internal.example/db')).toBe('db.***');
    expect(maskDatabaseHost('postgresql:///db?host=/cloudsql/x')).toBe('(socket)');
  });
});
