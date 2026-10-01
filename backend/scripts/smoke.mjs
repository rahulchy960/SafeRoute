// SPDX-License-Identifier: AGPL-3.0-only
/**
 * Deployment smoke checks (README "Deployment smoke checks"), against any running saferoute-api:
 *
 *   SMOKE_URL=https://<service url> node scripts/smoke.mjs --expect-version <git sha>
 *   node scripts/smoke.mjs --url http://127.0.0.1:8080 --expect-version <git sha>
 *
 * Checks, without any token:
 *   1. GET /health        → 200 and `version` equals --expect-version
 *   2. GET /health/ready  → 200 (the database answers)
 *   3. GET /v1/me         → 401 with `WWW-Authenticate: Bearer` and an X-Request-Id header
 *
 * A fresh Cloud Run revision may need a moment, so the whole set is retried with backoff until it
 * passes or --timeout-seconds (default 120) runs out. Exit code 0 = all passed, 1 = not.
 *
 * Prints status codes, the version and the request id only: never the URL (a Cloud Run URL
 * contains the project number) and never response bodies. Used by the deploy-staging workflow,
 * by container-smoke.mjs and by the rollback runbook. Plain Node, no dependencies.
 */
import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';

const REQUEST_TIMEOUT_MS = 10_000;

async function get(baseUrl, path) {
  return fetch(new URL(path, baseUrl), {
    redirect: 'manual',
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });
}

/**
 * Runs the three checks once. Returns one entry per check: `{ name, passed, detail }`.
 * A network error counts as a failed check, not as an exception.
 */
export async function runSmokeChecks(baseUrl, expectVersion) {
  const results = [];
  const check = async (name, run) => {
    try {
      results.push({ name, ...(await run()) });
    } catch (err) {
      // Error codes and names only (ECONNREFUSED, TimeoutError, …): a message could hold the URL.
      const cause = err instanceof Error ? err.cause : undefined;
      const reason = cause?.code ?? cause?.errors?.[0]?.code ?? err?.name ?? 'error';
      results.push({ name, passed: false, detail: `request failed (${reason})` });
    }
  };

  await check('GET /health → 200 and version matches', async () => {
    const response = await get(baseUrl, '/health');
    const version = response.status === 200 ? (await response.json()).version : undefined;
    return {
      passed: response.status === 200 && version === expectVersion,
      detail: `status ${response.status}, version ${version ?? 'n/a'}, expected ${expectVersion}`,
    };
  });

  await check('GET /health/ready → 200', async () => {
    const response = await get(baseUrl, '/health/ready');
    await response.arrayBuffer();
    return { passed: response.status === 200, detail: `status ${response.status}` };
  });

  await check(
    'GET /v1/me without a token → 401, WWW-Authenticate: Bearer, X-Request-Id',
    async () => {
      const response = await get(baseUrl, '/v1/me');
      await response.arrayBuffer();
      const challenge = response.headers.get('www-authenticate');
      const requestId = response.headers.get('x-request-id');
      return {
        passed: response.status === 401 && challenge === 'Bearer' && Boolean(requestId),
        detail: `status ${response.status}, www-authenticate ${challenge ?? 'missing'}, request id ${requestId ?? 'missing'}`,
      };
    },
  );

  return results;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Retries the check set with backoff (2 s, 4 s, 8 s, then every 15 s) until the deadline. */
export async function smokeWithRetry(baseUrl, expectVersion, { timeoutSeconds, log }) {
  const deadline = Date.now() + timeoutSeconds * 1000;
  let delayMs = 2_000;
  for (let attempt = 1; ; attempt++) {
    const results = await runSmokeChecks(baseUrl, expectVersion);
    const passed = results.every((result) => result.passed);
    for (const result of results) {
      log(`${result.passed ? 'ok  ' : 'FAIL'}  ${result.name}  (${result.detail})`);
    }
    if (passed) return true;
    if (Date.now() + delayMs > deadline) return false;
    log(`attempt ${attempt} failed; retrying in ${delayMs / 1000} s`);
    await sleep(delayMs);
    delayMs = Math.min(delayMs * 2, 15_000);
  }
}

async function main() {
  const { values } = parseArgs({
    options: {
      url: { type: 'string' },
      'expect-version': { type: 'string' },
      'timeout-seconds': { type: 'string', default: '120' },
    },
  });
  const url = values.url ?? process.env.SMOKE_URL;
  const expectVersion = values['expect-version'];
  const timeoutSeconds = Number(values['timeout-seconds']);
  if (!url || !expectVersion || !Number.isFinite(timeoutSeconds) || timeoutSeconds < 0) {
    console.error(
      'Usage: smoke.mjs --expect-version <git sha> [--url <base url> | SMOKE_URL=<base url>] [--timeout-seconds 120]',
    );
    return 2;
  }
  let baseUrl;
  try {
    baseUrl = new URL(url);
  } catch {
    console.error('The URL is not valid (not shown).');
    return 2;
  }

  const passed = await smokeWithRetry(baseUrl, expectVersion, { timeoutSeconds, log: console.log });
  console.log(passed ? 'smoke test passed' : 'smoke test FAILED');
  return passed ? 0 : 1;
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = await main();
}
