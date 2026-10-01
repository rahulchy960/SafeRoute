// SPDX-License-Identifier: AGPL-3.0-only
import { afterAll, beforeEach, describe, expect, it } from 'vitest';
import { auditLog, users } from '../../src/db/schema/index.js';
import { runSetRole } from '../../src/scripts/set-role.js';
import { connect, databaseUrl, truncateAll } from './helpers.js';

const { pool, db } = connect();

beforeEach(async () => {
  await truncateAll(pool);
});
afterAll(async () => {
  await pool.end();
});

function capture() {
  const out: string[] = [];
  const err: string[] = [];
  return { io: { out: (l: string) => out.push(l), err: (l: string) => err.push(l) }, out, err };
}

async function createUser() {
  const [user] = await db
    .insert(users)
    .values({
      firebaseUid: 'test-uid-role',
      phoneE164: '+910000000020',
      displayName: 'Sample Name',
    })
    .returning();
  if (!user) throw new Error('insert failed');
  return user;
}

describe('set-role script against the database', () => {
  it('changes the role, writes one audit row and prints only id, roles and masked host', async () => {
    const user = await createUser();
    const { io, out } = capture();
    const code = await runSetRole(
      ['--user-id', user.id, '--role', 'moderator', '--confirm'],
      { DATABASE_URL: databaseUrl() },
      io,
    );
    expect(code).toBe(0);
    expect(out).toHaveLength(1);
    const { hostname, port } = new URL(databaseUrl());
    const masked = `${hostname.slice(0, 3)}***:${port}`;
    expect(out[0]).toBe(`User ${user.id}: role user → moderator (database ${masked}).`);
    for (const secret of [
      '+910000000020',
      'Sample Name',
      'test-uid-role',
      new URL(databaseUrl()).password,
    ]) {
      expect(out.join('')).not.toContain(secret);
    }

    const [after] = await db.select().from(users);
    expect(after?.role).toBe('moderator');
    expect(after?.updatedAt.getTime()).toBeGreaterThanOrEqual(user.updatedAt.getTime());
    const audits = await db.select().from(auditLog);
    expect(audits).toHaveLength(1);
    expect(audits[0]).toMatchObject({
      actorType: 'system',
      actorUserId: null,
      action: 'user.role_changed',
      entity: 'user',
      entityId: user.id,
      metadata: { from: 'user', to: 'moderator' },
    });
  });

  it('setting the same role changes nothing and writes no audit row', async () => {
    const user = await createUser();
    const { io, out } = capture();
    expect(
      await runSetRole(
        ['--user-id', user.id, '--role', 'user', '--confirm'],
        { DATABASE_URL: databaseUrl() },
        io,
      ),
    ).toBe(0);
    expect(out[0]).toContain('already user');
    expect(await db.select().from(auditLog)).toHaveLength(0);
  });

  it('exits 1 for an unknown user and changes nothing', async () => {
    await createUser();
    const { io, err } = capture();
    const code = await runSetRole(
      ['--user-id', '00000000-0000-4000-8000-00000000abcd', '--role', 'admin', '--confirm'],
      { DATABASE_URL: databaseUrl() },
      io,
    );
    expect(code).toBe(1);
    expect(err.join('')).toContain('No user with id');
    const [row] = await db.select().from(users);
    expect(row?.role).toBe('user');
    expect(await db.select().from(auditLog)).toHaveLength(0);
  });
});
