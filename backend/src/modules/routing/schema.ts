// SPDX-License-Identifier: AGPL-3.0-only
import { z } from '@hono/zod-openapi';
import { distanceMeters } from '../../regions/routing-extent.js';

/** Origin and destination closer than this are one place: there is nothing to route. */
export const MIN_ROUTE_DISTANCE_METERS = 20;
export const MAX_ROUTES = 3;

export const RoutePointSchema = z
  .object({
    latitude: z
      .number()
      .min(-90)
      .max(90)
      .openapi({ description: 'WGS84 decimal degrees.', examples: [10.5] }),
    longitude: z
      .number()
      .min(-180)
      .max(180)
      .openapi({ description: 'WGS84 decimal degrees.', examples: [20.5] }),
  })
  .openapi('RoutePoint', { description: 'A position on the map.' });

/**
 * Body of POST /v1/routes. A BODY, never query parameters (ADR 0019): an origin and a
 * destination say where a person is and means to go.
 */
export const RouteRequestSchema = z
  .object({
    origin: RoutePointSchema,
    destination: RoutePointSchema,
    mode: z.enum(['walking', 'driving']).openapi({
      description: 'How the person travels. Driving means a car on roads open to cars.',
      examples: ['walking'],
    }),
    departAt: z.iso
      .datetime({ offset: true })
      .optional()
      .openapi({
        description:
          'When the trip starts, RFC 3339 with an offset. Validated and otherwise ignored for ' +
          'now: routes do not depend on the time of day yet.',
        examples: ['2026-01-01T18:30:00+05:30'],
      }),
  })
  .superRefine((value, ctx) => {
    if (distanceMeters(value.origin, value.destination) < MIN_ROUTE_DISTANCE_METERS) {
      ctx.addIssue({ code: 'custom', path: ['destination'], message: 'too close to origin' });
    }
  })
  .openapi('RouteRequest', {
    description:
      'A request for routes between two points. Sent as a request body so that no position ' +
      `ever appears in a URL. The two points must be at least ${String(MIN_ROUTE_DISTANCE_METERS)} m apart.`,
  });

export const RouteSchema = z
  .object({
    id: z.string().openapi({
      description:
        'Opaque identifier of this route within this response, for selecting it in the app. ' +
        'It means nothing to the server afterwards: routes are not stored.',
      examples: ['5d2f0c1e-3b7a-4c8d-9e1f-2a6b4c8d0e1f'],
    }),
    distanceMeters: z
      .number()
      .int()
      .min(0)
      .openapi({ examples: [2100] }),
    durationSeconds: z
      .number()
      .int()
      .min(0)
      .openapi({
        description: 'Estimated travel time without traffic. An estimate, not a promise.',
        examples: [1560],
      }),
    geometry: z
      .object({
        encoding: z.enum(['polyline6']).openapi({
          description:
            'Encoded polyline with six decimals: latitude then longitude, as differences.',
        }),
        value: z.string().openapi({ examples: ['_izlhA~rlgdF_{geC~ywl@'] }),
      })
      .openapi({ description: 'The whole line of the route.' }),
    bbox: z
      .array(z.number())
      .length(4)
      .openapi({
        description:
          'Box around the route: [minLongitude, minLatitude, maxLongitude, maxLatitude].',
        examples: [[20.5, 10.5, 20.6, 10.6]],
      }),
  })
  .openapi('Route', {
    description: 'One way to travel between the two points. Says nothing about safety.',
  });

export const RoutesSchema = z
  .object({
    routes: z.array(RouteSchema).min(1).max(MAX_ROUTES).openapi({
      description: 'Fastest first, then up to two alternatives.',
    }),
    attribution: z.string().openapi({
      description: 'Credit line the app must show with the routes (required by the map data).',
      examples: ['© OpenStreetMap contributors'],
    }),
  })
  .openapi('Routes', { description: 'Routes between two points.' });
