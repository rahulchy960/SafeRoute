// SPDX-License-Identifier: AGPL-3.0-only
import { createApp } from './app.js';
import type { Config } from './config.js';
import { createDb, databaseReadiness, type DbHandle } from './db/client.js';
import type { Logger } from './lib/logger.js';
import { FirebaseIdTokenVerifier } from './modules/auth/firebase-verifier.js';
import { createGeocoder } from './modules/search/providers/index.js';

/**
 * Wires the app's real dependencies from validated config. Used by server.ts and by the
 * deploy-readiness test, so both exercise the same code.
 *
 * Makes no network call: the pg pool connects on the first query and the verifier fetches
 * Google's keys on the first token. A cold start on Cloud Run therefore never waits for, or
 * fails because of, the database or Google.
 */
export function createRuntime(config: Config, logger: Logger) {
  // Without DATABASE_URL (local dev, unit tests) the API still runs; /health/ready reports 503.
  const database: DbHandle | undefined =
    config.DATABASE_URL === undefined
      ? undefined
      : createDb({ ...config, DATABASE_URL: config.DATABASE_URL }, logger);
  // One shared verifier, so its key cache is shared. Without FIREBASE_PROJECT_ID (dev/test only)
  // protected routes answer 503.
  const verifier =
    config.FIREBASE_PROJECT_ID === undefined
      ? undefined
      : new FirebaseIdTokenVerifier({ projectId: config.FIREBASE_PROJECT_ID });
  if (verifier === undefined) {
    logger.warn('FIREBASE_PROJECT_ID is not set; protected routes will answer 503');
  }
  // The key stays inside the adapter: it is not put on the app, the logger or any error.
  const geocoder =
    config.GEOCODING_API_KEY === undefined || config.GEOCODING_PROVIDER === undefined
      ? undefined
      : createGeocoder(config.GEOCODING_PROVIDER, config.GEOCODING_API_KEY, {
          timeoutMs: config.SEARCH_PROVIDER_TIMEOUT_MS,
        });
  if (geocoder === undefined) {
    logger.warn('GEOCODING_API_KEY is not set; search will answer 503');
  } else {
    logger.info({ geocoding_provider: geocoder.name }, 'geocoding provider configured');
  }
  const app = createApp({
    config,
    logger,
    ...(geocoder ? { geocoder } : {}),
    ...(database ? { readiness: databaseReadiness(database.db), db: database.db } : {}),
    ...(verifier ? { verifier } : {}),
  });
  return { app, database };
}
