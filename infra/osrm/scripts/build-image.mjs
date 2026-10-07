// SPDX-License-Identifier: AGPL-3.0-only
/**
 * Builds one saferoute-osrm image: one profile over one extent (P012a, ADR 0020).
 *
 *   node infra/osrm/scripts/build-image.mjs --profile foot --extent sample
 *   node infra/osrm/scripts/build-image.mjs --profile car --extent state \
 *     --extract-url https://download.geofabrik.de/asia/india/eastern-zone-261006.osm.pbf \
 *     --extract-date 2026-10-06
 *
 * Steps:
 *   1. extent "sample" uses the committed fixture. Any other extent downloads --extract-url into
 *      infra/osrm/data/ (git-ignored) unless that file is already there. The URL must start with
 *      a prefix from config.json, and the download must match the `.md5` file published next to
 *      it (a transfer check: it does not prove who made the file).
 *   2. writes data/metadata.json (source, date, SHA-256, licence, credit) for the image.
 *   3. `docker buildx build` with the same facts as image labels.
 *
 * Prints the image tag on the last line as `image=<tag>`. The tag is
 * saferoute-osrm:<profile>-<extent>-<extract date>. Plain Node, no dependencies; needs Docker.
 */
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { copyFileSync, createReadStream, createWriteStream, existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';

const here = dirname(fileURLToPath(import.meta.url));
const contextDir = join(here, '..');
const dataDir = join(contextDir, 'data');
const SAMPLE_DATE = '2026-10-06';

export function readConfig() {
  return JSON.parse(readFileSync(join(contextDir, 'config.json'), 'utf8'));
}

/** Throws unless the URL is https, ends in .osm.pbf and starts with an allowed prefix. */
export function checkExtractUrl(url, config) {
  const parsed = new URL(url);
  const allowed = config.allowedExtractUrlPrefixes.some((prefix) => parsed.href.startsWith(prefix));
  if (parsed.protocol !== 'https:' || !allowed || parsed.search || parsed.hash || !parsed.pathname.endsWith('.osm.pbf')) {
    throw new Error(`extract URL not allowed; allowed prefixes: ${config.allowedExtractUrlPrefixes.join(', ')}`);
  }
  return parsed;
}

export function checkExtractDate(date) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || Number.isNaN(Date.parse(`${date}T00:00:00Z`))) {
    throw new Error('--extract-date must be YYYY-MM-DD');
  }
  return date;
}

async function hashFile(path, algorithm) {
  const hash = createHash(algorithm);
  await pipeline(createReadStream(path), hash);
  return hash.digest('hex');
}

async function download(url, target) {
  const response = await fetch(url, { redirect: 'error' });
  if (!response.ok || !response.body) throw new Error(`download failed with status ${response.status}`);
  const partial = `${target}.part`;
  await pipeline(Readable.fromWeb(response.body), createWriteStream(partial));
  renameSync(partial, target);
}

async function fetchExtract(url, config) {
  const parsed = checkExtractUrl(url, config);
  const cached = join(dataDir, parsed.pathname.split('/').pop());
  if (!existsSync(cached)) {
    console.log('Downloading the extract (a few hundred MB)...');
    await download(parsed.href, cached);
  }
  const published = await fetch(`${parsed.href}.md5`, { redirect: 'error' });
  if (!published.ok) throw new Error(`no .md5 file next to the extract (status ${published.status})`);
  const expected = (await published.text()).trim().split(/\s+/)[0];
  const actual = await hashFile(cached, 'md5');
  if (expected !== actual) throw new Error('the extract does not match its published .md5; delete it from infra/osrm/data and retry');
  console.log('The extract matches its published MD5.');
  return cached;
}

async function main() {
  const { values } = parseArgs({
    options: {
      profile: { type: 'string' },
      extent: { type: 'string' },
      'extract-url': { type: 'string' },
      'extract-date': { type: 'string' },
      tag: { type: 'string' },
    },
  });
  const config = readConfig();
  if (!config.profiles.includes(values.profile)) throw new Error(`--profile must be one of: ${config.profiles.join(', ')}`);
  const extent = config.extents[values.extent];
  if (!extent) throw new Error(`--extent must be one of: ${Object.keys(config.extents).join(', ')}`);

  mkdirSync(dataDir, { recursive: true });
  const source = join(dataDir, 'source.osm.pbf');
  let extractUrl = 'repository fixture infra/osrm/sample/sample.osm.pbf';
  let extractDate = SAMPLE_DATE;
  if (values.extent === 'sample') {
    copyFileSync(join(contextDir, 'sample', 'sample.osm.pbf'), source);
  } else {
    if (!values['extract-url'] || !values['extract-date']) throw new Error('--extract-url and --extract-date are required for this extent');
    extractDate = checkExtractDate(values['extract-date']);
    extractUrl = checkExtractUrl(values['extract-url'], config).href;
    copyFileSync(await fetchExtract(extractUrl, config), source);
  }

  const metadata = {
    profile: values.profile,
    algorithm: 'MLD',
    extent: values.extent,
    extentKind: extent.kind,
    extentValue: extent.value,
    extentDescription: extent.description,
    extractUrl,
    extractDate,
    extractSha256: await hashFile(source, 'sha256'),
    licence: config.data.licence,
    licenceUrl: config.data.licenceUrl,
    attribution: config.data.attribution,
    attributionUrl: config.data.attributionUrl,
  };
  writeFileSync(join(dataDir, 'metadata.json'), `${JSON.stringify(metadata, null, 2)}\n`);

  const tag = values.tag ?? `saferoute-osrm:${values.profile}-${values.extent}-${extractDate}`;
  const labels = {
    'app.saferoute.osrm.profile': metadata.profile,
    'app.saferoute.osrm.extent': metadata.extent,
    'app.saferoute.osrm.extract-url': metadata.extractUrl,
    'app.saferoute.osrm.extract-date': metadata.extractDate,
    'app.saferoute.osrm.extract-sha256': metadata.extractSha256,
    'app.saferoute.osrm.data-licence': metadata.licence,
    'app.saferoute.osrm.data-attribution': metadata.attribution,
  };
  const args = [
    'buildx', 'build', '--load',
    '--build-arg', `PROFILE=${values.profile}`,
    '--build-arg', `EXTENT_KIND=${extent.kind}`,
    '--build-arg', `EXTENT_VALUE=${extent.value}`,
    ...Object.entries(labels).flatMap(([key, value]) => ['--label', `${key}=${value}`]),
    '--tag', tag,
    contextDir,
  ];
  const started = Date.now();
  const build = spawnSync('docker', args, { stdio: 'inherit' });
  if (build.status !== 0) throw new Error(`docker build failed (exit code ${build.status})`);
  console.log(`Built in ${Math.round((Date.now() - started) / 1000)} s.`);
  console.log(`image=${tag}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((err) => {
    console.error(`build-image: ${err?.message ?? err}`);
    process.exit(1);
  });
}
