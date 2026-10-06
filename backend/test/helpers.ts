import { createApp, type AppDeps } from '../src/app.js';
import { parseConfig, type Config } from '../src/config.js';
import { createLogger } from '../src/lib/logger.js';
import type { ReadinessCheck } from '../src/routes/ready.js';

export type LogLine = Record<string, unknown>;

/**
 * Builds an app whose logger writes JSON lines into memory instead of stdout. `readiness` is the
 * injected database probe for GET /health/ready (omit it to simulate "no database configured").
 * `deps` passes the token verifier, database and geocoder (omit them to simulate "not
 * configured").
 */
export function buildTestApp(
  overrides: Partial<Record<keyof Config, string>> = {},
  readiness?: ReadinessCheck,
  deps: Pick<AppDeps, 'verifier' | 'db' | 'geocoder'> = {},
) {
  const lines: string[] = [];
  const config = parseConfig({ NODE_ENV: 'test', LOG_LEVEL: 'debug', ...overrides });
  const logger = createLogger(config, { write: (chunk: string) => void lines.push(chunk) });
  const app = createApp({ config, logger, ...(readiness ? { readiness } : {}), ...deps });
  const logs = (): LogLine[] => lines.map((line) => JSON.parse(line) as LogLine);
  return { app, config, lines, logs };
}

export function accessLines(logs: LogLine[]): LogLine[] {
  return logs.filter((line) => line.message === 'request completed');
}
