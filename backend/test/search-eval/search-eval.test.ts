// SPDX-License-Identifier: AGPL-3.0-only
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  GeocoderError,
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
    const queries = fixture.entries.map((item) => item.query.normalize('NFC'));
    expect(new Set(queries).size).toBe(queries.length);
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
    ];
    for (const candidate of bad) {
      expect(FixtureEntrySchema.safeParse(candidate).success).toBe(false);
    }
    expect(FixtureEntrySchema.safeParse(entry()).success).toBe(true);
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
      ['every district top-3', false],
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

describe('pnpm search:eval settings', () => {
  const FAKE_KEY = 'FAKEKEY-do-not-print-7c1d';

  it('refuses to run without a provider name or a key, naming what is missing only', async () => {
    for (const env of [
      {},
      { GEOCODING_API_KEY: FAKE_KEY },
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
      });
    }
    expect(
      readSettings({
        GEOCODING_PROVIDER: 'geoapify',
        GEOCODING_API_KEY: FAKE_KEY,
        SEARCH_EVAL_RPS: '3',
      }),
    ).toMatchObject({ ok: true, rps: 3 });
  });
});
