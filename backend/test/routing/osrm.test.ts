// SPDX-License-Identifier: AGPL-3.0-only
import { afterEach, describe, expect, it } from 'vitest';
import { parseOsrmBaseUrl } from '../../src/config.js';
import { createMetadataIdTokenSource } from '../../src/modules/routing/providers/id-token.js';
import {
  createOsrmRoutingProvider,
  MAX_ROUTING_RESPONSE_BYTES,
} from '../../src/modules/routing/providers/osrm.js';
import { RoutingError, type RoutingFailure } from '../../src/modules/routing/types.js';
import {
  fakeIdToken,
  GEOMETRY,
  MONUMENT,
  osrmOk,
  startFakeServer,
  STATION_A,
  type FakeServer,
  type RecordedRequest,
} from './fakes.js';

const servers: FakeServer[] = [];
afterEach(async () => {
  await Promise.all(servers.splice(0).map((server) => server.close()));
});

async function fakeOsrm(
  reply: (r: RecordedRequest) => { status: number; body: unknown; delayMs?: number },
) {
  const server = await startFakeServer((request) => {
    const { status, body, delayMs } = reply(request);
    return { status, body: typeof body === 'string' ? body : JSON.stringify(body), delayMs };
  });
  servers.push(server);
  return server;
}

/** A fake metadata server that issues a new token per call and counts the calls. */
async function fakeMetadata(clock: { now: number }, status = 200, lifetimeMs = 3_600_000) {
  const server = await startFakeServer(() => ({
    status,
    body: fakeIdToken(clock.now + lifetimeMs, `token-${String(server.requests.length)}`),
  }));
  servers.push(server);
  return server;
}

function provider(walking: string, driving = walking, extra: Record<string, unknown> = {}) {
  return createOsrmRoutingProvider({
    baseUrls: { walking, driving },
    idTokens: undefined,
    timeoutMs: 2000,
    alternatives: 2,
    ...extra,
  });
}

const QUERY = { origin: STATION_A, destination: MONUMENT, mode: 'walking' as const };

async function failure(run: Promise<unknown>): Promise<RoutingError> {
  const err = await run.then(
    () => undefined,
    (e: unknown) => e,
  );
  expect(err).toBeInstanceOf(RoutingError);
  return err as RoutingError;
}

describe('OSRM adapter', () => {
  it('asks the service of the mode for a full polyline6 route with alternatives and no steps', async () => {
    const walking = await fakeOsrm(() => ({ status: 200, body: osrmOk(3) }));
    const driving = await fakeOsrm(() => ({ status: 200, body: osrmOk(1) }));
    const osrm = provider(walking.baseUrl, driving.baseUrl);

    const routes = await osrm.route(QUERY);
    expect(routes).toEqual([
      { distanceMeters: 5100, durationSeconds: 3661, geometry: GEOMETRY },
      { distanceMeters: 5500, durationSeconds: 3961, geometry: GEOMETRY },
      { distanceMeters: 5900, durationSeconds: 4261, geometry: GEOMETRY },
    ]);
    expect(walking.requests).toHaveLength(1);
    expect(driving.requests).toHaveLength(0);
    // longitude,latitude with six decimals; origin first
    expect(walking.requests[0]?.url).toBe(
      '/route/v1/walking/88.342810,22.582870;88.342640,22.545080' +
        '?steps=false&overview=full&geometries=polyline6&alternatives=2',
    );
    expect(walking.requests[0]?.headers.authorization).toBeUndefined();
    // osrm-routed drops idle connections after five seconds: never reuse one.
    expect(walking.requests[0]?.headers.connection).toBe('close');

    expect(await osrm.route({ ...QUERY, mode: 'driving' })).toHaveLength(1);
    expect(driving.requests[0]?.url).toContain('/route/v1/driving/');
    expect(osrm.attribution).toBe('© OpenStreetMap contributors');
  });

  it('asks for no alternatives when configured with 0', async () => {
    const server = await fakeOsrm(() => ({ status: 200, body: osrmOk() }));
    await provider(server.baseUrl, server.baseUrl, { alternatives: 0 }).route(QUERY);
    expect(server.requests[0]?.url).toContain('alternatives=false');
  });

  it.each<[string, number, unknown, RoutingFailure]>([
    ['NoRoute', 400, { code: 'NoRoute', message: 'Impossible route between points' }, 'no_route'],
    [
      'NoSegment',
      400,
      { code: 'NoSegment', message: 'Could not find a matching segment' },
      'not_routable',
    ],
    ['another 400 code', 400, { code: 'InvalidQuery', message: 'x' }, 'malformed'],
    ['Ok without routes', 200, { code: 'Ok', routes: [] }, 'malformed'],
    ['four routes', 200, osrmOk(4), 'malformed'],
    ['a body that is not JSON', 200, '<html>hello</html>', 'malformed'],
    ['401', 401, '', 'auth'],
    ['403 (the invoker role is missing)', 403, '<html>Forbidden</html>', 'auth'],
    ['429', 429, '', 'upstream'],
    ['500', 500, '', 'upstream'],
    ['503', 503, '', 'upstream'],
  ])('maps %s to a RoutingError of kind %s', async (_name, status, body, kind) => {
    const server = await fakeOsrm(() => ({ status, body }));
    expect((await failure(provider(server.baseUrl).route(QUERY))).kind).toBe(kind);
    expect(server.requests).toHaveLength(1); // one attempt, never a retry
  });

  it('gives up after the timeout, once', async () => {
    const server = await fakeOsrm(() => ({ status: 200, body: osrmOk(), delayMs: 600 }));
    const started = Date.now();
    const err = await failure(
      provider(server.baseUrl, server.baseUrl, { timeoutMs: 150 }).route(QUERY),
    );
    expect(err.kind).toBe('timeout');
    expect(Date.now() - started).toBeLessThan(550);
    expect(server.requests).toHaveLength(1);
  });

  it('reports a refused connection as network, and an oversized answer as oversized', async () => {
    const closed = await fakeOsrm(() => ({ status: 200, body: osrmOk() }));
    await closed.close();
    expect((await failure(provider(closed.baseUrl).route(QUERY))).kind).toBe('network');

    const big = await fakeOsrm(() => ({
      status: 200,
      body: 'x'.repeat(MAX_ROUTING_RESPONSE_BYTES + 1),
    }));
    expect((await failure(provider(big.baseUrl).route(QUERY))).kind).toBe('oversized');
  });

  it('never puts the URL, a coordinate or a token into an error', async () => {
    const clock = { now: Date.now() };
    const metadata = await fakeMetadata(clock);
    const server = await fakeOsrm(() => ({ status: 500, body: '' }));
    const idTokens = createMetadataIdTokenSource({ identityUrl: `${metadata.baseUrl}/identity` });
    for (const base of [server.baseUrl, 'http://127.0.0.1:1']) {
      const err = await failure(provider(base, base, { idTokens }).route(QUERY));
      const text = `${err.message} ${String(err.stack)} ${JSON.stringify(err)} ${String(err.cause)}`;
      for (const secret of ['127.0.0.1', '88.34', '22.58', '22.54', 'FAKE_SIGNATURE', '/route/']) {
        expect(text).not.toContain(secret);
      }
    }
  });
});

describe('service-to-service ID token', () => {
  it('sends a token whose audience is exactly the service URL, and caches it until 5 minutes before expiry', async () => {
    const clock = { now: 1_800_000_000_000 };
    const metadata = await fakeMetadata(clock);
    const osrm = await fakeOsrm(() => ({ status: 200, body: osrmOk() }));
    const idTokens = createMetadataIdTokenSource({
      identityUrl: `${metadata.baseUrl}/identity`,
      now: () => clock.now,
    });
    const routing = provider(osrm.baseUrl, osrm.baseUrl, { idTokens });

    await routing.route(QUERY);
    await routing.route(QUERY);
    expect(metadata.requests).toHaveLength(1);
    const asked = new URL(metadata.requests[0]?.url ?? '', metadata.baseUrl);
    expect(asked.pathname).toBe('/identity');
    // No trailing slash and no path: Cloud Run compares the audience with the service URL as is.
    expect(asked.searchParams.get('audience')).toBe(osrm.baseUrl);
    expect(osrm.baseUrl.endsWith('/')).toBe(false);
    expect(metadata.requests[0]?.headers['metadata-flavor']).toBe('Google');
    const first = osrm.requests[0]?.headers.authorization;
    expect(first).toMatch(/^Bearer [\w-]+\.[\w-]+\.FAKE_SIGNATURE$/);
    expect(osrm.requests[1]?.headers.authorization).toBe(first);

    clock.now += 3_600_000 - 5 * 60_000 - 1000; // 5 min 1 s before expiry: still the cached one
    await routing.route(QUERY);
    expect(metadata.requests).toHaveLength(1);

    clock.now += 2000; // inside the last five minutes: a new token
    await routing.route(QUERY);
    expect(metadata.requests).toHaveLength(2);
    expect(osrm.requests[3]?.headers.authorization).not.toBe(first);
  });

  it('uses one token per audience and one fetch for parallel requests', async () => {
    const clock = { now: Date.now() };
    const metadata = await fakeMetadata(clock);
    const tokens = createMetadataIdTokenSource({ identityUrl: `${metadata.baseUrl}/identity` });
    const [a, b, c] = await Promise.all([
      tokens.get('https://walking.example.invalid'),
      tokens.get('https://walking.example.invalid'),
      tokens.get('https://driving.example.invalid'),
    ]);
    expect(a).toBe(b);
    expect(c).not.toBe(a);
    expect(metadata.requests).toHaveLength(2);
  });

  it.each([
    ['the metadata server answers 404', 404, 3_600_000],
    ['the token is already expired', 200, -1000],
  ])('fails with kind auth and does not call OSRM when %s', async (_name, status, lifetime) => {
    const clock = { now: Date.now() };
    const metadata = await fakeMetadata(clock, status, lifetime);
    const osrm = await fakeOsrm(() => ({ status: 200, body: osrmOk() }));
    const idTokens = createMetadataIdTokenSource({ identityUrl: `${metadata.baseUrl}/identity` });
    const err = await failure(provider(osrm.baseUrl, osrm.baseUrl, { idTokens }).route(QUERY));
    expect(err.kind).toBe('auth');
    expect(osrm.requests).toHaveLength(0);
  });

  it('fails with kind auth when there is no metadata server, and tries again next time', async () => {
    const tokens = createMetadataIdTokenSource({ identityUrl: 'http://127.0.0.1:1/identity' });
    expect((await failure(tokens.get('https://x.example.invalid'))).kind).toBe('auth');
    expect((await failure(tokens.get('https://x.example.invalid'))).kind).toBe('auth');
  });
});

describe('OSRM base URL (the secret value format)', () => {
  it('accepts what Cloud Run reports and removes a trailing slash', () => {
    expect(parseOsrmBaseUrl('https://saferoute-osrm-walking-abc123-el.a.run.app')).toBe(
      'https://saferoute-osrm-walking-abc123-el.a.run.app',
    );
    expect(parseOsrmBaseUrl('https://saferoute-osrm-walking-abc123-el.a.run.app/')).toBe(
      'https://saferoute-osrm-walking-abc123-el.a.run.app',
    );
    expect(parseOsrmBaseUrl('http://localhost:5000')).toBe('http://localhost:5000');
    expect(parseOsrmBaseUrl('http://127.0.0.1:5000/')).toBe('http://127.0.0.1:5000');
  });

  it.each([
    'http://osrm.example.invalid',
    'https://osrm.example.invalid/route/v1',
    'https://osrm.example.invalid/?x=1',
    'https://user:pw@osrm.example.invalid',
    'osrm.example.invalid',
    'ftp://osrm.example.invalid',
    '',
  ])('rejects %s', (value) => {
    expect(parseOsrmBaseUrl(value)).toBeUndefined();
  });
});
