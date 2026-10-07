// SPDX-License-Identifier: AGPL-3.0-only
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { GEOCODING_PROVIDERS, type GeocodingProviderName } from '../../src/config.js';
import { createGeocoder } from '../../src/modules/search/providers/index.js';
import { loadFixture } from './fixture.js';
import { buildReport, formatReport, runEvaluation } from './harness.js';

/**
 * `pnpm search:eval`: measures a geocoding provider against the committed fixture
 * (addendum v7.2 section E, ADR 0018). Run by Rahul on his own machine; never in CI.
 *
 * It reads GEOCODING_PROVIDER and GEOCODING_API_KEY from the environment (`pnpm search:eval`
 * loads backend/.env for you). It prints the aggregate table only. Per-query detail goes to a
 * git-ignored file. It never prints the key or a raw provider response.
 *
 * Exit code is always 0: this is a measurement, not a gate.
 */
export const DETAIL_DIR = join(import.meta.dirname, 'out');

export interface CliEnv {
  GEOCODING_PROVIDER?: string | undefined;
  GEOCODING_API_KEY?: string | undefined;
  SEARCH_EVAL_RPS?: string | undefined;
  SEARCH_PROVIDER_TIMEOUT_MS?: string | undefined;
}

export type Settings =
  | { ok: true; provider: GeocodingProviderName; apiKey: string; rps: number; timeoutMs: number }
  | { ok: false; reason: string };

/** Names what is missing; never echoes a value. */
export function readSettings(env: CliEnv): Settings {
  // Geoapify is the default, as in the API's config; name another adapter to compare it.
  const provider = env.GEOCODING_PROVIDER ?? 'geoapify';
  if (!GEOCODING_PROVIDERS.includes(provider as GeocodingProviderName)) {
    return {
      ok: false,
      reason: `GEOCODING_PROVIDER must be one of: ${GEOCODING_PROVIDERS.join(', ')}.`,
    };
  }
  const apiKey = env.GEOCODING_API_KEY;
  if (apiKey === undefined || apiKey.trim() === '') {
    return { ok: false, reason: 'GEOCODING_API_KEY is not set. Put it in backend/.env.' };
  }
  const rps = Number(env.SEARCH_EVAL_RPS ?? '1');
  if (!(rps > 0 && rps <= 3)) {
    return { ok: false, reason: 'SEARCH_EVAL_RPS must be a number above 0 and at most 3.' };
  }
  const timeoutMs = Number(env.SEARCH_PROVIDER_TIMEOUT_MS ?? '5000');
  if (!(timeoutMs >= 200 && timeoutMs <= 20_000)) {
    return { ok: false, reason: 'SEARCH_PROVIDER_TIMEOUT_MS must be between 200 and 20000.' };
  }
  return { ok: true, provider: provider as GeocodingProviderName, apiKey, rps, timeoutMs };
}

export async function main(env: CliEnv, print: (line: string) => void): Promise<void> {
  const settings = readSettings(env);
  if (!settings.ok) {
    print(`search evaluation not run: ${settings.reason}`);
    return;
  }
  const fixture = loadFixture();
  const provider = createGeocoder(settings.provider, settings.apiKey, {
    timeoutMs: settings.timeoutMs,
  });
  print(
    `running ${String(fixture.entries.length)} queries against "${settings.provider}" at ` +
      `${String(settings.rps)} request(s) per second ...`,
  );

  const outcomes = await runEvaluation({
    provider,
    entries: fixture.entries,
    requestsPerSecond: settings.rps,
    onProgress: (done, total) => {
      if (done % 10 === 0 || done === total) print(`  ${String(done)}/${String(total)}`);
    },
  });

  mkdirSync(DETAIL_DIR, { recursive: true });
  const detailFile = join(DETAIL_DIR, `detail-${settings.provider}.json`);
  writeFileSync(detailFile, `${JSON.stringify(outcomes, null, 2)}\n`, 'utf8');

  print('');
  print(formatReport(buildReport(settings.provider, outcomes)));
  print('Per-query detail (local only, git-ignored): test/search-eval/out/');
  print('Share the tables above. Do not share the detail file, the key or raw responses.');
}

if (process.argv[1] !== undefined && import.meta.filename === process.argv[1]) {
  try {
    await main(process.env, (line) => {
      console.log(line);
    });
  } catch {
    // No message: an unexpected error could quote a request URL, and the key is in it.
    console.log('search evaluation stopped: unexpected error (details withheld on purpose).');
  }
}
