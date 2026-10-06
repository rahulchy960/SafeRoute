// SPDX-License-Identifier: AGPL-3.0-only

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
}

export interface GeocoderQuery {
  /** Already normalised (src/modules/search/normalize.ts). */
  query: string;
  /** Proximity bias, already coarsened. A bias, never a filter. */
  nearLatitude: number;
  nearLongitude: number;
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
