import { destination as pinoDestination, pino, type DestinationStream, type Logger } from 'pino';
import type { Config } from '../config.js';

export type { Logger };

/** pino level label → Cloud Logging `severity` (https://cloud.google.com/logging/docs/structured-logging). */
const SEVERITY: Record<string, string> = {
  trace: 'DEBUG',
  debug: 'DEBUG',
  info: 'INFO',
  warn: 'WARNING',
  error: 'ERROR',
  fatal: 'CRITICAL',
};

/**
 * JSON logger for stdout. Cloud Run forwards stdout to Cloud Logging, which reads `severity`,
 * `message` and `timestamp` from each line. Always JSON here; `pnpm dev` pipes through pino-pretty
 * so production and development run the same code.
 *
 * Writes are synchronous so the last lines before a shutdown or crash are not lost.
 */
export function createLogger(
  config: Pick<Config, 'LOG_LEVEL' | 'SERVICE_NAME' | 'APP_VERSION'>,
  destination: DestinationStream = pinoDestination({ dest: 1, sync: true }),
): Logger {
  return pino(
    {
      level: config.LOG_LEVEL,
      messageKey: 'message',
      base: { service: config.SERVICE_NAME, version: config.APP_VERSION },
      timestamp: () => `,"timestamp":"${new Date().toISOString()}"`,
      formatters: {
        level: (label) => ({ severity: SEVERITY[label] ?? 'DEFAULT' }),
      },
    },
    destination,
  );
}
