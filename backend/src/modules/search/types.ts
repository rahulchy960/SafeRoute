// SPDX-License-Identifier: AGPL-3.0-only
import type { CategoryKey } from './intents.js';

/** Why a result is in the list: its name matched, it is of the kind asked for, or of the brand. */
export type MatchType = 'name' | 'category' | 'brand';

/** One place, in a shape that names no provider (ADR 0018). */
export interface PlaceResult {
  /** Opaque: only meaningful to the provider that returned it. Never parse it. */
  id: string;
  /** Short name, e.g. the station or the locality. */
  name: string;
  /** Longer text that places the name (area, district, state). */
  label: string;
  latitude: number;
  longitude: number;
  /** What kind of place it is, in the provider's words. Open string. */
  kind: string;
  /**
   * Metres from the coarse `near` point of the search, rounded to 100 m. Set by
   * `searchLocalFirst`, never by an adapter; absent when the client sent no area.
   */
  distanceMeters?: number;
  /** Set by `searchByIntent`, never by an adapter. */
  matchType?: MatchType;
}

export interface GeocoderQuery {
  /** Already normalised (src/modules/search/normalize.ts). */
  query: string;
  /** Proximity bias, already coarsened. On its own a bias, never a filter. */
  nearLatitude: number;
  nearLongitude: number;
  /**
   * When set: only places within this many metres of the `near` point (a FILTER, on top of the
   * bias). An adapter whose provider has no circle asks for the box around it.
   */
  withinMeters?: number;
  /**
   * When true: named places only, no towns, districts or other administrative areas. Used for
   * a category word, where "Bankura" is not an answer to "bank". An adapter whose provider
   * cannot restrict the kind of result ignores it.
   */
  placesOnly?: boolean;
  language: 'en' | 'bn';
  limit: number;
}

/** A search for a KIND of place inside a circle (ADR 0018, "Category and brand search"). */
export interface CategoryQuery {
  category: CategoryKey;
  /** Centre of the circle, already coarsened. */
  nearLatitude: number;
  nearLongitude: number;
  withinMeters: number;
  language: 'en' | 'bn';
  limit: number;
}

/**
 * The boundary to a geocoding provider (Plan v7 §4). Everything outside
 * src/modules/search/providers talks to this interface, so swapping the provider touches one
 * file and the configuration.
 */
export interface GeocoderProvider {
  /** Adapter name, the value of GEOCODING_PROVIDER. Not a secret. */
  readonly name: string;
  /** Credit line the provider's terms require next to results, or null. */
  readonly attribution: string | null;
  search(query: GeocoderQuery): Promise<PlaceResult[]>;
  /**
   * Places of one kind inside a circle, whatever they are called. Optional: with a provider
   * that has no such search, the category's word is searched by name inside the circle.
   */
  searchCategory?(query: CategoryQuery): Promise<PlaceResult[]>;
}

export type GeocoderFailure =
  /** The provider refused our key (wrong, inactive, restricted or over plan). Our problem. */
  'auth' | 'rate_limited' | 'timeout' | 'network' | 'malformed' | 'oversized' | 'upstream';

/**
 * The only error an adapter throws. It carries a kind and nothing else: no URL (the key is in
 * it), no response body, no query. `message` is the kind, so it is safe to log.
 */
export class GeocoderError extends Error {
  readonly kind: GeocoderFailure;
  readonly retryAfterSeconds: number | undefined;

  constructor(kind: GeocoderFailure, retryAfterSeconds?: number) {
    super(`geocoder ${kind}`);
    this.name = 'GeocoderError';
    this.kind = kind;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}
