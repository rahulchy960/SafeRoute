import { Hono } from 'hono';
import { HTTPException } from 'hono/http-exception';
import type { Config } from './config.js';
import type { Logger } from './lib/logger.js';
import { AppError, problemResponse } from './lib/problem.js';
import { accessLog } from './middleware/access-log.js';
import { requestId } from './middleware/request-id.js';
import { healthRoutes } from './routes/health.js';
import type { AppEnv } from './types.js';

export interface AppDeps {
  config: Config;
  logger: Logger;
}

/**
 * Builds the HTTP app without starting a server, so tests can call `app.request()` directly.
 * The app holds no per-instance state: Cloud Run can run any number of copies side by side.
 *
 * Order: request-id → access-log → routes → notFound / onError. The error and not-found handlers
 * run inside the middleware chain, so their responses still get an access-log line and the
 * `X-Request-Id` header.
 */
export function createApp({ config, logger }: AppDeps) {
  const app = new Hono<AppEnv>();

  app.use('*', requestId(logger));
  app.use('*', accessLog);

  app.route('/', healthRoutes(config));

  app.notFound((c) => {
    c.set('unmatchedRoute', true);
    return problemResponse(
      c,
      404,
      'not_found',
      'No route matches this request.',
      c.get('requestId'),
    );
  });

  app.onError((err, c) => {
    const log = c.get('logger');
    const id = c.get('requestId');

    if (err instanceof AppError) {
      if (err.status >= 500) log.error({ err, code: err.code }, 'request failed');
      return problemResponse(c, err.status, err.code, err.detail, id);
    }
    if (err instanceof HTTPException && err.status < 500) {
      // Raised by Hono's built-in helpers (e.g. malformed input); the message is Hono's own text.
      return problemResponse(c, err.status, 'http_error', err.message, id);
    }

    // Unexpected: full error and stack go to the server log only; the client gets a generic body.
    log.error({ err }, 'unhandled error');
    return problemResponse(c, 500, 'internal_error', 'An unexpected error occurred.', id);
  });

  return app;
}

export type App = ReturnType<typeof createApp>;
