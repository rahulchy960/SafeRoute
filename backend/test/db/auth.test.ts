// SPDX-License-Identifier: AGPL-3.0-only
import { eq } from 'drizzle-orm';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { users } from '../../src/db/schema/index.js';
import { requireRole, requireUser } from '../../src/modules/auth/middleware.js';
import {
  createTestKeys,
  phoneClaims,
  signToken,
  TEST_PHONE,
  TEST_UID,
  testVerifier,
  type TestKeys,
} from '../auth/tokens.js';
import { accessLines, buildTestApp } from '../helpers.js';
import { connect, truncateAll } from './helpers.js';

const { pool, db } = connect();
let keys: TestKeys;
let token: string;

beforeAll(async () => {
  keys = await createTestKeys();
  token = await signToken(keys);
});
beforeEach(async () => {
  await truncateAll(pool);
});
afterAll(async () => {
  await pool.end();
});

/**
 * The production app plus test-only routes (no production route uses the middleware until
 * P005b): `/test/me` needs requireUser, `/test/moderation` also requireRole('moderator').
 */
function setup() {
  const built = buildTestApp();
  const deps = { verifier: testVerifier(keys), db };
  built.app.get('/test/me', requireUser(deps), (c) => c.json(c.get('currentUser')));
  built.app.get('/test/moderation', requireUser(deps), requireRole('moderator'), (c) =>
    c.text('ok'),
  );
  return built;
}

const withToken = () => ({ headers: { Authorization: `Bearer ${token}` } });

async function insertUser(values: Partial<typeof users.$inferInsert> = {}) {
  const [user] = await db
    .insert(users)
    .values({ firebaseUid: TEST_UID, phoneE164: TEST_PHONE, ...values })
    .returning();
  if (!user) throw new Error('insert failed');
  return user;
}

describe('requireUser against the database', () => {
  it('no user row → 403 bootstrap_required', async () => {
    const res = await setup().app.request('/test/me', withToken());
    expect(res.status).toBe(403);
    expect(await res.json()).toMatchObject({ code: 'bootstrap_required' });
  });

  it('soft-deleted user → 403 account_deleted', async () => {
    await insertUser({ deletedAt: new Date() });
    const res = await setup().app.request('/test/me', withToken());
    expect(res.status).toBe(403);
    expect(await res.json()).toMatchObject({ code: 'account_deleted' });
  });

  it('attaches userId, role and locale, and adds user_id (never uid or phone) to the logs', async () => {
    const user = await insertUser({ locale: 'bn' });
    const { app, logs, lines } = setup();
    const res = await app.request('/test/me', withToken());
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ userId: user.id, role: 'user', locale: 'bn' });

    const line = accessLines(logs()).at(-1);
    expect(line).toMatchObject({ path: '/test/me', status: 200, user_id: user.id });
    for (const secret of [TEST_UID, TEST_PHONE, token.slice(0, 40)]) {
      expect(lines.join('')).not.toContain(secret);
    }
  });
});

describe('requireRole with database-authoritative roles', () => {
  it('uses users.role, not token claims, and a change applies on the next request', async () => {
    await insertUser();
    // A token that claims admin: must be ignored.
    const claimsAdmin = await signToken(keys, { claims: phoneClaims({ role: 'admin' }) });
    const { app } = setup();
    const call = () =>
      app.request('/test/moderation', { headers: { Authorization: `Bearer ${claimsAdmin}` } });

    const denied = await call();
    expect(denied.status).toBe(403);
    expect(await denied.json()).toMatchObject({ code: 'forbidden' });

    await db.update(users).set({ role: 'moderator' }).where(eq(users.firebaseUid, TEST_UID));
    expect((await call()).status).toBe(200);

    await db.update(users).set({ role: 'admin' }).where(eq(users.firebaseUid, TEST_UID));
    expect((await call()).status).toBe(200);

    // Revocation is immediate: no token refresh needed.
    await db.update(users).set({ role: 'user' }).where(eq(users.firebaseUid, TEST_UID));
    expect((await call()).status).toBe(403);
  });
});
