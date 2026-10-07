// SPDX-License-Identifier: AGPL-3.0-only
/**
 * Smoke test for one saferoute-osrm image, with local Docker (P012a, ADR 0020).
 *
 *   node infra/osrm/scripts/local-smoke.mjs --image saferoute-osrm:foot-sample-2026-10-06 --profile foot
 *   node infra/osrm/scripts/local-smoke.mjs --image <tag> --profile car --pairs state
 *
 * Starts the image the way Cloud Run does (PORT set, no extra arguments), with 1 CPU and
 * --memory (default 2g), and checks:
 *   1. it answers within --timeout-seconds (the time to the first answer is printed);
 *   2. every pair of public places in sample/route-pairs.json for this profile gets a route with
 *      a plausible length (1 to 2.5 times the straight line) and a plausible average speed;
 *   3. three alternatives are the most it returns, and a four-point route and a four-point table
 *      request are refused (the limits in entrypoint.sh);
 *   4. the process is not root, the data labels are on the image, and metadata.json is inside;
 *   5. PRIVACY: after all those requests the container log holds no request path.
 *
 * Exit code 0 = all passed. Plain Node, no dependencies; needs Docker.
 */
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

import { haversineKm } from './measure.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const SPEED_KMH = { foot: [3.5, 5.5], car: [12, 95] };

function docker(args) {
  const result = spawnSync('docker', args, { encoding: 'utf8' });
  if (result.status !== 0) throw new Error(`docker ${args[0]} failed: ${result.stderr.trim().split('\n')[0]}`);
  return result.stdout.trim();
}

async function getJson(baseUrl, path) {
  const response = await fetch(new URL(path, baseUrl), { signal: AbortSignal.timeout(20_000) });
  let body = {};
  try {
    body = await response.json();
  } catch {
    // an empty or non-JSON answer is judged by its status alone
  }
  return { status: response.status, body };
}

const { values } = parseArgs({
  options: {
    image: { type: 'string' },
    profile: { type: 'string' },
    pairs: { type: 'string', default: 'sample' },
    memory: { type: 'string', default: '2g' },
    'timeout-seconds': { type: 'string', default: '120' },
  },
});
if (!values.image || !SPEED_KMH[values.profile]) {
  console.error('usage: local-smoke.mjs --image <tag> --profile foot|car [--pairs sample|state] [--memory 2g]');
  process.exit(1);
}
const pairs = JSON.parse(readFileSync(join(here, '..', 'sample', 'route-pairs.json'), 'utf8'))[values.pairs];
if (!pairs) {
  console.error('--pairs must be sample or state');
  process.exit(1);
}

const results = [];
const check = (name, passed, detail = '') => {
  results.push(passed);
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
};

const started = Date.now();
const container = docker(['run', '--detach', '--cpus', '1', '--memory', values.memory, '--env', 'PORT=8080', '--publish', '127.0.0.1::8080', values.image]);
try {
  const baseUrl = `http://${docker(['port', container, '8080/tcp']).split('\n')[0]}`;
  const [lon, lat] = pairs[0].from;
  const deadline = started + Number(values['timeout-seconds']) * 1000;
  let ready = false;
  while (!ready && Date.now() < deadline) {
    try {
      ready = (await getJson(baseUrl, `/nearest/v1/p/${lon},${lat}?number=1`)).status === 200;
    } catch {
      // not listening yet
    }
    if (!ready) await new Promise((resolve) => setTimeout(resolve, 100));
  }
  check('the service answers on $PORT', ready, `${Date.now() - started} ms from docker run to the first answer`);

  if (ready) {
    for (const pair of pairs.filter((p) => p.modes.includes(values.profile))) {
      const path = `/route/v1/p/${pair.from.join(',')};${pair.to.join(',')}?steps=false&overview=full&geometries=polyline6&alternatives=2`;
      const { status, body } = await getJson(baseUrl, path);
      const route = body.routes?.[0];
      const straightKm = haversineKm(pair.from, pair.to);
      const km = (route?.distance ?? 0) / 1000;
      const kmh = route ? km / (route.duration / 3600) : 0;
      const [slow, fast] = SPEED_KMH[values.profile];
      const plausible = status === 200 && body.code === 'Ok' && km >= straightKm * 0.98 && km <= straightKm * 2.5 && kmh >= slow && kmh <= fast;
      const minutes = route ? Math.round(route.duration / 60) : 0;
      check(`route: ${pair.name}`, plausible && body.routes.length <= 3 && typeof route.geometry === 'string',
        `status ${status}, ${body.code ?? 'no code'}, ${body.routes?.length ?? 0} route(s), ${km.toFixed(1)} km for ${straightKm.toFixed(1)} km straight, ${minutes} min, ${kmh.toFixed(1)} km/h`);
    }
    const a = pairs[0].from.join(',');
    const b = pairs[0].to.join(',');
    const fourPoints = await getJson(baseUrl, `/route/v1/p/${a};${b};${a};${b}`);
    check('a route with four points is refused', fourPoints.status === 400, `status ${fourPoints.status}, ${fourPoints.body.code}`);
    const table = await getJson(baseUrl, `/table/v1/p/${a};${b};${a};${b}`);
    check('a table request with four points is refused', table.status === 400, `status ${table.status}, ${table.body.code}`);
  }

  const uid = docker(['exec', container, 'id', '-u']);
  check('the process is not root', uid === '10001', `uid ${uid}`);
  const labels = JSON.parse(docker(['image', 'inspect', '--format', '{{json .Config.Labels}}', values.image]));
  const metadata = JSON.parse(docker(['exec', container, 'cat', '/graph/metadata.json']));
  check('data labels and metadata.json name the source, date, licence and credit',
    labels['app.saferoute.osrm.profile'] === values.profile && metadata.profile === values.profile &&
      /^\d{4}-\d{2}-\d{2}$/.test(labels['app.saferoute.osrm.extract-date'] ?? '') && labels['app.saferoute.osrm.data-licence'] === 'ODbL-1.0' &&
      (labels['app.saferoute.osrm.data-attribution'] ?? '').includes('OpenStreetMap') && /^[0-9a-f]{64}$/.test(metadata.extractSha256 ?? ''),
    `extent ${metadata.extent}, extract ${metadata.extractDate}`);

  const logs = spawnSync('docker', ['logs', container], { encoding: 'utf8' });
  const leaked = `${logs.stdout}${logs.stderr}`.split('\n').filter((line) => /\/(route|nearest|table)\/v1/.test(line) || /\d{2}\.\d{4,},\d{2}\.\d{4,}/.test(line)).length;
  check('PRIVACY: the container log holds no request path and no coordinate', leaked === 0, `${leaked} such line(s)`);
} finally {
  spawnSync('docker', ['rm', '--force', container]);
}

const failed = results.filter((passed) => !passed).length;
console.log(failed === 0 ? `All ${results.length} checks passed.` : `${failed} of ${results.length} checks FAILED.`);
process.exit(failed === 0 ? 0 : 1);
