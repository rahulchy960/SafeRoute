// SPDX-License-Identifier: AGPL-3.0-only
/**
 * Container smoke test (P006 R2): builds the API image and proves it behaves like a deployable
 * unit, using only local Docker. No Google access, no secrets, no project dependencies.
 *
 *   node scripts/container-smoke.mjs                  (from backend/; Docker must be running)
 *   node scripts/container-smoke.mjs --skip-build --image <tag> --git-sha <sha>
 *
 * It starts a throwaway PostGIS container (the image pinned in docker-compose.yml) on a private
 * Docker network and checks, in order: image contents and metadata, the migration command (twice:
 * the second run is a no-op), the admin script, the production guards (a `demo-` Firebase
 * project, a missing geocoding key), the HTTP smoke checks from README "Deployment smoke checks", non-root, JSON logs and
 * a clean SIGTERM shutdown. Containers and the network are removed on success and on failure.
 *
 * Runs on Linux (CI), macOS and Windows: plain Node, no shell. Exit code 0 = all checks passed.
 */
import { spawnSync } from 'node:child_process';
import { appendFileSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const BACKEND_DIR = join(dirname(fileURLToPath(import.meta.url)), '..');

// Throwaway credentials for a database that exists only inside this test's Docker network.
const DB = { user: 'saferoute_smoke', password: 'smoke-only-password', name: 'saferoute_smoke' };
// Syntactically valid, deliberately fake. Nothing here sends a token, so Google is never called.
const FAKE_FIREBASE_PROJECT_ID = 'saferoute-ci-fake';
// Obviously fake. The API must start with it and never call the provider: no search is made here.
const FAKE_GEOCODING = {
  GEOCODING_API_KEY: 'fake-geocoding-key-for-smoke-test',
  GEOCODING_PROVIDER: 'geoapify',
};
// Obviously fake (hosts under .invalid never resolve). The API must start with them and never
// call them: no route is requested here, and no ID token is fetched.
const FAKE_ROUTING = {
  OSRM_WALKING_URL: 'https://osrm-walking-smoke.example.invalid',
  OSRM_DRIVING_URL: 'https://osrm-driving-smoke.example.invalid',
};
const ROUTING_GUARD_MESSAGE = 'OSRM_DRIVING_URL: required when NODE_ENV=production';
const GEOCODING_GUARD_MESSAGE = 'GEOCODING_API_KEY: required when NODE_ENV=production';
const DEMO_GUARD_MESSAGE = 'a demo- project ID is not allowed when NODE_ENV=production';
const SHUTDOWN_LIMIT_MS = 10_000;

const { values: options } = parseArgs({
  options: {
    image: { type: 'string', default: 'saferoute-api:smoke' },
    'git-sha': { type: 'string' },
    'skip-build': { type: 'boolean', default: false },
  },
});

/** Runs a command without a shell and captures its output. Never throws on a non-zero exit. */
function run(command, args, { timeoutMs = 120_000 } = {}) {
  const result = spawnSync(command, args, { encoding: 'utf8', timeout: timeoutMs });
  if (result.error) throw new Error(`could not run ${command}: ${result.error.message}`);
  return { status: result.status, stdout: result.stdout, stderr: result.stderr };
}

function docker(args, runOptions) {
  return run('docker', args, runOptions);
}

/** docker, but a non-zero exit is a failure of the test itself. */
function dockerOk(args, runOptions) {
  const result = docker(args, runOptions);
  if (result.status !== 0) {
    throw new Error(
      `docker ${args.slice(0, 2).join(' ')} exited ${result.status}\n${result.stderr}`,
    );
  }
  return result.stdout.trim();
}

const results = [];
function check(name, passed, detail = '') {
  results.push({ name, passed });
  console.log(`${passed ? 'ok  ' : 'FAIL'}  ${name}${detail === '' ? '' : `  (${detail})`}`);
  if (!passed) throw new Error(`check failed: ${name}`);
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function waitFor(what, attempt, { timeoutMs, intervalMs = 500 }) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    if (await attempt()) return;
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
    await sleep(intervalMs);
  }
}

function jsonLines(text) {
  return text
    .split('\n')
    .filter((line) => line.trim() !== '')
    .map((line) => {
      try {
        return JSON.parse(line);
      } catch {
        return undefined;
      }
    });
}

function resolveGitSha() {
  if (options['git-sha'] !== undefined) return options['git-sha'];
  if (process.env.GITHUB_SHA) return process.env.GITHUB_SHA;
  const result = run('git', ['-C', BACKEND_DIR, 'rev-parse', 'HEAD']);
  if (result.status !== 0) throw new Error('pass --git-sha (not in a git checkout)');
  return result.stdout.trim();
}

/** The PostGIS image pinned for local development, so there is one place to update it. */
function postgisImage() {
  const compose = readFileSync(join(BACKEND_DIR, 'docker-compose.yml'), 'utf8');
  const match = /^\s*image:\s*(postgis\/postgis:\S+@sha256:[0-9a-f]{64})\s*$/m.exec(compose);
  if (!match) throw new Error('no digest-pinned postgis image found in docker-compose.yml');
  return match[1];
}

const image = options.image;
const gitSha = resolveGitSha();
const suffix = `${process.pid}-${Date.now().toString(36)}`;
const network = `saferoute-smoke-${suffix}`;
const dbContainer = `saferoute-smoke-db-${suffix}`;
const apiContainer = `saferoute-smoke-api-${suffix}`;
const databaseUrl = `postgres://${DB.user}:${DB.password}@${dbContainer}:5432/${DB.name}`;

/** Runs a one-off command in the image on the test network, with the given environment. */
function runInImage(env, command) {
  const envArgs = Object.entries(env).flatMap(([key, value]) => ['-e', `${key}=${value}`]);
  return docker(['run', '--rm', '--network', network, ...envArgs, image, ...command]);
}

function cleanup() {
  docker(['rm', '-f', '-v', apiContainer, dbContainer]);
  docker(['network', 'rm', network]);
}

function checkImage() {
  const config = JSON.parse(dockerOk(['image', 'inspect', image, '--format', '{{json .Config}}']));
  const labels = config.Labels ?? {};
  check('image runs as the non-root user "node"', config.User === 'node', `User=${config.User}`);
  check('image sets NODE_ENV=production', config.Env.includes('NODE_ENV=production'));
  check(
    'image label revision equals the GIT_SHA build arg',
    labels['org.opencontainers.image.revision'] === gitSha,
  );
  check(
    'image labels source and version are set',
    labels['org.opencontainers.image.source'] === 'https://github.com/rahulchy960/SafeRoute' &&
      labels['org.opencontainers.image.version'] === gitSha,
  );
  check(
    'image default command is the API',
    JSON.stringify(config.Cmd) === JSON.stringify(['node', 'dist/server.js']),
  );

  // Looks inside the image: what must be there, and what must never be.
  const probe = `
    const fs = require('node:fs');
    const present = ['dist/server.js', 'dist/db/migrate.js', 'dist/scripts/set-role.js',
      'drizzle/meta/_journal.json', 'package.json', 'node_modules/hono'];
    const absent = ['.env', '.env.example', '.git', 'src', 'test', 'scripts', 'Dockerfile',
      'node_modules/typescript', 'node_modules/vitest', 'node_modules/tsx', 'node_modules/eslint',
      'node_modules/drizzle-kit', 'node_modules/testcontainers'];
    const store = fs.readdirSync('node_modules/.pnpm');
    const devInStore = store.filter((d) => /^(typescript|vitest|tsx|eslint|drizzle-kit|esbuild|testcontainers)@/.test(d));
    console.log(JSON.stringify({
      missing: present.filter((p) => !fs.existsSync(p)),
      unexpected: absent.filter((p) => fs.existsSync(p)).concat(devInStore),
      node: process.version,
      uid: process.getuid(),
    }));`;
  const result = docker(['run', '--rm', image, 'node', '-e', probe]);
  const report = JSON.parse(result.stdout);
  check(
    'image contains dist/, migrations and production dependencies',
    report.missing.length === 0,
    report.missing.join(', '),
  );
  check(
    'image has no .env, sources, tests or dev dependencies',
    report.unexpected.length === 0,
    report.unexpected.join(', '),
  );
  const nvmrc = readFileSync(join(BACKEND_DIR, '.nvmrc'), 'utf8').trim();
  check(
    `image Node major matches .nvmrc (${nvmrc})`,
    report.node.startsWith(`v${nvmrc}.`),
    report.node,
  );
  check('one-off commands run as a non-root uid', report.uid !== 0, `uid=${report.uid}`);
}

async function startDatabase() {
  dockerOk(['network', 'create', network]);
  dockerOk(
    [
      'run',
      '-d',
      '--name',
      dbContainer,
      '--network',
      network,
      '-e',
      `POSTGRES_DB=${DB.name}`,
      '-e',
      `POSTGRES_USER=${DB.user}`,
      '-e',
      `POSTGRES_PASSWORD=${DB.password}`,
      postgisImage(),
    ],
    { timeoutMs: 300_000 },
  );
  // TCP check: during first-start initialisation the server listens only on a unix socket.
  await waitFor(
    'PostGIS to accept connections',
    () =>
      docker(['exec', dbContainer, 'pg_isready', '-h', '127.0.0.1', '-U', DB.user, '-d', DB.name])
        .status === 0,
    { timeoutMs: 90_000, intervalMs: 1_000 },
  );
}

function checkMigrations() {
  // No FIREBASE_PROJECT_ID: the migration job gets the database URL and nothing else.
  const first = runInImage({ DATABASE_URL: databaseUrl }, ['node', 'dist/db/migrate.js']);
  const firstLog = jsonLines(first.stdout).at(-1);
  check(
    'migrate: first run applies the migrations',
    first.status === 0 && firstLog?.count >= 1,
    `count=${firstLog?.count}`,
  );
  const second = runInImage({ DATABASE_URL: databaseUrl }, ['node', 'dist/db/migrate.js']);
  const secondLog = jsonLines(second.stdout).at(-1);
  check(
    'migrate: second run is a no-op',
    second.status === 0 && secondLog?.count === 0,
    `count=${secondLog?.count}`,
  );
  check(
    'migrate: output never contains the database password',
    ![first, second].some((r) => `${r.stdout}${r.stderr}`.includes(DB.password)),
  );
  const noUrl = runInImage({}, ['node', 'dist/db/migrate.js']);
  check('migrate: fails without DATABASE_URL', noUrl.status === 1);
}

function checkAdminScript() {
  const help = runInImage({}, ['node', 'dist/scripts/set-role.js', '--help']);
  check(
    'set-role: --help works without a database',
    help.status === 0 && help.stdout.includes('--user-id'),
  );
  // A user that does not exist: proves the script reaches the migrated database and changes nothing.
  const unknown = runInImage({ DATABASE_URL: databaseUrl }, [
    'node',
    'dist/scripts/set-role.js',
    '--user-id',
    '00000000-0000-4000-8000-000000000001',
    '--role',
    'moderator',
    '--confirm',
  ]);
  check(
    'set-role: reaches the database and reports an unknown user',
    unknown.status === 1 &&
      unknown.stderr.includes('No user with id') &&
      !unknown.stderr.includes(DB.password),
  );
}

/** R3: the deploy passes GIT_SHA and APP_VERSION at runtime; they must win over the baked values. */
function checkVersionVariables() {
  const probe = `import('/app/dist/config.js').then((m) => {
    const config = m.parseJobConfig(process.env);
    console.log(JSON.stringify({ version: config.APP_VERSION, sha: config.GIT_SHA }));
  });`;
  const read = (env) => JSON.parse(runInImage(env, ['node', '-e', probe]).stdout);
  const baked = read({});
  check(
    'config reads the baked GIT_SHA and APP_VERSION',
    baked.version === gitSha && baked.sha === gitSha,
  );
  const overridden = read({ APP_VERSION: 'runtime-version', GIT_SHA: 'runtime-sha' });
  check(
    'runtime GIT_SHA and APP_VERSION override the baked values',
    overridden.version === 'runtime-version' && overridden.sha === 'runtime-sha',
  );
}

function checkProductionGuards() {
  const demo = runInImage(
    {
      DATABASE_URL: databaseUrl,
      FIREBASE_PROJECT_ID: 'demo-saferoute',
      ...FAKE_GEOCODING,
      ...FAKE_ROUTING,
    },
    [],
  );
  check(
    'API refuses to start in production with a demo- Firebase project',
    demo.status === 1 &&
      demo.stderr.includes(DEMO_GUARD_MESSAGE) &&
      !demo.stderr.includes(DB.password),
    `exit=${demo.status}`,
  );
  const missing = runInImage({ DATABASE_URL: databaseUrl, ...FAKE_GEOCODING, ...FAKE_ROUTING }, []);
  check('API refuses to start in production without FIREBASE_PROJECT_ID', missing.status === 1);
  // What a deploy without the secret looks like: the candidate never starts (ADR 0018).
  const noKey = runInImage(
    { DATABASE_URL: databaseUrl, FIREBASE_PROJECT_ID: FAKE_FIREBASE_PROJECT_ID, ...FAKE_ROUTING },
    [],
  );
  check(
    'API refuses to start in production without the geocoding key, naming the variable only',
    noKey.status === 1 &&
      noKey.stderr.includes(GEOCODING_GUARD_MESSAGE) &&
      !noKey.stderr.includes(DB.password),
    `exit=${noKey.status}`,
  );
  // What a deploy without an OSRM secret looks like: GitHub passes an empty value, and the
  // candidate never starts (ADR 0020). The other URL must not appear in the message.
  const noOsrm = runInImage(
    {
      DATABASE_URL: databaseUrl,
      FIREBASE_PROJECT_ID: FAKE_FIREBASE_PROJECT_ID,
      ...FAKE_GEOCODING,
      OSRM_WALKING_URL: FAKE_ROUTING.OSRM_WALKING_URL,
      OSRM_DRIVING_URL: '',
    },
    [],
  );
  check(
    'API refuses to start in production without an OSRM service URL, naming the variable only',
    noOsrm.status === 1 &&
      noOsrm.stderr.includes(ROUTING_GUARD_MESSAGE) &&
      !noOsrm.stderr.includes('example.invalid'),
    `exit=${noOsrm.status}`,
  );
  const noAuth = runInImage(
    {
      DATABASE_URL: databaseUrl,
      FIREBASE_PROJECT_ID: FAKE_FIREBASE_PROJECT_ID,
      ...FAKE_GEOCODING,
      ...FAKE_ROUTING,
      ROUTING_AUTH: 'none',
    },
    [],
  );
  check(
    'API refuses to start in production with ROUTING_AUTH=none',
    noAuth.status === 1 && noAuth.stderr.includes('ROUTING_AUTH: none is not allowed'),
    `exit=${noAuth.status}`,
  );
}

async function checkApi() {
  // GIT_SHA and APP_VERSION are not passed: the values baked from the build arg must show through.
  dockerOk([
    'run',
    '-d',
    '--name',
    apiContainer,
    '--network',
    network,
    '-p',
    '127.0.0.1::8080',
    '-e',
    `DATABASE_URL=${databaseUrl}`,
    '-e',
    `FIREBASE_PROJECT_ID=${FAKE_FIREBASE_PROJECT_ID}`,
    '-e',
    `GEOCODING_API_KEY=${FAKE_GEOCODING.GEOCODING_API_KEY}`,
    '-e',
    `GEOCODING_PROVIDER=${FAKE_GEOCODING.GEOCODING_PROVIDER}`,
    '-e',
    `OSRM_WALKING_URL=${FAKE_ROUTING.OSRM_WALKING_URL}`,
    '-e',
    `OSRM_DRIVING_URL=${FAKE_ROUTING.OSRM_DRIVING_URL}`,
    image,
  ]);
  const address = dockerOk(['port', apiContainer, '8080/tcp']).split('\n')[0].trim();
  const base = `http://${address}`;

  await waitFor(
    'the API to answer /health',
    async () => {
      const state = dockerOk(['inspect', apiContainer, '--format', '{{.State.Running}}']);
      if (state !== 'true')
        throw new Error(`API container exited:\n${docker(['logs', apiContainer]).stderr}`);
      try {
        return (await fetch(`${base}/health`)).ok;
      } catch {
        return false;
      }
    },
    { timeoutMs: 30_000 },
  );

  const health = await fetch(`${base}/health`);
  const healthBody = await health.json();
  check(
    'GET /health → 200',
    health.status === 200 && healthBody.status === 'ok',
    `status=${health.status}`,
  );
  check(
    'GET /health version equals the GIT_SHA build arg',
    healthBody.version === gitSha,
    healthBody.version,
  );

  const ready = await fetch(`${base}/health/ready`);
  const readyBody = await ready.json();
  check(
    'GET /health/ready → 200 (database reachable)',
    ready.status === 200 && readyBody.status === 'ready' && readyBody.checks?.database === 'ok',
    `status=${ready.status}`,
  );

  const me = await fetch(`${base}/v1/me`);
  const requestId = me.headers.get('x-request-id');
  check('GET /v1/me without a token → 401', me.status === 401, `status=${me.status}`);
  check('401 carries WWW-Authenticate: Bearer', me.headers.get('www-authenticate') === 'Bearer');
  check('401 carries an X-Request-Id header', Boolean(requestId), `request id ${requestId}`);

  // Search is behind sign-in: without a token it answers 401 and never reaches the provider.
  // POST with a body: search text never travels in a URL (ADR 0019).
  const search = await fetch(`${base}/v1/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ q: 'station' }),
  });
  check(
    'POST /v1/search without a token → 401 with WWW-Authenticate: Bearer',
    search.status === 401 && search.headers.get('www-authenticate') === 'Bearer',
    `status=${search.status}`,
  );

  // Routes are behind sign-in too: 401 before any limit, extent check or OSRM call. The body
  // holds no position: nothing about a place is sent by a smoke test.
  const routes = await fetch(`${base}/v1/routes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mode: 'walking' }),
  });
  check(
    'POST /v1/routes without a token → 401 with WWW-Authenticate: Bearer',
    routes.status === 401 && routes.headers.get('www-authenticate') === 'Bearer',
    `status=${routes.status}`,
  );

  // The deploy workflow's smoke script, run as a CLI against this container: it must pass here,
  // and must exit 1 when the deployed version is not the expected one.
  const smokeScript = join(BACKEND_DIR, 'scripts', 'smoke.mjs');
  const smoke = (expectVersion, timeoutSeconds) =>
    run(process.execPath, [
      smokeScript,
      '--url',
      base,
      '--expect-version',
      expectVersion,
      '--timeout-seconds',
      String(timeoutSeconds),
    ]);
  const smokePass = smoke(gitSha, 20);
  check(
    'smoke.mjs passes against the container',
    smokePass.status === 0 && smokePass.stdout.includes('smoke test passed'),
    `exit=${smokePass.status}`,
  );
  const smokeFail = smoke('not-the-deployed-version', 3);
  check(
    'smoke.mjs exits 1 when the expected version is wrong',
    smokeFail.status === 1 && smokeFail.stdout.includes('smoke test FAILED'),
    `exit=${smokeFail.status}`,
  );
  check(
    'smoke.mjs never prints the URL',
    ![smokePass, smokeFail].some((r) => `${r.stdout}${r.stderr}`.includes(address)),
  );

  check('API process runs as a non-root uid', dockerOk(['exec', apiContainer, 'id', '-u']) !== '0');

  const startedAt = Date.now();
  dockerOk(['stop', '-t', String(SHUTDOWN_LIMIT_MS / 1000), apiContainer]);
  const stopMs = Date.now() - startedAt;
  const exitCode = dockerOk(['inspect', apiContainer, '--format', '{{.State.ExitCode}}']);
  check('SIGTERM: exits with code 0', exitCode === '0', `exit=${exitCode}`);
  check(
    `SIGTERM: stops within ${SHUTDOWN_LIMIT_MS / 1000} s`,
    stopMs < SHUTDOWN_LIMIT_MS,
    `${stopMs} ms`,
  );

  const logs = docker(['logs', apiContainer]);
  const lines = jsonLines(logs.stdout);
  check(
    'logs are JSON lines with severity, message and timestamp',
    lines.length > 0 &&
      lines.every((l) => l && typeof l.severity === 'string' && l.message && l.timestamp),
    `${lines.length} lines`,
  );
  const messages = lines.map((l) => l.message);
  check(
    "logs show startup, the 401's request id and a complete shutdown",
    messages.includes('server listening') &&
      messages.includes('shutdown complete') &&
      lines.some((l) => l.request_id === requestId),
  );
  check(
    'logs never contain the database password, the geocoding key or an OSRM service URL',
    !`${logs.stdout}${logs.stderr}`.includes(DB.password) &&
      !`${logs.stdout}${logs.stderr}`.includes(FAKE_GEOCODING.GEOCODING_API_KEY) &&
      !`${logs.stdout}${logs.stderr}`.includes('example.invalid'),
  );
}

function summary() {
  const sizeBytes = Number(dockerOk(['image', 'inspect', image, '--format', '{{.Size}}']));
  const text = [
    `container smoke test: ${results.length} checks passed`,
    `image ${image}, revision ${gitSha}, size ${(sizeBytes / 1e6).toFixed(1)} MB (as reported by docker image inspect)`,
  ].join('\n');
  console.log(`\n${text}`);
  if (process.env.GITHUB_STEP_SUMMARY) {
    appendFileSync(
      process.env.GITHUB_STEP_SUMMARY,
      `### Container smoke test\n\n${text.replace('\n', '\n\n')}\n`,
    );
  }
}

async function main() {
  if (!options['skip-build']) {
    console.log(`building ${image} (GIT_SHA=${gitSha}) ...`);
    const build = spawnSync(
      'docker',
      ['buildx', 'build', '--load', '--build-arg', `GIT_SHA=${gitSha}`, '-t', image, BACKEND_DIR],
      { stdio: 'inherit', timeout: 900_000 },
    );
    if (build.status !== 0) throw new Error(`docker buildx build exited ${build.status}`);
  }
  try {
    checkImage();
    await startDatabase();
    checkMigrations();
    checkAdminScript();
    checkVersionVariables();
    checkProductionGuards();
    await checkApi();
    summary();
  } finally {
    cleanup();
  }
}

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    cleanup();
    process.exit(130);
  });
}

try {
  await main();
} catch (err) {
  console.error(`\ncontainer smoke test FAILED: ${err.message}`);
  process.exitCode = 1;
}
