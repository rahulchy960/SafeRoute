// SPDX-License-Identifier: AGPL-3.0-only
import { describe, expect, it } from 'vitest';
import { GEOCODING_PROVIDERS, type GeocodingProviderName } from '../../src/config.js';
import { MAX_PROVIDER_RESPONSE_BYTES } from '../../src/modules/search/providers/http.js';
import { createGeocoder } from '../../src/modules/search/providers/index.js';
import { GeocoderError, type GeocoderQuery } from '../../src/modules/search/types.js';

/** Obviously fake; distinctive so a leak is easy to assert. */
const FAKE_KEY = 'FAKEKEY-do-not-log-9f3a';

const QUERY: GeocoderQuery = {
  query: 'হাওড়া স্টেশন',
  nearLatitude: 10.12,
  nearLongitude: 20.34,
  language: 'bn',
  limit: 3,
};

type FetchReply = Response | Error | (() => Promise<Response>);

/** A fetch that records the URL it was given and answers with a canned reply. */
function fakeFetch(reply: FetchReply) {
  const urls: URL[] = [];
  const fetchImpl = ((input: URL) => {
    urls.push(input);
    if (reply instanceof Error) return Promise.reject(reply);
    return typeof reply === 'function' ? reply() : Promise.resolve(reply);
  }) as unknown as typeof fetch;
  return { fetchImpl, urls };
}

const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers });

function geocoder(name: GeocodingProviderName, reply: FetchReply, timeoutMs = 1000) {
  const { fetchImpl, urls } = fakeFetch(reply);
  return { provider: createGeocoder(name, FAKE_KEY, { timeoutMs, fetchImpl }), urls };
}

/** A well-formed "one result" body for each provider. */
const OK_BODY: Record<GeocodingProviderName, unknown> = {
  geoapify: {
    results: [
      {
        place_id: 'fake-place-1',
        name: 'Main Station',
        formatted: 'Main Station, Station Road, Example District',
        address_line1: 'Main Station',
        address_line2: 'Station Road, Example District',
        lat: 10.5,
        lon: 20.5,
        result_type: 'amenity',
        rank: { confidence: 1 },
        unexpected_field: 'ignored',
      },
    ],
    query: { text: 'ignored' },
  },
  locationiq: [
    {
      place_id: 'fake-place-1',
      lat: '10.5',
      lon: '20.5',
      display_name: 'Main Station, Station Road, Example District',
      display_place: 'Main Station',
      display_address: 'Station Road, Example District',
      class: 'railway',
      type: 'station',
      unexpected_field: 'ignored',
    },
  ],
};

async function failure(promise: Promise<unknown>): Promise<GeocoderError> {
  try {
    await promise;
  } catch (err) {
    if (err instanceof GeocoderError) return err;
    throw err;
  }
  throw new Error('expected the adapter to throw');
}

/** Everything an error could carry to a log line or a response. */
function everythingIn(err: Error): string {
  return [err.name, err.message, err.stack ?? '', JSON.stringify(err), String(err.cause)].join(' ');
}

describe('geoapify adapter: category search (Places API)', () => {
  const CATEGORY_QUERY = {
    category: 'pharmacy',
    nearLatitude: 10.12,
    nearLongitude: 20.34,
    withinMeters: 10_000,
    language: 'bn',
    limit: 6,
  } as const;
  const feature = (properties: Record<string, unknown>) => ({
    type: 'Feature',
    properties,
    geometry: { type: 'Point', coordinates: [20.5, 10.5] },
  });
  const PLACES_BODY = {
    type: 'FeatureCollection',
    features: [
      feature({
        place_id: 'fake-poi-1',
        name: 'Example Medical Hall',
        address_line1: 'Example Medical Hall',
        address_line2: 'Station Road, Example District',
        categories: ['healthcare', 'healthcare.pharmacy'],
        distance: 420,
        lat: 10.5,
        lon: 20.5,
      }),
      // No name: common for small shops. The first address line stands in.
      feature({ formatted: 'Market Road, Example District', lat: 10.6, lon: 20.6 }),
    ],
  };

  it('asks for the category inside the circle, nearest first, and maps the features', async () => {
    const { provider, urls } = geocoder('geoapify', json(PLACES_BODY));
    const results = await provider.searchCategory?.(CATEGORY_QUERY);
    const url = urls[0] ?? new URL('https://missing.invalid');
    expect(`${url.origin}${url.pathname}`).toBe('https://api.geoapify.com/v2/places');
    expect(Object.fromEntries(url.searchParams)).toEqual({
      categories: 'healthcare.pharmacy',
      filter: 'circle:20.34,10.12,10000',
      bias: 'proximity:20.34,10.12',
      lang: 'bn',
      limit: '6',
      apiKey: FAKE_KEY,
    });
    expect(results).toEqual([
      {
        id: 'fake-poi-1',
        name: 'Example Medical Hall',
        label: 'Station Road, Example District',
        latitude: 10.5,
        longitude: 20.5,
        kind: 'pharmacy',
      },
      {
        id: 'geoapify-place-1',
        name: 'Market Road, Example District',
        label: 'Market Road, Example District',
        latitude: 10.6,
        longitude: 20.6,
        kind: 'pharmacy',
      },
    ]);
  });

  it('never asks for more than one credit buys, and joins several categories', async () => {
    const { provider, urls } = geocoder('geoapify', json({ features: [] }));
    expect(
      await provider.searchCategory?.({ ...CATEGORY_QUERY, category: 'grocery', limit: 500 }),
    ).toEqual([]);
    const params = (urls[0] ?? new URL('https://missing.invalid')).searchParams;
    expect(params.get('limit')).toBe('20');
    expect(params.get('categories')).toBe('commercial.supermarket,commercial.convenience');
  });

  it.each([
    ['a refused key', json({ message: 'Invalid apiKey' }, 401), 'auth'],
    ['a category it does not know', json({ message: 'bad categories' }, 400), 'upstream'],
    ['a body that is not GeoJSON', json({ results: [] }), 'malformed'],
    [
      'a network error that quotes the URL',
      new TypeError(`fetch failed: ?apiKey=${FAKE_KEY}`),
      'network',
    ],
  ] as const)('%s → a GeocoderError with a kind and no key', async (_name, reply, kind) => {
    const { provider } = geocoder('geoapify', reply);
    const err = await failure(provider.searchCategory?.(CATEGORY_QUERY) ?? Promise.resolve());
    expect(err.kind).toBe(kind);
    expect(everythingIn(err)).not.toContain(FAKE_KEY);
    expect(everythingIn(err)).not.toContain('10.12');
  });

  it('placesOnly restricts a name search to amenities; without it there is no type filter', async () => {
    // A Response can be read once: a fresh one for each call.
    const { provider, urls } = geocoder('geoapify', () => Promise.resolve(json(OK_BODY.geoapify)));
    await provider.search({ ...QUERY, placesOnly: true });
    await provider.search(QUERY);
    expect(urls.map((url) => url.searchParams.get('type'))).toEqual(['amenity', null]);
  });

  it('locationiq has no category search and ignores placesOnly', async () => {
    const { provider, urls } = geocoder('locationiq', json(OK_BODY.locationiq));
    expect('searchCategory' in provider).toBe(false);
    await provider.search({ ...QUERY, placesOnly: true });
    expect([...(urls[0]?.searchParams.keys() ?? [])]).not.toContain('type');
  });
});

describe.each(GEOCODING_PROVIDERS)('%s adapter', (name) => {
  it('maps a result to the provider-neutral shape and ignores unknown fields', async () => {
    const { provider } = geocoder(name, json(OK_BODY[name]));
    expect(provider.name).toBe(name);
    expect(provider.attribution).toContain('OpenStreetMap');
    expect(await provider.search(QUERY)).toEqual([
      {
        id: 'fake-place-1',
        name: 'Main Station',
        label: 'Station Road, Example District',
        latitude: 10.5,
        longitude: 20.5,
        kind: name === 'geoapify' ? 'amenity' : 'station',
      },
    ]);
  });

  it('sends the query, the limit, the country filter and the bias; never a hard area filter', async () => {
    const { provider, urls } = geocoder(name, json(OK_BODY[name]));
    await provider.search(QUERY);
    expect(urls).toHaveLength(1);
    const url = urls[0] ?? new URL('https://missing.invalid');
    expect(url.protocol).toBe('https:');
    const params = url.searchParams;
    expect(params.get(name === 'geoapify' ? 'text' : 'q')).toBe(QUERY.query);
    expect(params.get('limit')).toBe('3');
    if (name === 'geoapify') {
      expect(params.get('filter')).toBe('countrycode:in');
      expect(params.get('bias')).toBe('proximity:20.34,10.12');
      expect(params.get('lang')).toBe('bn');
    } else {
      expect(params.get('countrycodes')).toBe('in');
      expect(params.get('viewbox')).toBe('19.84,9.62,20.84,10.62');
      expect(params.has('bounded')).toBe(false);
      expect(params.get('accept-language')).toBe('native');
    }
  });

  it('with withinMeters, adds an area filter around the same point and keeps the bias', async () => {
    const { provider, urls } = geocoder(name, json(OK_BODY[name]));
    await provider.search({ ...QUERY, withinMeters: 50_000 });
    const params = (urls[0] ?? new URL('https://missing.invalid')).searchParams;
    if (name === 'geoapify') {
      // circle:lon,lat,radiusMeters, joined to the country filter with a pipe (both must hold).
      expect(params.get('filter')).toBe('circle:20.34,10.12,50000|countrycode:in');
      expect(params.get('bias')).toBe('proximity:20.34,10.12');
    } else {
      // The square around the circle: 50 km is 0.45 degrees of latitude, 0.46 of longitude here.
      expect(params.get('viewbox')).toBe('19.88,9.67,20.80,10.57');
      expect(params.get('bounded')).toBe('1');
      expect(params.get('countrycodes')).toBe('in');
    }
  });

  it('returns at most `limit` results even when the provider sends more', async () => {
    const many =
      name === 'geoapify'
        ? { results: Array.from({ length: 8 }, () => ({ lat: 10.5, lon: 20.5 })) }
        : Array.from({ length: 8 }, () => ({ lat: '10.5', lon: '20.5' }));
    const { provider } = geocoder(name, json(many));
    const results = await provider.search(QUERY);
    expect(results).toHaveLength(3);
    // No id from the provider: a positional one is made up, still a non-empty string.
    expect(results.every((result) => result.id.length > 0 && result.kind === 'unknown')).toBe(true);
  });

  it.each([
    [401, 'auth'],
    [403, 'auth'],
    [429, 'rate_limited'],
    [500, 'upstream'],
    [503, 'upstream'],
    [400, 'upstream'],
  ])('provider status %i → %s, with nothing but the kind', async (status, kind) => {
    const { provider } = geocoder(name, json({ message: `denied for ${FAKE_KEY}` }, status));
    const err = await failure(provider.search(QUERY));
    expect(err.kind).toBe(kind);
    expect(everythingIn(err)).not.toContain(FAKE_KEY);
    expect(everythingIn(err)).not.toContain(QUERY.query);
  });

  it('passes on a Retry-After from a 429', async () => {
    const { provider } = geocoder(name, json({}, 429, { 'Retry-After': '17' }));
    expect((await failure(provider.search(QUERY))).retryAfterSeconds).toBe(17);
    const odd = geocoder(name, json({}, 429, { 'Retry-After': 'Wed, 21 Oct 2026 07:28:00 GMT' }));
    expect((await failure(odd.provider.search(QUERY))).retryAfterSeconds).toBeUndefined();
  });

  it('a network error that quotes the URL never leaks the key', async () => {
    // What Node's fetch does: the failure's message and cause can contain the full URL.
    const cause = new Error(`getaddrinfo ENOTFOUND for https://provider.invalid/?key=${FAKE_KEY}`);
    const { provider } = geocoder(name, new TypeError(`fetch failed ${FAKE_KEY}`, { cause }));
    const err = await failure(provider.search(QUERY));
    expect(err.kind).toBe('network');
    expect(err.cause).toBeUndefined();
    expect(everythingIn(err)).not.toContain(FAKE_KEY);
  });

  it('a timeout → timeout', async () => {
    const realTimeout = createGeocoder(name, FAKE_KEY, {
      timeoutMs: 30,
      // Honours the abort signal like the real fetch does.
      fetchImpl: ((_url: URL, init: RequestInit) =>
        new Promise<Response>((_resolve, reject) => {
          init.signal?.addEventListener('abort', () => {
            reject(init.signal?.reason as Error);
          });
        })) as unknown as typeof fetch,
    });
    expect((await failure(realTimeout.search(QUERY))).kind).toBe('timeout');
  });

  it.each([
    ['not JSON', new Response('<html>busy</html>', { status: 200 })],
    ['an empty body', new Response('', { status: 200 })],
    ['the wrong shape', json({ features: 'nope' })],
    [
      'a coordinate out of range',
      json(name === 'geoapify' ? { results: [{ lat: 95, lon: 20 }] } : [{ lat: '95', lon: '20' }]),
    ],
    [
      'a coordinate that is not a number',
      json(name === 'geoapify' ? { results: [{ lat: 'x', lon: 20 }] } : [{ lat: 'x', lon: '20' }]),
    ],
  ])('%s → malformed', async (_label, response) => {
    const { provider } = geocoder(name, response);
    expect((await failure(provider.search(QUERY))).kind).toBe('malformed');
  });

  it('a body over the size limit → oversized, without reading it all', async () => {
    const huge = 'x'.repeat(MAX_PROVIDER_RESPONSE_BYTES + 10);
    const { provider } = geocoder(name, new Response(huge, { status: 200 }));
    expect((await failure(provider.search(QUERY))).kind).toBe('oversized');
  });
});

describe('locationiq adapter', () => {
  it('treats 404 "unable to geocode" as no results, not as a failure', async () => {
    const { provider } = geocoder('locationiq', json({ error: 'Unable to geocode' }, 404));
    expect(await provider.search(QUERY)).toEqual([]);
  });

  it('asks for English names for an English search', async () => {
    const { provider, urls } = geocoder('locationiq', json(OK_BODY.locationiq));
    await provider.search({ ...QUERY, language: 'en' });
    expect(urls[0]?.searchParams.get('accept-language')).toBe('en');
  });
});

describe('geoapify adapter', () => {
  it('returns an empty list for no results', async () => {
    const { provider } = geocoder('geoapify', json({ results: [] }));
    expect(await provider.search(QUERY)).toEqual([]);
  });

  it('falls back to the formatted address when there is no name', async () => {
    const { provider } = geocoder(
      'geoapify',
      json({ results: [{ formatted: 'Station Road,   Example District', lat: 1, lon: 2 }] }),
    );
    expect(await provider.search(QUERY)).toMatchObject([
      { name: 'Station Road, Example District', label: 'Station Road, Example District' },
    ]);
  });
});
