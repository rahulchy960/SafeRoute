// SPDX-License-Identifier: AGPL-3.0-only
import { eq } from 'drizzle-orm';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { auditLog, consentRecords, users } from '../../src/db/schema/index.js';
import {
  createTestKeys,
  phoneClaims,
  signToken,
  testVerifier,
  type TestKeys,
} from '../auth/tokens.js';
import { accessLines, buildTestApp } from '../helpers.js';
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

/** What the app sends after the age gate and the consent notice. */
const CONSENT = {
  ageConfirmed: true,
  noticeVersion: 'test-v1',
  noticeLocale: 'en',
  purposes: ['account_core'],
};

/** Bootstrap with valid consent merged into `body`; `bootstrapRaw` sends exactly `body`. */
async function bootstrap(
  app: ReturnType<typeof setup>['app'],
  token: string,
  body: Record<string, unknown> = {},
) {
  return bootstrapRaw(app, token, { consent: CONSENT, ...body });
}

async function bootstrapRaw(app: ReturnType<typeof setup>['app'], token: string, body?: unknown) {
  return app.request('/v1/me/bootstrap', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}

async function getMe(app: ReturnType<typeof setup>['app'], token: string) {
  return app.request('/v1/me', { headers: { Authorization: `Bearer ${token}` } });
}

const auditRows = () => db.select().from(auditLog);
const userRows = () => db.select().from(users);
const consentRows = () => db.select().from(consentRecords);

describe('POST /v1/me/bootstrap', () => {
  it('creates the user (201) with an audit row, phone from the token', async () => {
    const { app, logs } = setup();
    const res = await bootstrap(app, await tokenFor(1));
    expect(res.status).toBe(201);
    expect(res.headers.get('cache-control')).toBe('no-store');
    const me = (await res.json()) as Record<string, unknown>;
    expect(me).toMatchObject({
      phoneE164: '+910000000001',
      displayName: null,
      locale: 'en',
      role: 'user',
    });
    expect(Object.keys(me).sort()).toEqual([
      'createdAt',
      'displayName',
      'id',
      'locale',
      'phoneE164',
      'role',
    ]);
    expect(me.createdAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);

    const [row] = await userRows();
    expect(row).toMatchObject({ id: me.id, firebaseUid: 'test-uid-1', phoneE164: '+910000000001' });
    expect(row?.adultAttestedAt).toBeInstanceOf(Date);
    expect(row?.adultAttestedAt).toEqual(row?.createdAt);

    const consents = await consentRows();
    expect(consents).toHaveLength(1);
    expect(consents[0]).toMatchObject({
      userId: me.id,
      purpose: 'account_core',
      status: 'granted',
      noticeVersion: 'test-v1',
      noticeLocale: 'en',
    });
    // One transaction: the account, its consent and the attestation share one timestamp.
    expect(consents[0]?.decidedAt).toEqual(row?.createdAt);

    const audits = await auditRows();
    expect(audits).toHaveLength(2);
    expect(audits[0]).toMatchObject({
      actorType: 'user',
      actorUserId: me.id,
      action: 'user.created',
      entity: 'user',
      entityId: me.id,
      metadata: {},
    });
    expect(audits[1]).toMatchObject({
      actorType: 'user',
      actorUserId: me.id,
      action: 'consent.recorded',
      entity: 'consent',
      entityId: me.id,
      metadata: { purposes: ['account_core'], noticeVersion: 'test-v1' },
    });
    expect(JSON.stringify(audits)).not.toContain('+910000000001');

    const created = logs().find((line) => line.message === 'user created');
    expect(created).toMatchObject({ severity: 'INFO', user_id: me.id });
    const recorded = logs().find((line) => line.message === 'consent recorded');
    expect(recorded).toMatchObject({
      severity: 'INFO',
      user_id: me.id,
      purposes: ['account_core'],
    });
    expect(JSON.stringify(logs())).not.toContain('+910000000001');
    expect(JSON.stringify(logs())).not.toContain('test-uid-1');
  });

  it('is idempotent: a second call returns 200 with the unchanged user, body ignored', async () => {
    const { app } = setup();
    const token = await tokenFor(2);
    const first = await (
      await bootstrap(app, token, { locale: 'bn', displayName: '  First  ' })
    ).json();
    expect(first).toMatchObject({ locale: 'bn', displayName: 'First' });
    const [before] = await userRows();

    const res = await bootstrap(app, token, { locale: 'en', displayName: 'Second' });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual(first);
    const [after] = await userRows();
    expect(after?.updatedAt).toEqual(before?.updatedAt);
    expect(await auditRows()).toHaveLength(2);
    expect(await consentRows()).toHaveLength(1);
  });

  it('an existing user gets 200 with or without consent; the consent object is ignored', async () => {
    const { app } = setup();
    const token = await tokenFor(20);
    const first = await (await bootstrap(app, token)).json();

    const without = await bootstrapRaw(app, token);
    expect(without.status).toBe(200);
    expect(await without.json()).toEqual(first);

    const withOther = await bootstrapRaw(app, token, {
      consent: {
        ageConfirmed: false,
        noticeVersion: 'test-v2',
        noticeLocale: 'bn',
        purposes: ['account_core', 'sos_alerts'],
      },
    });
    expect(withOther.status).toBe(200);
    expect(await withOther.json()).toEqual(first);

    const consents = await consentRows();
    expect(consents).toHaveLength(1);
    expect(consents[0]).toMatchObject({ purpose: 'account_core', noticeVersion: 'test-v1' });
    expect(await auditRows()).toHaveLength(2);
  });

  it('records one consent row per purpose, without duplicates', async () => {
    const { app } = setup();
    const res = await bootstrap(app, await tokenFor(21), {
      consent: {
        ...CONSENT,
        noticeLocale: 'bn',
        purposes: ['sos_alerts', 'account_core', 'sos_alerts'],
      },
    });
    expect(res.status).toBe(201);
    const consents = await consentRows();
    expect(consents.map((row) => row.purpose).sort()).toEqual(['account_core', 'sos_alerts']);
    expect(new Set(consents.map((row) => row.noticeLocale))).toEqual(new Set(['bn']));
  });

  it.each([
    ['no body', 'consent_required', undefined],
    ['a body without consent', 'consent_required', { locale: 'bn' }],
    ['ageConfirmed false', 'adult_required', { consent: { ...CONSENT, ageConfirmed: false } }],
    [
      'ageConfirmed absent',
      'adult_required',
      { consent: { noticeVersion: 'test-v1', noticeLocale: 'en', purposes: ['account_core'] } },
    ],
  ])('new user, %s → 403 %s and nothing is written', async (_name, code, body) => {
    const { app } = setup();
    const token = await tokenFor(22);
    const res = await bootstrapRaw(app, token, body);
    expect(res.status).toBe(403);
    expect(res.headers.get('content-type')).toBe('application/problem+json');
    expect(await res.json()).toMatchObject({ code });
    expect(await userRows()).toHaveLength(0);
    expect(await consentRows()).toHaveLength(0);
    expect(await auditRows()).toHaveLength(0);
    // Still no account: the next call is told to bootstrap, not that it exists.
    expect(await (await getMe(app, token)).json()).toMatchObject({ code: 'bootstrap_required' });
  });

  it.each([
    [
      'an unknown purpose',
      { purposes: ['account_core', 'secret_purpose'] },
      'body.consent.purposes.1',
    ],
    [
      'a malformed purpose',
      { purposes: ['account_core', 'Secret Purpose!'] },
      'body.consent.purposes.1',
    ],
    ['purposes without account_core', { purposes: ['sos_alerts'] }, 'body.consent.purposes'],
    ['empty purposes', { purposes: [] }, 'body.consent.purposes'],
    [
      'a malformed noticeVersion',
      { noticeVersion: 'secret version' },
      'body.consent.noticeVersion',
    ],
    [
      'a noticeVersion over 40 characters',
      { noticeVersion: 'v'.repeat(41) },
      'body.consent.noticeVersion',
    ],
    ['an unknown noticeLocale', { noticeLocale: 'fr' }, 'body.consent.noticeLocale'],
    ['a non-boolean ageConfirmed', { ageConfirmed: 'yes' }, 'body.consent.ageConfirmed'],
  ])(
    'consent with %s → 400 validation_error, no echo, nothing written',
    async (_name, part, path) => {
      const { app } = setup();
      const res = await bootstrapRaw(app, await tokenFor(23), { consent: { ...CONSENT, ...part } });
      expect(res.status).toBe(400);
      const text = await res.text();
      const problem = JSON.parse(text) as { code: string; errors: { path: string }[] };
      expect(problem.code).toBe('validation_error');
      expect(problem.errors.map((error) => error.path)).toContain(path);
      expect(text.toLowerCase()).not.toContain('secret');
      expect(text).not.toContain('"fr"');
      expect(await userRows()).toHaveLength(0);
      expect(await consentRows()).toHaveLength(0);
      expect(await auditRows()).toHaveLength(0);
    },
  );

  it('never takes the phone number from the body', async () => {
    const { app } = setup();
    const res = await bootstrap(app, await tokenFor(3), { phoneE164: '+910000009999' });
    expect(res.status).toBe(201);
    expect(await res.json()).toMatchObject({ phoneE164: '+910000000003' });
  });

  it('10 parallel bootstraps create exactly one user and one set of consent rows', async () => {
    const { app } = setup();
    const token = await tokenFor(4);
    const responses = await Promise.all(Array.from({ length: 10 }, () => bootstrap(app, token)));
    const statuses = responses.map((res) => res.status).sort();
    expect(statuses).toEqual([200, 200, 200, 200, 200, 200, 200, 200, 200, 201]);
    const ids = new Set(
      await Promise.all(responses.map(async (res) => ((await res.json()) as { id: string }).id)),
    );
    expect(ids.size).toBe(1);
    expect(await userRows()).toHaveLength(1);
    expect(await consentRows()).toHaveLength(1);
    expect(await auditRows()).toHaveLength(2);
  });

  it('a soft-deleted account → 403 account_deleted', async () => {
    const { app } = setup();
    const token = await tokenFor(5);
    await bootstrap(app, token);
    await db.update(users).set({ deletedAt: new Date() });

    const res = await bootstrap(app, token);
    expect(res.status).toBe(403);
    expect(await res.json()).toMatchObject({ code: 'account_deleted' });
    expect((await getMe(app, token)).status).toBe(403);
  });

  it('a phone number held by another account → 409, nothing written', async () => {
    const { app } = setup();
    await bootstrap(app, await tokenFor(6));
    const samePhoneOtherUid = await signToken(keys, {
      claims: phoneClaims({ sub: 'test-uid-other', phone_number: '+910000000006' }),
    });

    const res = await bootstrap(app, samePhoneOtherUid);
    expect(res.status).toBe(409);
    const text = await res.text();
    expect(JSON.parse(text)).toMatchObject({ code: 'phone_already_registered' });
    expect(text).not.toContain('+910000000006');
    expect(await userRows()).toHaveLength(1);
    expect(await consentRows()).toHaveLength(1);
    expect(await auditRows()).toHaveLength(2);
  });

  it.each([
    ['empty displayName', { displayName: '' }, 'body.displayName'],
    ['blank displayName', { displayName: '    ' }, 'body.displayName'],
    ['displayName over 80 characters', { displayName: 'n'.repeat(81) }, 'body.displayName'],
    ['displayName with a newline', { displayName: 'Secret\nName' }, 'body.displayName'],
    ['displayName with NUL', { displayName: 'Secret\u0000Name' }, 'body.displayName'],
    ['unknown locale', { locale: 'fr' }, 'body.locale'],
  ])('%s → 400 validation_error without echoing the value', async (_name, body, path) => {
    const { app } = setup();
    const res = await bootstrap(app, await tokenFor(7), body);
    expect(res.status).toBe(400);
    const text = await res.text();
    const problem = JSON.parse(text) as { code: string; errors: { path: string }[] };
    expect(problem.code).toBe('validation_error');
    expect(problem.errors.map((error) => error.path)).toEqual([path]);
    expect(text).not.toContain('Secret');
    expect(text).not.toContain('"fr"');
    expect(await userRows()).toHaveLength(0);
  });

  it('accepts exactly 80 characters and stores the trimmed name', async () => {
    const { app } = setup();
    const res = await bootstrap(app, await tokenFor(8), { displayName: ` ${'n'.repeat(80)} ` });
    expect(res.status).toBe(201);
    expect(await res.json()).toMatchObject({ displayName: 'n'.repeat(80) });
  });
});

describe('GET /v1/me', () => {
  it('403 bootstrap_required before bootstrap, then 200 with the documented fields only', async () => {
    const { app, logs } = setup();
    const token = await tokenFor(10);

    const before = await getMe(app, token);
    expect(before.status).toBe(403);
    expect(await before.json()).toMatchObject({ code: 'bootstrap_required' });

    const created = await (await bootstrap(app, token)).json();
    const res = await getMe(app, token);
    expect(res.status).toBe(200);
    expect(res.headers.get('cache-control')).toBe('no-store');
    const me = (await res.json()) as Record<string, unknown>;
    expect(me).toEqual(created);
    for (const hidden of ['firebaseUid', 'deletedAt', 'updatedAt'])
      expect(me).not.toHaveProperty(hidden);

    // requireUser adds the internal user id (never the uid or phone) to the request logger.
    const line = accessLines(logs()).at(-1);
    expect(line).toMatchObject({ path: '/v1/me', status: 200, user_id: me.id });
  });

  it('a role change in the database applies on the next request (no caching)', async () => {
    const { app } = setup();
    const token = await tokenFor(11);
    await bootstrap(app, token);
    expect(await (await getMe(app, token)).json()).toMatchObject({ role: 'user' });

    await db.update(users).set({ role: 'moderator' }).where(eq(users.firebaseUid, 'test-uid-11'));
    expect(await (await getMe(app, token)).json()).toMatchObject({ role: 'moderator' });
  });
});
