// SPDX-License-Identifier: AGPL-3.0-only
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  GeocoderError,
  type CategoryQuery,
  type GeocoderProvider,
  type GeocoderQuery,
  type PlaceResult,
} from '../../src/modules/search/types.js';
import {
  FIXTURE_PATH,
  FixtureEntrySchema,
  loadFixture,
  SCRIPTS,
  type FixtureEntry,
} from './fixture.js';
import {
  buildReport,
  formatReport,
  haversineMeters,
  nameMatches,
  rate,
  runEvaluation,
  scoreQuery,
} from './harness.js';
import { main, readSettings } from './run.js';

const TEMPLATE_PATH = join(import.meta.dirname, 'small-town-template.json');

const place = (name: string, label = '', latitude = 10, longitude = 20): PlaceResult => ({
  id: `fake-${name}`,
  name,
  label,
  latitude,
  longitude,
  kind: 'fake',
});

const entry = (overrides: Partial<FixtureEntry> = {}): FixtureEntry => ({
  id: 'fake-entry',
  query: 'Main Station',
  script: 'en',
  district: 'District A',
  expectedNameContains: ['Main Station'],
  addedBy: 'claude-known',
  ...overrides,
});

describe('the committed fixture', () => {
  const fixture = loadFixture();

  it('is valid, with unique ids and queries', () => {
    const ids = fixture.entries.map((item) => item.id);
    expect(new Set(ids).size).toBe(ids.length);
    // The same generic query ("bank") may be asked from different towns, never twice from one.
    const queries = fixture.entries.map(
      (item) =>
        item.query.normalize('NFC') +
        (item.near === undefined
          ? ''
          : ` @ ${String(item.near.latitude)},${String(item.near.longitude)}`),
    );
    expect(new Set(queries).size).toBe(queries.length);
  });

  it('has at least 15 local-intent entries in at least 6 districts, all for Rahul to review', () => {
    const local = fixture.entries.filter((item) => item.near !== undefined);
    expect(local.length).toBeGreaterThanOrEqual(15);
    expect(new Set(local.map((item) => item.district)).size).toBeGreaterThanOrEqual(6);
    expect(local.some((item) => item.intent === 'generic')).toBe(true);
    expect(local.some((item) => item.intent === 'named')).toBe(true);
    for (const item of local) {
      // Inside the box around West Bengal, and coarse: a town, never a spot.
      expect(item.near?.latitude, item.id).toBeGreaterThan(21.4);
      expect(item.near?.latitude, item.id).toBeLessThan(27.3);
      expect(item.near?.longitude, item.id).toBeGreaterThan(85.8);
      expect(item.near?.longitude, item.id).toBeLessThan(89.9);
    }
  });

  it('has at least 40 entries in at least 10 districts, in all three scripts', () => {
    expect(fixture.entries.length).toBeGreaterThanOrEqual(40);
    expect(new Set(fixture.entries.map((item) => item.district)).size).toBeGreaterThanOrEqual(10);
    for (const script of SCRIPTS) {
      expect(fixture.entries.filter((item) => item.script === script).length).toBeGreaterThan(5);
    }
  });

  it('labels the script truthfully', () => {
    const bengali = /[ঀ-৿]/;
    for (const item of fixture.entries) {
      expect(bengali.test(item.query), item.id).toBe(item.script === 'bn');
    }
  });

  it('holds nothing that looks like personal data', () => {
    const raw = readFileSync(FIXTURE_PATH, 'utf8');
    // No phone numbers, e-mail addresses, house numbers or people's titles.
    expect(raw).not.toMatch(/\d{5,}/);
    expect(raw).not.toMatch(/@/);
    expect(raw).not.toMatch(/\b(flat|house no|h\.?no|apartment|residence|mr\.?|mrs\.?|ms\.?)\b/i);
  });

  it('never has coordinates without a cited source', () => {
    for (const item of fixture.entries) {
      if (item.expected !== undefined) expect(item.expected.source.length).toBeGreaterThan(5);
    }
  });

  it('rejects entries that break the rules', () => {
    const bad: unknown[] = [
      { ...entry(), id: 'Has Spaces' },
      { ...entry(), query: 'a' },
      { ...entry(), script: 'hi' },
      { ...entry(), expectedNameContains: [] },
      { ...entry(), addedBy: 'someone' },
      { ...entry(), homeAddress: 'not allowed' },
      { ...entry(), expected: { latitude: 10, longitude: 20, toleranceMeters: 500 } },
      // A local-intent entry needs both fields, and a coarse point.
      { ...entry(), near: { latitude: 10.12, longitude: 20.34 } },
      { ...entry(), intent: 'generic' },
      { ...entry(), intent: 'generic', near: { latitude: 10.123, longitude: 20.34 } },
      { ...entry(), intent: 'nearby', near: { latitude: 10.12, longitude: 20.34 } },
    ];
    for (const candidate of bad) {
      expect(FixtureEntrySchema.safeParse(candidate).success).toBe(false);
    }
    expect(FixtureEntrySchema.safeParse(entry()).success).toBe(true);
    expect(
      FixtureEntrySchema.safeParse({
        ...entry(),
        intent: 'generic',
        near: { latitude: 10.12, longitude: 20.3 },
      }).success,
    ).toBe(true);
  });
});

describe('scoring', () => {
  it('matches the name or the label, ignoring case, spacing and Unicode form', () => {
    expect(nameMatches(place('MAIN   station'), entry())).toBe(true);
    expect(nameMatches(place('Platform 1', 'near Main Station, District A'), entry())).toBe(true);
    expect(nameMatches(place('Bus Stand'), entry())).toBe(false);
    // Decomposed and precomposed Bengali vowel signs compare equal.
    const expected = entry({ expectedNameContains: ['কোন'] });
    expect(nameMatches(place('কোন পথ'), expected)).toBe(true);
    // Any one of the alternatives is enough.
    expect(nameMatches(place('Old Name'), entry({ expectedNameContains: ['New', 'Old'] }))).toBe(
      true,
    );
  });

  it('scores top-1 and top-3 separately', () => {
    const hit = place('Main Station');
    const miss = place('Elsewhere');
    expect(scoreQuery(entry(), [hit, miss])).toMatchObject({ top1: true, top3: true });
    expect(scoreQuery(entry(), [miss, miss, hit])).toMatchObject({ top1: false, top3: true });
    expect(scoreQuery(entry(), [miss, miss, miss, hit])).toMatchObject({
      top1: false,
      top3: false,
    });
    expect(scoreQuery(entry(), [])).toMatchObject({ top1: false, top3: false, resultCount: 0 });
  });

  it('scores distance only when the entry has an expected position', () => {
    expect(scoreQuery(entry(), [place('Main Station')])).not.toHaveProperty('withinDistance');
    const located = entry({
      expected: { latitude: 10, longitude: 20, toleranceMeters: 500, source: 'fake source' },
    });
    expect(scoreQuery(located, [place('X', '', 10.001, 20.001)]).withinDistance).toBe(true);
    expect(scoreQuery(located, [place('X', '', 10.1, 20.1)]).withinDistance).toBe(false);
  });

  it('haversine is right to within a percent', () => {
    // One degree of latitude is about 111.2 km.
    const metres = haversineMeters(
      { latitude: 10, longitude: 20 },
      { latitude: 11, longitude: 20 },
    );
    expect(metres).toBeGreaterThan(110_000);
    expect(metres).toBeLessThan(112_500);
  });
});

/** Answers by query text; unknown queries get no results. */
function scriptedProvider(answers: Record<string, PlaceResult[] | GeocoderError>) {
  const calls: GeocoderQuery[] = [];
  const provider: GeocoderProvider = {
    name: 'fake',
    attribution: null,
    search: (query) => {
      calls.push(query);
      const answer = answers[query.query] ?? [];
      return answer instanceof GeocoderError ? Promise.reject(answer) : Promise.resolve(answer);
    },
  };
  return { provider, calls };
}

const ENTRIES: FixtureEntry[] = [
  entry({ id: 'a1', query: 'Main Station', district: 'District A' }),
  entry({
    id: 'a2',
    query: 'Old Market',
    district: 'District A',
    expectedNameContains: ['Market'],
  }),
  entry({
    id: 'b1',
    query: '  Hill   Temple ',
    district: 'District B',
    expectedNameContains: ['Temple'],
  }),
  entry({
    id: 'b2',
    query: 'প্রধান স্টেশন',
    script: 'bn',
    district: 'District B',
    expectedNameContains: ['স্টেশন'],
  }),
  entry({ id: 'c1', query: 'Riverside', script: 'translit', district: 'District C' }),
];

describe('runEvaluation and the report', () => {
  const answers = {
    'Main Station': [place('Main Station')],
    'Old Market': [place('Bus Stand'), place('Old Market')],
    'Hill Temple': [place('Elsewhere')],
    'প্রধান স্টেশন': [place('প্রধান স্টেশন')],
    Riverside: new GeocoderError('timeout'),
  };

  it('calls the provider like the endpoint does, paced, and scores each query', async () => {
    const { provider, calls } = scriptedProvider(answers);
    const pauses: number[] = [];
    const outcomes = await runEvaluation({
      provider,
      entries: ENTRIES,
      requestsPerSecond: 2,
      sleep: (ms) => {
        pauses.push(ms);
        return Promise.resolve();
      },
    });

    expect(pauses).toEqual([500, 500, 500, 500]);
    // Normalised query, default bias and limit, Bengali names for Bengali text.
    expect(calls.map((call) => call.query)).toContain('Hill Temple');
    expect(calls.every((call) => call.limit === 6 && call.nearLatitude === 22.57)).toBe(true);
    expect(calls.map((call) => call.language)).toEqual(['en', 'en', 'en', 'bn', 'en']);

    expect(outcomes.map(({ id, top1, top3, error }) => ({ id, top1, top3, error }))).toEqual([
      { id: 'a1', top1: true, top3: true, error: undefined },
      { id: 'a2', top1: false, top3: true, error: undefined },
      { id: 'b1', top1: false, top3: false, error: undefined },
      { id: 'b2', top1: true, top3: true, error: undefined },
      { id: 'c1', top1: false, top3: false, error: 'timeout' },
    ]);

    const report = buildReport('fake', outcomes);
    // A provider error is counted apart and left out of the rate.
    expect(report.overall).toEqual({ group: 'overall', queries: 5, errors: 1, top1: 2, top3: 3 });
    expect(rate(report.overall.top3, report.overall)).toBe(0.75);
    expect(report.byDistrict.map((score) => [score.group, score.top3, score.queries])).toEqual([
      ['District A', 2, 2],
      ['District B', 1, 2],
      ['District C', 0, 1],
    ]);
    expect(report.byScript.map((score) => score.group)).toEqual(['bn', 'en', 'translit']);
    expect(report.thresholds.map((threshold) => [threshold.name, threshold.met])).toEqual([
      ['overall top-3', false],
      ['every district with at least 3 queries, top-3', true],
      ['Bengali-script top-3', true],
    ]);

    const text = formatReport(report);
    expect(text).toContain('| overall | 5 | 1 | 50% | 75% |');
    expect(text).toContain('| District B | 2 | 0 | 50% | 50% |');
    expect(text).toContain('| Bengali-script top-3 | 70% | yes |');
    // The shareable table has counts only: no query and no result name.
    for (const secret of ['Main Station', 'Old Market', 'Riverside', 'প্রধান', 'Bus Stand']) {
      expect(text).not.toContain(secret);
    }
  });

  it('all thresholds met for a perfect run; a group with only errors has rate 0', () => {
    const perfect = ENTRIES.map((item) =>
      scoreQuery(item, [place(item.expectedNameContains[0] ?? '')]),
    );
    expect(buildReport('fake', perfect).thresholds.every((threshold) => threshold.met)).toBe(true);
    const failed = buildReport('fake', [{ ...scoreQuery(entry(), []), error: 'auth' }]);
    expect(rate(failed.overall.top3, failed.overall)).toBe(0);
  });
});

describe('local-intent queries', () => {
  const NEAR = { latitude: 10, longitude: 20 };
  const local = (id: string, query: string, intent: 'generic' | 'named') =>
    entry({ id, query, intent, near: NEAR, expectedNameContains: ['Bank'] });
  // 0.1 degrees north is 11 km (nearby); 2 degrees is 222 km (not nearby).
  const bank = (north: number) => place(`Town Bank ${String(north)}`, '', 10 + north, 20);

  it("searches local-first from the entry's point and scores a nearby name match only", async () => {
    const calls: GeocoderQuery[] = [];
    const provider: GeocoderProvider = {
      name: 'fake',
      attribution: null,
      search: (query) => {
        calls.push(query);
        const nearbyPass = query.withinMeters !== undefined;
        if (query.query === 'bank') {
          return Promise.resolve(nearbyPass ? [bank(0.1), bank(0.2), bank(0.3)] : []);
        }
        if (query.query === 'far bank') return Promise.resolve(nearbyPass ? [] : [bank(2)]);
        if (query.query === 'slow bank') return Promise.reject(new GeocoderError('timeout'));
        return Promise.resolve([place('Main Station')]);
      },
    };
    const pauses: number[] = [];
    const outcomes = await runEvaluation({
      provider,
      entries: [
        local('l1', 'bank', 'generic'),
        local('l2', 'far bank', 'generic'),
        local('l3', 'slow bank', 'named'),
        entry({ id: 's1' }),
      ],
      requestsPerSecond: 1,
      sleep: (ms) => {
        pauses.push(ms);
        return Promise.resolve();
      },
    });

    // The entry's own point, with the default radius; the wide pass only when needed, and
    // paced like any other call (three waits between entries, one before the wide pass).
    expect(calls.map((call) => [call.query, call.nearLatitude, call.withinMeters])).toEqual([
      ['bank', 10, 50_000],
      ['far bank', 10, 50_000],
      ['far bank', 10, undefined],
      ['slow bank', 10, 50_000],
      ['Main Station', 22.57, undefined],
    ]);
    expect(pauses).toHaveLength(4);
    expect(
      outcomes.map(({ id, intent, top3, nearbyTop3, providerCalls, error }) => ({
        id,
        intent,
        top3,
        nearbyTop3,
        providerCalls,
        error,
      })),
    ).toEqual([
      { id: 'l1', intent: 'generic', top3: true, nearbyTop3: true, providerCalls: 1 },
      // The right name, 222 km away: a name hit, but not what a person nearby wanted.
      { id: 'l2', intent: 'generic', top3: true, nearbyTop3: false, providerCalls: 2 },
      { id: 'l3', intent: 'named', top3: false, nearbyTop3: false, error: 'timeout' },
      { id: 's1', top3: true },
    ]);

    const report = buildReport('fake', outcomes);
    // Local-intent queries stay out of the tables that earlier runs are compared with.
    expect(report.overall).toMatchObject({ queries: 1, top3: 1 });
    expect(report.byDistrict).toHaveLength(1);
    expect(report.localIntent).toEqual([
      { group: 'generic', queries: 2, errors: 0, nearbyTop3: 1, widePasses: 1 },
      { group: 'named', queries: 1, errors: 1, nearbyTop3: 0, widePasses: 0 },
      { group: 'all local intent', queries: 3, errors: 1, nearbyTop3: 1, widePasses: 1 },
    ]);
    const text = formatReport(report);
    expect(text).toContain('### Local intent (top-3 within 25 km of where the search is made)');
    expect(text).toContain('| generic | 2 | 0 | 50% | 1 |');
    expect(text).toContain('| named | 1 | 1 | 0% | 0 |');
    for (const secret of ['Town Bank', 'far bank', '10,20']) expect(text).not.toContain(secret);
  });

  it('prints no local table for a fixture without local-intent entries', () => {
    const report = buildReport('fake', [scoreQuery(entry(), [place('Main Station')])]);
    expect(report.localIntent).toEqual([]);
    expect(formatReport(report)).not.toContain('Local intent');
  });
});

describe('pnpm search:eval settings', () => {
  const FAKE_KEY = 'FAKEKEY-do-not-print-7c1d';

  it('refuses to run without a provider name or a key, naming what is missing only', async () => {
    for (const env of [
      {},
      { GEOCODING_PROVIDER: 'not-a-provider', GEOCODING_API_KEY: FAKE_KEY },
      { GEOCODING_PROVIDER: 'geoapify' },
      { GEOCODING_PROVIDER: 'geoapify', GEOCODING_API_KEY: '   ' },
      { GEOCODING_PROVIDER: 'geoapify', GEOCODING_API_KEY: FAKE_KEY, SEARCH_EVAL_RPS: '50' },
    ]) {
      const lines: string[] = [];
      // Resolves without calling any provider: nothing here may reach the network.
      await main(env, (line) => lines.push(line));
      expect(lines).toHaveLength(1);
      expect(lines[0]).toMatch(/^search evaluation not run: /);
      expect(lines.join('\n')).not.toContain(FAKE_KEY);
      expect(lines.join('\n')).not.toContain('not-a-provider');
    }
  });

  it('selects the provider by name and defaults to one request per second', () => {
    for (const provider of ['geoapify', 'locationiq']) {
      expect(readSettings({ GEOCODING_PROVIDER: provider, GEOCODING_API_KEY: FAKE_KEY })).toEqual({
        ok: true,
        provider,
        apiKey: FAKE_KEY,
        rps: 1,
        timeoutMs: 5000,
        fixture: 'fixture.json',
      });
    }
    // Another fixture is chosen by FILE NAME in this folder; a path is refused, not echoed.
    const key = { GEOCODING_API_KEY: FAKE_KEY };
    expect(readSettings({ ...key, SEARCH_EVAL_FIXTURE: 'small-town-template.json' })).toMatchObject(
      { ok: true, fixture: 'small-town-template.json' },
    );
    for (const bad of ['../../.env', 'C:/somewhere/else.json', 'fixture', 'a b.json']) {
      const refused = readSettings({ ...key, SEARCH_EVAL_FIXTURE: bad });
      expect(refused.ok).toBe(false);
      expect(JSON.stringify(refused)).not.toContain(bad);
    }
    expect(
      readSettings({
        GEOCODING_PROVIDER: 'geoapify',
        GEOCODING_API_KEY: FAKE_KEY,
        SEARCH_EVAL_RPS: '3',
      }),
    ).toMatchObject({ ok: true, rps: 3 });
    // No provider named: the default.
    expect(readSettings({ GEOCODING_API_KEY: FAKE_KEY })).toMatchObject({
      ok: true,
      provider: 'geoapify',
    });
  });
});

describe('category, brand and name intents (P011f1)', () => {
  const NEAR = { latitude: 10, longitude: 20 };
  const nearby = (
    id: string,
    query: string,
    intent: 'category' | 'brand' | 'name',
    extra: Partial<FixtureEntry> = {},
  ) => entry({ id, query, intent, near: NEAR, expectedNameContains: ['Bank'], ...extra });
  const at = (name: string, north: number) => place(name, '', 10 + north, 20);

  it('the small-town template is valid: 20+ rows, 8+ districts, all for Rahul to review', () => {
    const template = loadFixture(TEMPLATE_PATH);
    expect(template.description).toMatch(/^TEMPLATE/);
    expect(template.entries.length).toBeGreaterThanOrEqual(20);
    expect(new Set(template.entries.map((item) => item.id)).size).toBe(template.entries.length);
    expect(new Set(template.entries.map((item) => item.district)).size).toBeGreaterThanOrEqual(8);
    for (const intent of ['category', 'brand']) {
      expect(template.entries.some((item) => item.intent === intent)).toBe(true);
    }
    for (const item of template.entries) {
      expect(item.addedBy, item.id).toBe('claude-known');
      expect(item.inOsm, item.id).toBe('unknown');
      expect(item.near?.latitude, item.id).toBeGreaterThan(21.4);
      expect(item.near?.latitude, item.id).toBeLessThan(27.3);
      expect(item.near?.longitude, item.id).toBeGreaterThan(85.8);
      expect(item.near?.longitude, item.id).toBeLessThan(89.9);
    }
    const raw = readFileSync(TEMPLATE_PATH, 'utf8');
    expect(raw).not.toMatch(/\d{5,}/);
    expect(raw).not.toMatch(/@/);
    expect(raw).not.toMatch(/\b(flat|house no|h\.?no|apartment|residence|mr\.?|mrs\.?|ms\.?)\b/i);
  });

  it('inOsm and radiusKm belong to these intents only; the radius never exceeds 25 km', () => {
    expect(
      FixtureEntrySchema.safeParse(nearby('n', 'bank', 'category', { inOsm: 'no' })).success,
    ).toBe(true);
    expect(
      FixtureEntrySchema.safeParse(nearby('n', 'bank', 'brand', { radiusKm: 25 })).success,
    ).toBe(true);
    const bad: unknown[] = [
      { ...entry(), inOsm: 'yes' },
      { ...entry({ near: NEAR, intent: 'generic' }), inOsm: 'yes' },
      { ...entry({ near: NEAR, intent: 'generic' }), radiusKm: 10 },
      { ...nearby('n', 'bank', 'category'), radiusKm: 26 },
      { ...nearby('n', 'bank', 'category'), inOsm: 'maybe' },
      { ...entry(), intent: 'category' },
    ];
    for (const item of bad) expect(FixtureEntrySchema.safeParse(item).success).toBe(false);
  });

  it('searches like the endpoint, looks further away only after a miss, and counts gaps apart', async () => {
    const categoryCalls: CategoryQuery[] = [];
    const nameCalls: GeocoderQuery[] = [];
    const provider: GeocoderProvider = {
      name: 'fake',
      attribution: null,
      // Plain name search: a "Bank" 222 km away exists for every query but "pharmacy".
      search: (query) => {
        nameCalls.push(query);
        return Promise.resolve(query.query === 'pharmacy' ? [] : [at('Far Bank', 2)]);
      },
      searchCategory: (query) => {
        categoryCalls.push(query);
        if (query.category === 'atm') return Promise.reject(new GeocoderError('upstream'));
        if (query.category === 'bank' && query.nearLatitude === 10) {
          return Promise.resolve([
            at('Town Bank', 0.01),
            at('SBI Bank', 0.02),
            at('Old Bank', 0.03),
          ]);
        }
        return Promise.resolve([]);
      },
    };
    const pauses: number[] = [];
    const outcomes = await runEvaluation({
      provider,
      entries: [
        nearby('n1', 'bank', 'category', { inOsm: 'yes' }),
        nearby('n2', 'pharmacy', 'category', { inOsm: 'no', district: 'District B' }),
        nearby('n3', 'pnb', 'brand', { inOsm: 'yes', district: 'District B' }),
        nearby('n4', 'atm', 'category', { district: 'District B' }),
        nearby('n5', 'hospital', 'category', { radiusKm: 25, inOsm: 'unknown' }),
      ],
      requestsPerSecond: 1,
      sleep: (ms) => {
        pauses.push(ms);
        return Promise.resolve();
      },
    });

    // Default first radius 10 km, widened once; the entry's own radius of 25 km is not widened.
    expect(categoryCalls.map((call) => [call.category, call.withinMeters])).toEqual([
      ['bank', 10_000],
      ['pharmacy', 10_000],
      ['pharmacy', 25_000],
      ['bank', 10_000],
      ['bank', 25_000],
      ['atm', 10_000],
      ['hospital', 25_000],
    ]);
    // One plain name search after each miss, without an area.
    expect(nameCalls.map((call) => [call.query, call.withinMeters])).toEqual([
      ['pharmacy', undefined],
      ['pnb', undefined],
      ['hospital', undefined],
    ]);
    expect(
      outcomes.map(({ id, nearby: found, providerCalls, error }) => ({
        id,
        found,
        providerCalls,
        error,
      })),
    ).toEqual([
      { id: 'n1', found: 'within', providerCalls: 1 },
      { id: 'n2', found: 'none', providerCalls: 3 },
      // A bank is nearby, but not of this brand; only the far name search has the word.
      { id: 'n3', found: 'far', providerCalls: 3 },
      { id: 'n4', found: undefined, providerCalls: undefined, error: 'upstream' },
      { id: 'n5', found: 'far', providerCalls: 2 },
    ]);
    // Paced: between entries (4), before each widening (2) and before each far search (3).
    expect(pauses).toHaveLength(9);

    const report = buildReport('fake', outcomes);
    // These entries stay out of every earlier table.
    expect(report.overall.queries).toBe(0);
    expect(report.localIntent).toEqual([]);
    expect(report.nearbyIntent).toEqual([
      {
        group: 'District A',
        queries: 2,
        errors: 0,
        within: 1,
        far: 1,
        none: 0,
        dataGap: 0,
        searchFailure: 0,
      },
      {
        group: 'District B',
        queries: 3,
        errors: 1,
        within: 0,
        far: 1,
        none: 1,
        dataGap: 1,
        searchFailure: 1,
      },
      {
        group: 'all districts',
        queries: 5,
        errors: 1,
        within: 1,
        far: 2,
        none: 1,
        dataGap: 1,
        searchFailure: 1,
      },
    ]);
    const text = formatReport(report);
    expect(text).toContain('### Category/brand intent');
    expect(text).toContain('| District B | 3 | 1 | 0 | 1 | 1 | 1 | 1 |');
    // Counts only: no query, no result name.
    for (const secret of ['pharmacy', 'Town Bank', 'Far Bank', 'pnb']) {
      expect(text).not.toContain(secret);
    }
  });

  it('prints no category table for a fixture without such entries', () => {
    const report = buildReport('fake', [scoreQuery(entry(), [place('Main Station')])]);
    expect(report.nearbyIntent).toEqual([]);
    expect(formatReport(report)).not.toContain('Category/brand intent');
  });
});
