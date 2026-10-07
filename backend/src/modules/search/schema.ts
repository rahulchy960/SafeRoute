// SPDX-License-Identifier: AGPL-3.0-only
import { z } from '@hono/zod-openapi';
import { normalizeQuery, QUERY_MAX_CODE_POINTS, QUERY_MIN_CODE_POINTS } from './normalize.js';

export const SEARCH_DEFAULT_LIMIT = 6;
export const SEARCH_MAX_LIMIT = 10;

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
      .openapi({
        description:
          `What the user typed. After trimming, collapsing whitespace and Unicode NFC ` +
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
          '`nearLongitude` or not at all. A bias, not a filter. The server rounds it to two ' +
          'decimals (about 1 km) before using it; send the map centre, not a precise position.',
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
  })
  .superRefine((value, ctx) => {
    if ((value.nearLatitude === undefined) !== (value.nearLongitude === undefined)) {
      const missing = value.nearLatitude === undefined ? 'nearLatitude' : 'nearLongitude';
      ctx.addIssue({ code: 'custom', path: [missing], message: 'both or neither' });
    }
  })
  .openapi('SearchRequest', {
    description:
      'A place search. Sent as a request body so that the text and the area never appear in a ' +
      'URL.',
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
  })
  .openapi('Place', { description: 'One search result.' });

export const SearchResultsSchema = z
  .object({
    results: z.array(PlaceSchema).max(SEARCH_MAX_LIMIT).openapi({
      description: 'Best match first. Empty when nothing was found.',
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
  })
  .openapi('SearchResults', { description: 'Places that match a search.' });
