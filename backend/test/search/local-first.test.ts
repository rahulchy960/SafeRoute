// SPDX-License-Identifier: AGPL-3.0-only
import { describe, expect, it } from 'vitest';
import {
  searchLocalFirst,
  WidePassRefused,
  type LocalFirstOptions,
} from '../../src/modules/search/local-first.js';
import {
  GeocoderError,
  type GeocoderProvider,
  type GeocoderQuery,
  type PlaceResult,
} from '../../src/modules/search/types.js';

/** Round fixture coordinates; nobody's position. One hundredth of a degree north is 1112 m. */
const QUERY: GeocoderQuery = {
  query: 'bank',
  nearLatitude: 10,
  nearLongitude: 20,
  language: 'en',
  limit: 6,
};
const OPTIONS: LocalFirstOptions = { radiusMeters: 50_000, minLocalResults: 3 };

const place = (id: string, northDegrees: number, name = `Place ${id}`): PlaceResult => ({
  id,
  name,
  label: 'Example District',
  latitude: 10 + northDegrees,
  longitude: 20,
  kind: 'fake',
});

type Reply = PlaceResult[] | GeocoderError;

/** Answers the nearby pass and the wide pass from two scripts, and records every call. */
function provider(nearby: Reply, wide: Reply = []) {
  const calls: GeocoderQuery[] = [];
  const fake: GeocoderProvider = {
    name: 'fake',
    attribution: null,
    search: (query) => {
      calls.push(query);
      const reply = query.withinMeters === undefined ? wide : nearby;
      return reply instanceof GeocoderError ? Promise.reject(reply) : Promise.resolve(reply);
    },
  };
  return { fake, calls };
}

describe('searchLocalFirst', () => {
  it('enough nearby results: one filtered call, and every result carries a rounded distance', async () => {
    const { fake, calls } = provider([place('a', 0.02), place('b', 0.1), place('c', 0.3)]);
    const outcome = await searchLocalFirst(fake, QUERY, OPTIONS);

    expect(calls).toEqual([{ ...QUERY, withinMeters: 50_000 }]);
    expect(outcome).toMatchObject({ localCount: 3, providerCalls: 1, widePass: 'not_needed' });
    // 2224 m, 11 120 m and 33 359 m, each rounded to 100 m.
    expect(outcome.results.map((result) => [result.id, result.distanceMeters])).toEqual([
      ['a', 2200],
      ['b', 11_100],
      ['c', 33_400],
    ]);
  });

  it('too few nearby: a second, unfiltered call; nearby first, then the rest, no duplicates', async () => {
    const near = place('near', 0.05, 'Town Branch');
    const { fake, calls } = provider(
      [near],
      [
        place('far-1', 3),
        // The same place again: once with the same id, once under another id.
        near,
        { ...near, id: 'other-id', name: 'TOWN BRANCH' },
        place('far-2', 5),
      ],
    );
    const outcome = await searchLocalFirst(fake, QUERY, OPTIONS);

    expect(calls.map((call) => call.withinMeters)).toEqual([50_000, undefined]);
    expect(outcome).toMatchObject({ localCount: 1, providerCalls: 2, widePass: 'ok' });
    expect(outcome.results.map((result) => result.id)).toEqual(['near', 'far-1', 'far-2']);
    expect(outcome.results[1]?.distanceMeters).toBe(333_600);
  });

  it('cuts the merged list to the limit, nearby results first', async () => {
    const { fake } = provider(
      [place('n1', 0.01), place('n2', 0.02)],
      [place('w1', 2), place('w2', 3), place('w3', 4)],
    );
    const outcome = await searchLocalFirst(fake, { ...QUERY, limit: 4 }, OPTIONS);
    expect(outcome.results.map((result) => result.id)).toEqual(['n1', 'n2', 'w1', 'w2']);
    expect(outcome.localCount).toBe(2);
  });

  it('drops a "nearby" result that is outside the radius (a box filter has corners)', async () => {
    const { fake } = provider([place('inside', 0.4), place('corner', 0.6)], [place('wide', 2)]);
    const outcome = await searchLocalFirst(fake, QUERY, OPTIONS);
    // 0.6 degrees is 66.7 km: outside 50 km, so it does not count as nearby.
    expect(outcome.results.map((result) => result.id)).toEqual(['inside', 'wide']);
    expect(outcome.localCount).toBe(1);
  });

  it('a limit below the minimum never forces a wide pass', async () => {
    const { fake, calls } = provider([place('only', 0.01)]);
    const outcome = await searchLocalFirst(fake, { ...QUERY, limit: 1 }, OPTIONS);
    expect(calls).toHaveLength(1);
    expect(outcome.widePass).toBe('not_needed');
  });

  it('runs beforeWidePass once, between the two calls, and not when one call is enough', async () => {
    const order: string[] = [];
    const { fake, calls } = provider([], [place('w', 2)]);
    await searchLocalFirst(fake, QUERY, OPTIONS, () => {
      order.push(`before-wide after ${String(calls.length)} call(s)`);
      return Promise.resolve();
    });
    expect(order).toEqual(['before-wide after 1 call(s)']);

    const enough = provider([place('a', 0.01), place('b', 0.02), place('c', 0.03)]);
    await searchLocalFirst(enough.fake, QUERY, OPTIONS, () => {
      order.push('must not run');
      return Promise.resolve();
    });
    expect(order).toHaveLength(1);
  });

  it('a refused wide pass keeps the nearby results; with none, the reason is thrown', async () => {
    const reason = new Error('over the limit');
    const refuse = () => Promise.reject(new WidePassRefused(reason));

    const some = provider([place('n', 0.01)], [place('w', 2)]);
    const outcome = await searchLocalFirst(some.fake, QUERY, OPTIONS, refuse);
    expect(outcome).toMatchObject({ localCount: 1, providerCalls: 1, widePass: 'refused' });
    expect(outcome.results.map((result) => result.id)).toEqual(['n']);
    expect(some.calls).toHaveLength(1);

    const none = provider([], [place('w', 2)]);
    await expect(searchLocalFirst(none.fake, QUERY, OPTIONS, refuse)).rejects.toBe(reason);
    expect(none.calls).toHaveLength(1);
  });

  it('any other error from beforeWidePass is not swallowed', async () => {
    const broken = new Error('database down');
    const { fake } = provider([place('n', 0.01)]);
    await expect(searchLocalFirst(fake, QUERY, OPTIONS, () => Promise.reject(broken))).rejects.toBe(
      broken,
    );
  });

  it('a provider failure on the wide pass keeps the nearby results; with none, it fails', async () => {
    const some = provider([place('n', 0.01)], new GeocoderError('timeout'));
    const outcome = await searchLocalFirst(some.fake, QUERY, OPTIONS);
    expect(outcome).toMatchObject({ localCount: 1, providerCalls: 2, widePass: 'timeout' });

    const none = provider([], new GeocoderError('upstream'));
    await expect(searchLocalFirst(none.fake, QUERY, OPTIONS)).rejects.toMatchObject({
      kind: 'upstream',
    });
  });

  it('a provider failure on the nearby pass fails the search without a second call', async () => {
    const { fake, calls } = provider(new GeocoderError('rate_limited', 7), [place('w', 2)]);
    await expect(searchLocalFirst(fake, QUERY, OPTIONS)).rejects.toMatchObject({
      kind: 'rate_limited',
      retryAfterSeconds: 7,
    });
    expect(calls).toHaveLength(1);
  });
});
