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

const SAFE_NAME = /^[A-Za-z_$][A-Za-z0-9_$.]{0,59}$/;
const SAFE_CODE = /^[A-Za-z0-9_.-]{1,40}$/;
const MAX_FRAMES = 30;

/** What is kept of an error in a log line: see [safeError]. */
export interface SafeError {
  type: string;
  code?: string;
  stack_trace: string;
}

/**
 * The parts of an error that are safe to log and enough to find it (P014d2).
 *
 * An error's message is written by whatever failed and can quote what it was working on: a
 * value that would not parse, a row, a URL with its query. So the message is never logged.
 * What is kept: the error's class, its `code` when it is a short machine word (an AppError
 * code, a PostgreSQL SQLSTATE, a Node `ECONNRESET`), and the stack frames, which name files,
 * functions and lines only.
 *
 * `stack_trace` is rebuilt in the shape Node prints (`Type: text` and then the `at` lines), so
 * that Cloud Error Reporting recognises it and groups the same failure into one entry.
 */
export function safeError(err: unknown): SafeError {
  if (!(err instanceof Error)) {
    return { type: 'NonError', stack_trace: 'NonError: a value that is not an Error was thrown' };
  }
  const type = SAFE_NAME.test(err.name) ? err.name : 'Error';
  const rawCode = (err as { code?: unknown }).code;
  const code = typeof rawCode === 'string' && SAFE_CODE.test(rawCode) ? rawCode : undefined;
  // Node's stack is "Name: message" followed by the frames. The message can span lines and can
  // even contain a line that looks like a frame, so it is cut off as a whole first; only then
  // are the lines that end in "file:line:column" kept.
  const stack = err.stack ?? '';
  const start = stack.indexOf(err.message);
  const afterMessage =
    err.message !== '' && start >= 0 ? stack.slice(start + err.message.length) : stack;
  const frames = afterMessage
    .split('\n')
    .filter((line) => /^\s+at .+:\d+:\d+\)?$/.test(line))
    .slice(0, MAX_FRAMES);
  const head = `${type}: ${code ?? 'message not logged'}`;
  return {
    type,
    ...(code === undefined ? {} : { code }),
    stack_trace: [head, ...frames].join('\n'),
  };
}

/**
 * JSON logger for stdout. Cloud Run forwards stdout to Cloud Logging, which reads `severity`,
 * `message` and `timestamp` from each line. Always JSON here; `pnpm dev` pipes through pino-pretty
 * so production and development run the same code.
 *
 * Writes are synchronous so the last lines before a shutdown or crash are not lost.
 *
 * An `err` field is never written as it is: `formatters.log` replaces it with [safeError] and
 * adds `stack_trace` and `serviceContext`, the fields Cloud Error Reporting reads.
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
        log: (fields) => {
          if (!('err' in fields)) return fields;
          const { stack_trace, ...err } = safeError(fields.err);
          return {
            ...fields,
            err,
            stack_trace,
            serviceContext: { service: config.SERVICE_NAME, version: config.APP_VERSION },
          };
        },
      },
    },
    destination,
  );
}
