import type { Logger } from './lib/logger.js';

/** Hono environment shared by the app, middleware and routes (typed `c.get` / `c.set`). */
export interface AppEnv {
  Variables: {
    /** Correlation ID for this request; also returned as the `X-Request-Id` header. */
    requestId: string;
    /** Child logger that stamps `request_id` on every line. */
    logger: Logger;
    /** Set by the not-found handler so the access log does not record the raw, unmatched path. */
    unmatchedRoute?: true;
  };
}
