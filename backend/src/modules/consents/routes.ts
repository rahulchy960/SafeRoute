// SPDX-License-Identifier: AGPL-3.0-only
import { createRoute, OpenAPIHono } from '@hono/zod-openapi';
import { errorResponses, headerRef } from '../../contract/responses.js';
import { problemResponse } from '../../lib/problem.js';
import type { AppEnv } from '../../types.js';
import { requireUser, type AuthDeps } from '../auth/middleware.js';
import {
  ConsentListSchema,
  ConsentPurposeParamSchema,
  ConsentSchema,
  SetConsentRequestSchema,
} from './schema.js';
import { listLatestConsents, setConsent } from './service.js';

const consentResponseHeaders = {
  'Cache-Control': headerRef('CacheControlNoStore'),
  'X-Request-Id': headerRef('RequestId'),
};

export const getMyConsentsRoute = createRoute({
  method: 'get',
  path: '/v1/me/consents',
  operationId: 'getMyConsents',
  tags: ['me'],
  summary: "List the signed-in user's consents",
  description:
    'The latest decision for each purpose the user has decided on. A purpose that was never ' +
    'asked is absent; treat it as not granted.',
  security: [{ firebaseBearer: [] }],
  responses: {
    200: {
      description: 'The current consents.',
      headers: consentResponseHeaders,
      content: { 'application/json': { schema: ConsentListSchema } },
    },
    ...errorResponses(401, 403, 500, 503),
  },
});

/**
 * No Idempotency-Key: the call is naturally idempotent. Repeating the same decision under the
 * same notice version writes nothing and returns the existing state.
 */
export const setMyConsentRoute = createRoute({
  method: 'put',
  path: '/v1/me/consents/{purpose}',
  operationId: 'setMyConsent',
  tags: ['me'],
  summary: 'Grant or withdraw consent for one purpose',
  description:
    'Records the decision and returns the latest state; safe to retry. Ask just-in-time, when ' +
    'the feature that needs the purpose is first used. Errors: `validation_error` (400) for an ' +
    'unknown purpose; `account_deletion_required` (409) when withdrawing `account_core`, which ' +
    'can only be withdrawn by deleting the account. **Withdrawing `sos_alerts` deletes all of ' +
    "the user's emergency contacts**, and their opt-out links stop working, in the same step; " +
    'it cannot be undone.',
  security: [{ firebaseBearer: [] }],
  request: {
    params: ConsentPurposeParamSchema,
    body: {
      required: true,
      content: { 'application/json': { schema: SetConsentRequestSchema } },
    },
  },
  responses: {
    200: {
      description: 'The latest decision for this purpose.',
      headers: consentResponseHeaders,
      content: { 'application/json': { schema: ConsentSchema } },
    },
    ...errorResponses(400, 401, 403, 409, 500, 503),
  },
});

export function consentRoutes(deps: AuthDeps) {
  const { db } = deps;
  const dbNotConfigured = (c: Parameters<typeof problemResponse>[0], requestId: string) =>
    problemResponse(c, 503, 'db_not_configured', 'No database is configured.', requestId);

  return new OpenAPIHono<AppEnv>()
    .openapi({ ...getMyConsentsRoute, middleware: requireUser(deps) }, async (c) => {
      c.header('Cache-Control', 'no-store');
      if (db === undefined) return dbNotConfigured(c, c.get('requestId'));
      const items = await listLatestConsents(db, c.get('currentUser').userId);
      return c.json({ items }, 200);
    })
    .openapi({ ...setMyConsentRoute, middleware: requireUser(deps) }, async (c) => {
      c.header('Cache-Control', 'no-store');
      if (db === undefined) return dbNotConfigured(c, c.get('requestId'));
      const { purpose } = c.req.valid('param');
      const body = c.req.valid('json');
      const { changed, consent } = await setConsent(db, c.get('currentUser').userId, {
        purpose,
        ...body,
      });
      // The request logger already carries user_id; the status and notice version stay out.
      if (changed) c.get('logger').info({ purpose }, 'consent changed');
      return c.json(consent, 200);
    });
}
