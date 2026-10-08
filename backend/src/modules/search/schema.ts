// SPDX-License-Identifier: AGPL-3.0-only
import { z } from '@hono/zod-openapi';
import { CATEGORY_KEYS, isCategoryKey } from './intents.js';
import { normalizeQuery, QUERY_MAX_CODE_POINTS, QUERY_MIN_CODE_POINTS } from './normalize.js';

export const SEARCH_DEFAULT_LIMIT = 6;
export const SEARCH_MAX_LIMIT = 10;
/** Half the Earth's circumference, rounded up: no two places are further apart. */
const MAX_DISTANCE_METERS = 20_100_000;

/**
 * Body of POST /v1/search.
 *
 * A BODY, not query parameters, on purpose (ADR 0019): the platform's request log records every
 * URL with its query string, and what a person searches for must not be stored there.
 *
 * `q` is checked through `normalizeQuery`; the handler normalises again to get the value, so the
 * schema stays a plain string in the contract.
 */
export const SearchRequestSchema = z
  .object({
    q: z
      .string()
      // A generous cap on the raw text, in UTF-16 units; the real rule is in the refinement.
      .max(QUERY_MAX_CODE_POINTS * 4)
      .refine((value) => normalizeQuery(value) !== undefined, { message: 'invalid query' })
      .optional()
      .openapi({
        description:
          'What the user typed. Required unless `category` is sent. A word for a kind of ' +
          'place, or a well-known brand, is searched as that kind or brand (see the ' +
          `operation). After trimming, collapsing whitespace and Unicode NFC ` +
          `normalisation it must be ${String(QUERY_MIN_CODE_POINTS)} to ` +
          `${String(QUERY_MAX_CODE_POINTS)} Unicode code points with no control characters. ` +
          'Any script is accepted.',
        examples: ['railway station'],
      }),
    nearLatitude: z
      .number()
      .min(-90)
      .max(90)
      .optional()
      .openapi({
        description:
          'Latitude of the area to prefer, WGS84 decimal degrees; send it together with ' +
          '`nearLongitude` or not at all. Places near it come first; places further away ' +
          'follow when too few are near. The server rounds it to two decimals (about 1 km) ' +
          'before using it; send a coarse point (the map centre, or a position already rounded ' +
          'to two decimals), never a precise position.',
        examples: [10.5],
      }),
    nearLongitude: z
      .number()
      .min(-180)
      .max(180)
      .optional()
      .openapi({
        description: 'Longitude of the area to prefer; see `nearLatitude`.',
        examples: [20.5],
      }),
    language: z
      .enum(['en', 'bn'])
      .default('en')
      .openapi({ description: 'Preferred language of the result names.', examples: ['en'] }),
    limit: z
      .number()
      .int()
      .min(1)
      .max(SEARCH_MAX_LIMIT)
      .default(SEARCH_DEFAULT_LIMIT)
      .openapi({ description: 'Maximum number of results.', examples: [SEARCH_DEFAULT_LIMIT] }),
    // Declared LAST on purpose: generated clients build this object positionally, and a new
    // property in the middle shifts every argument after it (android-ci, P011f1).
    category: z
      .string()
      .regex(/^[a-z][a-z_]{1,39}$/)
      .optional()
      .openapi({
        description:
          'A kind of place to look for near the point, for a quick-search button. When it is ' +
          'sent, `q` may be left out and is ignored. Open set: the values this version knows ' +
          `are ${CATEGORY_KEYS.join(', ')}; an unknown value is a 400. Send the point with it: ` +
          'without `nearLatitude`/`nearLongitude` there is no circle to search, and the ' +
          'answer is a plain name search for the word.',
        examples: ['pharmacy'],
      }),
  })
  .superRefine((value, ctx) => {
    if (value.category !== undefined && !isCategoryKey(value.category)) {
      ctx.addIssue({ code: 'custom', path: ['category'], message: 'unknown category' });
    }
    if (value.q === undefined && value.category === undefined) {
      ctx.addIssue({ code: 'custom', path: ['q'], message: 'q or category is required' });
    }
    if ((value.nearLatitude === undefined) !== (value.nearLongitude === undefined)) {
      const missing = value.nearLatitude === undefined ? 'nearLatitude' : 'nearLongitude';
      ctx.addIssue({ code: 'custom', path: [missing], message: 'both or neither' });
    }
  })
  .openapi('SearchRequest', {
    description:
      'A place search: `q`, `category` or both. Sent as a request body so that the text and ' +
      'the area never appear in a URL.',
  });

export const PlaceSchema = z
  .object({
    id: z.string().openapi({
      description: 'Opaque identifier from the geocoding provider. Do not parse or store it.',
      examples: ['a1b2c3'],
    }),
    name: z
      .string()
      .openapi({ description: 'Short name of the place.', examples: ['Main Station'] }),
    label: z.string().openapi({
      description: 'Longer text that places the name (area, district, state). May be empty.',
      examples: ['Station Road, Example District'],
    }),
    latitude: z
      .number()
      .min(-90)
      .max(90)
      .openapi({ examples: [10.5] }),
    longitude: z
      .number()
      .min(-180)
      .max(180)
      .openapi({ examples: [20.5] }),
    kind: z.string().openapi({
      description: 'Kind of place. Open set: clients must tolerate unknown values.',
      examples: ['amenity'],
    }),
    distanceMeters: z
      .number()
      .int()
      .min(0)
      .max(MAX_DISTANCE_METERS)
      .optional()
      .openapi({
        description:
          'Straight-line distance in metres from the rounded `nearLatitude`/`nearLongitude` of ' +
          'the request to this place, rounded to 100 m. Present only when the request carried ' +
          'that point. It is measured from the rounded point, so it is approximate: about 1 km ' +
          'either way.',
        examples: [2300],
      }),
    matchType: z
      .string()
      .optional()
      .openapi({
        description:
          'Why the place is in the list: `name` (its name matched the text), `category` (it ' +
          'is of the kind asked for) or `brand` (it is of that kind and carries the name of ' +
          'the brand). Sent with every result since 0.8.0. Open set: clients must tolerate ' +
          'unknown values. It says nothing about the quality or safety of a place.',
        examples: ['category'],
      }),
  })
  .openapi('Place', { description: 'One search result.' });

export const SearchResultsSchema = z
  .object({
    results: z
      .array(PlaceSchema)
      .max(SEARCH_MAX_LIMIT)
      .openapi({
        description:
          'A name search: places near the requested point first, then places from further ' +
          'away; within each group, best match first. A search by kind or brand: nearest ' +
          'first, all inside the circle. Empty when nothing was found.',
      }),
    attribution: z
      .string()
      .nullable()
      .openapi({
        description:
          'Credit line the app must show next to the results when it is not null (required by ' +
          'the terms of the geocoding provider and of the map data).',
        examples: ['© OpenStreetMap contributors'],
      }),
    // After the older properties, for the same reason as `category` above.
    searchedRadiusKm: z
      .number()
      .min(1)
      .max(25)
      .optional()
      .openapi({
        description:
          'Present for a search by kind or brand around a point: the radius, in kilometres, ' +
          'of the circle that was searched. The first circle is widened once when it holds ' +
          'too few places, never beyond 25 km. Every result lies inside it, and nothing ' +
          'outside it was looked at.',
        examples: [10],
      }),
    searchedAround: z
      .string()
      .optional()
      .openapi({
        description:
          'Present together with `searchedRadiusKm`: what the centre of the circle was. ' +
          '`near`: the point of the request. `placeHint`: a place named in `q` ("pharmacy ' +
          'near Exampletown"); `distanceMeters` is then measured from that place, not from the ' +
          'point of the request. Open set.',
        examples: ['near'],
      }),
  })
  .openapi('SearchResults', { description: 'Places that match a search.' });
