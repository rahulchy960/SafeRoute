import { Hono } from 'hono';
import type { Config } from '../config.js';
import type { AppEnv } from '../types.js';

/**
 * Liveness: answers "is this process up and serving HTTP?". It must stay fast and must not call
 * the database or any provider, otherwise a slow dependency would make Cloud Run restart healthy
 * instances.
 *
 * TODO(P003+): add a separate readiness check (e.g. /health/ready) that pings Postgres, once a
 * database exists. Do not add dependency checks here.
 */
export function healthRoutes(config: Pick<Config, 'SERVICE_NAME' | 'APP_VERSION'>) {
  return new Hono<AppEnv>().get('/health', (c) => {
    c.header('Cache-Control', 'no-store');
    return c.json({
      status: 'ok',
      service: config.SERVICE_NAME,
      version: config.APP_VERSION,
      uptime_s: Math.floor(process.uptime()),
    });
  });
}
