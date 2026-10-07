// SPDX-License-Identifier: AGPL-3.0-only
/**
 * Makes the small "routing extent" polygon that the API will use to answer "outside covered
 * area" without asking OSRM (P012a for P012b, ADR 0020).
 *
 *   node infra/osrm/scripts/make-extent.mjs --boundary <boundary.geojson> --extract-date 2026-10-06
 *
 * <boundary.geojson> is the full boundary that cut-extent.sh assembles from the extract (about
 * 96 000 points for West Bengal; the runbook has the two osmium commands). This script:
 *   - simplifies every ring with Douglas-Peucker at --tolerance degrees (default 0.002, about
 *     200 m), so the result can sit up to that far inside or outside the real line;
 *   - drops rings smaller than --min-size degrees across (default 0.01, about 1 km: tiny river
 *     and delta islands) and all holes;
 *   - rounds to 5 decimals and writes infra/osrm/extents/state-extent.json with its source.
 *
 * The output is derived from OpenStreetMap (ODbL 1.0) and says so in the file.
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

import { checkExtractDate, readConfig } from './build-image.mjs';

function distanceToSegment([px, py], [ax, ay], [bx, by]) {
  const dx = bx - ax;
  const dy = by - ay;
  const length2 = dx * dx + dy * dy;
  const t = length2 === 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / length2));
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
}

/** Douglas-Peucker without recursion (rings have tens of thousands of points). */
export function simplify(points, tolerance) {
  const keep = new Uint8Array(points.length);
  keep[0] = 1;
  keep[points.length - 1] = 1;
  const stack = [[0, points.length - 1]];
  while (stack.length > 0) {
    const [first, last] = stack.pop();
    let worst = 0;
    let index = -1;
    for (let i = first + 1; i < last; i++) {
      const d = distanceToSegment(points[i], points[first], points[last]);
      if (d > worst) {
        worst = d;
        index = i;
      }
    }
    if (index !== -1 && worst > tolerance) {
      keep[index] = 1;
      stack.push([first, index], [index, last]);
    }
  }
  return points.filter((_, i) => keep[i] === 1);
}

const here = dirname(fileURLToPath(import.meta.url));
const { values } = parseArgs({
  options: {
    boundary: { type: 'string' },
    'extract-date': { type: 'string' },
    tolerance: { type: 'string', default: '0.002' },
    'min-size': { type: 'string', default: '0.01' },
  },
});
if (values.boundary) {
  const config = readConfig();
  const tolerance = Number(values.tolerance);
  const minSize = Number(values['min-size']);
  const json = JSON.parse(readFileSync(values.boundary, 'utf8'));
  const geometry = json.type === 'FeatureCollection' ? json.features[0].geometry : (json.geometry ?? json);
  const polygons = geometry.type === 'Polygon' ? [geometry.coordinates] : geometry.coordinates;
  let pointsBefore = 0;
  const rings = [];
  for (const [outer] of polygons) {
    pointsBefore += outer.length;
    const lons = outer.map((p) => p[0]);
    const lats = outer.map((p) => p[1]);
    if (Math.hypot(Math.max(...lons) - Math.min(...lons), Math.max(...lats) - Math.min(...lats)) < minSize) continue;
    // Split the closed ring at its far point so both halves have distinct ends to simplify between.
    const half = Math.floor(outer.length / 2);
    const ring = [...simplify(outer.slice(0, half + 1), tolerance), ...simplify(outer.slice(half), tolerance).slice(1)];
    if (ring.length >= 4) rings.push(ring.map(([lon, lat]) => [Number(lon.toFixed(5)), Number(lat.toFixed(5))]));
  }
  rings.sort((a, b) => b.length - a.length);
  const all = rings.flat();
  const output = {
    description: 'Simplified outline of the routing extent, for a fast inside/outside answer. Not a legal or administrative boundary.',
    version: checkExtractDate(values['extract-date']),
    source: `${config.extents.state.description}; extract of ${values['extract-date']} from ${config.data.provider}`,
    licence: config.data.licence,
    attribution: config.data.attribution,
    simplification: `Douglas-Peucker, tolerance ${tolerance} degrees (about ${Math.round(tolerance * 111_000)} m); outer rings under ${minSize} degrees across and all holes dropped; ${pointsBefore} points before, ${all.length} after, ${polygons.length} rings before, ${rings.length} after`,
    bbox: [Math.min(...all.map((p) => p[0])), Math.min(...all.map((p) => p[1])), Math.max(...all.map((p) => p[0])), Math.max(...all.map((p) => p[1]))],
    geometry: { type: 'MultiPolygon', coordinates: rings.map((ring) => [ring]) },
  };
  const target = join(here, '..', 'extents', 'state-extent.json');
  mkdirSync(dirname(target), { recursive: true });
  const lines = ['{', ...Object.entries(output).filter(([key]) => key !== 'geometry').map(([key, value]) => `  ${JSON.stringify(key)}: ${JSON.stringify(value)},`)];
  lines.push('  "geometry": { "type": "MultiPolygon", "coordinates": [', rings.map((ring) => `    [${JSON.stringify(ring)}]`).join(',\n'), '  ] }', '}');
  writeFileSync(target, `${lines.join('\n')}\n`);
  console.log(output.simplification);
  console.log(`bbox ${output.bbox.join(',')}; wrote infra/osrm/extents/state-extent.json`);
}
