// SPDX-License-Identifier: AGPL-3.0-only
import { classifier, type Brand, type CategoryKey, type QueryIntent } from './intents.js';
import {
  keysOf,
  searchLocalFirst,
  WidePassRefused,
  withDistance,
  type LocalFirstOptions,
  type WidePass,
} from './local-first.js';
import { coarsen } from './normalize.js';
import { GeocoderError, type GeocoderProvider, type MatchType, type PlaceResult } from './types.js';

/** Default of SEARCH_CATEGORY_RADIUS_KM, and the one radius a category search may widen to. */
export const CATEGORY_SEARCH_DEFAULTS = { radiusKm: 10, wideRadiusKm: 25 } as const;

/**
 * A brand search asks the provider for this many places of the brand's category and keeps the
 * ones with the brand's name. Twenty is the most one provider credit buys (ADR 0018).
 */
export const BRAND_CANDIDATES = 20;

export interface IntentSearchOptions {
  /** The name search around an area (ADR 0018, "Local ranking"). */
  localFirst: LocalFirstOptions;
  /** First radius of a category or brand search. */
  categoryRadiusMeters: number;
}

export interface IntentRequest {
  /** What the user typed, normalised. Absent when the app sent a category alone. */
  text: string | undefined;
  /** A category the app asked for by name (a quick-search chip). Wins over `text`. */
  category: CategoryKey | undefined;
  /** Coarse point: the area the app sent, or the default bias. */
  near: { latitude: number; longitude: number };
  /** True when `near` came from the app. The default bias is a guess, never a search centre. */
  hasArea: boolean;
  language: 'en' | 'bn';
  limit: number;
}

export interface IntentOutcome {
  results: PlaceResult[];
  /** What kind of search answered: the log line carries this word and nothing about the text. */
  matchType: MatchType;
  /** Set when a circle was searched: its radius, and what its centre was. */
  searchedRadiusKm?: number;
  searchedAround?: 'near' | 'placeHint';
  providerCalls: number;
  /** Name search around an area only. */
  localCount?: number;
  widePass?: WidePass;
}

/**
 * Runs `charge` before a provider call that the search cannot do without. `charge` refuses by
 * throwing {@link WidePassRefused}; its `reason` is what the caller gets.
 */
async function must(charge: () => Promise<void>): Promise<void> {
  try {
    await charge();
  } catch (err) {
    throw err instanceof WidePassRefused ? err.reason : err;
  }
}

/**
 * One search, whatever it asks for (ADR 0018, "Category and brand search"). The endpoint and the
 * evaluation harness both come through here.
 *
 * - NAME (anything the dictionary does not know): the name search as before, local-first when
 *   the app sent an area.
 * - CATEGORY ("bank", or a category sent by the app): places of that kind inside a circle,
 *   nearest first. The circle is `categoryRadiusMeters`; with fewer than `minLocalResults`
 *   places it is searched once more at 25 km and never beyond. NOTHING from outside the circle
 *   is ever added: an empty list is the answer when there is nothing.
 * - BRAND ("sbi"): the same, keeping only the places that carry the brand's name.
 * - A PLACE HINT ("sbi exampletown") is looked up once and its coarse point becomes the centre.
 *   When the geocoder does not know the hint, the whole text is searched by name instead.
 * - Without a centre (no area sent, no hint) there is no circle to search. The word is then
 *   searched by name among places only, so a town called Bankura is not an answer to "bank".
 *
 * `charge` runs before EVERY provider call (the endpoint takes the rate-limit tokens there; the
 * harness waits). It refuses by throwing {@link WidePassRefused}. A refusal, or a provider
 * failure, on the widening call keeps the first circle's places when there are any.
 */
export async function searchByIntent(
  provider: GeocoderProvider,
  request: IntentRequest,
  options: IntentSearchOptions,
  charge: () => Promise<void> = () => Promise.resolve(),
): Promise<IntentOutcome> {
  const { near, language, limit } = request;
  const bias = { nearLatitude: near.latitude, nearLongitude: near.longitude };
  const tag = (places: PlaceResult[], matchType: MatchType) =>
    places.map((place) => ({ ...place, matchType }));

  async function byName(text: string, callsBefore: number): Promise<IntentOutcome> {
    const query = { query: text, ...bias, language, limit };
    await must(charge);
    if (!request.hasArea) {
      const results = await provider.search(query);
      return {
        results: tag(results, 'name'),
        matchType: 'name',
        providerCalls: callsBefore + 1,
        localCount: 0,
        widePass: 'off',
      };
    }
    const found = await searchLocalFirst(provider, query, options.localFirst, charge);
    return {
      results: tag(found.results, 'name'),
      matchType: 'name',
      providerCalls: callsBefore + found.providerCalls,
      localCount: found.localCount,
      widePass: found.widePass,
    };
  }

  const intent: QueryIntent =
    request.category !== undefined
      ? { kind: 'category', category: request.category }
      : classifier.classify(request.text ?? '');
  if (intent.kind === 'name') return byName(request.text ?? '', 0);

  const brand: Brand | undefined = intent.kind === 'brand' ? intent.brand : undefined;
  const matchType: MatchType = intent.kind;
  const category: CategoryKey = intent.category;
  const label = brand?.name ?? classifier.labelOf(intent.category);
  const ofBrand = (places: PlaceResult[]) =>
    brand === undefined ? places : places.filter((place) => classifier.isBrand(place.name, brand));
  let calls = 0;

  let centre = request.hasArea ? near : undefined;
  let searchedAround: 'near' | 'placeHint' = 'near';
  if (intent.placeHint !== undefined) {
    await must(charge);
    calls += 1;
    const [place] = await provider.search({ query: intent.placeHint, ...bias, language, limit: 1 });
    // Not a place the geocoder knows: the words after the brand were part of a name after all.
    if (place === undefined) return byName(request.text ?? label, calls);
    centre = { latitude: coarsen(place.latitude), longitude: coarsen(place.longitude) };
    searchedAround = 'placeHint';
  }

  if (centre === undefined) {
    await must(charge);
    const found = await provider.search({
      query: label,
      ...bias,
      language,
      limit,
      placesOnly: true,
    });
    return { results: tag(ofBrand(found), 'name'), matchType: 'name', providerCalls: calls + 1 };
  }

  const around = { nearLatitude: centre.latitude, nearLongitude: centre.longitude };
  const candidates = brand === undefined ? limit : BRAND_CANDIDATES;
  async function within(radiusMeters: number): Promise<PlaceResult[]> {
    calls += 1;
    const found =
      provider.searchCategory !== undefined
        ? await provider.searchCategory({
            category,
            ...around,
            withinMeters: radiusMeters,
            language,
            limit: candidates,
          })
        : await provider.search({
            query: label,
            ...around,
            withinMeters: radiusMeters,
            placesOnly: true,
            language,
            limit: Math.min(candidates, limit),
          });
    const seen = new Set<string>();
    return (
      ofBrand(found)
        .map((place) => withDistance(place, around))
        // A provider may ignore the circle, or only be able to ask for the box around it.
        .filter((place) => (place.distanceMeters ?? 0) <= radiusMeters)
        .filter((place) => {
          const keys = keysOf(place);
          if (keys.some((key) => seen.has(key))) return false;
          for (const key of keys) seen.add(key);
          return true;
        })
        .sort((a, b) => (a.distanceMeters ?? 0) - (b.distanceMeters ?? 0))
        .slice(0, limit)
    );
  }
  const done = (places: PlaceResult[], radiusMeters: number): IntentOutcome => ({
    results: tag(places, matchType),
    matchType,
    searchedRadiusKm: radiusMeters / 1000,
    searchedAround,
    providerCalls: calls,
  });

  const firstRadius = options.categoryRadiusMeters;
  const wideRadius = CATEGORY_SEARCH_DEFAULTS.wideRadiusKm * 1000;
  await must(charge);
  const first = await within(firstRadius);
  if (
    first.length >= Math.min(options.localFirst.minLocalResults, limit) ||
    firstRadius >= wideRadius
  ) {
    return done(first, firstRadius);
  }
  try {
    await charge();
  } catch (err) {
    if (!(err instanceof WidePassRefused)) throw err;
    if (first.length === 0) throw err.reason;
    return done(first, firstRadius);
  }
  try {
    return done(await within(wideRadius), wideRadius);
  } catch (err) {
    if (!(err instanceof GeocoderError) || first.length === 0) throw err;
    return done(first, firstRadius);
  }
}
