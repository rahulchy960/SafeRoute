import { createApp } from '../src/app.js';
import { parseConfig, type Config } from '../src/config.js';
import { createLogger } from '../src/lib/logger.js';

export type LogLine = Record<string, unknown>;

/** Builds an app whose logger writes JSON lines into memory instead of stdout. */
export function buildTestApp(overrides: Partial<Record<keyof Config, string>> = {}) {
  const lines: string[] = [];
  const config = parseConfig({ NODE_ENV: 'test', LOG_LEVEL: 'debug', ...overrides });
  const logger = createLogger(config, { write: (chunk: string) => void lines.push(chunk) });
  const app = createApp({ config, logger });
  const logs = (): LogLine[] => lines.map((line) => JSON.parse(line) as LogLine);
  return { app, config, lines, logs };
}

export function accessLines(logs: LogLine[]): LogLine[] {
  return logs.filter((line) => line.message === 'request completed');
}
