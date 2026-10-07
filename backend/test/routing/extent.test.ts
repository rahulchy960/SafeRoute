// SPDX-License-Identifier: AGPL-3.0-only
import { existsSync, readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { polyline6Bounds } from '../../src/modules/routing/polyline.js';
import {
  distanceMeters,
  EXTENT_EDGE_TOLERANCE_METERS,
  isInsideRoutingExtent,
  ROUTING_EXTENT_VERSION,
} from '../../src/regions/routing-extent.js';
import { encodePolyline6, GEOMETRY, MONUMENT, OUTSIDE, STATION_A, STATION_NORTH } from './fakes.js';

const extent = JSON.parse(readFileSync('src/regions/routing-extent.json', 'utf8')) as {
  version: string;
  source: string;
  licence: string;
  attribution: string;
  bbox: [number, number, number, number];
  geometry: { coordinates: [number, number][][][] };
};

describe('routing extent (ADR 0020)', () => {
  it('names its source, version, licence and credit', () => {
    expect(ROUTING_EXTENT_VERSION).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(extent.source).toContain('OpenStreetMap');
    expect(extent.licence).toBe('ODbL-1.0');
    expect(extent.attribution).toContain('OpenStreetMap');
  });

  it('is the same file as the one the graphs were cut with', () => {
    const original = '../infra/osrm/extents/state-extent.json';
    // The container image holds backend/ only; in a checkout both files exist.
    if (!existsSync(original)) return;
    expect(readFileSync('src/regions/routing-extent.json', 'utf8')).toBe(
      readFileSync(original, 'utf8'),
    );
  });

  it('contains public places in the south and the north of the covered area', () => {
    for (const place of [STATION_A, MONUMENT, STATION_NORTH]) {
      expect(isInsideRoutingExtent(place)).toBe(true);
    }
  });

  it('excludes places far away, in the box but outside the outline, and at the poles', () => {
    expect(isInsideRoutingExtent(OUTSIDE)).toBe(false);
    // Inside the bounding box, hundreds of kilometres from the outline (its north-west corner).
    expect(
      isInsideRoutingExtent({ latitude: extent.bbox[3] - 0.5, longitude: extent.bbox[0] + 0.5 }),
    ).toBe(false);
    expect(isInsideRoutingExtent({ latitude: 90, longitude: 0 })).toBe(false);
    expect(isInsideRoutingExtent({ latitude: -90, longitude: 180 })).toBe(false);
  });

  it('accepts a point just outside the outline and refuses one clearly outside (edge tolerance)', () => {
    // The outline's westernmost point: going further west leaves the area.
    const ring = extent.geometry.coordinates[0]?.[0] ?? [];
    const west = ring.reduce((a, b) => (b[0] < a[0] ? b : a), ring[0] ?? [0, 0]);
    const metersPerDegLon = 111_320 * Math.cos((west[1] * Math.PI) / 180);
    const at = (meters: number) => ({
      latitude: west[1],
      longitude: west[0] - meters / metersPerDegLon,
    });
    expect(isInsideRoutingExtent(at(EXTENT_EDGE_TOLERANCE_METERS - 100))).toBe(true);
    expect(isInsideRoutingExtent(at(EXTENT_EDGE_TOLERANCE_METERS + 300))).toBe(false);
  });

  it('measures distances in metres', () => {
    expect(distanceMeters(STATION_A, STATION_A)).toBe(0);
    // One degree of latitude is about 111.2 km.
    expect(
      distanceMeters({ latitude: 10, longitude: 20 }, { latitude: 11, longitude: 20 }),
    ).toBeCloseTo(111_195, -2);
    expect(distanceMeters(STATION_A, MONUMENT)).toBeGreaterThan(4000);
    expect(distanceMeters(STATION_A, MONUMENT)).toBeLessThan(4400);
  });
});

describe('polyline6 bounds', () => {
  it('reads the well-known example line', () => {
    // The textbook polyline (38.5,-120.2) (40.7,-120.95) (43.252,-126.453), with six decimals.
    expect(polyline6Bounds('_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI')).toEqual([
      -126.453, 38.5, -120.2, 43.252,
    ]);
  });

  it('agrees with an encoder for the fixture and for negative and tiny steps', () => {
    expect(polyline6Bounds(GEOMETRY)).toEqual([88.34264, 22.54508, 88.351, 22.58287]);
    const line = encodePolyline6([
      [-0.000001, 0.000001],
      [-33.123456, -70.654321],
      [0, 179.999999],
    ]);
    expect(polyline6Bounds(line)).toEqual([-70.654321, -33.123456, 179.999999, 0]);
    expect(polyline6Bounds(encodePolyline6([[10.5, 20.5]]))).toEqual([20.5, 10.5, 20.5, 10.5]);
  });

  it.each([
    ['empty', ''],
    ['cut off inside a number', GEOMETRY.slice(0, -1) + '~'],
    ['a latitude without a longitude', encodePolyline6([[10.5, 20.5]]).slice(0, 5)],
    ['characters outside the alphabet', 'hello world !!'],
    ['a latitude beyond the pole', encodePolyline6([[95, 20]])],
  ])('rejects %s', (_name, text) => {
    expect(polyline6Bounds(text)).toBeUndefined();
  });
});
