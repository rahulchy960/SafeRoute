// SPDX-License-Identifier: AGPL-3.0-only
import { eq } from 'drizzle-orm';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { rateLimitBuckets, users } from '../../src/db/schema/index.js';
import { SEARCH_LIMITS } from '../../src/modules/search/service.js';
import {
  GeocoderError,
  type GeocoderFailure,
  type GeocoderProvider,
  type GeocoderQuery,
  type PlaceResult,
} from '../../src/modules/search/types.js';
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

/** Fake values only. The digits are distinctive so a leak into a log is easy to find. */
const PLACE: PlaceResult = {
  id: 'fake-place-1',
  name: 'Main Station RESULTNAME',
  label: 'Station Road, Example District RESULTLABEL',
  latitude: 11.777777,
  longitude: 22.888888,
  kind: 'station',
};
const BENGALI_QUERY = 'হাওড়া‌ স্টেশন';
const NEAR = { nearLatitude: 10.123456, nearLongitude: 20.987654 };

/** A geocoder that records what it was asked and answers from a script. */
function fakeGeocoder(reply: PlaceResult[] | GeocoderError = [PLACE]) {
  return scriptedGeocoder(() => reply);
}

/** Like `fakeGeocoder`, with a reply that depends on the call (nearby pass or wide pass). */
function scriptedGeocoder(reply: (query: GeocoderQuery) => PlaceResult[] | GeocoderError) {
  const calls: GeocoderQuery[] = [];
  const provider: GeocoderProvider = {
    name: 'fake',
    attribution: 'Fake attribution line',
    search: (query) => {
      calls.push(query);
      const answer = reply(query);
      return answer instanceof GeocoderError ? Promise.reject(answer) : Promise.resolve(answer);
    },
  };
  return { provider, calls };
}

/** A fake place `northDegrees` north of the coarse NEAR point (0.01 degrees is 1112 m). */
const placeNear = (id: string, northDegrees: number): PlaceResult => ({
  id,
  name: `Branch ${id} RESULTNAME`,
  label: 'Example District RESULTLABEL',
  latitude: 10.12 + northDegrees,
  longitude: 20.99,
  kind: 'bank',
});
const NEARBY = [placeNear('n1', 0.02), placeNear('n2', 0.1), placeNear('n3', 0.3)];

function setup(
  geocoder: GeocoderProvider = fakeGeocoder().provider,
  overrides: Record<string, string> = {},
) {
  return buildTestApp(overrides, undefined, {
    verifier: testVerifier(keys),
    db,
    geocoder,
  });
}

type TestApp = ReturnType<typeof setup>['app'];

function tokenFor(n: number) {
  const phone = `+91000000${String(n).padStart(4, '0')}`;
  return signToken(keys, {
    claims: phoneClaims({ sub: `test-uid-${String(n)}`, phone_number: phone }),
  });
}

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

/** POST with a JSON body: nothing about a search is ever put in the URL (ADR 0019). */
async function search(app: TestApp, token: string | undefined, body: Record<string, unknown>) {
  return await app.request('/v1/search', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token === undefined ? {} : { Authorization: `Bearer ${token}` }),
    },
    body: JSON.stringify(body),
  });
}

interface Problem {
  code: string;
  errors?: { path: string; code: string }[];
}

describe('POST /v1/search', () => {
  it('401 without a token, with WWW-Authenticate: Bearer, and the provider is not called', async () => {
    const { provider, calls } = fakeGeocoder();
    const { app } = setup(provider);
    const res = await search(app, undefined, { q: 'station' });
    expect(res.status).toBe(401);
    expect(res.headers.get('www-authenticate')).toBe('Bearer');
    expect(await res.json()).toMatchObject({ code: 'unauthorized' });
    expect(calls).toHaveLength(0);
  });

  it('403 bootstrap_required for a token without an account; 403 account_deleted after deletion', async () => {
    const { provider, calls } = fakeGeocoder();
    const { app } = setup(provider);
    const stranger = await search(app, await tokenFor(1), { q: 'station' });
    expect(stranger.status).toBe(403);
    expect(await stranger.json()).toMatchObject({ code: 'bootstrap_required' });

    const { token, userId } = await signUp(app, 2);
    await db.update(users).set({ deletedAt: new Date() }).where(eq(users.id, userId));
    const deleted = await search(app, token, { q: 'station' });
    expect(deleted.status).toBe(403);
    expect(await deleted.json()).toMatchObject({ code: 'account_deleted' });
    expect(calls).toHaveLength(0);
  });

  it('returns the mapped results and the attribution, never cached', async () => {
    const { provider, calls } = fakeGeocoder();
    const { app } = setup(provider);
    const { token } = await signUp(app, 3);

    const res = await search(app, token, { q: 'station' });
    expect(res.status).toBe(200);
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(res.headers.get('x-request-id')).toBeTruthy();
    expect(await res.json()).toEqual({ results: [PLACE], attribution: 'Fake attribution line' });
    // Defaults: English, 6 results, the default bias (coarse already).
    expect(calls).toEqual([
      { query: 'station', nearLatitude: 22.57, nearLongitude: 88.36, language: 'en', limit: 6 },
    ]);
  });

  it('an empty result list is a 200, not an error', async () => {
    const { app } = setup(fakeGeocoder([]).provider);
    const { token } = await signUp(app, 4);
    const res = await search(app, token, { q: 'nowhere at all' });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ results: [], attribution: 'Fake attribution line' });
  });

  it('normalises the query and keeps Bengali joiners', async () => {
    const { provider, calls } = fakeGeocoder();
    const { app } = setup(provider);
    const { token } = await signUp(app, 5);

    expect((await search(app, token, { q: `  ${BENGALI_QUERY}   ` })).status).toBe(200);
    expect((await search(app, token, { q: '  main    station ' })).status).toBe(200);
    // Decomposed vowel sign (U+09C7 U+09BE) arrives composed (U+09CB).
    expect((await search(app, token, { q: 'কোন পথ', language: 'bn' })).status).toBe(200);

    expect(calls.map((call) => call.query)).toEqual([BENGALI_QUERY, 'main station', 'কোন পথ']);
    expect(calls[0]?.query).toContain('‌');
    expect(calls[2]?.language).toBe('bn');
  });

  it('coarsens `near` to two decimals before the provider sees it', async () => {
    const { provider, calls } = fakeGeocoder();
    const { app } = setup(provider);
    const { token } = await signUp(app, 6);

    expect((await search(app, token, { q: 'station', ...NEAR })).status).toBe(200);
    expect(calls[0]).toMatchObject({ nearLatitude: 10.12, nearLongitude: 20.99 });
  });

  it.each([
    ['q of one code point', { q: 'a' }, 'body.q'],
    ['q of 101 code points', { q: 'a'.repeat(101) }, 'body.q'],
    ['q of whitespace only', { q: '      ' }, 'body.q'],
    ['q with a control character', { q: 'sta\u0007tion' }, 'body.q'],
    ['q with a newline', { q: 'sta\ntion' }, 'body.q'],
    ['latitude without longitude', { q: 'station', nearLatitude: 10.5 }, 'body.nearLongitude'],
    ['longitude without latitude', { q: 'station', nearLongitude: 20.5 }, 'body.nearLatitude'],
    [
      'latitude out of range',
      { q: 'station', nearLatitude: 90.5, nearLongitude: 20.5 },
      'body.nearLatitude',
    ],
    [
      'longitude out of range',
      { q: 'station', nearLatitude: 10.5, nearLongitude: -180.5 },
      'body.nearLongitude',
    ],
    [
      'latitude that is not a number',
      { q: 'station', nearLatitude: 'SECRETPLACE', nearLongitude: 20.5 },
      'body.nearLatitude',
    ],
    ['an unknown language', { q: 'station', language: 'fr' }, 'body.language'],
    ['limit 0', { q: 'station', limit: 0 }, 'body.limit'],
    ['limit 11', { q: 'station', limit: 11 }, 'body.limit'],
    ['a fractional limit', { q: 'station', limit: 2.5 }, 'body.limit'],
  ])('%s → 400 validation_error, no echo, provider not called', async (_name, params, path) => {
    const { provider, calls } = fakeGeocoder();
    const { app } = setup(provider);
    const { token } = await signUp(app, 7);

    const res = await search(app, token, params);
    expect(res.status).toBe(400);
    const text = await res.text();
    const problem = JSON.parse(text) as Problem;
    expect(problem.code).toBe('validation_error');
    expect([...new Set(problem.errors?.map((error) => error.path))]).toEqual([path]);
    for (const value of Object.values(params)) {
      if (String(value).trim().length > 3) expect(text).not.toContain(String(value));
    }
    expect(calls).toHaveLength(0);
  });

  it('GET is gone, and a search in the query string is ignored and never logged', async () => {
    const { provider, calls } = fakeGeocoder();
    const { app, lines } = setup(provider);
    const { token } = await signUp(app, 11);
    const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };

    // The old form of the endpoint no longer exists.
    const get = await app.request('/v1/search?q=URLSECRET&nearLatitude=10.123456', { headers });
    expect(get.status).toBe(404);
    // A body-less POST is invalid even when the URL carries a query: the URL is not read.
    const post = await app.request('/v1/search?q=URLSECRET', {
      method: 'POST',
      headers,
      body: '{}',
    });
    expect(post.status).toBe(400);
    expect(((await post.json()) as Problem).errors?.[0]?.path).toBe('body.q');

    expect(calls).toHaveLength(0);
    const raw = lines.join(' ');
    for (const secret of ['URLSECRET', '10.123456', '?']) expect(raw).not.toContain(secret);
  });

  it('a body that is not JSON → 400, nothing echoed', async () => {
    const { provider, calls } = fakeGeocoder();
    const { app } = setup(provider);
    const { token } = await signUp(app, 12);
    const res = await app.request('/v1/search', {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: '{"q": "BODYSECRET',
    });
    expect(res.status).toBe(400);
    expect(await res.text()).not.toContain('BODYSECRET');
    expect(calls).toHaveLength(0);
  });

  it('a missing q → 400', async () => {
    const { app } = setup();
    const { token } = await signUp(app, 8);
    const res = await search(app, token, {});
    expect(res.status).toBe(400);
    expect(((await res.json()) as Problem).errors?.[0]?.path).toBe('body.q');
  });

  it.each([
    ['q of exactly 2 code points', { q: 'ab' }],
    ['q of exactly 100 code points', { q: 'a'.repeat(100) }],
    ['limit 1', { q: 'station', limit: 1 }],
    ['limit 10', { q: 'station', limit: 10 }],
    ['the edge of the coordinate range', { q: 'station', nearLatitude: -90, nearLongitude: 180 }],
  ])('%s is accepted', async (_name, params) => {
    const { app } = setup();
    const { token } = await signUp(app, 9);
    expect((await search(app, token, params)).status).toBe(200);
  });

  it('503 search_not_configured when the instance has no geocoder', async () => {
    const { app } = buildTestApp({}, undefined, { verifier: testVerifier(keys), db });
    const { token } = await signUp(app, 10);
    const res = await search(app, token, { q: 'station' });
    expect(res.status).toBe(503);
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(await res.json()).toMatchObject({ code: 'search_not_configured' });
  });
});

describe('POST /v1/search: local-first ranking (ADR 0018)', () => {
  const tokensLeft = async (key: string) =>
    Number(
      (await db.select().from(rateLimitBuckets).where(eq(rateLimitBuckets.key, key)))[0]?.tokens,
    );

  it('without an area: one unfiltered call and no distances', async () => {
    const { provider, calls } = fakeGeocoder(NEARBY);
    const { app } = setup(provider);
    const { token } = await signUp(app, 50);

    const res = await search(app, token, { q: 'bank' });
    expect(calls).toHaveLength(1);
    expect(calls[0]).not.toHaveProperty('withinMeters');
    const body = (await res.json()) as { results: Record<string, unknown>[] };
    expect(body.results).toHaveLength(3);
    for (const result of body.results) expect(result).not.toHaveProperty('distanceMeters');
  });

  it('enough nearby places: one filtered call, one token, distances from the coarse point', async () => {
    const { provider, calls } = scriptedGeocoder(() => NEARBY);
    const { app } = setup(provider, { SEARCH_GLOBAL_DAILY_LIMIT: '10' });
    const { token, userId } = await signUp(app, 51);

    const res = await search(app, token, { q: 'bank', ...NEAR });
    expect(res.status).toBe(200);
    expect(calls).toEqual([
      {
        query: 'bank',
        nearLatitude: 10.12,
        nearLongitude: 20.99,
        language: 'en',
        limit: 6,
        withinMeters: 50_000,
      },
    ]);
    const body = (await res.json()) as { results: { id: string; distanceMeters: number }[] };
    expect(body.results.map((result) => [result.id, result.distanceMeters])).toEqual([
      ['n1', 2200],
      ['n2', 11_100],
      ['n3', 33_400],
    ]);
    expect(await tokensLeft('search:global')).toBeCloseTo(9, 1);
    expect(await tokensLeft(`search:burst:${userId}`)).toBeCloseTo(
      SEARCH_LIMITS.userBurst.capacity - 1,
      0,
    );
  });

  it('too few nearby: a wide call follows, nearby first, and BOTH calls spend the limits', async () => {
    const far = { ...PLACE, id: 'far-1' };
    const { provider, calls } = scriptedGeocoder((query) =>
      query.withinMeters === undefined ? [far, NEARBY[0] ?? far] : NEARBY.slice(0, 1),
    );
    const { app, logs } = setup(provider, { SEARCH_GLOBAL_DAILY_LIMIT: '10' });
    const { token, userId } = await signUp(app, 52);

    const res = await search(app, token, { q: 'bank', ...NEAR });
    expect(res.status).toBe(200);
    expect(calls.map((call) => call.withinMeters)).toEqual([50_000, undefined]);
    const body = (await res.json()) as { results: { id: string; distanceMeters: number }[] };
    expect(body.results.map((result) => result.id)).toEqual(['n1', 'far-1']);
    expect(body.results[1]?.distanceMeters).toBeGreaterThan(200_000);

    expect(await tokensLeft('search:global')).toBeCloseTo(8, 1);
    expect(await tokensLeft(`search:daily:${userId}`)).toBeCloseTo(
      SEARCH_LIMITS.userDaily.capacity - 2,
      0,
    );
    expect(logs().find((line) => line.message === 'geocoder call')).toMatchObject({
      outcome: 'ok',
      result_count: 2,
      local_count: 1,
      provider_calls: 2,
      wide_pass: 'ok',
    });
  });

  it('the budget runs out between the passes: nearby results alone; with none, 503', async () => {
    const one = scriptedGeocoder((query) =>
      query.withinMeters === undefined ? [PLACE] : NEARBY.slice(0, 1),
    );
    const first = setup(one.provider, { SEARCH_GLOBAL_DAILY_LIMIT: '1' });
    const alice = await signUp(first.app, 53);
    const kept = await search(first.app, alice.token, { q: 'bank', ...NEAR });
    expect(kept.status).toBe(200);
    expect(((await kept.json()) as { results: { id: string }[] }).results.map((r) => r.id)).toEqual(
      ['n1'],
    );
    expect(one.calls).toHaveLength(1);
    expect(first.logs().find((line) => line.message === 'geocoder call')).toMatchObject({
      provider_calls: 1,
      wide_pass: 'refused',
    });

    await truncateAll(pool);
    const none = scriptedGeocoder((query) => (query.withinMeters === undefined ? [PLACE] : []));
    const second = setup(none.provider, { SEARCH_GLOBAL_DAILY_LIMIT: '1' });
    const bob = await signUp(second.app, 54);
    const refused = await search(second.app, bob.token, { q: 'bank', ...NEAR });
    expect(refused.status).toBe(503);
    expect(await refused.json()).toMatchObject({ code: 'search_unavailable' });
    expect(refused.headers.get('retry-after')).not.toBeNull();
    expect(none.calls).toHaveLength(1);
  });

  it('the provider fails on the wide pass: nearby results alone', async () => {
    const { provider } = scriptedGeocoder((query) =>
      query.withinMeters === undefined ? new GeocoderError('timeout') : NEARBY.slice(0, 2),
    );
    const { app, logs } = setup(provider);
    const { token } = await signUp(app, 55);
    const res = await search(app, token, { q: 'bank', ...NEAR });
    expect(res.status).toBe(200);
    expect(((await res.json()) as { results: unknown[] }).results).toHaveLength(2);
    expect(logs().find((line) => line.message === 'geocoder call')).toMatchObject({
      outcome: 'ok',
      wide_pass: 'timeout',
    });
  });

  it('the radius and the minimum come from the configuration', async () => {
    const { provider, calls } = scriptedGeocoder(() => NEARBY.slice(0, 1));
    const { app } = setup(provider, {
      SEARCH_NEARBY_RADIUS_KM: '20',
      SEARCH_MIN_LOCAL_RESULTS: '1',
    });
    const { token } = await signUp(app, 56);
    expect((await search(app, token, { q: 'bank', ...NEAR })).status).toBe(200);
    // One nearby result is enough now, so there is no wide call.
    expect(calls.map((call) => call.withinMeters)).toEqual([20_000]);
  });
});

describe('POST /v1/search: provider failures', () => {
  const KINDS: GeocoderFailure[] = [
    'auth',
    'rate_limited',
    'timeout',
    'network',
    'malformed',
    'oversized',
    'upstream',
  ];

  it.each(KINDS)(
    'provider failure "%s" → 503 search_unavailable, never 401 or 403',
    async (kind) => {
      const { app, logs } = setup(fakeGeocoder(new GeocoderError(kind, 42)).provider);
      const { token } = await signUp(app, 20);

      const res = await search(app, token, { q: 'station' });
      expect(res.status).toBe(503);
      expect(res.headers.get('content-type')).toBe('application/problem+json');
      expect(res.headers.get('www-authenticate')).toBeNull();
      expect(await res.json()).toMatchObject({ code: 'search_unavailable' });
      expect(res.headers.get('retry-after')).toBe(kind === 'rate_limited' ? '42' : null);

      const line = logs().find((entry) => entry.message === 'geocoder call');
      expect(line).toMatchObject({ outcome: kind, result_count: 0 });
      // Our key being refused is the one failure someone must act on.
      expect(line?.severity).toBe(kind === 'auth' ? 'ERROR' : 'WARNING');
      expect(line?.alert).toBe(kind === 'auth' ? 'geocoder_key_rejected' : undefined);
    },
  );

  it('a provider 429 without Retry-After still tells the client to wait', async () => {
    const { app } = setup(fakeGeocoder(new GeocoderError('rate_limited')).provider);
    const { token } = await signUp(app, 21);
    const res = await search(app, token, { q: 'station' });
    expect(res.status).toBe(503);
    expect(res.headers.get('retry-after')).toBe('60');
  });
});

describe('POST /v1/search: rate limits', () => {
  it('a user gets the burst, then 429 rate_limited with Retry-After; other users are unaffected', async () => {
    const { provider, calls } = fakeGeocoder();
    const { app } = setup(provider);
    const alice = await signUp(app, 30);
    const bob = await signUp(app, 31);
    const burst = SEARCH_LIMITS.userBurst.capacity;

    const statuses: number[] = [];
    for (let i = 0; i < burst; i += 1) {
      statuses.push((await search(app, alice.token, { q: 'station' })).status);
    }
    expect(statuses).toEqual(Array(burst).fill(200));

    const denied = await search(app, alice.token, { q: 'station' });
    expect(denied.status).toBe(429);
    expect(denied.headers.get('content-type')).toBe('application/problem+json');
    expect(denied.headers.get('cache-control')).toBe('no-store');
    expect(await denied.json()).toMatchObject({ code: 'rate_limited' });
    expect(denied.headers.get('retry-after')).toBe('1');
    expect(calls).toHaveLength(burst);

    expect((await search(app, bob.token, { q: 'station' })).status).toBe(200);

    // After the refill time alice may search again.
    await pool.query(
      `update rate_limit_buckets set refilled_at = refilled_at - interval '2 seconds' where key = $1`,
      [`search:burst:${alice.userId}`],
    );
    expect((await search(app, alice.token, { q: 'station' })).status).toBe(200);
  });

  it('parallel requests from one user never exceed the burst', async () => {
    const { provider, calls } = fakeGeocoder();
    const { app } = setup(provider);
    const { token } = await signUp(app, 32);
    const burst = SEARCH_LIMITS.userBurst.capacity;

    const responses = await Promise.all(
      Array.from({ length: burst + 15 }, () => search(app, token, { q: 'station' })),
    );
    expect(responses.filter((res) => res.status === 200)).toHaveLength(burst);
    expect(responses.filter((res) => res.status === 429)).toHaveLength(15);
    expect(calls).toHaveLength(burst);
  });

  it('the daily bucket denies with a long Retry-After once it is empty', async () => {
    const { app } = setup();
    const { token, userId } = await signUp(app, 33);
    expect((await search(app, token, { q: 'station' })).status).toBe(200);
    await pool.query(
      `update rate_limit_buckets set tokens = 0, refilled_at = now() where key = $1`,
      [`search:daily:${userId}`],
    );

    const res = await search(app, token, { q: 'station' });
    expect(res.status).toBe(429);
    // 86 400 s / 1000 tokens = 86.4 s for one token.
    expect(Number(res.headers.get('retry-after'))).toBeGreaterThanOrEqual(86);
    expect(Number(res.headers.get('retry-after'))).toBeLessThanOrEqual(87);
  });

  it('the global budget → 503 search_unavailable with Retry-After, for every user', async () => {
    const { provider, calls } = fakeGeocoder();
    const { app } = setup(provider, { SEARCH_GLOBAL_DAILY_LIMIT: '3' });
    const alice = await signUp(app, 34);
    const bob = await signUp(app, 35);

    expect((await search(app, alice.token, { q: 'station' })).status).toBe(200);
    expect((await search(app, bob.token, { q: 'station' })).status).toBe(200);
    expect((await search(app, alice.token, { q: 'station' })).status).toBe(200);

    for (const token of [alice.token, bob.token]) {
      const res = await search(app, token, { q: 'station' });
      expect(res.status).toBe(503);
      expect(await res.json()).toMatchObject({ code: 'search_unavailable' });
      // 86 400 s / 3 tokens = 28 800 s for one token.
      expect(Number(res.headers.get('retry-after'))).toBeGreaterThan(28_700);
    }
    expect(calls).toHaveLength(3);
  });

  it('keeps three rows per user plus one shared row, and drops the user rows with the user', async () => {
    const { app } = setup();
    const { token, userId } = await signUp(app, 36);
    for (let i = 0; i < 5; i += 1) await search(app, token, { q: 'station' });

    const rows = await db.select().from(rateLimitBuckets);
    expect(rows.map((row) => row.key).sort()).toEqual(
      [`search:burst:${userId}`, `search:daily:${userId}`, 'search:global'].sort(),
    );
    expect(rows.find((row) => row.key === 'search:global')?.userId).toBeNull();

    await db.delete(users).where(eq(users.id, userId));
    expect((await db.select().from(rateLimitBuckets)).map((row) => row.key)).toEqual([
      'search:global',
    ]);
  });

  it('a rejected request (400) spends no tokens', async () => {
    const { app } = setup();
    const { token } = await signUp(app, 37);
    await search(app, token, { q: 'a' });
    expect(await db.select().from(rateLimitBuckets)).toHaveLength(0);
  });
});

// These tests see the API's OWN log lines. The platform's request log (the URL) is outside any
// test here: that is why the search travels in a body (ADR 0019).
describe("POST /v1/search: nothing about the search is in the API's log lines", () => {
  it('logs no query, coordinate, result or key, in any outcome', async () => {
    const outcomes: [PlaceResult[] | GeocoderError, number][] = [
      [[PLACE], 200],
      [new GeocoderError('auth'), 503],
      [new GeocoderError('timeout'), 503],
    ];
    for (const [index, [reply, status]] of outcomes.entries()) {
      const { app, lines, logs } = setup(fakeGeocoder(reply).provider, {
        GEOCODING_API_KEY: 'FAKEKEY-do-not-log-9f3a',
        GEOCODING_PROVIDER: 'geoapify',
      });
      const { token, userId } = await signUp(app, 40 + index);

      const res = await search(app, token, { q: BENGALI_QUERY, language: 'bn', ...NEAR });
      expect(res.status).toBe(status);
      // A rejected request is logged too, and must not echo what was sent.
      await search(app, token, { q: BENGALI_QUERY, nearLatitude: 10.123456 });

      const raw = lines.join('\n');
      for (const secret of [
        BENGALI_QUERY,
        'হাওড়া',
        'স্টেশন',
        encodeURIComponent('হাওড়া'),
        '10.123456',
        '20.987654',
        '10.12',
        '20.99',
        '11.777777',
        '22.888888',
        'RESULTNAME',
        'RESULTLABEL',
        'fake-place-1',
        'distanceMeters',
        'withinMeters',
        'FAKEKEY',
        '?',
        'nearLatitude',
        'language=',
      ]) {
        expect(raw, `log contains ${secret}`).not.toContain(secret);
      }

      // What IS logged: one provider line with outcome, latency and count; the route pattern.
      const call = logs().filter((line) => line.message === 'geocoder call');
      expect(call).toHaveLength(1);
      expect(Object.keys(call[0] ?? {}).sort()).toEqual(
        [
          ...(status === 503 && index === 1 ? ['alert'] : []),
          // Counts and a fixed word only, on a search that reached the provider.
          ...(status === 200 ? ['local_count', 'provider_calls', 'wide_pass'] : []),
          'latency_ms',
          'message',
          'outcome',
          'request_id',
          'result_count',
          'service',
          'severity',
          'timestamp',
          'user_id',
          'version',
        ].sort(),
      );
      expect(call[0]).toMatchObject({ user_id: userId, result_count: status === 200 ? 1 : 0 });
      const access = accessLines(logs()).filter((line) => line.path === '/v1/search');
      expect(access.map((line) => line.status)).toEqual([status, 400]);
    }
  });
});
