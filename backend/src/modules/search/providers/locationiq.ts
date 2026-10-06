// SPDX-License-Identifier: AGPL-3.0-only
import { z } from 'zod';
import { LAUNCH_COUNTRY_CODE } from '../../../regions/defaults.js';
import { GeocoderError, type GeocoderProvider, type PlaceResult } from '../types.js';
import { clean, providerGet, type ProviderHttpOptions } from './http.js';

const ENDPOINT = 'https://api.locationiq.com/v1/autocomplete';

/** Half-width of the preferred box around the bias point, in degrees (about 55 km). */
const VIEWBOX_HALF_DEGREES = 0.5;

/** LocationIQ sends coordinates as strings. */
const coordinate = (limit: number) =>
  z
    .string()
    .regex(/^-?\d{1,3}(\.\d+)?$/)
    .transform(Number)
    .refine((value) => Math.abs(value) <= limit);

const ResponseSchema = z
  .array(
    z.object({
      place_id: z.string().optional(),
      lat: coordinate(90),
      lon: coordinate(180),
      display_name: z.string().optional(),
      display_place: z.string().optional(),
      display_address: z.string().optional(),
      type: z.string().optional(),
    }),
  )
  .max(50);

/**
 * LocationIQ Autocomplete (ADR 0018). The key is the `key` query parameter. One request is one
 * request credit. "Nothing found" is a 404 here, not an empty list.
 *
 * The API has no proximity point, only a preferred box (`viewbox`, not `bounded`): a box around
 * the bias point is the nearest equivalent. Its documented result languages do not include
 * Bengali, so `bn` asks for native-language names.
 */
export function createLocationIqGeocoder(
  apiKey: string,
  http: ProviderHttpOptions,
): GeocoderProvider {
  return {
    name: 'locationiq',
    // Pricing page: the free plan asks for this credit; the data is OpenStreetMap's.
    attribution: 'Search by LocationIQ.com · © OpenStreetMap contributors',
    async search({ query, nearLatitude, nearLongitude, language, limit }) {
      const url = new URL(ENDPOINT);
      url.searchParams.set('q', query);
      url.searchParams.set('limit', String(limit));
      url.searchParams.set('countrycodes', LAUNCH_COUNTRY_CODE);
      url.searchParams.set('dedupe', '1');
      url.searchParams.set('accept-language', language === 'bn' ? 'native' : 'en');
      const box = [
        nearLongitude - VIEWBOX_HALF_DEGREES,
        nearLatitude - VIEWBOX_HALF_DEGREES,
        nearLongitude + VIEWBOX_HALF_DEGREES,
        nearLatitude + VIEWBOX_HALF_DEGREES,
      ];
      url.searchParams.set('viewbox', box.map((value) => value.toFixed(2)).join(','));
      url.searchParams.set('key', apiKey);

      const { status, json } = await providerGet(url, http);
      if (status === 404) return [];
      if (status !== 200) throw new GeocoderError('upstream');
      const parsed = ResponseSchema.safeParse(json);
      if (!parsed.success) throw new GeocoderError('malformed');

      return parsed.data.slice(0, limit).map((item, index): PlaceResult => {
        const label = clean(item.display_address ?? item.display_name);
        const name = clean(item.display_place ?? item.display_name, 200);
        return {
          id: clean(item.place_id, 200) || `locationiq-${String(index)}`,
          name: name || label,
          label,
          latitude: item.lat,
          longitude: item.lon,
          kind: clean(item.type, 60) || 'unknown',
        };
      });
    },
  };
}
