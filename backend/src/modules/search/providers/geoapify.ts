// SPDX-License-Identifier: AGPL-3.0-only
import { z } from 'zod';
import { LAUNCH_COUNTRY_CODE } from '../../../regions/defaults.js';
import type { CategoryKey } from '../intents.js';
import { GeocoderError, type GeocoderProvider, type PlaceResult } from '../types.js';
import { clean, providerGet, type ProviderHttpOptions } from './http.js';

const ENDPOINT = 'https://api.geoapify.com/v1/geocode/autocomplete';
const PLACES_ENDPOINT = 'https://api.geoapify.com/v2/places';

/**
 * Our category names in Geoapify's (Places API, "Supported categories", read on 2026-10-08).
 * Several are joined with a comma: a place of any of them matches. NOT verified against the
 * live service; a key it refuses answers 400, which reaches the user as "unavailable".
 */
export const GEOAPIFY_CATEGORIES: Record<CategoryKey, string> = {
  bank: 'service.financial.bank',
  atm: 'service.financial.atm',
  pharmacy: 'healthcare.pharmacy',
  hospital: 'healthcare.hospital',
  clinic: 'healthcare.clinic_or_praxis',
  fuel: 'service.vehicle.fuel',
  restaurant: 'catering.restaurant',
  grocery: 'commercial.supermarket,commercial.convenience',
  bus: 'public_transport.bus',
  train: 'public_transport.train',
  public_transport: 'public_transport.bus,public_transport.train',
  police: 'service.police',
  post_office: 'service.post.office',
  school: 'education.school',
  college: 'education.college,education.university',
};

/** Up to this many places, a Places request costs one credit (pricing details, 2026-10-08). */
export const GEOAPIFY_PLACES_ONE_CREDIT_LIMIT = 20;

/** GeoJSON; only the properties we use. */
const PlacesResponseSchema = z.object({
  features: z
    .array(
      z.object({
        properties: z.object({
          place_id: z.string().optional(),
          name: z.string().optional(),
          formatted: z.string().optional(),
          address_line1: z.string().optional(),
          address_line2: z.string().optional(),
          lat: z.number().min(-90).max(90),
          lon: z.number().min(-180).max(180),
        }),
      }),
    )
    .max(GEOAPIFY_PLACES_ONE_CREDIT_LIMIT),
});

/** Only the fields we use; anything else the provider sends is ignored. */
const ResponseSchema = z.object({
  results: z
    .array(
      z.object({
        place_id: z.string().optional(),
        name: z.string().optional(),
        formatted: z.string().optional(),
        address_line1: z.string().optional(),
        address_line2: z.string().optional(),
        lat: z.number().min(-90).max(90),
        lon: z.number().min(-180).max(180),
        result_type: z.string().optional(),
      }),
    )
    .max(50),
});

/**
 * Geoapify Address Autocomplete (ADR 0018). The key is the `apiKey` query parameter. One request
 * is one credit. Bias by proximity; filter by country and, when the query has `withinMeters`,
 * by a circle around the bias point. Filters are joined with `|` and all must hold; the circle
 * is `circle:lon,lat,radiusMeters` (Geoapify docs, "Location filters"). `type=amenity` keeps
 * administrative areas out (`placesOnly`).
 *
 * `searchCategory` is the Places API: places of given categories inside a circle, nearest
 * first. One request is one credit while the limit is at most 20, so the limit is capped there.
 */
export function createGeoapifyGeocoder(
  apiKey: string,
  http: ProviderHttpOptions,
): GeocoderProvider {
  return {
    name: 'geoapify',
    // Terms: OpenStreetMap credit always; Geoapify credit on the free plan.
    attribution: 'Powered by Geoapify · © OpenStreetMap contributors',
    async search(request) {
      const { query, nearLatitude, nearLongitude, withinMeters, language, limit } = request;
      const point = `${String(nearLongitude)},${String(nearLatitude)}`;
      const country = `countrycode:${LAUNCH_COUNTRY_CODE}`;
      const url = new URL(ENDPOINT);
      url.searchParams.set('text', query);
      url.searchParams.set('format', 'json');
      url.searchParams.set('lang', language);
      url.searchParams.set('limit', String(limit));
      url.searchParams.set(
        'filter',
        withinMeters === undefined
          ? country
          : `circle:${point},${String(Math.round(withinMeters))}|${country}`,
      );
      url.searchParams.set('bias', `proximity:${point}`);
      if (request.placesOnly === true) url.searchParams.set('type', 'amenity');
      url.searchParams.set('apiKey', apiKey);

      const { status, json } = await providerGet(url, http);
      if (status !== 200) throw new GeocoderError('upstream');
      const parsed = ResponseSchema.safeParse(json);
      if (!parsed.success) throw new GeocoderError('malformed');

      return parsed.data.results.slice(0, limit).map((item, index): PlaceResult => {
        const name = clean(item.name ?? item.address_line1 ?? item.formatted, 200);
        const label = clean(item.address_line2 ?? item.formatted);
        return {
          id: clean(item.place_id, 200) || `geoapify-${String(index)}`,
          name: name || label,
          label,
          latitude: item.lat,
          longitude: item.lon,
          kind: clean(item.result_type, 60) || 'unknown',
        };
      });
    },

    async searchCategory({ category, nearLatitude, nearLongitude, withinMeters, language, limit }) {
      const point = `${String(nearLongitude)},${String(nearLatitude)}`;
      const capped = Math.min(limit, GEOAPIFY_PLACES_ONE_CREDIT_LIMIT);
      const url = new URL(PLACES_ENDPOINT);
      url.searchParams.set('categories', GEOAPIFY_CATEGORIES[category]);
      url.searchParams.set('filter', `circle:${point},${String(Math.round(withinMeters))}`);
      url.searchParams.set('bias', `proximity:${point}`);
      url.searchParams.set('lang', language);
      url.searchParams.set('limit', String(capped));
      url.searchParams.set('apiKey', apiKey);

      const { status, json } = await providerGet(url, http);
      if (status !== 200) throw new GeocoderError('upstream');
      const parsed = PlacesResponseSchema.safeParse(json);
      if (!parsed.success) throw new GeocoderError('malformed');

      return parsed.data.features.slice(0, capped).map(({ properties: item }, index) => {
        const name = clean(item.name ?? item.address_line1 ?? item.formatted, 200);
        const label = clean(item.address_line2 ?? item.formatted);
        return {
          id: clean(item.place_id, 200) || `geoapify-place-${String(index)}`,
          name: name || label,
          label,
          latitude: item.lat,
          longitude: item.lon,
          kind: category,
        };
      });
    },
  };
}
