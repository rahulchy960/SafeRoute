// SPDX-License-Identifier: AGPL-3.0-only
import { describe, expect, it } from 'vitest';
import {
  BRAND_CANDIDATES,
  CATEGORY_SEARCH_DEFAULTS,
  searchByIntent,
  type IntentRequest,
  type IntentSearchOptions,
} from '../../src/modules/search/intent-search.js';
import { WidePassRefused } from '../../src/modules/search/local-first.js';
import {
  GeocoderError,
  type CategoryQuery,
  type GeocoderProvider,
  type GeocoderQuery,
  type PlaceResult,
} from '../../src/modules/search/types.js';

const OPTIONS: IntentSearchOptions = {
  localFirst: { radiusMeters: 50_000, minLocalResults: 3 },
  categoryRadiusMeters: CATEGORY_SEARCH_DEFAULTS.radiusKm * 1000,
};
const NEAR = { latitude: 10, longitude: 20 };

/** A fake place `north` degrees north of NEAR (0.01 degrees is about 1.1 km). */
const place = (id: string, north: number, name = `Place ${id}`): PlaceResult => ({
  id,
  name,
  label: 'Example District',
  latitude: NEAR.latitude + north,
  longitude: NEAR.longitude,
  kind: 'fake',
});

type Reply = PlaceResult[] | GeocoderError;
const settle = (reply: Reply) =>
  reply instanceof GeocoderError ? Promise.reject(reply) : Promise.resolve(reply);

function fakeProvider(
  byName: (query: GeocoderQuery) => Reply,
  byCategory?: (query: CategoryQuery) => Reply,
) {
  const nameCalls: GeocoderQuery[] = [];
  const categoryCalls: CategoryQuery[] = [];
  const provider: GeocoderProvider = {
    name: 'fake',
    attribution: null,
    search: (query) => {
      nameCalls.push(query);
      return settle(byName(query));
    },
    ...(byCategory === undefined
      ? {}
      : {
          searchCategory: (query: CategoryQuery) => {
            categoryCalls.push(query);
            return settle(byCategory(query));
          },
        }),
  };
  return { provider, nameCalls, categoryCalls };
}

const request = (overrides: Partial<IntentRequest>): IntentRequest => ({
  text: undefined,
  category: undefined,
  near: NEAR,
  hasArea: true,
  language: 'en',
  limit: 6,
  ...overrides,
});

/** Counts the charges and refuses from the `refuseFrom`-th on. */
function meter(refuseFrom = Infinity) {
  const state = { charges: 0 };
  const charge = () => {
    state.charges += 1;
    return state.charges >= refuseFrom
      ? Promise.reject(new WidePassRefused(new Error('limit')))
      : Promise.resolve();
  };
  return { state, charge };
}

describe('searchByIntent: names', () => {
  it('an unknown word is the name search as before, local-first around an area', async () => {
    const { provider, nameCalls, categoryCalls } = fakeProvider(
      () => [place('a', 0.01), place('b', 0.02), place('c', 0.03)],
      () => [],
    );
    const { state, charge } = meter();
    const found = await searchByIntent(provider, request({ text: 'Bankura' }), OPTIONS, charge);
    expect(found.matchType).toBe('name');
    expect(found.results.map((r) => r.matchType)).toEqual(['name', 'name', 'name']);
    expect(found).not.toHaveProperty('searchedRadiusKm');
    expect(nameCalls.map((call) => call.withinMeters)).toEqual([50_000]);
    expect(nameCalls[0]).not.toHaveProperty('placesOnly');
    expect(categoryCalls).toHaveLength(0);
    expect(state.charges).toBe(1);
  });
});

describe('searchByIntent: categories', () => {
  it('without an area there is no circle: the word is searched among places only', async () => {
    const { provider, nameCalls, categoryCalls } = fakeProvider(
      () => [place('a', 3)],
      () => [],
    );
    const found = await searchByIntent(
      provider,
      request({ text: 'bank', hasArea: false }),
      OPTIONS,
    );
    expect(nameCalls).toEqual([
      {
        query: 'bank',
        nearLatitude: 10,
        nearLongitude: 20,
        language: 'en',
        limit: 6,
        placesOnly: true,
      },
    ]);
    expect(categoryCalls).toHaveLength(0);
    expect(found.results).toEqual([{ ...place('a', 3), matchType: 'name' }]);
    expect(found).not.toHaveProperty('searchedRadiusKm');
  });

  it('a Bengali word is sent on as the English label, never as typed, to a text-only provider', async () => {
    const { provider, nameCalls } = fakeProvider(() => [place('a', 0.01), place('z', 0.5)]);
    const found = await searchByIntent(provider, request({ text: 'হাসপাতাল' }), OPTIONS);
    // No category search in this provider: the label, inside the circle, places only.
    expect(nameCalls.map((call) => [call.query, call.withinMeters, call.placesOnly])).toEqual([
      ['hospital', 10_000, true],
      ['hospital', 25_000, true],
    ]);
    // The place 55 km away came back although the circle was asked for: it is dropped.
    expect(found.results.map((r) => r.id)).toEqual(['a']);
    expect(found.searchedRadiusKm).toBe(25);
    expect(found.providerCalls).toBe(2);
  });

  it('enough places in the first circle: one call, cut to the limit, duplicates dropped', async () => {
    const many = [0.05, 0.01, 0.03, 0.02, 0.04].map((north, i) => place(`p${String(i)}`, north));
    const { provider, categoryCalls } = fakeProvider(
      () => [],
      () => [...many, { ...place('dup', 0.01), name: 'Place p1' }],
    );
    const found = await searchByIntent(provider, request({ category: 'atm', limit: 3 }), OPTIONS);
    expect(categoryCalls).toHaveLength(1);
    expect(found.results.map((r) => r.id)).toEqual(['p1', 'p3', 'p2']);
    expect(found.searchedRadiusKm).toBe(10);
  });

  it('a first radius of 25 km is never widened', async () => {
    const { provider, categoryCalls } = fakeProvider(
      () => [],
      () => [],
    );
    const found = await searchByIntent(provider, request({ category: 'atm' }), {
      ...OPTIONS,
      categoryRadiusMeters: 25_000,
    });
    expect(categoryCalls.map((call) => call.withinMeters)).toEqual([25_000]);
    expect(found.results).toEqual([]);
    expect(found.searchedRadiusKm).toBe(25);
  });

  it('the provider fails on the wider circle: the first circle alone; with none, the error', async () => {
    const some = fakeProvider(
      () => [],
      (query) =>
        query.withinMeters === 10_000 ? [place('a', 0.01)] : new GeocoderError('timeout'),
    );
    const kept = await searchByIntent(some.provider, request({ category: 'bank' }), OPTIONS);
    expect(kept.results.map((r) => r.id)).toEqual(['a']);
    expect(kept.searchedRadiusKm).toBe(10);

    const none = fakeProvider(
      () => [],
      (query) => (query.withinMeters === 10_000 ? [] : new GeocoderError('timeout')),
    );
    await expect(
      searchByIntent(none.provider, request({ category: 'bank' }), OPTIONS),
    ).rejects.toBeInstanceOf(GeocoderError);
  });

  it('a refused first charge fails the search with the reason, before any provider call', async () => {
    const { provider, categoryCalls } = fakeProvider(
      () => [],
      () => [place('a', 0.01)],
    );
    await expect(
      searchByIntent(provider, request({ category: 'bank' }), OPTIONS, meter(1).charge),
    ).rejects.toThrow('limit');
    expect(categoryCalls).toHaveLength(0);
  });
});

describe('searchByIntent: brands', () => {
  const branches = [
    place('other', 0.01, 'Example Co-operative Bank'),
    place('sbi-far', 0.06, 'SBI'),
    place('sbi-near', 0.02, 'State Bank of India'),
    place('boi', 0.03, 'Bank of India'),
  ];

  it('asks for twenty of the category and keeps the brand, nearest first', async () => {
    const { provider, categoryCalls } = fakeProvider(
      () => [],
      () => branches,
    );
    const found = await searchByIntent(provider, request({ text: 'state bank' }), {
      ...OPTIONS,
      localFirst: { radiusMeters: 50_000, minLocalResults: 2 },
    });
    expect(categoryCalls).toEqual([
      {
        category: 'bank',
        nearLatitude: 10,
        nearLongitude: 20,
        withinMeters: 10_000,
        language: 'en',
        limit: BRAND_CANDIDATES,
      },
    ]);
    expect(found.results.map((r) => [r.id, r.matchType])).toEqual([
      ['sbi-near', 'brand'],
      ['sbi-far', 'brand'],
    ]);
  });

  it('without an area: the canonical name among places only, other brands dropped', async () => {
    const { provider, nameCalls } = fakeProvider(() => branches);
    const found = await searchByIntent(provider, request({ text: 'sbi', hasArea: false }), OPTIONS);
    expect(nameCalls.map((call) => [call.query, call.placesOnly])).toEqual([
      ['State Bank of India', true],
    ]);
    expect(found.results.map((r) => r.id)).toEqual(['sbi-far', 'sbi-near']);
  });

  it('a hint the geocoder does not know: the whole text is searched by name instead', async () => {
    const { provider, nameCalls, categoryCalls } = fakeProvider(
      (query) => (query.query === 'life insurance' ? [] : [place('office', 0.01, 'SBI Life')]),
      () => branches,
    );
    const { state, charge } = meter();
    const found = await searchByIntent(
      provider,
      request({ text: 'sbi life insurance', hasArea: false }),
      OPTIONS,
      charge,
    );
    expect(nameCalls.map((call) => call.query)).toEqual(['life insurance', 'sbi life insurance']);
    expect(categoryCalls).toHaveLength(0);
    expect(found.matchType).toBe('name');
    expect(found.providerCalls).toBe(2);
    expect(state.charges).toBe(2);
  });

  it('a hint works without an area, and the circle is centred on its coarse point', async () => {
    const town = { ...place('town', 0), latitude: 11.23456, longitude: 21.98765 };
    const { provider, categoryCalls } = fakeProvider(
      () => [town],
      () => [{ ...place('m', 0, 'MedPlus'), latitude: 11.24, longitude: 21.99 }],
    );
    const found = await searchByIntent(
      provider,
      request({ text: 'medplus in exampletown', hasArea: false }),
      { ...OPTIONS, localFirst: { radiusMeters: 50_000, minLocalResults: 1 } },
    );
    expect(categoryCalls[0]).toMatchObject({
      category: 'pharmacy',
      nearLatitude: 11.23,
      nearLongitude: 21.99,
    });
    expect(found.searchedAround).toBe('placeHint');
    expect(found.results.map((r) => [r.id, r.distanceMeters])).toEqual([['m', 1100]]);
  });
});
