// SPDX-License-Identifier: AGPL-3.0-only
import extent from './routing-extent.json' with { type: 'json' };

/**
 * Where routes can be computed: the area the OSRM graphs were built for (ADR 0020).
 *
 * `routing-extent.json` is a copy of infra/osrm/extents/state-extent.json (a test compares them):
 * the state outline simplified to about 5 000 points, derived from OpenStreetMap
 * (© OpenStreetMap contributors, ODbL 1.0). It is data, so no region name appears in an
 * identifier (ADR 0005); P017 moves it into the regions configuration (ADR 0013).
 */
interface Point {
  latitude: number;
  longitude: number;
}

type Ring = [number, number][];

const RINGS = (extent.geometry.coordinates as unknown as Ring[][]).map(
  (polygon) => polygon[0] ?? [],
);
const [MIN_LON, MIN_LAT, MAX_LON, MAX_LAT] = extent.bbox as [number, number, number, number];

/** Version of the outline: the date of the extract it was cut from. */
export const ROUTING_EXTENT_VERSION = extent.version;

/**
 * The outline is simplified with a tolerance of about 220 m, so it can lie that far inside the
 * real line. A point up to this far outside the outline still counts as inside: a real place at
 * the edge is then never refused, and the routing engine has the last word.
 */
export const EXTENT_EDGE_TOLERANCE_METERS = 500;

const EARTH_RADIUS_METERS = 6_371_008.8;
const RAD = Math.PI / 180;

/** Great-circle distance in metres. */
export function distanceMeters(a: Point, b: Point): number {
  const h =
    Math.sin(((b.latitude - a.latitude) * RAD) / 2) ** 2 +
    Math.cos(a.latitude * RAD) *
      Math.cos(b.latitude * RAD) *
      Math.sin(((b.longitude - a.longitude) * RAD) / 2) ** 2;
  return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(h));
}

function inRing(x: number, y: number, ring: Ring): boolean {
  let inside = false;
  // Every ring is closed (its last point repeats the first), so consecutive pairs are its edges.
  let previous = ring[0];
  for (const current of ring) {
    if (previous === undefined) break;
    const [xi, yi] = current;
    const [xj, yj] = previous;
    if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside;
    previous = current;
  }
  return inside;
}

/** Whether a point is within `meters` of a ring's line (flat approximation, fine at this size). */
function nearRing(x: number, y: number, ring: Ring, meters: number): boolean {
  const metersPerDegLat = RAD * EARTH_RADIUS_METERS;
  const metersPerDegLon = metersPerDegLat * Math.cos(y * RAD);
  let previous = ring[0];
  for (const current of ring) {
    if (previous === undefined) break;
    const [x1, y1] = previous;
    const [x2, y2] = current;
    previous = current;
    const ax = (x1 - x) * metersPerDegLon;
    const ay = (y1 - y) * metersPerDegLat;
    const bx = (x2 - x) * metersPerDegLon;
    const by = (y2 - y) * metersPerDegLat;
    const dx = bx - ax;
    const dy = by - ay;
    const length2 = dx * dx + dy * dy;
    const t = length2 === 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / length2));
    if (Math.hypot(ax + t * dx, ay + t * dy) <= meters) return true;
  }
  return false;
}

/** True when routes can be computed for this point. Says nothing about roads being there. */
export function isInsideRoutingExtent({ latitude, longitude }: Point): boolean {
  const margin = 0.01; // about 1 km: wider than the edge tolerance
  if (
    longitude < MIN_LON - margin ||
    longitude > MAX_LON + margin ||
    latitude < MIN_LAT - margin ||
    latitude > MAX_LAT + margin
  ) {
    return false;
  }
  return (
    RINGS.some((ring) => inRing(longitude, latitude, ring)) ||
    RINGS.some((ring) => nearRing(longitude, latitude, ring, EXTENT_EDGE_TOLERANCE_METERS))
  );
}
