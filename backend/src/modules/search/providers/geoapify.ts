// SPDX-License-Identifier: AGPL-3.0-only
import { z } from 'zod';
import { LAUNCH_COUNTRY_CODE } from '../../../regions/defaults.js';
import { GeocoderError, type GeocoderProvider, type PlaceResult } from '../types.js';
import { clean, providerGet, type ProviderHttpOptions } from './http.js';

const ENDPOINT = 'https://api.geoapify.com/v1/geocode/autocomplete';

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
 * is one credit. Bias by proximity, filter by country only.
 */
export function createGeoapifyGeocoder(
  apiKey: string,
  http: ProviderHttpOptions,
): GeocoderProvider {
  return {
    name: 'geoapify',
    // Terms: OpenStreetMap credit always; Geoapify credit on the free plan.
    attribution: 'Powered by Geoapify · © OpenStreetMap contributors',
    async search({ query, nearLatitude, nearLongitude, language, limit }) {
      const url = new URL(ENDPOINT);
      url.searchParams.set('text', query);
      url.searchParams.set('format', 'json');
      url.searchParams.set('lang', language);
      url.searchParams.set('limit', String(limit));
      url.searchParams.set('filter', `countrycode:${LAUNCH_COUNTRY_CODE}`);
      url.searchParams.set('bias', `proximity:${String(nearLongitude)},${String(nearLatitude)}`);
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
  };
}
