import { createMiddleware } from 'hono/factory';
import type { Logger } from '../lib/logger.js';
import type { AppEnv } from '../types.js';

/**
 * A caller (load balancer, app, another service) may send its own ID so one action can be traced
 * across systems. The value is copied into logs and a response header, so only a short, plain
 * token is trusted: anything else (newlines that could forge log lines, spaces, very long values,
 * personal data pasted by mistake) is replaced by a fresh UUID.
 */
export const REQUEST_ID_PATTERN = /^[A-Za-z0-9._-]{8,64}$/;

export function requestId(baseLogger: Logger) {
  return createMiddleware<AppEnv>(async (c, next) => {
    const incoming = c.req.header('x-request-id');
    const id =
      incoming !== undefined && REQUEST_ID_PATTERN.test(incoming) ? incoming : crypto.randomUUID();

    c.set('requestId', id);
    c.set('logger', baseLogger.child({ request_id: id }));
    await next();
    c.header('X-Request-Id', id);
  });
}
