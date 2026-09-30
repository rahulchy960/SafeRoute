import { createMiddleware } from 'hono/factory';
import { routePath } from 'hono/route';
import type { AppEnv } from '../types.js';

/**
 * One log line per request: request_id (from the child logger), method, path, status, duration_ms.
 *
 * `path` is the matched route pattern (e.g. `/v1/sos/:id`), not the URL. The URL can carry personal
 * data or capability tokens (share links, `?lat=&lng=`), and Plan v7 §12.2 forbids tokens and
 * locations in logs. For the same reason headers, bodies, query strings and client IPs are never
 * logged.
 */
export const accessLog = createMiddleware<AppEnv>(async (c, next) => {
  const start = performance.now();
  let status = 500;
  try {
    await next();
    status = c.res.status;
  } finally {
    const fields = {
      method: c.req.method,
      // After next() the route index points at the deepest handler that ran.
      path: c.get('unmatchedRoute') ? '(unmatched)' : routePath(c),
      status,
      duration_ms: Math.round((performance.now() - start) * 10) / 10,
    };
    const log = c.get('logger');
    if (status >= 500) log.error(fields, 'request completed');
    else if (status >= 400) log.warn(fields, 'request completed');
    else log.info(fields, 'request completed');
  }
});
