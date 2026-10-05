// SPDX-License-Identifier: AGPL-3.0-only
import { eq } from 'drizzle-orm';
import { afterAll, beforeEach, describe, expect, it } from 'vitest';
import {
  auditLog,
  consentRecords,
  devices,
  idempotencyKeys,
  users,
} from '../../src/db/schema/index.js';
import { connect, truncateAll } from './helpers.js';

// Obviously fake test data only.
const { pool, db } = connect();

/** Postgres SQLSTATE of a failed query (Drizzle wraps driver errors in `cause`). */
async function sqlState(promise: Promise<unknown>): Promise<string | undefined> {
  try {
    await promise;
  } catch (err) {
    const cause = (err as { cause?: { code?: string }; code?: string }).cause ?? err;
    return (cause as { code?: string }).code;
  }
  return undefined;
}

const UNIQUE_VIOLATION = '23505';
const FK_VIOLATION = '23503';
const CHECK_VIOLATION = '23514';

async function createUser(firebaseUid: string, phoneE164: string | null = null) {
  const [row] = await db.insert(users).values({ firebaseUid, phoneE164 }).returning();
  if (!row) throw new Error('insert returned no row');
  return row;
}

beforeEach(async () => {
  await truncateAll(pool);
});
afterAll(async () => {
  await pool.end();
});

describe('users', () => {
  it('applies defaults: uuid id, locale en, role user, timestamps', async () => {
    const user = await createUser('test-uid-1');
    expect(user.id).toMatch(/^[0-9a-f-]{36}$/);
    expect(user).toMatchObject({
      locale: 'en',
      role: 'user',
      deletedAt: null,
      adultAttestedAt: null,
    });
    expect(user.createdAt).toBeInstanceOf(Date);
  });

  it('rejects a duplicate firebase_uid', async () => {
    await createUser('test-uid-1');
    expect(await sqlState(createUser('test-uid-1'))).toBe(UNIQUE_VIOLATION);
  });

  it('allows many NULL phones but only one account per phone number', async () => {
    await createUser('test-uid-1');
    await createUser('test-uid-2');
    await createUser('test-uid-3', '+910000000001');
    expect(await sqlState(createUser('test-uid-4', '+910000000001'))).toBe(UNIQUE_VIOLATION);
  });

  it.each([
    ['locale', { locale: 'fr' }],
    ['role', { role: 'superuser' }],
  ])('CHECK rejects an invalid %s', async (_label, values) => {
    const insert = db.insert(users).values({ firebaseUid: 'test-uid-1', ...values });
    expect(await sqlState(insert)).toBe(CHECK_VIOLATION);
  });

  it('sets updated_at in application code on Drizzle updates', async () => {
    const user = await createUser('test-uid-1');
    await new Promise((resolve) => setTimeout(resolve, 20));
    const [updated] = await db
      .update(users)
      .set({ displayName: 'Test User' })
      .where(eq(users.id, user.id))
      .returning();
    expect(updated?.updatedAt.getTime()).toBeGreaterThan(user.updatedAt.getTime());
  });
});

describe('devices', () => {
  it('enforces the user foreign key', async () => {
    const insert = db.insert(devices).values({
      installationId: 'test-install-1',
      userId: '00000000-0000-4000-8000-000000000000',
    });
    expect(await sqlState(insert)).toBe(FK_VIOLATION);
  });

  it('uses installation_id as primary key and cascades on user delete', async () => {
    const user = await createUser('test-uid-1');
    await db.insert(devices).values({ installationId: 'test-install-1', userId: user.id });
    const dup = db.insert(devices).values({ installationId: 'test-install-1', userId: user.id });
    expect(await sqlState(dup)).toBe(UNIQUE_VIOLATION);

    await db.delete(users).where(eq(users.id, user.id));
    expect(await db.select().from(devices)).toHaveLength(0);
  });
});

describe('idempotency_keys', () => {
  const entry = (userId: string, key = 'test-key-1') => ({
    userId,
    key,
    route: 'POST /v1/sos',
    responseHash: 'test-hash',
    responseStatus: 201,
    responseBody: { id: 'test-sos-1' },
    expiresAt: new Date(Date.now() + 24 * 3600 * 1000),
  });

  it('rejects the same (user, route, key) twice and cascades on user delete', async () => {
    const user = await createUser('test-uid-1');
    await db.insert(idempotencyKeys).values(entry(user.id));
    expect(await sqlState(db.insert(idempotencyKeys).values(entry(user.id)))).toBe(
      UNIQUE_VIOLATION,
    );
    await db.insert(idempotencyKeys).values(entry(user.id, 'test-key-2'));

    const [stored] = await db.select().from(idempotencyKeys).limit(1);
    expect(stored).toMatchObject({ responseStatus: 201, responseBody: { id: 'test-sos-1' } });

    await db.delete(users).where(eq(users.id, user.id));
    expect(await db.select().from(idempotencyKeys)).toHaveLength(0);
  });

  it('has an index on expires_at for the purge job', async () => {
    const { rows } = await pool.query<{ indexdef: string }>(
      `select indexdef from pg_indexes where indexname = 'idempotency_keys_expires_at_idx'`,
    );
    expect(rows[0]?.indexdef).toContain('(expires_at)');
  });
});

describe('consent_records', () => {
  const row = (userId: string, values: Partial<typeof consentRecords.$inferInsert> = {}) => ({
    userId,
    purpose: 'account_core',
    status: 'granted',
    noticeVersion: 'test-v1',
    noticeLocale: 'en',
    ...values,
  });

  it('applies defaults (uuid id, decided_at) and enforces the user foreign key', async () => {
    const user = await createUser('test-uid-1');
    const [stored] = await db.insert(consentRecords).values(row(user.id)).returning();
    expect(stored?.id).toMatch(/^[0-9a-f-]{36}$/);
    expect(stored?.decidedAt).toBeInstanceOf(Date);

    const orphan = db.insert(consentRecords).values(row('00000000-0000-4000-8000-000000000000'));
    expect(await sqlState(orphan)).toBe(FK_VIOLATION);
  });

  it.each([
    ['an upper-case purpose', { purpose: 'Account_Core' }],
    ['a purpose starting with a digit', { purpose: '1abc' }],
    ['a purpose shorter than 3 characters', { purpose: 'ab' }],
    ['a purpose longer than 41 characters', { purpose: `a${'b'.repeat(41)}` }],
    ['an unknown status', { status: 'maybe' }],
    ['an unknown notice locale', { noticeLocale: 'fr' }],
  ])('CHECK rejects %s', async (_label, values) => {
    const user = await createUser('test-uid-1');
    const insert = db.insert(consentRecords).values(row(user.id, values));
    expect(await sqlState(insert)).toBe(CHECK_VIOLATION);
  });

  it('accepts a purpose the code does not know yet (the allowlist lives in the server)', async () => {
    const user = await createUser('test-uid-1');
    await db.insert(consentRecords).values(row(user.id, { purpose: 'trusted_circle' }));
    expect(await db.select().from(consentRecords)).toHaveLength(1);
  });

  it('keeps history (several rows per purpose) and cascades on user delete', async () => {
    const user = await createUser('test-uid-1');
    await db.insert(consentRecords).values(row(user.id));
    await db.insert(consentRecords).values(row(user.id, { status: 'withdrawn' }));
    expect(await db.select().from(consentRecords)).toHaveLength(2);

    await db.delete(users).where(eq(users.id, user.id));
    expect(await db.select().from(consentRecords)).toHaveLength(0);
  });

  it('has the (user_id, purpose, decided_at desc) index and a personal-data table comment', async () => {
    const { rows } = await pool.query<{ indexdef: string }>(
      `select indexdef from pg_indexes where indexname = 'consent_records_user_purpose_decided_idx'`,
    );
    expect(rows[0]?.indexdef).toContain('(user_id, purpose, decided_at DESC)');

    const comment = await pool.query<{ comment: string }>(
      `select obj_description('consent_records'::regclass, 'pg_class') as comment`,
    );
    expect(comment.rows[0]?.comment).toMatch(/^PERSONAL DATA: append-only/);
  });
});

describe('audit_log', () => {
  it('increments the identity id and keeps rows after the actor is deleted', async () => {
    const user = await createUser('test-uid-1');
    const rows = await db
      .insert(auditLog)
      .values([
        { actorUserId: user.id, actorType: 'user', action: 'account_created', entity: 'user' },
        { actorUserId: user.id, actorType: 'user', action: 'account_deleted', entity: 'user' },
      ])
      .returning();
    expect(rows.map((r) => r.id)).toEqual([1, 2]);
    expect(rows[0]?.metadata).toEqual({});

    await db.delete(users).where(eq(users.id, user.id));
    expect(await db.select().from(auditLog)).toHaveLength(2);
  });

  it('CHECK rejects an unknown actor_type', async () => {
    const insert = db
      .insert(auditLog)
      .values({ actorType: 'robot', action: 'test_action', entity: 'user' });
    expect(await sqlState(insert)).toBe(CHECK_VIOLATION);
  });

  it('carries the no-precise-location rule as a table comment', async () => {
    const { rows } = await pool.query<{ comment: string }>(
      `select obj_description('audit_log'::regclass, 'pg_class') as comment`,
    );
    expect(rows[0]?.comment).toContain('never contain precise locations');
  });
});

describe('personal-data markers', () => {
  it('comments every personal-data column for the P020 export/erasure work', async () => {
    const { rows } = await pool.query<{ col: string }>(
      `select c.relname || '.' || a.attname as col
         from pg_attribute a
         join pg_class c on c.oid = a.attrelid
         join pg_namespace n on n.oid = c.relnamespace
        where n.nspname = 'public' and a.attnum > 0
          and col_description(c.oid, a.attnum) like 'PERSONAL DATA%'
        order by 1`,
    );
    expect(rows.map((r) => r.col)).toEqual([
      'devices.fcm_token',
      'users.adult_attested_at',
      'users.display_name',
      'users.firebase_uid',
      'users.phone_e164',
    ]);
  });
});
