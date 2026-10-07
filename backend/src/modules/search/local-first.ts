// SPDX-License-Identifier: AGPL-3.0-only
import { distanceMeters } from '../../regions/routing-extent.js';
import {
  GeocoderError,
  type GeocoderFailure,
  type GeocoderProvider,
  type GeocoderQuery,
  type PlaceResult,
} from './types.js';

/** Defaults of SEARCH_NEARBY_RADIUS_KM and SEARCH_MIN_LOCAL_RESULTS (src/config.ts). */
export const LOCAL_SEARCH_DEFAULTS = { radiusKm: 50, minLocalResults: 3 } as const;

/** Distances are rounded to this, so a result says "about how far", not where the user is. */
export const DISTANCE_ROUNDING_METERS = 100;

export interface LocalFirstOptions {
  /** Radius of the nearby pass around the `near` point. */
  radiusMeters: number;
  /** With fewer nearby results than this, the wide pass runs too. */
  minLocalResults: number;
}

/**
 * What happened to the wide pass. `off`: the caller sent no area, so there was one plain call.
 * `refused`: the caller's `beforeWidePass` said no (a rate limit). A failure kind: the provider
 * failed on the wide pass and the nearby results were returned alone.
 */
export type WidePass = 'off' | 'not_needed' | 'ok' | 'refused' | GeocoderFailure;

export interface LocalFirstOutcome {
  results: PlaceResult[];
  /** How many of `results` came from the nearby pass. */
  localCount: number;
  providerCalls: 1 | 2;
  widePass: WidePass;
}

/** Thrown by `beforeWidePass` to skip the wide pass without failing the search. */
export class WidePassRefused extends Error {
  /** What the caller gets when there are no nearby results to fall back on. */
  readonly reason: Error;

  constructor(reason: Error) {
    super('wide pass refused');
    this.name = 'WidePassRefused';
    this.reason = reason;
  }
}

/** Rounded to 100 m, from the coarse `near` point. */
function withDistance(place: PlaceResult, query: GeocoderQuery): PlaceResult {
  const meters = distanceMeters(
    { latitude: query.nearLatitude, longitude: query.nearLongitude },
    place,
  );
  return {
    ...place,
    distanceMeters: Math.round(meters / DISTANCE_ROUNDING_METERS) * DISTANCE_ROUNDING_METERS,
  };
}

/** The same place can come back from both passes, with or without the same id. */
function keysOf(place: PlaceResult): string[] {
  const name = place.name.normalize('NFC').toLowerCase();
  return [
    `id:${place.id}`,
    `at:${name}|${place.latitude.toFixed(4)}|${place.longitude.toFixed(4)}`,
  ];
}

/**
 * Local-first search (ADR 0018, "Local ranking").
 *
 * Pass 1 asks the provider only for places within `radiusMeters` of the `near` point. When that
 * finds fewer than `minLocalResults`, pass 2 asks again without the area filter (bias only).
 * Nearby results come first, in the provider's order; then the wide results that are not already
 * there; cut to `query.limit`. Every result carries its distance from the `near` point.
 *
 * `beforeWidePass` runs before the second provider call: the endpoint charges the rate limits
 * there, the evaluation harness waits. If it throws {@link WidePassRefused}, or the provider
 * fails on pass 2, the nearby results are returned alone when there are any; with none, the
 * error is the caller's (a refusal rethrows its `reason`).
 *
 * This file knows no provider: the area filter is `withinMeters` on the query, and each adapter
 * turns it into its own parameter.
 */
export async function searchLocalFirst(
  provider: GeocoderProvider,
  query: GeocoderQuery,
  options: LocalFirstOptions,
  beforeWidePass: () => Promise<void> = () => Promise.resolve(),
): Promise<LocalFirstOutcome> {
  const seen = new Set<string>();
  const merged: PlaceResult[] = [];
  const add = (places: PlaceResult[]) => {
    for (const place of places) {
      const keys = keysOf(place);
      if (keys.some((key) => seen.has(key))) continue;
      for (const key of keys) seen.add(key);
      merged.push(place);
    }
  };

  const nearby = (await provider.search({ ...query, withinMeters: options.radiusMeters }))
    .map((place) => withDistance(place, query))
    // An adapter may only be able to ask for a box, and a provider may ignore a filter.
    .filter((place) => (place.distanceMeters ?? 0) <= options.radiusMeters);
  add(nearby);
  const localCount = Math.min(merged.length, query.limit);
  const done = (providerCalls: 1 | 2, widePass: WidePass): LocalFirstOutcome => ({
    results: merged.slice(0, query.limit),
    localCount,
    providerCalls,
    widePass,
  });

  if (merged.length >= Math.min(options.minLocalResults, query.limit)) {
    return done(1, 'not_needed');
  }

  try {
    await beforeWidePass();
  } catch (err) {
    if (!(err instanceof WidePassRefused)) throw err;
    if (merged.length === 0) throw err.reason;
    return done(1, 'refused');
  }
  try {
    add((await provider.search(query)).map((place) => withDistance(place, query)));
  } catch (err) {
    if (!(err instanceof GeocoderError) || merged.length === 0) throw err;
    return done(2, err.kind);
  }
  return done(2, 'ok');
}
