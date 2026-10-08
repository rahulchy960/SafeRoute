// SPDX-License-Identifier: AGPL-3.0-only
import {
  CATEGORY_SEARCH_DEFAULTS,
  searchByIntent,
} from '../../src/modules/search/intent-search.js';
import { LOCAL_SEARCH_DEFAULTS, searchLocalFirst } from '../../src/modules/search/local-first.js';
import { normalizeQuery } from '../../src/modules/search/normalize.js';
import { SEARCH_DEFAULT_LIMIT } from '../../src/modules/search/schema.js';
import {
  GeocoderError,
  type GeocoderProvider,
  type PlaceResult,
} from '../../src/modules/search/types.js';
import { LAUNCH_REGION_CENTER } from '../../src/regions/defaults.js';
import {
  isNearbyIntent,
  LOCAL_INTENTS,
  type FixtureEntry,
  type Intent,
  type Script,
} from './fixture.js';

/**
 * Thresholds confirmed by Rahul on 2026-10-07 (addendum v7.2 section E, ADR 0018). The report
 * says whether each is met; nothing fails when one is not.
 */
export const PROPOSED_THRESHOLDS = {
  overallTop3: 0.8,
  everyDistrictTop3: 0.6,
  bengaliScriptTop3: 0.7,
} as const;

/**
 * The per-district rule looks only at districts with at least this many queries: with one or two
 * queries a single miss decides the rate (ADR 0018, note of 2026-10-07). Smaller districts are
 * still listed in the table.
 */
export const MIN_DISTRICT_QUERIES = 3;

/**
 * A local-intent query is a hit when one of the first three results has the right name AND lies
 * within this distance of the entry's `near` point. 25 km: about the reach of a town and the
 * villages around it, and half the default radius of the nearby pass.
 */
export const LOCAL_INTENT_RADIUS_METERS = 25_000;

export interface QueryOutcome {
  id: string;
  district: string;
  script: Script;
  /** Only for a local-intent entry (one with `near`). */
  intent?: Intent;
  /** Local intent: a top-3 result with the right name within LOCAL_INTENT_RADIUS_METERS. */
  nearbyTop3?: boolean;
  /** Local intent: how many provider calls the search took (1 or 2). */
  providerCalls?: number;
  /**
   * Category, brand and name intents: `within` when the search, run like the endpoint runs it,
   * returned a place with the right name inside the circle it searched; `far` when only a plain
   * name search without any area found one; `none` when neither did.
   */
  nearby?: 'within' | 'far' | 'none';
  /** Copied from the entry, for the data-gap and search-failure counts. */
  inOsm?: 'yes' | 'no' | 'unknown';
  /** Category, brand and name intents: the radius the search ended with, when it used a circle. */
  searchedRadiusKm?: number;
  /** Set when the provider call failed; such a query is counted apart, not as a miss. */
  error?: string;
  top1: boolean;
  top3: boolean;
  /** Only when the entry has `expected`: a top-3 result within its tolerance. */
  withinDistance?: boolean;
  resultCount: number;
  /** Names of the first three results, for the local detail file only. */
  topNames: string[];
}

export interface GroupScore {
  group: string;
  queries: number;
  errors: number;
  top1: number;
  top3: number;
}

export interface LocalScore {
  group: string;
  queries: number;
  errors: number;
  /** Answered queries with a matching result nearby in the top three. */
  nearbyTop3: number;
  /** Answered queries that needed the second, wide provider call. */
  widePasses: number;
}

/** One row of the "category/brand intent" table. Counts only. */
export interface NearbyScore {
  group: string;
  queries: number;
  errors: number;
  within: number;
  far: number;
  none: number;
  /** Not found within the radius, and the entry says the place is NOT on OpenStreetMap. */
  dataGap: number;
  /** Not found within the radius, although the entry says the place IS on OpenStreetMap. */
  searchFailure: number;
}

export interface Report {
  provider: string;
  overall: GroupScore;
  byDistrict: GroupScore[];
  byScript: GroupScore[];
  /** One row per intent and one for all; empty when the fixture has no local-intent entry. */
  localIntent: LocalScore[];
  /** One row per district and one for all; empty without category, brand or name entries. */
  nearbyIntent: NearbyScore[];
  thresholds: { name: string; target: number; met: boolean }[];
}

/** Same folding for expected text and results: NFC, lower case, single spaces. */
const fold = (text: string) => text.normalize('NFC').toLowerCase().replace(/\s+/g, ' ').trim();

export function nameMatches(result: PlaceResult, entry: FixtureEntry): boolean {
  const haystack = fold(`${result.name} ${result.label}`);
  return entry.expectedNameContains.some((needle) => haystack.includes(fold(needle)));
}

/** Great-circle distance in metres. */
export function haversineMeters(
  a: { latitude: number; longitude: number },
  b: { latitude: number; longitude: number },
): number {
  const rad = (degrees: number) => (degrees * Math.PI) / 180;
  const dLat = rad(b.latitude - a.latitude);
  const dLon = rad(b.longitude - a.longitude);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(rad(a.latitude)) * Math.cos(rad(b.latitude)) * Math.sin(dLon / 2) ** 2;
  return 2 * 6_371_000 * Math.asin(Math.sqrt(h));
}

export function scoreQuery(entry: FixtureEntry, results: PlaceResult[]): QueryOutcome {
  const top3 = results.slice(0, 3);
  const expected = entry.expected;
  const near = entry.near;
  return {
    id: entry.id,
    district: entry.district,
    script: entry.script,
    ...(near === undefined || entry.intent === undefined || isNearbyIntent(entry.intent)
      ? entry.intent === undefined
        ? {}
        : { intent: entry.intent }
      : {
          intent: entry.intent,
          nearbyTop3: top3.some(
            (result) =>
              nameMatches(result, entry) &&
              haversineMeters(result, near) <= LOCAL_INTENT_RADIUS_METERS,
          ),
        }),
    top1: top3[0] !== undefined && nameMatches(top3[0], entry),
    top3: top3.some((result) => nameMatches(result, entry)),
    ...(expected === undefined
      ? {}
      : {
          withinDistance: top3.some(
            (result) => haversineMeters(result, expected) <= expected.toleranceMeters,
          ),
        }),
    resultCount: results.length,
    topNames: top3.map((result) => result.name),
  };
}

function scoreGroup(group: string, outcomes: QueryOutcome[]): GroupScore {
  const answered = outcomes.filter((outcome) => outcome.error === undefined);
  return {
    group,
    queries: outcomes.length,
    errors: outcomes.length - answered.length,
    top1: answered.filter((outcome) => outcome.top1).length,
    top3: answered.filter((outcome) => outcome.top3).length,
  };
}

/** Hit rate over the queries the provider answered; 0 when it answered none. */
export function rate(hits: number, score: GroupScore): number {
  const answered = score.queries - score.errors;
  return answered === 0 ? 0 : hits / answered;
}

function groupBy(outcomes: QueryOutcome[], key: (outcome: QueryOutcome) => string): GroupScore[] {
  const groups = new Map<string, QueryOutcome[]>();
  for (const outcome of outcomes) {
    groups.set(key(outcome), [...(groups.get(key(outcome)) ?? []), outcome]);
  }
  return [...groups.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([group, members]) => scoreGroup(group, members));
}

function scoreLocal(group: string, outcomes: QueryOutcome[]): LocalScore {
  const answered = outcomes.filter((outcome) => outcome.error === undefined);
  return {
    group,
    queries: outcomes.length,
    errors: outcomes.length - answered.length,
    nearbyTop3: answered.filter((outcome) => outcome.nearbyTop3 === true).length,
    widePasses: answered.filter((outcome) => outcome.providerCalls === 2).length,
  };
}

function scoreNearby(group: string, outcomes: QueryOutcome[]): NearbyScore {
  const answered = outcomes.filter((outcome) => outcome.error === undefined);
  const count = (test: (outcome: QueryOutcome) => boolean) => answered.filter(test).length;
  return {
    group,
    queries: outcomes.length,
    errors: outcomes.length - answered.length,
    within: count((outcome) => outcome.nearby === 'within'),
    far: count((outcome) => outcome.nearby === 'far'),
    none: count((outcome) => outcome.nearby === 'none'),
    dataGap: count((outcome) => outcome.nearby !== 'within' && outcome.inOsm === 'no'),
    searchFailure: count((outcome) => outcome.nearby !== 'within' && outcome.inOsm === 'yes'),
  };
}

/**
 * Local-intent queries are scored apart. The overall, per-script and per-district tables and the
 * thresholds cover the other queries only, so they stay comparable with earlier runs.
 */
export function buildReport(provider: string, all: QueryOutcome[]): Report {
  const outcomes = all.filter((outcome) => outcome.intent === undefined);
  const local = all.filter(
    (outcome) => outcome.intent !== undefined && !isNearbyIntent(outcome.intent),
  );
  const nearby = all.filter((outcome) => isNearbyIntent(outcome.intent));
  const nearbyDistricts = [...new Set(nearby.map((outcome) => outcome.district))].sort((a, b) =>
    a.localeCompare(b),
  );
  const nearbyIntent =
    nearby.length === 0
      ? []
      : [
          ...nearbyDistricts.map((district) =>
            scoreNearby(
              district,
              nearby.filter((outcome) => outcome.district === district),
            ),
          ),
          scoreNearby('all districts', nearby),
        ];
  const localIntent =
    local.length === 0
      ? []
      : [
          ...LOCAL_INTENTS.map((intent) =>
            scoreLocal(
              intent,
              local.filter((outcome) => outcome.intent === intent),
            ),
          ).filter((score) => score.queries > 0),
          scoreLocal('all local intent', local),
        ];
  const overall = scoreGroup('overall', outcomes);
  const byDistrict = groupBy(outcomes, (outcome) => outcome.district);
  const byScript = groupBy(outcomes, (outcome) => outcome.script);
  const bengali = byScript.find((score) => score.group === 'bn');
  const t = PROPOSED_THRESHOLDS;
  return {
    provider,
    overall,
    byDistrict,
    byScript,
    localIntent,
    nearbyIntent,
    thresholds: [
      {
        name: 'overall top-3',
        target: t.overallTop3,
        met: rate(overall.top3, overall) >= t.overallTop3,
      },
      {
        name: `every district with at least ${String(MIN_DISTRICT_QUERIES)} queries, top-3`,
        target: t.everyDistrictTop3,
        met: byDistrict
          .filter((score) => score.queries >= MIN_DISTRICT_QUERIES)
          .every((score) => rate(score.top3, score) >= t.everyDistrictTop3),
      },
      {
        name: 'Bengali-script top-3',
        target: t.bengaliScriptTop3,
        met: bengali !== undefined && rate(bengali.top3, bengali) >= t.bengaliScriptTop3,
      },
    ],
  };
}

const percent = (value: number) => `${(value * 100).toFixed(0)}%`;

/** The aggregate table Rahul shares. Counts and percentages only: no query, no result, no key. */
export function formatReport(report: Report): string {
  const row = (score: GroupScore) =>
    `| ${score.group} | ${String(score.queries)} | ${String(score.errors)} | ` +
    `${percent(rate(score.top1, score))} | ${percent(rate(score.top3, score))} |`;
  const table = (title: string, scores: GroupScore[]) => [
    `### ${title}`,
    '',
    '| Group | Queries | Provider errors | Top-1 | Top-3 |',
    '| --- | --- | --- | --- | --- |',
    ...scores.map(row),
    '',
  ];
  const localRow = (score: LocalScore) => {
    const answered = score.queries - score.errors;
    return (
      `| ${score.group} | ${String(score.queries)} | ${String(score.errors)} | ` +
      `${percent(answered === 0 ? 0 : score.nearbyTop3 / answered)} | ${String(score.widePasses)} |`
    );
  };
  const localTable =
    report.localIntent.length === 0
      ? []
      : [
          `### Local intent (top-3 within ${String(LOCAL_INTENT_RADIUS_METERS / 1000)} km of where the search is made)`,
          '',
          '| Group | Queries | Provider errors | Nearby top-3 | Needed the wide pass |',
          '| --- | --- | --- | --- | --- |',
          ...report.localIntent.map(localRow),
          '',
        ];
  const nearbyTable =
    report.nearbyIntent.length === 0
      ? []
      : [
          '### Category/brand intent (searched like the app does: a circle, widened once)',
          '',
          '| District | Queries | Provider errors | Found within radius | Found only far | Not found | Data gap (not on the map) | Search failure (on the map) |',
          '| --- | --- | --- | --- | --- | --- | --- | --- |',
          ...report.nearbyIntent.map(
            (score) =>
              `| ${score.group} | ${String(score.queries)} | ${String(score.errors)} | ` +
              `${String(score.within)} | ${String(score.far)} | ${String(score.none)} | ` +
              `${String(score.dataGap)} | ${String(score.searchFailure)} |`,
          ),
          '',
          'The last two columns count only entries whose `inOsm` is `yes` or `no`.',
          '',
        ];
  return [
    `## Search evaluation: provider \`${report.provider}\``,
    '',
    ...table('Overall', [report.overall]),
    ...table('By script', report.byScript),
    ...table('By district', report.byDistrict),
    ...localTable,
    ...nearbyTable,
    '### Thresholds (not a gate)',
    '',
    '| Threshold | Target | Met |',
    '| --- | --- | --- |',
    ...report.thresholds.map(
      (threshold) =>
        `| ${threshold.name} | ${percent(threshold.target)} | ${threshold.met ? 'yes' : 'no'} |`,
    ),
    '',
  ].join('\n');
}

export interface RunOptions {
  provider: GeocoderProvider;
  entries: FixtureEntry[];
  /** Requests per second; the harness waits between calls to respect the provider's limits. */
  requestsPerSecond: number;
  sleep?: (ms: number) => Promise<void>;
  onProgress?: (done: number, total: number) => void;
}

/**
 * Runs every fixture query through the real adapter, one at a time, the way the endpoint would:
 * the same normalisation, the default bias, the default limit, Bengali names for Bengali text.
 *
 * An entry with `near` is searched like a request that carries that area: local-first, with the
 * default radius and minimum (ADR 0018, "Local ranking"). It can take two provider calls; the
 * harness waits between them like between any two calls.
 *
 * An entry with a category, brand or name intent goes through `searchByIntent`, the function
 * the endpoint calls: the classifier, the circle and the one widening (ADR 0018, "Category and
 * brand search"). When that finds no place with the right name, ONE more plain name search
 * without any area says whether such a place exists further away. Up to four provider calls.
 */
export async function runEvaluation(options: RunOptions): Promise<QueryOutcome[]> {
  const sleep =
    options.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));
  const pause = Math.ceil(1000 / options.requestsPerSecond);
  const outcomes: QueryOutcome[] = [];

  for (const [index, entry] of options.entries.entries()) {
    if (index > 0) await sleep(pause);
    const query = normalizeQuery(entry.query);
    const failed = (error: string): QueryOutcome => ({ ...scoreQuery(entry, []), error });
    if (query === undefined) {
      outcomes.push(failed('invalid_query'));
    } else {
      try {
        const near = entry.near ?? LAUNCH_REGION_CENTER;
        const request = {
          query,
          nearLatitude: near.latitude,
          nearLongitude: near.longitude,
          language: entry.script === 'bn' ? ('bn' as const) : ('en' as const),
          limit: SEARCH_DEFAULT_LIMIT,
        };
        if (entry.near === undefined) {
          outcomes.push(scoreQuery(entry, await options.provider.search(request)));
        } else if (isNearbyIntent(entry.intent)) {
          const found = await searchByIntent(
            options.provider,
            {
              text: query,
              category: undefined,
              near: entry.near,
              hasArea: true,
              language: request.language,
              limit: request.limit,
            },
            {
              localFirst: {
                radiusMeters: LOCAL_SEARCH_DEFAULTS.radiusKm * 1000,
                minLocalResults: LOCAL_SEARCH_DEFAULTS.minLocalResults,
              },
              categoryRadiusMeters: (entry.radiusKm ?? CATEGORY_SEARCH_DEFAULTS.radiusKm) * 1000,
            },
            // The first call was already spaced by the loop.
            (() => {
              let first = true;
              return () => {
                const wait = first ? Promise.resolve() : sleep(pause);
                first = false;
                return wait;
              };
            })(),
          );
          // A name search is not held to a circle: the widest one counts as "within".
          const reachMeters =
            (found.searchedRadiusKm ?? CATEGORY_SEARCH_DEFAULTS.wideRadiusKm) * 1000;
          const near = entry.near;
          const within = found.results.some(
            (result) => nameMatches(result, entry) && haversineMeters(result, near) <= reachMeters,
          );
          let nearby: 'within' | 'far' | 'none' = 'within';
          if (!within) {
            await sleep(pause);
            const anywhere = await options.provider.search(request);
            nearby = anywhere.some((result) => nameMatches(result, entry)) ? 'far' : 'none';
          }
          outcomes.push({
            ...scoreQuery(entry, found.results),
            nearby,
            providerCalls: found.providerCalls + (within ? 0 : 1),
            ...(entry.inOsm === undefined ? {} : { inOsm: entry.inOsm }),
            ...(found.searchedRadiusKm === undefined
              ? {}
              : { searchedRadiusKm: found.searchedRadiusKm }),
          });
        } else {
          const found = await searchLocalFirst(
            options.provider,
            request,
            {
              radiusMeters: LOCAL_SEARCH_DEFAULTS.radiusKm * 1000,
              minLocalResults: LOCAL_SEARCH_DEFAULTS.minLocalResults,
            },
            () => sleep(pause),
          );
          outcomes.push({
            ...scoreQuery(entry, found.results),
            providerCalls: found.providerCalls,
          });
        }
      } catch (err) {
        // A GeocoderError carries a kind only; anything else is reported without its message.
        outcomes.push(failed(err instanceof GeocoderError ? err.kind : 'unexpected'));
      }
    }
    options.onProgress?.(index + 1, options.entries.length);
  }
  return outcomes;
}
