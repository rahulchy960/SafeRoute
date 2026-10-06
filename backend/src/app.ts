import { OpenAPIHono } from '@hono/zod-openapi';
import { HTTPException } from 'hono/http-exception';
import type { Config } from './config.js';
import { registerContractComponents } from './contract/components.js';
import { validationHook } from './contract/validation.js';
import type { Db } from './db/client.js';
import type { Logger } from './lib/logger.js';
import { AppError, problemResponse } from './lib/problem.js';
import { accessLog } from './middleware/access-log.js';
import { requestId } from './middleware/request-id.js';
import type { TokenVerifier } from './modules/auth/verifier.js';
import { consentRoutes } from './modules/consents/routes.js';
import { searchRoutes } from './modules/search/routes.js';
import type { GeocoderProvider } from './modules/search/types.js';
import { userRoutes } from './modules/users/routes.js';
import { healthRoutes } from './routes/health.js';
import { readyRoutes, type ReadinessCheck } from './routes/ready.js';
import type { AppEnv } from './types.js';

export interface AppDeps {
  config: Config;
  logger: Logger;
  /** Database probe for GET /health/ready; omitted when no DATABASE_URL is configured. */
  readiness?: ReadinessCheck;
  /**
   * Firebase ID-token verifier, one shared instance (its key cache is per instance). Omitted when
   * FIREBASE_PROJECT_ID is not set (dev/test only): protected routes then answer 503.
   */
  verifier?: TokenVerifier;
  /** Database for the /v1 modules; omitted when no DATABASE_URL is configured. */
  db?: Db;
  /**
   * Geocoding provider behind GET /v1/search. Omitted when GEOCODING_API_KEY is not set (dev/test
   * only): the route then answers 503 `search_not_configured`.
   */
  geocoder?: GeocoderProvider;
}

/**
 * Builds the HTTP app without starting a server, so tests can call `app.request()` directly.
 * The app holds no per-instance state: Cloud Run can run any number of copies side by side.
 *
 * Order: request-id → access-log → routes → notFound / onError. The error and not-found handlers
 * run inside the middleware chain, so their responses still get an access-log line and the
 * `X-Request-Id` header.
 *
 * Every route is declared with `createRoute` on an `OpenAPIHono` router, so it appears in the
 * generated contract (contracts/openapi.json, see src/contract/openapi.ts). `validationHook`
 * applies to all routers mounted below, because OpenAPIHono resolves the default hook through
 * the parent app.
 */
export function createApp({ config, logger, readiness, verifier, db, geocoder }: AppDeps) {
  const app = new OpenAPIHono<AppEnv>({ defaultHook: validationHook });
  registerContractComponents(app);

  app.use('*', requestId(logger));
  app.use('*', accessLog);

  app.route('/', healthRoutes(config));
  app.route('/', readyRoutes(readiness));
  app.route('/', userRoutes({ verifier, db }));
  app.route('/', consentRoutes({ verifier, db }));
  app.route(
    '/',
    searchRoutes({ verifier, db, geocoder, globalDailyLimit: config.SEARCH_GLOBAL_DAILY_LIMIT }),
  );

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
