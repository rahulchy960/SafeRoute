// SPDX-License-Identifier: AGPL-3.0-only
import { asc, eq } from 'drizzle-orm';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { auditLog, consentRecords, users } from '../../src/db/schema/index.js';
import {
  createTestKeys,
  phoneClaims,
  signToken,
  testVerifier,
  type TestKeys,
} from '../auth/tokens.js';
import { buildTestApp } from '../helpers.js';
import { connect, truncateAll } from './helpers.js';

const { pool, db } = connect();
let keys: TestKeys;

beforeAll(async () => {
  keys = await createTestKeys();
});
beforeEach(async () => {
  await truncateAll(pool);
});
afterAll(async () => {
  await pool.end();
});

type TestApp = ReturnType<typeof setup>['app'];

/** Fake identities: uid `test-uid-N`, phone +9100000000NN. */
function tokenFor(n: number) {
  const phone = `+91000000${String(n).padStart(4, '0')}`;
  return signToken(keys, {
    claims: phoneClaims({ sub: `test-uid-${String(n)}`, phone_number: phone }),
  });
}

function setup() {
  return buildTestApp({}, undefined, { verifier: testVerifier(keys), db });
}

/** Creates the account with the sign-up consent (`account_core`, notice `test-v1`). */
async function signUp(app: TestApp, n: number) {
  const token = await tokenFor(n);
  const res = await app.request('/v1/me/bootstrap', {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      consent: {
        ageConfirmed: true,
        noticeVersion: 'test-v1',
        noticeLocale: 'en',
        purposes: ['account_core'],
      },
    }),
  });
  expect(res.status).toBe(201);
  return { token, userId: ((await res.json()) as { id: string }).id };
}

async function getConsents(app: TestApp, token: string) {
  return app.request('/v1/me/consents', { headers: { Authorization: `Bearer ${token}` } });
}

const DECISION = { status: 'granted', noticeVersion: 'test-v1', noticeLocale: 'en' };

async function putConsent(app: TestApp, token: string, purpose: string, body: unknown = DECISION) {
  return app.request(`/v1/me/consents/${purpose}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

interface ConsentItem {
  purpose: string;
  status: string;
  noticeVersion: string;
  decidedAt: string;
}

async function items(app: TestApp, token: string): Promise<ConsentItem[]> {
  const res = await getConsents(app, token);
  expect(res.status).toBe(200);
  return ((await res.json()) as { items: ConsentItem[] }).items;
}

const historyOf = (userId: string) =>
  db
    .select()
    .from(consentRecords)
    .where(eq(consentRecords.userId, userId))
    .orderBy(asc(consentRecords.decidedAt));

const auditActions = async () =>
  (await db.select().from(auditLog).orderBy(asc(auditLog.id))).map((row) => row.action);

describe('GET /v1/me/consents', () => {
  it('returns the sign-up consent with the documented fields only, never cached', async () => {
    const { app } = setup();
    const { token } = await signUp(app, 1);

    const res = await getConsents(app, token);
    expect(res.status).toBe(200);
    expect(res.headers.get('cache-control')).toBe('no-store');
    const body = (await res.json()) as { items: Record<string, unknown>[] };
    expect(body.items).toHaveLength(1);
    expect(body.items[0]).toMatchObject({
      purpose: 'account_core',
      status: 'granted',
      noticeVersion: 'test-v1',
    });
    expect(Object.keys(body.items[0] ?? {}).sort()).toEqual([
      'decidedAt',
      'noticeVersion',
      'purpose',
      'status',
    ]);
    expect(body.items[0]?.decidedAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
  });

  it('returns the latest state per purpose, ordered by purpose', async () => {
    const { app } = setup();
    const { token } = await signUp(app, 2);
    await putConsent(app, token, 'sos_alerts');
    await putConsent(app, token, 'live_sharing');
    await putConsent(app, token, 'live_sharing', { ...DECISION, status: 'withdrawn' });

    expect((await items(app, token)).map(({ purpose, status }) => `${purpose}:${status}`)).toEqual([
      'account_core:granted',
      'live_sharing:withdrawn',
      'sos_alerts:granted',
    ]);
  });

  it('403 bootstrap_required before sign-up', async () => {
    const { app } = setup();
    const res = await getConsents(app, await tokenFor(3));
    expect(res.status).toBe(403);
    expect(await res.json()).toMatchObject({ code: 'bootstrap_required' });
  });
});

describe('PUT /v1/me/consents/{purpose}', () => {
  it('grant → withdraw → grant keeps three history rows; the latest wins', async () => {
    const { app, logs } = setup();
    const { token, userId } = await signUp(app, 10);

    const granted = await putConsent(app, token, 'sos_alerts');
    expect(granted.status).toBe(200);
    expect(granted.headers.get('cache-control')).toBe('no-store');
    expect(await granted.json()).toMatchObject({ purpose: 'sos_alerts', status: 'granted' });

    const withdrawn = await putConsent(app, token, 'sos_alerts', {
      ...DECISION,
      status: 'withdrawn',
    });
    expect(await withdrawn.json()).toMatchObject({ purpose: 'sos_alerts', status: 'withdrawn' });
    expect((await items(app, token)).find((item) => item.purpose === 'sos_alerts')).toMatchObject({
      status: 'withdrawn',
    });

    const again = await putConsent(app, token, 'sos_alerts', {
      ...DECISION,
      noticeLocale: 'bn',
    });
    expect(await again.json()).toMatchObject({ purpose: 'sos_alerts', status: 'granted' });

    const history = (await historyOf(userId)).filter((row) => row.purpose === 'sos_alerts');
    expect(history.map((row) => row.status)).toEqual(['granted', 'withdrawn', 'granted']);
    expect(history.map((row) => row.noticeLocale)).toEqual(['en', 'en', 'bn']);
    expect((await items(app, token)).find((item) => item.purpose === 'sos_alerts')).toMatchObject({
      status: 'granted',
    });

    expect(await auditActions()).toEqual([
      'user.created',
      'consent.recorded',
      'consent.changed',
      'consent.changed',
      'consent.changed',
    ]);
    const [, , firstChange] = await db.select().from(auditLog).orderBy(asc(auditLog.id));
    expect(firstChange).toMatchObject({
      actorType: 'user',
      actorUserId: userId,
      entity: 'consent',
      entityId: userId,
      metadata: { purpose: 'sos_alerts', status: 'granted' },
    });

    // INFO log with user_id and purpose only (no phone, uid, status or notice version).
    const changes = logs().filter((line) => line.message === 'consent changed');
    expect(changes).toHaveLength(3);
    expect(changes[0]).toMatchObject({ severity: 'INFO', user_id: userId, purpose: 'sos_alerts' });
    expect(changes[0]).not.toHaveProperty('status');
    expect(JSON.stringify(logs())).not.toContain('+910000000010');
    expect(JSON.stringify(logs())).not.toContain('test-uid-10');
  });

  it('an identical decision inserts nothing and returns the existing state', async () => {
    const { app, logs } = setup();
    const { token, userId } = await signUp(app, 11);
    const first = await (await putConsent(app, token, 'live_sharing')).json();

    const repeat = await putConsent(app, token, 'live_sharing');
    expect(repeat.status).toBe(200);
    expect(await repeat.json()).toEqual(first);
    expect(await historyOf(userId)).toHaveLength(2);
    expect(logs().filter((line) => line.message === 'consent changed')).toHaveLength(1);

    // Re-sending the sign-up consent is the same decision too.
    expect((await putConsent(app, token, 'account_core')).status).toBe(200);
    expect(await historyOf(userId)).toHaveLength(2);
    expect(await auditActions()).toEqual(['user.created', 'consent.recorded', 'consent.changed']);
  });

  it('the same status under a new notice version is a new row (re-consent)', async () => {
    const { app } = setup();
    const { token, userId } = await signUp(app, 12);

    const res = await putConsent(app, token, 'account_core', {
      ...DECISION,
      noticeVersion: 'test-v2',
    });
    expect(await res.json()).toMatchObject({ status: 'granted', noticeVersion: 'test-v2' });
    expect((await historyOf(userId)).map((row) => row.noticeVersion)).toEqual([
      'test-v1',
      'test-v2',
    ]);
    expect(await items(app, token)).toHaveLength(1);
  });

  it('10 parallel identical decisions insert exactly one row', async () => {
    const { app } = setup();
    const { token, userId } = await signUp(app, 13);

    const responses = await Promise.all(
      Array.from({ length: 10 }, () => putConsent(app, token, 'safety_reports')),
    );
    expect(responses.map((res) => res.status)).toEqual(Array(10).fill(200));
    const history = await historyOf(userId);
    expect(history.filter((row) => row.purpose === 'safety_reports')).toHaveLength(1);
  });

  it('withdrawing account_core → 409 account_deletion_required, nothing written', async () => {
    const { app } = setup();
    const { token, userId } = await signUp(app, 14);

    const res = await putConsent(app, token, 'account_core', { ...DECISION, status: 'withdrawn' });
    expect(res.status).toBe(409);
    expect(res.headers.get('content-type')).toBe('application/problem+json');
    expect(await res.json()).toMatchObject({ code: 'account_deletion_required' });
    expect(await historyOf(userId)).toHaveLength(1);
    expect(await items(app, token)).toMatchObject([{ purpose: 'account_core', status: 'granted' }]);
  });

  it.each([
    ['an unknown purpose', 'secret_purpose', DECISION, 'path.purpose'],
    ['a purpose reserved for later', 'trusted_circle', DECISION, 'path.purpose'],
    ['a malformed purpose', 'Secret-Purpose', DECISION, 'path.purpose'],
    ['an unknown status', 'sos_alerts', { ...DECISION, status: 'secret' }, 'body.status'],
    [
      'a missing noticeVersion',
      'sos_alerts',
      { status: 'granted', noticeLocale: 'en' },
      'body.noticeVersion',
    ],
    [
      'a malformed noticeVersion',
      'sos_alerts',
      { ...DECISION, noticeVersion: 'secret version' },
      'body.noticeVersion',
    ],
    [
      'an unknown noticeLocale',
      'sos_alerts',
      { ...DECISION, noticeLocale: 'fr' },
      'body.noticeLocale',
    ],
  ])('%s → 400 validation_error, no echo, nothing written', async (_name, purpose, body, path) => {
    const { app } = setup();
    const { token, userId } = await signUp(app, 15);

    const res = await putConsent(app, token, purpose, body);
    expect(res.status).toBe(400);
    const text = await res.text();
    const problem = JSON.parse(text) as { code: string; errors: { path: string }[] };
    expect(problem.code).toBe('validation_error');
    expect([...new Set(problem.errors.map((error) => error.path))]).toEqual([path]);
    expect(text.toLowerCase()).not.toContain('secret');
    expect(text).not.toContain('trusted_circle');
    expect(text).not.toContain('"fr"');
    expect(await historyOf(userId)).toHaveLength(1);
  });

  it('users only read and write their own consents', async () => {
    const { app } = setup();
    const alice = await signUp(app, 16);
    const bob = await signUp(app, 17);

    await putConsent(app, alice.token, 'sos_alerts');
    await putConsent(app, bob.token, 'live_sharing');
    await putConsent(app, bob.token, 'sos_alerts', { ...DECISION, status: 'withdrawn' });

    expect((await items(app, alice.token)).map(({ purpose, status }) => [purpose, status])).toEqual(
      [
        ['account_core', 'granted'],
        ['sos_alerts', 'granted'],
      ],
    );
    expect((await items(app, bob.token)).map(({ purpose, status }) => [purpose, status])).toEqual([
      ['account_core', 'granted'],
      ['live_sharing', 'granted'],
      ['sos_alerts', 'withdrawn'],
    ]);
    // Every row belongs to the caller that wrote it; the API takes no user id from the client.
    expect(new Set((await historyOf(alice.userId)).map((row) => row.userId))).toEqual(
      new Set([alice.userId]),
    );
    expect(await historyOf(bob.userId)).toHaveLength(3);
  });

  it('ignores a user id smuggled into the body or query', async () => {
    const { app } = setup();
    const alice = await signUp(app, 18);
    const bob = await signUp(app, 19);

    const res = await app.request(`/v1/me/consents/sos_alerts?userId=${bob.userId}`, {
      method: 'PUT',
      headers: { Authorization: `Bearer ${alice.token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...DECISION, userId: bob.userId, user_id: bob.userId }),
    });
    expect(res.status).toBe(200);
    expect(await historyOf(bob.userId)).toHaveLength(1);
    expect(await historyOf(alice.userId)).toHaveLength(2);
  });

  it('a soft-deleted user → 403 account_deleted on both routes, nothing written', async () => {
    const { app } = setup();
    const { token, userId } = await signUp(app, 20);
    await db.update(users).set({ deletedAt: new Date() }).where(eq(users.id, userId));

    for (const res of [await getConsents(app, token), await putConsent(app, token, 'sos_alerts')]) {
      expect(res.status).toBe(403);
      expect(await res.json()).toMatchObject({ code: 'account_deleted' });
    }
    expect(await historyOf(userId)).toHaveLength(1);
  });

  it('401 without a token; 403 bootstrap_required before sign-up', async () => {
    const { app } = setup();
    const anonymous = await app.request('/v1/me/consents/sos_alerts', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(DECISION),
    });
    expect(anonymous.status).toBe(401);
    expect((await app.request('/v1/me/consents')).status).toBe(401);

    const res = await putConsent(app, await tokenFor(21), 'sos_alerts');
    expect(res.status).toBe(403);
    expect(await res.json()).toMatchObject({ code: 'bootstrap_required' });
    expect(await db.select().from(consentRecords)).toHaveLength(0);
  });

  it('consent rows go away with the user (cascade)', async () => {
    const { app } = setup();
    const { token, userId } = await signUp(app, 22);
    await putConsent(app, token, 'sos_alerts');

    await db.delete(users).where(eq(users.id, userId));
    expect(await db.select().from(consentRecords)).toHaveLength(0);
    // Audit rows have no foreign key and survive (ADR 0003).
    expect(await auditActions()).toHaveLength(3);
  });
});
