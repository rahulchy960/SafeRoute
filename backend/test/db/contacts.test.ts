// SPDX-License-Identifier: AGPL-3.0-only
import { asc, eq } from 'drizzle-orm';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import {
  auditLog,
  contactOptoutTokens,
  emergencyContacts,
  users,
} from '../../src/db/schema/index.js';
import {
  CONTACT_LIMITS,
  createContact,
  hashOptOutToken,
  MAX_TOKENS_PER_CONTACT,
} from '../../src/modules/contacts/service.js';
import {
  createTestKeys,
  phoneClaims,
  signToken,
  testVerifier,
  type TestKeys,
} from '../auth/tokens.js';
import { buildTestApp } from '../helpers.js';
import { connect, truncateAll } from './helpers.js';

// Obviously fake data only: users +91000000NNNN, contacts +9100001000NN, names "Test Contact N".
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

const userPhone = (n: number) => `+91000000${String(n).padStart(4, '0')}`;
const contactPhone = (n: number) => `+91000010${String(n).padStart(4, '0')}`;
const DECISION = { status: 'granted', noticeVersion: 'test-v1', noticeLocale: 'en' };

function setup() {
  return buildTestApp({}, undefined, { verifier: testVerifier(keys), db });
}

function call(
  app: TestApp,
  token: string | undefined,
  method: string,
  path: string,
  body?: unknown,
) {
  return app.request(path, {
    method,
    headers: {
      ...(token === undefined ? {} : { Authorization: `Bearer ${token}` }),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}

/** Creates the account; with `consent` (the default) also grants `sos_alerts`. */
async function signUp(app: TestApp, n: number, consent = true) {
  const token = await signToken(keys, {
    claims: phoneClaims({ sub: `test-uid-${String(n)}`, phone_number: userPhone(n) }),
  });
  const res = await call(app, token, 'POST', '/v1/me/bootstrap', {
    consent: {
      ageConfirmed: true,
      noticeVersion: 'test-v1',
      noticeLocale: 'en',
      purposes: ['account_core'],
    },
  });
  expect(res.status).toBe(201);
  const userId = ((await res.json()) as { id: string }).id;
  if (consent) {
    expect((await call(app, token, 'PUT', '/v1/me/consents/sos_alerts', DECISION)).status).toBe(
      200,
    );
  }
  return { token, userId };
}

interface ContactBody {
  id: string;
  name: string;
  phoneE164: string;
  createdAt: string;
  invitedAt: string | null;
  optedOutAt: string | null;
}

const add = (app: TestApp, token: string, n: number, name = `Test Contact ${String(n)}`) =>
  call(app, token, 'POST', '/v1/contacts', { name, phone: contactPhone(n) });

async function addOk(app: TestApp, token: string, n: number): Promise<ContactBody> {
  const res = await add(app, token, n);
  expect(res.status).toBe(201);
  return (await res.json()) as ContactBody;
}

async function list(app: TestApp, token: string) {
  const res = await call(app, token, 'GET', '/v1/contacts');
  expect(res.status).toBe(200);
  return (await res.json()) as { items: ContactBody[]; maxContacts: number };
}

async function invite(app: TestApp, token: string, id: string): Promise<string> {
  const res = await call(app, token, 'POST', `/v1/contacts/${id}/invite`);
  expect(res.status).toBe(200);
  return ((await res.json()) as { optOutToken: string }).optOutToken;
}

const optOut = (app: TestApp, token: unknown) =>
  call(app, undefined, 'POST', '/v1/public/contacts/opt-out', { token });

/** The rate-limit buckets are not what most tests are about. */
const resetLimits = () => pool.query('delete from rate_limit_buckets');

const code = async (res: Response) => ((await res.json()) as { code: string }).code;
const auditRows = () => db.select().from(auditLog).orderBy(asc(auditLog.id));
const contactActions = async () =>
  (await auditRows()).map((row) => row.action).filter((action) => action.startsWith('contact'));

describe('contacts: create, list, rename, remove', () => {
  it('adds a contact and returns the documented fields only, never cached', async () => {
    const { app } = setup();
    const { token, userId } = await signUp(app, 1);

    const res = await add(app, token, 1, '  Test Contact 1  ');
    expect(res.status).toBe(201);
    expect(res.headers.get('cache-control')).toBe('no-store');
    const created = (await res.json()) as ContactBody;
    expect(Object.keys(created).sort()).toEqual([
      'createdAt',
      'id',
      'invitedAt',
      'name',
      'optedOutAt',
      'phoneE164',
    ]);
    expect(created).toMatchObject({
      name: 'Test Contact 1',
      phoneE164: contactPhone(1),
      invitedAt: null,
      optedOutAt: null,
    });

    const listed = await list(app, token);
    expect(listed).toEqual({ items: [created], maxContacts: 5 });

    const [row] = await db.select().from(emergencyContacts);
    expect(row).toMatchObject({ userId, hasAppUserId: null, deletedAt: null });
    const [audit] = (await auditRows()).filter((entry) => entry.action === 'contact.created');
    expect(audit).toMatchObject({
      actorType: 'user',
      actorUserId: userId,
      entity: 'contact',
      entityId: created.id,
      metadata: { contactId: created.id },
    });
  });

  it('lists oldest first and only the caller’s contacts', async () => {
    const { app } = setup();
    const a = await signUp(app, 2);
    const b = await signUp(app, 3);
    await addOk(app, a.token, 1);
    await addOk(app, a.token, 2);
    await addOk(app, b.token, 3);

    expect((await list(app, a.token)).items.map((item) => item.phoneE164)).toEqual([
      contactPhone(1),
      contactPhone(2),
    ]);
    expect((await list(app, b.token)).items).toHaveLength(1);
  });

  it('renames (trimmed) and removes for good', async () => {
    const { app } = setup();
    const { token } = await signUp(app, 4);
    const contact = await addOk(app, token, 1);
    await invite(app, token, contact.id);

    const renamed = await call(app, token, 'PATCH', `/v1/contacts/${contact.id}`, {
      name: ' New Name ',
    });
    expect(renamed.status).toBe(200);
    expect(await renamed.json()).toMatchObject({ id: contact.id, name: 'New Name' });

    const removed = await call(app, token, 'DELETE', `/v1/contacts/${contact.id}`);
    expect(removed.status).toBe(204);
    expect(await db.select().from(emergencyContacts)).toHaveLength(0);
    expect(await db.select().from(contactOptoutTokens)).toHaveLength(0);
    expect((await call(app, token, 'DELETE', `/v1/contacts/${contact.id}`)).status).toBe(404);

    // A number that was removed without an opt-out can be added again.
    await addOk(app, token, 1);
    expect(await contactActions()).toEqual([
      'contact.created',
      'contact.invite_created',
      'contact.renamed',
      'contact.deleted',
      'contact.created',
    ]);
  });

  it.each([
    ['a number without +', { name: 'Test Contact', phone: '910000100001' }, 'body.phone'],
    [
      'a number with spaces inside',
      { name: 'Test Contact', phone: '+91 00001 00001' },
      'body.phone',
    ],
    ['a number that is too short', { name: 'Test Contact', phone: '+91000' }, 'body.phone'],
    ['an empty name', { name: '   ', phone: contactPhone(1) }, 'body.name'],
    ['a name of 81 characters', { name: 'x'.repeat(81), phone: contactPhone(1) }, 'body.name'],
    [
      'a name with a control character',
      { name: 'Test\nContact', phone: contactPhone(1) },
      'body.name',
    ],
    ['a missing phone', { name: 'Test Contact' }, 'body.phone'],
  ])('400 for %s, without echoing the value', async (_label, body, path) => {
    const { app } = setup();
    const { token } = await signUp(app, 5);
    const res = await call(app, token, 'POST', '/v1/contacts', body);
    expect(res.status).toBe(400);
    const problem = (await res.json()) as { code: string; errors: { path: string }[] };
    expect(problem.code).toBe('validation_error');
    expect(problem.errors.map((issue) => issue.path)).toContain(path);
    expect(JSON.stringify(problem)).not.toContain('Test');
    expect(await db.select().from(emergencyContacts)).toHaveLength(0);
  });

  it('accepts a name of exactly 80 characters, in Bengali too', async () => {
    const { app } = setup();
    const { token } = await signUp(app, 6);
    expect((await add(app, token, 1, 'ক'.repeat(80))).status).toBe(201);
  });

  it('409 invalid_contact for the user’s own number', async () => {
    const { app } = setup();
    const { token } = await signUp(app, 7);
    const res = await call(app, token, 'POST', '/v1/contacts', {
      name: 'Test Contact',
      phone: userPhone(7),
    });
    expect(res.status).toBe(409);
    expect(await code(res)).toBe('invalid_contact');
  });

  it('409 contact_exists for the same number again; another user can add it', async () => {
    const { app } = setup();
    const a = await signUp(app, 8);
    const b = await signUp(app, 9);
    await addOk(app, a.token, 1);

    const again = await add(app, a.token, 1, 'Another Name');
    expect(again.status).toBe(409);
    expect(await code(again)).toBe('contact_exists');
    await addOk(app, b.token, 1);
  });

  it('401 without a token on every contacts route', async () => {
    const { app } = setup();
    const id = '00000000-0000-4000-8000-000000000000';
    for (const [method, path] of [
      ['GET', '/v1/contacts'],
      ['POST', '/v1/contacts'],
      ['PATCH', `/v1/contacts/${id}`],
      ['DELETE', `/v1/contacts/${id}`],
      ['POST', `/v1/contacts/${id}/invite`],
      ['POST', `/v1/contacts/${id}/invite/confirm`],
    ] as const) {
      const res = await call(app, undefined, method, path, method === 'GET' ? undefined : {});
      expect(res.status, `${method} ${path}`).toBe(401);
    }
  });

  it('another user’s contact is 404 everywhere, and stays untouched', async () => {
    const { app } = setup();
    const owner = await signUp(app, 10);
    const other = await signUp(app, 11);
    const contact = await addOk(app, owner.token, 1);

    for (const [method, path, body] of [
      ['PATCH', `/v1/contacts/${contact.id}`, { name: 'Changed' }],
      ['DELETE', `/v1/contacts/${contact.id}`, undefined],
      ['POST', `/v1/contacts/${contact.id}/invite`, undefined],
      ['POST', `/v1/contacts/${contact.id}/invite/confirm`, undefined],
    ] as const) {
      const res = await call(app, other.token, method, path, body);
      expect(res.status, `${method} ${path}`).toBe(404);
      expect(await code(res)).toBe('not_found');
    }
    expect((await list(app, owner.token)).items).toEqual([contact]);
    expect(await db.select().from(contactOptoutTokens)).toHaveLength(0);
  });
});

describe('contacts: the limit of 5', () => {
  it('the sixth contact is 409 contact_limit_reached; removing one frees a place', async () => {
    const { app } = setup();
    const { token } = await signUp(app, 20);
    const created: ContactBody[] = [];
    for (let n = 1; n <= 5; n += 1) created.push(await addOk(app, token, n));
    await resetLimits();

    const sixth = await add(app, token, 6);
    expect(sixth.status).toBe(409);
    expect(await code(sixth)).toBe('contact_limit_reached');
    // A repeat of an existing number at the limit still says "exists", so the app refetches.
    expect(await code(await add(app, token, 5))).toBe('contact_exists');

    await call(app, token, 'DELETE', `/v1/contacts/${created[0]?.id ?? ''}`);
    await resetLimits();
    await addOk(app, token, 6);
    expect((await list(app, token)).items).toHaveLength(5);
  });

  it('10 parallel creates: exactly 5 succeed, the rest hit the limit', async () => {
    const { app } = setup();
    const { userId } = await signUp(app, 21);
    const user = { id: userId, phoneE164: userPhone(21) };

    // The service is called directly: through the route the burst bucket (also 5) would answer
    // first, and this test is about the row lock.
    const results = await Promise.allSettled(
      Array.from({ length: 10 }, (_, n) =>
        createContact(db, user, { name: 'Test Contact', phoneE164: contactPhone(n + 1) }),
      ),
    );
    expect(results.filter((result) => result.status === 'fulfilled')).toHaveLength(5);
    const reasons = results
      .filter((result) => result.status === 'rejected')
      .map((result) => (result.reason as { code?: string }).code);
    expect(reasons).toEqual(Array(5).fill('contact_limit_reached'));
    expect(await db.select().from(emergencyContacts)).toHaveLength(5);
  });

  it('10 parallel creates of the same number: exactly one row', async () => {
    const { app } = setup();
    const { userId } = await signUp(app, 22);
    const user = { id: userId, phoneE164: userPhone(22) };
    const results = await Promise.allSettled(
      Array.from({ length: 10 }, () =>
        createContact(db, user, { name: 'Test Contact', phoneE164: contactPhone(1) }),
      ),
    );
    expect(results.filter((result) => result.status === 'fulfilled')).toHaveLength(1);
    expect(await db.select().from(emergencyContacts)).toHaveLength(1);
  });
});

describe('contacts: sos_alerts consent', () => {
  it('403 consent_required when the purpose was never granted; nothing is stored', async () => {
    const { app } = setup();
    const { token } = await signUp(app, 30, false);
    const res = await add(app, token, 1);
    expect(res.status).toBe(403);
    expect(await code(res)).toBe('consent_required');
    expect(await db.select().from(emergencyContacts)).toHaveLength(0);
    // Reading is allowed and empty: there is nothing to read.
    expect((await list(app, token)).items).toEqual([]);
  });

  it('withdrawing deletes every contact, tombstone and token in one step, then blocks adding', async () => {
    const { app } = setup();
    const { token, userId } = await signUp(app, 31);
    const other = await signUp(app, 32);
    const kept = await addOk(app, other.token, 9);

    const first = await addOk(app, token, 1);
    const second = await addOk(app, token, 2);
    await invite(app, token, first.id);
    expect((await optOut(app, await invite(app, token, second.id))).status).toBe(200);
    // `second` is now a tombstone.
    expect((await call(app, token, 'DELETE', `/v1/contacts/${second.id}`)).status).toBe(204);
    expect(await db.select().from(emergencyContacts)).toHaveLength(3);

    const withdrawn = await call(app, token, 'PUT', '/v1/me/consents/sos_alerts', {
      ...DECISION,
      status: 'withdrawn',
    });
    expect(withdrawn.status).toBe(200);

    const left = await db.select().from(emergencyContacts);
    expect(left.map((row) => row.id)).toEqual([kept.id]);
    expect(await db.select().from(contactOptoutTokens)).toHaveLength(0);
    const erased = (await auditRows()).find((row) => row.action === 'contacts.erased');
    expect(erased).toMatchObject({
      actorType: 'user',
      actorUserId: userId,
      metadata: { reason: 'consent_withdrawn', count: 2 },
    });

    const blocked = await add(app, token, 3);
    expect(blocked.status).toBe(403);
    expect(await code(blocked)).toBe('consent_required');

    // Granting again starts from nothing: the tombstone is gone too.
    await call(app, token, 'PUT', '/v1/me/consents/sos_alerts', DECISION);
    await resetLimits();
    await addOk(app, token, 2);
    expect((await list(app, token)).items).toHaveLength(1);
  });

  it('a withdrawal with no contacts writes no erasure audit row', async () => {
    const { app } = setup();
    const { token } = await signUp(app, 33);
    await call(app, token, 'PUT', '/v1/me/consents/sos_alerts', {
      ...DECISION,
      status: 'withdrawn',
    });
    expect(await contactActions()).toEqual([]);
  });

  it('deleting the user row removes the contacts and their tokens (cascade)', async () => {
    const { app } = setup();
    const { token, userId } = await signUp(app, 34);
    const contact = await addOk(app, token, 1);
    await invite(app, token, contact.id);

    await db.delete(users).where(eq(users.id, userId));
    expect(await db.select().from(emergencyContacts)).toHaveLength(0);
    expect(await db.select().from(contactOptoutTokens)).toHaveLength(0);
  });
});

describe('contacts: invite', () => {
  it('mints distinct tokens and stores only their hashes', async () => {
    const { app } = setup();
    const { token } = await signUp(app, 40);
    const contact = await addOk(app, token, 1);

    const first = await invite(app, token, contact.id);
    const second = await invite(app, token, contact.id);
    expect(first).toMatch(/^[A-Za-z0-9_-]{22}$/);
    expect(second).toMatch(/^[A-Za-z0-9_-]{22}$/);
    expect(first).not.toBe(second);

    const stored = await db.select().from(contactOptoutTokens);
    expect(stored.map((row) => row.tokenHash.toString('hex')).sort()).toEqual(
      [first, second].map((value) => hashOptOutToken(value).toString('hex')).sort(),
    );
    // No plaintext token anywhere in the database, in any encoding a column could hold.
    const dump = await pool.query<{ text: string }>(
      `select row_to_json(t)::text as text from contact_optout_tokens t
       union all select row_to_json(c)::text from emergency_contacts c
       union all select row_to_json(a)::text from audit_log a
       union all select row_to_json(r)::text from rate_limit_buckets r`,
    );
    const everything = dump.rows.map((row) => row.text).join('\n');
    for (const value of [first, second]) {
      expect(everything).not.toContain(value);
      expect(everything).not.toContain(Buffer.from(value, 'utf8').toString('hex'));
    }
    // Minting does not mark the contact as invited.
    expect((await list(app, token)).items[0]?.invitedAt).toBeNull();
  });

  it('confirm sets invitedAt; without an invite link it is 409 conflict', async () => {
    const { app } = setup();
    const { token } = await signUp(app, 41);
    const contact = await addOk(app, token, 1);

    const early = await call(app, token, 'POST', `/v1/contacts/${contact.id}/invite/confirm`);
    expect(early.status).toBe(409);
    expect(await code(early)).toBe('conflict');

    await invite(app, token, contact.id);
    const confirmed = await call(app, token, 'POST', `/v1/contacts/${contact.id}/invite/confirm`);
    expect(confirmed.status).toBe(204);
    expect(confirmed.headers.get('cache-control')).toBe('no-store');
    expect((await list(app, token)).items[0]?.invitedAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    // Repeating it is fine.
    expect(
      (await call(app, token, 'POST', `/v1/contacts/${contact.id}/invite/confirm`)).status,
    ).toBe(204);
  });

  it(`the ${String(MAX_TOKENS_PER_CONTACT + 1)}th link of one contact is 429 rate_limited`, async () => {
    const { app } = setup();
    const { token } = await signUp(app, 42);
    const contact = await addOk(app, token, 1);
    for (let n = 0; n < MAX_TOKENS_PER_CONTACT; n += 1) await invite(app, token, contact.id);

    const res = await call(app, token, 'POST', `/v1/contacts/${contact.id}/invite`);
    expect(res.status).toBe(429);
    expect(await code(res)).toBe('rate_limited');
    expect(await db.select().from(contactOptoutTokens)).toHaveLength(MAX_TOKENS_PER_CONTACT);
  });
});

describe('contacts: public opt-out', () => {
  it('a valid token opts the contact out; repeating it and older tokens work too', async () => {
    const { app } = setup();
    const { token } = await signUp(app, 50);
    const contact = await addOk(app, token, 1);
    const older = await invite(app, token, contact.id);
    const newer = await invite(app, token, contact.id);

    const res = await optOut(app, newer);
    expect(res.status).toBe(200);
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(await res.json()).toEqual({ optedOut: true });

    const [listed] = (await list(app, token)).items;
    expect(listed?.optedOutAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    const at = (await db.select().from(emergencyContacts))[0]?.optedOutAt;

    expect((await optOut(app, newer)).status).toBe(200);
    expect((await optOut(app, older)).status).toBe(200);
    // The first opt-out time stands.
    expect((await db.select().from(emergencyContacts))[0]?.optedOutAt).toEqual(at);

    const audits = (await auditRows()).filter((row) => row.action === 'contact.opted_out');
    expect(audits).toHaveLength(1);
    expect(audits[0]).toMatchObject({
      actorType: 'system',
      actorUserId: null,
      entity: 'contact',
      entityId: contact.id,
      metadata: { contactId: contact.id },
    });
    expect(Object.keys(audits[0]?.metadata ?? {})).toEqual(['contactId']);
  });

  it.each([
    ['unknown', 'AAAAAAAAAAAAAAAAAAAAAA'],
    ['too short', 'abc'],
    ['with a character outside base64url', 'AAAAAAAAAAAAAAAAAAAAA+'],
    ['too long for the pattern', 'A'.repeat(23)],
  ])('a token that is %s gets the same generic 404', async (_label, value) => {
    const { app } = setup();
    const res = await optOut(app, value);
    expect(res.status).toBe(404);
    const problem = (await res.json()) as Record<string, unknown>;
    expect(problem).toMatchObject({ code: 'not_found', detail: 'This link is not active.' });
    expect(JSON.stringify(problem)).not.toContain(value);
  });

  it('400 for a body without a token string', async () => {
    const { app } = setup();
    expect((await optOut(app, undefined)).status).toBe(400);
    expect((await optOut(app, 42)).status).toBe(400);
  });

  it('the token of a removed contact is 404', async () => {
    const { app } = setup();
    const { token } = await signUp(app, 51);
    const contact = await addOk(app, token, 1);
    const link = await invite(app, token, contact.id);
    await call(app, token, 'DELETE', `/v1/contacts/${contact.id}`);
    expect((await optOut(app, link)).status).toBe(404);
  });

  it('one shared bucket answers 429 with Retry-After, and stores nothing about the caller', async () => {
    const { app } = setup();
    const { token } = await signUp(app, 52);
    const contact = await addOk(app, token, 1);
    const link = await invite(app, token, contact.id);
    await pool.query(
      `insert into rate_limit_buckets (key, user_id, tokens, refilled_at)
       values ('contacts:optout:global', null, 0, now() + interval '1 hour')`,
    );

    const res = await call(app, undefined, 'POST', '/v1/public/contacts/opt-out', { token: link });
    expect(res.status).toBe(429);
    expect(Number(res.headers.get('retry-after'))).toBeGreaterThan(0);
    expect(await code(res)).toBe('rate_limited');
    expect((await list(app, token)).items[0]?.optedOutAt).toBeNull();

    const buckets = await pool.query<{ key: string; user_id: string | null }>(
      `select key, user_id from rate_limit_buckets where key like 'contacts:optout%'`,
    );
    expect(buckets.rows).toEqual([{ key: 'contacts:optout:global', user_id: null }]);
  });

  it('the bucket has the documented size', async () => {
    const { app } = setup();
    const { capacity } = CONTACT_LIMITS.optOutGlobal;
    const statuses: number[] = [];
    for (let n = 0; n < capacity + 5; n += 1) {
      statuses.push((await optOut(app, 'AAAAAAAAAAAAAAAAAAAAAA')).status);
    }
    expect(statuses.slice(0, capacity)).toEqual(Array(capacity).fill(404));
    expect(statuses.filter((status) => status === 429).length).toBeGreaterThan(0);
  });
});

describe('contacts: an opted-out contact (failure matrix: never alerted)', () => {
  async function optedOutContact(n: number) {
    const { app, logs } = setup();
    const { token, userId } = await signUp(app, n);
    const contact = await addOk(app, token, 1);
    expect((await optOut(app, await invite(app, token, contact.id))).status).toBe(200);
    return { app, logs, token, userId, contact };
  }

  it('cannot be invited again, confirmed or added again while it is listed', async () => {
    const { app, token, contact } = await optedOutContact(60);
    for (const path of [
      `/v1/contacts/${contact.id}/invite`,
      `/v1/contacts/${contact.id}/invite/confirm`,
    ]) {
      const res = await call(app, token, 'POST', path);
      expect(res.status, path).toBe(409);
      expect(await code(res)).toBe('contact_opted_out');
    }
    const again = await add(app, token, 1);
    expect(again.status).toBe(409);
    expect(await code(again)).toBe('contact_opted_out');
  });

  it('removing it leaves a tombstone with the number only, which blocks adding it again', async () => {
    const { app, token, userId, contact } = await optedOutContact(61);

    expect((await call(app, token, 'DELETE', `/v1/contacts/${contact.id}`)).status).toBe(204);
    expect((await list(app, token)).items).toEqual([]);

    const [tombstone] = await db.select().from(emergencyContacts);
    expect(tombstone).toMatchObject({
      id: contact.id,
      userId,
      phoneE164: contactPhone(1),
      name: '-',
      invitedAt: null,
    });
    expect(tombstone?.deletedAt).toBeInstanceOf(Date);
    expect(tombstone?.optedOutAt).toBeInstanceOf(Date);
    expect(await db.select().from(contactOptoutTokens)).toHaveLength(0);

    const again = await add(app, token, 1);
    expect(again.status).toBe(409);
    expect(await code(again)).toBe('contact_opted_out');
    // The tombstone is not a contact any more: not found, and it does not count to the limit.
    for (const [method, path, body] of [
      ['PATCH', `/v1/contacts/${contact.id}`, { name: 'Changed' }],
      ['DELETE', `/v1/contacts/${contact.id}`, undefined],
      ['POST', `/v1/contacts/${contact.id}/invite`, undefined],
    ] as const) {
      expect((await call(app, token, method, path, body)).status, path).toBe(404);
    }
    await resetLimits();
    for (let n = 2; n <= 6; n += 1) await addOk(app, token, n);
  });

  it('another user can still add the same number', async () => {
    const { app } = await optedOutContact(62);
    const other = await signUp(app, 63);
    await addOk(app, other.token, 1);
  });
});

describe('contacts: rate limits', () => {
  it('the sixth create in a burst is 429 with Retry-After', async () => {
    const { app } = setup();
    const { token } = await signUp(app, 70);
    for (let n = 1; n <= 5; n += 1) {
      const res = await add(app, token, 1, 'Test Contact');
      expect([201, 409]).toContain(res.status);
    }
    const res = await add(app, token, 2);
    expect(res.status).toBe(429);
    expect(Number(res.headers.get('retry-after'))).toBeGreaterThan(0);
    expect(await code(res)).toBe('rate_limited');
    const buckets = await pool.query<{ key: string }>(
      `select key from rate_limit_buckets where key like 'contacts:create%' order by key`,
    );
    expect(buckets.rows).toHaveLength(2);
    // The key names the bucket and the user id, never a phone number.
    expect(JSON.stringify(buckets.rows)).not.toContain('+91');
  });

  it(`${String(CONTACT_LIMITS.createDaily.capacity)} creates a day, ${String(CONTACT_LIMITS.inviteDaily.capacity)} invite links a day`, async () => {
    const { app } = setup();
    const { token, userId } = await signUp(app, 71);
    const contact = await addOk(app, token, 1);
    const empty = (name: string) =>
      pool.query(
        `insert into rate_limit_buckets (key, user_id, tokens, refilled_at)
         values ($1, $2, 0, now()) on conflict (key) do update set tokens = 0, refilled_at = now()`,
        [`contacts:${name}:${userId}`, userId],
      );

    await resetLimits();
    await empty('createDaily');
    const create = await add(app, token, 2);
    expect(create.status).toBe(429);
    // 20 a day: the next token is more than an hour away.
    expect(Number(create.headers.get('retry-after'))).toBeGreaterThan(3600);

    await empty('inviteDaily');
    const link = await call(app, token, 'POST', `/v1/contacts/${contact.id}/invite`);
    expect(link.status).toBe(429);
    expect(Number(link.headers.get('retry-after'))).toBeGreaterThan(1800);
  });
});

describe('contacts: logs', () => {
  it('no name, phone number, token or contact id in any log line of the whole flow', async () => {
    const { app, logs } = setup();
    const { token, userId } = await signUp(app, 80);
    const contact = await addOk(app, token, 1);
    await call(app, token, 'PATCH', `/v1/contacts/${contact.id}`, { name: 'Renamed Person' });
    const older = await invite(app, token, contact.id);
    const link = await invite(app, token, contact.id);
    await call(app, token, 'POST', `/v1/contacts/${contact.id}/invite/confirm`);
    await list(app, token);
    await optOut(app, link);
    await optOut(app, 'AAAAAAAAAAAAAAAAAAAAAA');
    await add(app, token, 1);
    await call(app, token, 'DELETE', `/v1/contacts/${contact.id}`);
    await call(app, token, 'PUT', '/v1/me/consents/sos_alerts', {
      ...DECISION,
      status: 'withdrawn',
    });

    const text = JSON.stringify(logs());
    for (const secret of [
      'Test Contact',
      'Renamed Person',
      contactPhone(1),
      contactPhone(1).slice(1),
      userPhone(80),
      older,
      link,
      contact.id,
    ]) {
      expect(text, secret).not.toContain(secret);
    }

    const changes = logs().filter((line) => line.message === 'contact changed');
    expect(changes.map((line) => line.action)).toEqual([
      'contact.created',
      'contact.renamed',
      'contact.invite_created',
      'contact.invite_created',
      'contact.invited',
      'contact.opted_out',
      'contact.deleted',
    ]);
    for (const line of changes) {
      expect(line.severity).toBe('INFO');
      // Only the fields every line has, plus `action` and, when signed in, `user_id`.
      expect(
        Object.keys(line)
          .filter((key) => !['user_id'].includes(key))
          .sort(),
      ).toEqual(['action', 'message', 'request_id', 'service', 'severity', 'timestamp', 'version']);
    }
    expect(changes[0]?.user_id).toBe(userId);
    expect(changes[5]).not.toHaveProperty('user_id');

    // The access log records route patterns, never the contact's id.
    const paths = logs()
      .filter((line) => line.message === 'request completed')
      .map((line) => String(line.path));
    expect(paths).toContain('/v1/contacts/:id/invite');
    expect(paths).toContain('/v1/public/contacts/opt-out');
  });
});

describe('contacts: tables', () => {
  it('CHECK constraints reject a bad name or number written past the API', async () => {
    const { app } = setup();
    const { userId } = await signUp(app, 90);
    const state = async (values: { name: string; phoneE164: string }) => {
      try {
        await db.insert(emergencyContacts).values({ userId, ...values });
      } catch (err) {
        return ((err as { cause?: { code?: string } }).cause ?? (err as { code?: string })).code;
      }
      return undefined;
    };
    expect(await state({ name: '', phoneE164: contactPhone(1) })).toBe('23514');
    expect(await state({ name: 'x'.repeat(81), phoneE164: contactPhone(1) })).toBe('23514');
    expect(await state({ name: 'Test Contact', phoneE164: '0000100001' })).toBe('23514');
    expect(await state({ name: 'Test Contact', phoneE164: contactPhone(1) })).toBeUndefined();
    expect(await state({ name: 'Test Contact', phoneE164: contactPhone(1) })).toBe('23505');
  });

  it('table and column comments mark the personal data', async () => {
    const rows = await pool.query<{ comment: string | null }>(
      `select obj_description('emergency_contacts'::regclass) as comment
       union all select col_description('emergency_contacts'::regclass, attnum) from pg_attribute
         where attrelid = 'emergency_contacts'::regclass and attname in ('name', 'phone_e164')`,
    );
    expect(rows.rows).toHaveLength(3);
    for (const row of rows.rows) expect(row.comment).toContain('PERSONAL DATA');
  });
});
