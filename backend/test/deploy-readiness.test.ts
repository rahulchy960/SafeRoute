// SPDX-License-Identifier: AGPL-3.0-only
import { afterEach, describe, expect, it, vi } from 'vitest';
import { parseConfig } from '../src/config.js';
import { createLogger } from '../src/lib/logger.js';
import { createRuntime } from '../src/runtime.js';

/**
 * R13 (P005): with production config the app starts without touching the network, so a Cloud Run
 * cold start never depends on Google or the database being reachable. These are also the
 * "Deployment smoke checks" in backend/README.md that P006 runs against the deployed service.
 */
describe('production-mode startup (deploy readiness for P006)', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('builds the app with real dependencies, makes no network call, and answers the smoke checks', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    const config = parseConfig({
      NODE_ENV: 'production',
      APP_VERSION: 'abc1234',
      GIT_SHA: 'abc1234',
      // Unroutable documentation address: any connection attempt would fail the checks below.
      DATABASE_URL: 'postgres://fake-user:fake-pw@192.0.2.1:5432/fake_db',
      FIREBASE_PROJECT_ID: 'example-staging-1',
      GEOCODING_API_KEY: 'fake-geocoding-key-for-tests',
      GEOCODING_PROVIDER: 'geoapify',
    });
    const logger = createLogger(config, { write: () => undefined });
    const { app, database } = createRuntime(config, logger);

    try {
      const health = await app.request('/health');
      expect(health.status).toBe(200);
      expect(await health.json()).toMatchObject({ status: 'ok', version: 'abc1234' });

      const me = await app.request('/v1/me');
      expect(me.status).toBe(401);
      expect(me.headers.get('www-authenticate')).toBe('Bearer');
      expect(me.headers.get('x-request-id')).toBeTruthy();

      const search = await app.request('/v1/search?q=station');
      expect(search.status).toBe(401);
      expect(search.headers.get('www-authenticate')).toBe('Bearer');

      expect(fetchSpy).not.toHaveBeenCalled();
      expect(database?.pool.totalCount).toBe(0);
    } finally {
      await database?.close();
    }
  });
});
