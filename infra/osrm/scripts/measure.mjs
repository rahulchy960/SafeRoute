// SPDX-License-Identifier: AGPL-3.0-only
/**
 * Latency and failure-rate measurement for one running osrm-routed (P012a, ADR 0020).
 *
 *   node infra/osrm/scripts/measure.mjs --bbox 88.00,22.25,88.70,23.05 --pairs 200
 *   node infra/osrm/scripts/measure.mjs --bbox ... --polygon boundary.geojson --max-km 30
 *
 * The service comes from the environment, never from an argument (arguments end up in shell
 * history): OSRM_URL (default http://127.0.0.1:5000) and, for a private Cloud Run service,
 * OSRM_ID_TOKEN (sent as a Bearer token).
 *
 * What it does:
 *   1. draws random points in --bbox (inside --polygon when given) from a seeded generator and
 *      snaps each to the road network with /nearest; points further than --snap-m from a road
 *      are dropped. No point comes from a person or a device.
 *   2. builds --pairs origin/destination pairs (straight-line distance between --min-km and
 *      --max-km) and requests each route three ways, one at a time: without alternatives, with
 *      alternatives=2, then all pairs again with --concurrency requests in flight.
 *   3. prints ONE JSON object with counts and timings.
 *
 * Never printed: the URL, the token, any coordinate, any geometry. Plain Node, no dependencies.
 */
import { readFileSync } from 'node:fs';
import { performance } from 'node:perf_hooks';
import { parseArgs } from 'node:util';

const REQUEST_TIMEOUT_MS = 30_000;
const DISTANCE_BUCKETS_KM = [5, 20, 50, 150, 400, Infinity];

/** mulberry32: a tiny seeded generator, so a run can be repeated with the same points. */
function seededRandom(seed) {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function haversineKm([lon1, lat1], [lon2, lat2]) {
  const rad = Math.PI / 180;
  const a =
    Math.sin(((lat2 - lat1) * rad) / 2) ** 2 +
    Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(((lon2 - lon1) * rad) / 2) ** 2;
  return 12742 * Math.asin(Math.sqrt(a));
}

function inRing([x, y], ring) {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i];
    const [xj, yj] = ring[j];
    if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

/** Point in a GeoJSON Polygon or MultiPolygon (outer ring minus holes). */
export function inGeometry(point, geometry) {
  const polygons = geometry.type === 'Polygon' ? [geometry.coordinates] : geometry.coordinates;
  return polygons.some(
    ([outer, ...holes]) => inRing(point, outer) && !holes.some((hole) => inRing(point, hole)),
  );
}

function readGeometry(path) {
  const json = JSON.parse(readFileSync(path, 'utf8'));
  const geometry = json.type === 'FeatureCollection' ? json.features[0].geometry : (json.geometry ?? json);
  if (geometry.type !== 'Polygon' && geometry.type !== 'MultiPolygon') {
    throw new Error('--polygon must hold a Polygon or MultiPolygon');
  }
  return geometry;
}

export function percentile(sorted, p) {
  if (sorted.length === 0) return null;
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];
}

function summarize(samples) {
  const ms = samples.map((s) => s.ms).sort((a, b) => a - b);
  const codes = {};
  for (const s of samples) codes[s.code] = (codes[s.code] ?? 0) + 1;
  const ok = samples.filter((s) => s.code === 'Ok');
  const round = (v) => (v === null ? null : Math.round(v * 10) / 10);
  const byDistance = DISTANCE_BUCKETS_KM.map((upper, i) => {
    const lower = i === 0 ? 0 : DISTANCE_BUCKETS_KM[i - 1];
    const inBucket = samples.filter((s) => s.km >= lower && s.km < upper);
    const bucket = inBucket.map((s) => s.ms).sort((a, b) => a - b);
    return {
      straightLineKm: `${lower}-${upper === Infinity ? 'more' : upper}`,
      requests: bucket.length,
      ok: inBucket.filter((s) => s.code === 'Ok').length,
      p50Ms: round(percentile(bucket, 50)),
      p95Ms: round(percentile(bucket, 95)),
    };
  }).filter((b) => b.requests > 0);
  return {
    requests: samples.length,
    codes,
    p50Ms: round(percentile(ms, 50)),
    p95Ms: round(percentile(ms, 95)),
    maxMs: round(ms.at(-1) ?? null),
    meanRoutesPerOk: ok.length ? round(ok.reduce((n, s) => n + s.routes, 0) / ok.length) : null,
    meanRouteKm: ok.length ? round(ok.reduce((n, s) => n + s.routeKm, 0) / ok.length) : null,
    maxResponseKb: round(Math.max(0, ...samples.map((s) => s.bytes)) / 1024),
    byDistance,
  };
}

async function main() {
  const { values } = parseArgs({
    options: {
      bbox: { type: 'string' },
      polygon: { type: 'string' },
      pairs: { type: 'string', default: '200' },
      seed: { type: 'string', default: '12' },
      'min-km': { type: 'string', default: '0.2' },
      'max-km': { type: 'string', default: '100000' },
      'snap-m': { type: 'string', default: '500' },
      concurrency: { type: 'string', default: '8' },
      label: { type: 'string', default: '' },
    },
  });
  const bbox = (values.bbox ?? '').split(',').map(Number);
  if (bbox.length !== 4 || bbox.some(Number.isNaN)) throw new Error('--bbox minLon,minLat,maxLon,maxLat is required');
  const pairsWanted = Number(values.pairs);
  const minKm = Number(values['min-km']);
  const maxKm = Number(values['max-km']);
  const snapMeters = Number(values['snap-m']);
  const geometry = values.polygon ? readGeometry(values.polygon) : null;
  const random = seededRandom(Number(values.seed));
  const baseUrl = process.env.OSRM_URL ?? 'http://127.0.0.1:5000';
  const headers = process.env.OSRM_ID_TOKEN ? { Authorization: `Bearer ${process.env.OSRM_ID_TOKEN}` } : {};

  const call = async (path) => {
    const started = performance.now();
    const response = await fetch(new URL(path, baseUrl), { headers, signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS) });
    const text = await response.text();
    const ms = performance.now() - started;
    let body = {};
    try {
      body = JSON.parse(text);
    } catch {
      body = { code: `http_${response.status}` };
    }
    return { ms, body, bytes: text.length };
  };

  // 1. random points, snapped to the network
  const points = [];
  let drawn = 0;
  let tooFar = 0;
  while (points.length < pairsWanted * 2 && drawn < pairsWanted * 400) {
    const candidate = [bbox[0] + random() * (bbox[2] - bbox[0]), bbox[1] + random() * (bbox[3] - bbox[1])];
    if (geometry && !inGeometry(candidate, geometry)) continue;
    drawn++;
    const { body } = await call(`/nearest/v1/p/${candidate[0].toFixed(6)},${candidate[1].toFixed(6)}?number=1`);
    const waypoint = body.waypoints?.[0];
    if (body.code !== 'Ok' || !waypoint || waypoint.distance > snapMeters) {
      tooFar++;
      continue;
    }
    points.push(waypoint.location);
  }

  // 2. pairs within the distance window
  const pairs = [];
  for (let attempt = 0; pairs.length < pairsWanted && attempt < pairsWanted * 2000; attempt++) {
    const a = points[Math.floor(random() * points.length)];
    const b = points[Math.floor(random() * points.length)];
    const km = haversineKm(a, b);
    if (km >= minKm && km <= maxKm) pairs.push({ a, b, km });
  }

  const route = async ({ a, b, km }, alternatives) => {
    const path =
      `/route/v1/p/${a[0]},${a[1]};${b[0]},${b[1]}` +
      `?steps=false&overview=full&geometries=polyline6&alternatives=${alternatives}`;
    try {
      const { ms, body, bytes } = await call(path);
      return { ms, km, bytes, code: body.code ?? 'unknown', routes: body.routes?.length ?? 0, routeKm: (body.routes?.[0]?.distance ?? 0) / 1000 };
    } catch (err) {
      // Name only: an error message could hold the request URL, which has coordinates in it.
      return { ms: REQUEST_TIMEOUT_MS, km, bytes: 0, code: `error_${err?.name ?? 'unknown'}`, routes: 0, routeKm: 0 };
    }
  };

  const single = [];
  for (const pair of pairs) single.push(await route(pair, 'false'));
  const withAlternatives = [];
  for (const pair of pairs) withAlternatives.push(await route(pair, '2'));

  const concurrency = Number(values.concurrency);
  const concurrent = [];
  const queue = [...pairs];
  const startedAt = performance.now();
  await Promise.all(
    Array.from({ length: concurrency }, async () => {
      for (let pair = queue.pop(); pair; pair = queue.pop()) concurrent.push(await route(pair, '2'));
    }),
  );
  const seconds = (performance.now() - startedAt) / 1000;

  console.log(
    JSON.stringify(
      {
        label: values.label,
        seed: Number(values.seed),
        pointsDrawn: drawn,
        pointsDroppedFarFromRoad: tooFar,
        pairs: pairs.length,
        distanceWindowKm: [minKm, maxKm],
        oneAtATime: summarize(single),
        oneAtATimeAlternatives2: summarize(withAlternatives),
        concurrentAlternatives2: { concurrency, requestsPerSecond: Math.round((concurrent.length / seconds) * 10) / 10, ...summarize(concurrent) },
      },
      null,
      2,
    ),
  );
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/').split('/').pop())) {
  main().catch((err) => {
    console.error(`measure failed: ${err?.name ?? 'error'}: ${err?.message ?? ''}`.replace(/https?:\/\/\S+/g, '<url>'));
    process.exit(1);
  });
}
