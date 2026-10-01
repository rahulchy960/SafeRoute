// SPDX-License-Identifier: AGPL-3.0-only
import { createRoute, OpenAPIHono } from '@hono/zod-openapi';
import { errorResponses, headerRef } from '../../contract/responses.js';
import { problemResponse } from '../../lib/problem.js';
import type { AppEnv } from '../../types.js';
import { authenticate, requireUser, type AuthDeps } from '../auth/middleware.js';
import { BootstrapMeRequestSchema, MeSchema } from './schema.js';
import { bootstrapUser, toMe } from './service.js';

const meResponseHeaders = {
  'Cache-Control': headerRef('CacheControlNoStore'),
  'X-Request-Id': headerRef('RequestId'),
};

/**
 * Route definitions. Middleware is attached in `userRoutes` (it needs the shared verifier and
 * database), so these objects describe the contract only.
 *
 * No Idempotency-Key: bootstrap is naturally idempotent (keyed by the token's uid), so a retry
 * after a lost response returns 200 with the same account.
 */
export const bootstrapMeRoute = createRoute({
  method: 'post',
  path: '/v1/me/bootstrap',
  operationId: 'bootstrapMe',
  tags: ['me'],
  summary: "Create the signed-in user's account if needed",
  description:
    "Call after every sign-in. Creates the account for the token's Firebase user (201) or " +
    'returns the existing one unchanged (200); safe to retry. The phone number comes from the ' +
    'verified token, never from the body. The client must record DPDP consent before calling ' +
    'this. Errors: `phone_already_registered` (409) when another account holds the number, ' +
    '`account_deleted` (403).',
  security: [{ firebaseBearer: [] }],
  request: {
    body: {
      required: false,
      content: { 'application/json': { schema: BootstrapMeRequestSchema } },
    },
  },
  responses: {
    200: {
      description: 'The account already existed; returned unchanged.',
      headers: meResponseHeaders,
      content: { 'application/json': { schema: MeSchema } },
    },
    201: {
      description: 'The account was created.',
      headers: meResponseHeaders,
      content: { 'application/json': { schema: MeSchema } },
    },
    ...errorResponses(400, 401, 403, 409, 500, 503),
  },
});

export const getMeRoute = createRoute({
  method: 'get',
  path: '/v1/me',
  operationId: 'getMe',
  tags: ['me'],
  summary: "Get the signed-in user's account",
  description:
    'Errors: `bootstrap_required` (403) before POST /v1/me/bootstrap, `account_deleted` (403).',
  security: [{ firebaseBearer: [] }],
  responses: {
    200: {
      description: 'The account.',
      headers: meResponseHeaders,
      content: { 'application/json': { schema: MeSchema } },
    },
    ...errorResponses(401, 403, 500, 503),
  },
});

export function userRoutes(deps: AuthDeps) {
  const { db } = deps;
  return new OpenAPIHono<AppEnv>()
    .openapi({ ...bootstrapMeRoute, middleware: authenticate(deps.verifier) }, async (c) => {
      c.header('Cache-Control', 'no-store');
      if (db === undefined) {
        return problemResponse(
          c,
          503,
          'db_not_configured',
          'No database is configured.',
          c.get('requestId'),
        );
      }
      const body = c.req.valid('json');
      const identity = c.get('identity');
      const { created, user } = await bootstrapUser(db, {
        firebaseUid: identity.firebaseUid,
        phoneE164: identity.phoneE164,
        // Schema defaults don't apply when the body is omitted, so default here.
        locale: body.locale ?? 'en',
        displayName: body.displayName,
      });
      if (created) c.get('logger').info({ user_id: user.id }, 'user created');
      return c.json(toMe(user), created ? 201 : 200);
    })
    .openapi({ ...getMeRoute, middleware: requireUser(deps) }, (c) => {
      c.header('Cache-Control', 'no-store');
      return c.json(toMe(c.get('userRecord')), 200);
    });
}
