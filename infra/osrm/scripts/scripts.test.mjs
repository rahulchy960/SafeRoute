// SPDX-License-Identifier: AGPL-3.0-only
// Unit tests for the pure parts of the OSRM scripts: `node --test infra/osrm/scripts/scripts.test.mjs`.
// No Docker and no network. Coordinates are round invented values.
import assert from 'node:assert/strict';
import { test } from 'node:test';

import { checkExtractDate, checkExtractUrl, readConfig } from './build-image.mjs';
import { haversineKm, inGeometry, percentile } from './measure.mjs';

const config = readConfig();

test('an extract URL must be https, under an allowed prefix, a .osm.pbf, with no query', () => {
  const good = 'https://download.geofabrik.de/asia/india/eastern-zone-261006.osm.pbf';
  assert.equal(checkExtractUrl(good, config).href, good);
  for (const bad of [
    'http://download.geofabrik.de/asia/india/eastern-zone-261006.osm.pbf',
    'https://download.geofabrik.de/asia/india-latest.osm.pbf',
    'https://download.geofabrik.de.example.org/asia/india/x.osm.pbf',
    'https://download.geofabrik.de/asia/india/../../europe/x.osm.pbf',
    'https://download.geofabrik.de/asia/india/x.osm.pbf?to=elsewhere',
    'https://download.geofabrik.de/asia/india/x.zip',
    'https://user:secret@download.geofabrik.de/asia/india/x.osm.pbf',
  ]) {
    assert.throws(() => checkExtractUrl(bad, config), /not allowed/, bad);
  }
});

test('the extract date is a real calendar date in YYYY-MM-DD', () => {
  assert.equal(checkExtractDate('2026-10-06'), '2026-10-06');
  for (const bad of ['261006', '2026-13-01', '2026-10-6', 'latest', '']) {
    assert.throws(() => checkExtractDate(bad), /YYYY-MM-DD/, bad);
  }
});

test('the configuration names the licence, the credit and three extents', () => {
  assert.equal(config.data.licence, 'ODbL-1.0');
  assert.match(config.data.attribution, /OpenStreetMap/);
  assert.deepEqual(Object.keys(config.extents), ['state', 'metro', 'sample']);
  assert.deepEqual(config.profiles, ['foot', 'car']);
  for (const prefix of config.allowedExtractUrlPrefixes) assert.match(prefix, /^https:\/\/.+\/$/);
});

test('percentile takes the nearest rank', () => {
  const values = Array.from({ length: 100 }, (_, i) => i + 1);
  assert.equal(percentile(values, 50), 50);
  assert.equal(percentile(values, 95), 95);
  assert.equal(percentile([7], 95), 7);
  assert.equal(percentile([], 95), null);
});

test('point in polygon respects holes and multipolygons', () => {
  const square = [[[0, 0], [10, 0], [10, 10], [0, 10], [0, 0]], [[4, 4], [6, 4], [6, 6], [4, 6], [4, 4]]];
  const polygon = { type: 'Polygon', coordinates: square };
  assert.equal(inGeometry([1, 1], polygon), true);
  assert.equal(inGeometry([5, 5], polygon), false);
  assert.equal(inGeometry([11, 1], polygon), false);
  const multi = { type: 'MultiPolygon', coordinates: [square, [[[20, 20], [21, 20], [21, 21], [20, 21], [20, 20]]]] };
  assert.equal(inGeometry([20.5, 20.5], multi), true);
});

test('haversine distance of one degree of latitude is about 111 km', () => {
  assert.ok(Math.abs(haversineKm([10, 20], [10, 21]) - 111.2) < 0.5);
});
