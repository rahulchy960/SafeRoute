// SPDX-License-Identifier: AGPL-3.0-only
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { rateLimitBuckets } from '../../src/db/schema/index.js';
import { ROUTING_LIMITS } from '../../src/modules/routing/service.js';
import {
  RoutingError,
  type ProviderRoute,
  type RouteQuery,
  type RoutingFailure,
  type RoutingProvider,
} from '../../src/modules/routing/types.js';
import {
  createTestKeys,
  phoneClaims,
  signToken,
  testVerifier,
  type TestKeys,
} from '../auth/tokens.js';
import { accessLines, buildTestApp } from '../helpers.js';
import { GEOMETRY, MONUMENT, OUTSIDE, STATION_A, STATION_NORTH } from '../routing/fakes.js';
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

const ROUTE: ProviderRoute = { distanceMeters: 5100, durationSeconds: 3661, geometry: GEOMETRY };
/** Invented points inside the covered area, with digits that are easy to find in a log. */
const LOG_ORIGIN = { latitude: 22.987654, longitude: 88.123456 };
const LOG_DESTINATION = { latitude: 22.912345, longitude: 88.198765 };
const WALK = { origin: STATION_A, destination: MONUMENT, mode: 'walking' };

/** A routing engine that records what it was asked and answers from a script. */
function fakeRouting(reply: ProviderRoute[] | RoutingError = [ROUTE]) {
  const calls: RouteQuery[] = [];
  const provider: RoutingProvider = {
    attribution: 'Fake map credit',
    route: (query) => {
      calls.push(query);
      return reply instanceof RoutingError ? Promise.reject(reply) : Promise.resolve(reply);
    },
  };
  return { provider, calls };
}

function setup(
  routing: RoutingProvider | null = fakeRouting().provider,
  overrides: Record<string, string> = {},
) {
  return buildTestApp(overrides, undefined, {
    verifier: testVerifier(keys),
    db,
    ...(routing ? { routing } : {}),
  });
}

type TestApp = ReturnType<typeof setup>['app'];

function tokenFor(n: number) {
  return signToken(keys, {
    claims: phoneClaims({
      sub: `test-uid-${String(n)}`,
      phone_number: `+91000000${String(n).padStart(4, '0')}`,
    }),
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

/** POST with a JSON body: no position is ever put in the URL (ADR 0019). */
async function routes(app: TestApp, token: string | undefined, body: unknown, path = '/v1/routes') {
  return await app.request(path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token === undefined ? {} : { Authorization: `Bearer ${token}` }),
    },
    body: typeof body === 'string' ? body : JSON.stringify(body),
  });
}

interface Problem {
  code: string;
  detail: string;
  errors?: { path: string; code: string }[];
}
interface RoutesBody {
  routes: {
    id: string;
    distanceMeters: number;
    durationSeconds: number;
    geometry: unknown;
    bbox: number[];
  }[];
  attribution: string;
}

describe('POST /v1/routes', () => {
  it('401 without a token, 403 without an account, and the engine is not called', async () => {
    const { provider, calls } = fakeRouting();
    const { app } = setup(provider);
    const anonymous = await routes(app, undefined, WALK);
    expect(anonymous.status).toBe(401);
    expect(anonymous.headers.get('www-authenticate')).toBe('Bearer');
    const stranger = await routes(app, await tokenFor(1), WALK);
    expect(stranger.status).toBe(403);
    expect(await stranger.json()).toMatchObject({ code: 'bootstrap_required' });
    expect(calls).toHaveLength(0);
  });

  it.each(['walking', 'driving'] as const)(
    'returns up to three %s routes, never cached',
    async (mode) => {
      const { provider, calls } = fakeRouting([
        ROUTE,
        { ...ROUTE, distanceMeters: 5500 },
        { ...ROUTE, durationSeconds: 4000 },
      ]);
      const { app } = setup(provider);
      const { token } = await signUp(app, 2);

      const res = await routes(app, token, {
        ...WALK,
        mode,
        departAt: '2026-01-01T18:30:00+05:30',
      });
      expect(res.status).toBe(200);
      expect(res.headers.get('cache-control')).toBe('no-store');
      const body = (await res.json()) as RoutesBody;
      expect(body.attribution).toBe('Fake map credit');
      expect(body.routes).toHaveLength(3);
      expect(body.routes[0]).toEqual({
        id: expect.stringMatching(/^[0-9a-f-]{36}$/) as string,
        distanceMeters: 5100,
        durationSeconds: 3661,
        geometry: { encoding: 'polyline6', value: GEOMETRY },
        bbox: [88.34264, 22.54508, 88.351, 22.58287],
      });
      expect(new Set(body.routes.map((route) => route.id)).size).toBe(3);
      // The engine gets the points and the mode, and nothing else (departAt is not used yet).
      expect(calls).toEqual([{ origin: STATION_A, destination: MONUMENT, mode }]);
    },
  );

  it.each<[string, unknown, string]>([
    ['no origin', { destination: MONUMENT, mode: 'walking' }, 'body.origin'],
    [
      'a latitude out of range',
      { ...WALK, origin: { latitude: 91, longitude: 88 } },
      'body.origin.latitude',
    ],
    [
      'a longitude out of range',
      { ...WALK, destination: { latitude: 22, longitude: 181 } },
      'body.destination.longitude',
    ],
    [
      'a coordinate as text',
      { ...WALK, origin: { latitude: '22.5', longitude: 88.3 } },
      'body.origin.latitude',
    ],
    ['no longitude', { ...WALK, destination: { latitude: 22.5 } }, 'body.destination.longitude'],
    ['an unknown mode', { ...WALK, mode: 'cycling' }, 'body.mode'],
    ['no mode', { origin: STATION_A, destination: MONUMENT }, 'body.mode'],
    ['departAt without an offset', { ...WALK, departAt: '2026-01-01T18:30:00' }, 'body.departAt'],
    ['departAt that is not a time', { ...WALK, departAt: 'tomorrow' }, 'body.departAt'],
    ['the same point twice', { ...WALK, destination: STATION_A }, 'body.destination'],
    [
      'points 15 m apart',
      {
        ...WALK,
        destination: { latitude: STATION_A.latitude + 0.000135, longitude: STATION_A.longitude },
      },
      'body.destination',
    ],
  ])('400 validation_error for %s, nothing echoed, no token spent', async (_name, body, path) => {
    const { provider, calls } = fakeRouting();
    const { app } = setup(provider);
    const { token } = await signUp(app, 3);
    const res = await routes(app, token, body);
    expect(res.status).toBe(400);
    const problem = (await res.json()) as Problem;
    expect(problem.code).toBe('validation_error');
    expect(problem.errors?.map((issue) => issue.path)).toContain(path);
    expect(JSON.stringify(problem)).not.toMatch(/22\.5|88\.3|cycling|tomorrow/);
    expect(calls).toHaveLength(0);
    expect(await db.select().from(rateLimitBuckets)).toHaveLength(0);
  });

  it('points 25 m apart are accepted; a body that is not JSON is a 400', async () => {
    const { app } = setup();
    const { token } = await signUp(app, 4);
    const near = { latitude: STATION_A.latitude + 0.000225, longitude: STATION_A.longitude };
    expect((await routes(app, token, { ...WALK, destination: near })).status).toBe(200);
    expect((await routes(app, token, 'origin=22.58287,88.34281')).status).toBe(400);
  });

  it('GET is not a route, and positions in a query string are ignored', async () => {
    const { provider, calls } = fakeRouting();
    const { app } = setup(provider);
    const { token } = await signUp(app, 5);
    const get = await app.request('/v1/routes?origin=22.58287,88.34281', {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(get.status).toBe(404);
    expect(
      (await routes(app, token, {}, '/v1/routes?origin=22.58287,88.34281&mode=walking')).status,
    ).toBe(400);
    expect(calls).toHaveLength(0);
  });

  it('503 routing_not_configured when the instance has no routing engine', async () => {
    const { app } = setup(null);
    const { token } = await signUp(app, 6);
    const res = await routes(app, token, WALK);
    expect(res.status).toBe(503);
    expect(await res.json()).toMatchObject({ code: 'routing_not_configured' });
  });
});

describe('POST /v1/routes: the covered area and distance limits', () => {
  it.each([
    ['the origin', { ...WALK, origin: OUTSIDE }],
    ['the destination', { ...WALK, mode: 'driving', destination: OUTSIDE }],
    [
      'both points',
      { origin: OUTSIDE, destination: { latitude: 10.6, longitude: 20.6 }, mode: 'driving' },
    ],
  ])(
    '422 outside_covered_area when %s is outside; no coordinate echoed; the engine is not asked',
    async (_name, body) => {
      const { provider, calls } = fakeRouting();
      const { app } = setup(provider);
      const { token } = await signUp(app, 10);
      const res = await routes(app, token, body);
      expect(res.status).toBe(422);
      expect(res.headers.get('content-type')).toBe('application/problem+json');
      const problem = (await res.json()) as Problem;
      expect(problem).toMatchObject({
        code: 'outside_covered_area',
        detail: "Routes aren't available here yet.",
      });
      expect(JSON.stringify(problem)).not.toMatch(/10\.5|20\.5|22\.5|88\.3/);
      expect(calls).toHaveLength(0);
    },
  );

  it('422 route_too_long for a 457 km walk; the same pair is fine for driving', async () => {
    const { provider, calls } = fakeRouting();
    const { app } = setup(provider);
    const { token } = await signUp(app, 11);
    const far = { origin: STATION_A, destination: STATION_NORTH };
    const walk = await routes(app, token, { ...far, mode: 'walking' });
    expect(walk.status).toBe(422);
    expect(await walk.json()).toMatchObject({ code: 'route_too_long' });
    expect(calls).toHaveLength(0);
    expect((await routes(app, token, { ...far, mode: 'driving' })).status).toBe(200);
  });
});

describe('POST /v1/routes: engine failures', () => {
  it.each<[RoutingFailure, number, string, boolean]>([
    ['no_route', 404, 'no_route_found', false],
    ['not_routable', 422, 'location_not_routable', false],
    ['timeout', 503, 'routing_unavailable', true],
    ['network', 503, 'routing_unavailable', true],
    ['upstream', 503, 'routing_unavailable', true],
    ['auth', 503, 'routing_unavailable', true],
    ['malformed', 503, 'routing_unavailable', true],
    ['oversized', 503, 'routing_unavailable', true],
  ])('%s → %i %s (one attempt)', async (kind, status, code, retryAfter) => {
    const { provider, calls } = fakeRouting(new RoutingError(kind));
    const { app } = setup(provider);
    const { token } = await signUp(app, 20);
    const res = await routes(app, token, WALK);
    expect(res.status).toBe(status);
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(await res.json()).toMatchObject({ code });
    expect(res.headers.get('retry-after')).toBe(retryAfter ? '10' : null);
    expect(calls).toHaveLength(1);
  });

  it('a geometry the API cannot read is routing_unavailable, not a 500', async () => {
    const { app } = setup(fakeRouting([{ ...ROUTE, geometry: 'not a polyline !!' }]).provider);
    const { token } = await signUp(app, 21);
    const res = await routes(app, token, WALK);
    expect(res.status).toBe(503);
    expect(await res.json()).toMatchObject({ code: 'routing_unavailable' });
  });
});

describe('POST /v1/routes: rate limits', () => {
  it('a user gets the burst of 10, then 429 with Retry-After; another user is unaffected', async () => {
    const { provider, calls } = fakeRouting();
    const { app } = setup(provider);
    const first = await signUp(app, 30);
    const second = await signUp(app, 31);
    for (let i = 0; i < ROUTING_LIMITS.userBurst.capacity; i++) {
      expect((await routes(app, first.token, WALK)).status).toBe(200);
    }
    const denied = await routes(app, first.token, WALK);
    expect(denied.status).toBe(429);
    expect(await denied.json()).toMatchObject({ code: 'rate_limited' });
    // One token every 10 seconds.
    expect(Number(denied.headers.get('retry-after'))).toBeGreaterThanOrEqual(9);
    expect(Number(denied.headers.get('retry-after'))).toBeLessThanOrEqual(10);
    expect(calls).toHaveLength(10);
    expect((await routes(app, second.token, WALK)).status).toBe(200);
  });

  it('the daily bucket of 300 denies once it is empty', async () => {
    const { app } = setup();
    const { token, userId } = await signUp(app, 32);
    expect((await routes(app, token, WALK)).status).toBe(200);
    await pool.query(
      `update rate_limit_buckets set tokens = 0, refilled_at = now() where key = $1`,
      [`routes:daily:${userId}`],
    );
    const res = await routes(app, token, WALK);
    expect(res.status).toBe(429);
    // 86 400 s / 300 tokens = 288 s for one token.
    expect(Number(res.headers.get('retry-after'))).toBeGreaterThanOrEqual(287);
    expect(Number(res.headers.get('retry-after'))).toBeLessThanOrEqual(288);
  });

  it('the global budget → 503 routing_unavailable with Retry-After, for every user', async () => {
    const { provider, calls } = fakeRouting();
    const { app } = setup(provider, { ROUTING_GLOBAL_DAILY_LIMIT: '2' });
    const first = await signUp(app, 33);
    const second = await signUp(app, 34);
    expect((await routes(app, first.token, WALK)).status).toBe(200);
    expect((await routes(app, second.token, WALK)).status).toBe(200);
    const res = await routes(app, first.token, WALK);
    expect(res.status).toBe(503);
    expect(await res.json()).toMatchObject({ code: 'routing_unavailable' });
    expect(Number(res.headers.get('retry-after'))).toBeGreaterThan(1000);
    expect(calls).toHaveLength(2);
  });

  it('stores three bucket rows whose keys hold no position, and nothing else', async () => {
    const { app } = setup();
    const { token, userId } = await signUp(app, 35);
    const auditRows = async () =>
      ((await pool.query('select count(*)::int as n from audit_log')).rows[0] as { n: number }).n;
    const auditBefore = await auditRows();
    expect(
      (
        await routes(app, token, {
          origin: LOG_ORIGIN,
          destination: LOG_DESTINATION,
          mode: 'driving',
        })
      ).status,
    ).toBe(200);
    const rows = await db.select().from(rateLimitBuckets);
    expect(rows.map((row) => row.key).sort()).toEqual(
      [`routes:burst:${userId}`, `routes:daily:${userId}`, 'routes:global'].sort(),
    );
    expect(await auditRows()).toBe(auditBefore);
  });
});

describe("POST /v1/routes: nothing about the route is in the API's log lines", () => {
  // This captures the API's OWN log lines. It cannot see Cloud Run's request log; there the
  // endpoint is safe because the positions are in the body, and the OSRM services' request logs
  // are excluded (docs/runbooks/routing-capacity-staging.md, section 7).
  it('logs no coordinate, geometry, route id or URL, in any outcome', async () => {
    const outcomes: [ProviderRoute[] | RoutingError, number][] = [
      [[ROUTE, ROUTE], 200],
      [new RoutingError('auth'), 503],
      [new RoutingError('timeout'), 503],
      [new RoutingError('no_route'), 404],
    ];
    for (const [index, [reply, status]] of outcomes.entries()) {
      const { app, lines, logs } = setup(fakeRouting(reply).provider, {
        OSRM_WALKING_URL: 'https://walking-FAKEHOST.example.invalid',
        OSRM_DRIVING_URL: 'https://driving-FAKEHOST.example.invalid',
      });
      const { token, userId } = await signUp(app, 40 + index);
      const body = { origin: LOG_ORIGIN, destination: LOG_DESTINATION, mode: 'driving' };

      const res = await routes(app, token, body);
      expect(res.status).toBe(status);
      const ids =
        status === 200 ? ((await res.json()) as RoutesBody).routes.map((route) => route.id) : [];
      // Rejected and out-of-area requests are logged too, and must not echo what was sent.
      await routes(app, token, { ...body, origin: { latitude: 22.987654 } });
      await routes(app, token, { ...body, destination: OUTSIDE });

      const raw = lines.join('\n');
      for (const secret of [
        '22.987654',
        '88.123456',
        '22.912345',
        '88.198765',
        '22.98',
        '88.12',
        GEOMETRY,
        'FAKEHOST',
        '/route/v1',
        'polyline',
        '?',
        ...ids,
      ]) {
        expect(raw, `log contains ${secret}`).not.toContain(secret);
      }

      const call = logs().filter((line) => line.message === 'routing call');
      expect(call).toHaveLength(1);
      expect(Object.keys(call[0] ?? {}).sort()).toEqual(
        [
          ...(index === 1 ? ['alert'] : []),
          'latency_ms',
          'message',
          'mode',
          'outcome',
          'request_id',
          'route_count',
          'service',
          'severity',
          'timestamp',
          'user_id',
          'version',
        ].sort(),
      );
      expect(call[0]).toMatchObject({
        user_id: userId,
        mode: 'driving',
        route_count: status === 200 ? 2 : 0,
      });
      const access = accessLines(logs()).filter((line) => line.path === '/v1/routes');
      expect(access.map((line) => line.status)).toEqual([status, 400, 422]);
    }
  });
});
