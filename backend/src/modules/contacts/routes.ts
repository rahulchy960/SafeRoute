// SPDX-License-Identifier: AGPL-3.0-only
import { createRoute, OpenAPIHono, z } from '@hono/zod-openapi';
import { errorResponses, headerRef } from '../../contract/responses.js';
import { problemResponse } from '../../lib/problem.js';
import { createRateLimiter } from '../../lib/rate-limit.js';
import type { AppEnv } from '../../types.js';
import { requireUser, type AuthDeps } from '../auth/middleware.js';
import { OPT_OUT_PAGE_CSP, OPT_OUT_PAGE_HTML } from './optout-page.js';
import {
  ContactIdParamSchema,
  ContactInviteSchema,
  ContactListSchema,
  ContactSchema,
  CreateContactRequestSchema,
  MAX_CONTACTS,
  normalizeContactName,
  normalizeContactPhone,
  OptOutRequestSchema,
  OptOutResultSchema,
  RenameContactRequestSchema,
} from './schema.js';
import {
  confirmInvite,
  ContactRateLimitError,
  createContact,
  createInvite,
  deleteContact,
  listContacts,
  optOutByToken,
  renameContact,
  takeOptOutToken,
  takeUserTokens,
} from './service.js';

const noStoreHeaders = {
  'Cache-Control': headerRef('CacheControlNoStore'),
  'X-Request-Id': headerRef('RequestId'),
};

const NO_MESSAGE = 'The server sends no message to a contact: no SMS, push or call.';

export const listContactsRoute = createRoute({
  method: 'get',
  path: '/v1/contacts',
  operationId: 'listContacts',
  tags: ['contacts'],
  summary: "List the signed-in user's emergency contacts",
  description:
    'The server is the source of truth; the app keeps a copy for use without a network. ' +
    'Removed contacts are not listed. Opted-out contacts are listed with `optedOutAt` set and ' +
    'must never be alerted.',
  security: [{ firebaseBearer: [] }],
  responses: {
    200: {
      description: 'The contacts, oldest first.',
      headers: noStoreHeaders,
      content: { 'application/json': { schema: ContactListSchema } },
    },
    ...errorResponses(401, 403, 500, 503),
  },
});

export const createContactRoute = createRoute({
  method: 'post',
  path: '/v1/contacts',
  operationId: 'createContact',
  tags: ['contacts'],
  summary: 'Add an emergency contact',
  description:
    `Adds a contact, up to ${String(MAX_CONTACTS)} per user. Needs the \`sos_alerts\` consent: ` +
    'the latest decision must be `granted`, otherwise 403 `consent_required` and nothing is ' +
    `stored. ${NO_MESSAGE} Errors (409): \`invalid_contact\` (the user's own number), ` +
    '`contact_exists` (already a contact), `contact_opted_out` (this number opted out and ' +
    'cannot be added again), `contact_limit_reached`. No `Idempotency-Key`: a number can be ' +
    'added only once, so a retry that reached the server the first time gets `contact_exists`; ' +
    'read the list again and continue. `rate_limited` (429) carries `Retry-After`.',
  security: [{ firebaseBearer: [] }],
  request: {
    body: {
      required: true,
      content: { 'application/json': { schema: CreateContactRequestSchema } },
    },
  },
  responses: {
    201: {
      description: 'The new contact.',
      headers: noStoreHeaders,
      content: { 'application/json': { schema: ContactSchema } },
    },
    ...errorResponses(400, 401, 403, 409, 429, 500, 503),
  },
});

export const renameContactRoute = createRoute({
  method: 'patch',
  path: '/v1/contacts/{id}',
  operationId: 'renameContact',
  tags: ['contacts'],
  summary: 'Rename an emergency contact',
  description:
    'Changes the name only; the number cannot be changed (remove the contact and add it ' +
    "again). Another user's contact, or a removed one, is `not_found` (404).",
  security: [{ firebaseBearer: [] }],
  request: {
    params: ContactIdParamSchema,
    body: {
      required: true,
      content: { 'application/json': { schema: RenameContactRequestSchema } },
    },
  },
  responses: {
    200: {
      description: 'The contact with its new name.',
      headers: noStoreHeaders,
      content: { 'application/json': { schema: ContactSchema } },
    },
    ...errorResponses(400, 401, 403, 404, 500, 503),
  },
});

export const deleteContactRoute = createRoute({
  method: 'delete',
  path: '/v1/contacts/{id}',
  operationId: 'deleteContact',
  tags: ['contacts'],
  summary: 'Remove an emergency contact',
  description:
    'Deletes the contact and its opt-out links for good. Exception: for a contact that opted ' +
    'out, the server keeps the number (and nothing else) so that it cannot be added again; that ' +
    'record goes away with the account or when `sos_alerts` is withdrawn. ' +
    "Another user's contact is `not_found` (404).",
  security: [{ firebaseBearer: [] }],
  request: { params: ContactIdParamSchema },
  responses: {
    204: { description: 'Removed.', headers: noStoreHeaders },
    ...errorResponses(400, 401, 403, 404, 500, 503),
  },
});

export const createContactInviteRoute = createRoute({
  method: 'post',
  path: '/v1/contacts/{id}/invite',
  operationId: 'createContactInvite',
  tags: ['contacts'],
  summary: 'Create an opt-out link token for an invite SMS',
  description:
    'Returns a new opt-out token for this contact, once. The app builds the link ' +
    '`<API address>/c#<token>` (the token in the URL FRAGMENT) and opens the SMS app with a ' +
    `prefilled invite that the user sends from their own phone. ${NO_MESSAGE} Earlier tokens ` +
    'of the contact stay valid. Errors: `contact_opted_out` (409); `rate_limited` (429) when ' +
    'the contact already has 10 links (no `Retry-After`) or the user created too many links ' +
    'today (with `Retry-After`). Then call `confirmContactInvite` when the user says the SMS ' +
    'was sent.',
  security: [{ firebaseBearer: [] }],
  request: { params: ContactIdParamSchema },
  responses: {
    200: {
      description: 'The token for the link.',
      headers: noStoreHeaders,
      content: { 'application/json': { schema: ContactInviteSchema } },
    },
    ...errorResponses(400, 401, 403, 404, 409, 429, 500, 503),
  },
});

export const confirmContactInviteRoute = createRoute({
  method: 'post',
  path: '/v1/contacts/{id}/invite/confirm',
  operationId: 'confirmContactInvite',
  tags: ['contacts'],
  summary: 'Record that the user sent the invite SMS',
  description:
    'Sets `invitedAt` to now. It records what the user said, not a delivery: the server ' +
    'cannot see the SMS. Safe to repeat. Errors (409): `contact_opted_out`; `conflict` when ' +
    'no invite link was created for the contact.',
  security: [{ firebaseBearer: [] }],
  request: { params: ContactIdParamSchema },
  responses: {
    204: { description: 'Recorded.', headers: noStoreHeaders },
    ...errorResponses(400, 401, 403, 404, 409, 500, 503),
  },
});

export const getContactOptOutPageRoute = createRoute({
  method: 'get',
  path: '/c',
  operationId: 'getContactOptOutPage',
  tags: ['public'],
  summary: 'The opt-out page for emergency contacts',
  description:
    'Public by design: a contact has no account. A static HTML page in English and Bengali, ' +
    'the same for everyone, with no cookie and no external resource. The link in the invite ' +
    'SMS is `/c#<token>`: a browser never sends the fragment to a server, so this request ' +
    'carries no token. The page sends the token in the body of `optOutContact` only when the ' +
    'visitor taps "Opt out". Not for the app.',
  security: [],
  responses: {
    200: {
      description: 'The page.',
      headers: noStoreHeaders,
      content: { 'text/html': { schema: z.string() } },
    },
    ...errorResponses(500),
  },
});

export const optOutContactRoute = createRoute({
  method: 'post',
  path: '/v1/public/contacts/opt-out',
  operationId: 'optOutContact',
  tags: ['public'],
  summary: 'Opt out as an emergency contact',
  description:
    'Public by design: called by the opt-out page with the token from the invite SMS, in the ' +
    'request body. The contact is then never alerted and cannot be invited or added again by ' +
    'that user. Safe to repeat (200 again). A malformed, unknown or no longer active token ' +
    'gets the same `not_found` (404). One rate limit is shared by all callers (`rate_limited`, ' +
    '429, with `Retry-After`); nothing about the caller is stored or logged.',
  security: [],
  request: {
    body: { required: true, content: { 'application/json': { schema: OptOutRequestSchema } } },
  },
  responses: {
    200: {
      description: 'Opted out.',
      headers: noStoreHeaders,
      content: { 'application/json': { schema: OptOutResultSchema } },
    },
    ...errorResponses(400, 404, 429, 500, 503),
  },
});

/**
 * Emergency contacts (ADR 0024). Every mutation writes ONE info line with `action` only (the
 * request logger adds `request_id` and, on signed-in routes, `user_id`): never a name, a phone
 * number, a token or a contact id.
 */
export function contactRoutes(deps: AuthDeps) {
  const { db } = deps;
  const limiter = db === undefined ? undefined : createRateLimiter(db);
  const auth = requireUser(deps);

  type Ctx = Parameters<typeof problemResponse>[0];
  const dbNotConfigured = (c: Ctx, requestId: string) =>
    problemResponse(c, 503, 'db_not_configured', 'No database is configured.', requestId);
  /** A 429 from a bucket carries the wait; every other error goes to the app's error handler. */
  const limited = (c: Ctx, err: unknown, requestId: string) => {
    if (!(err instanceof ContactRateLimitError)) throw err;
    c.header('Retry-After', String(err.retryAfterSeconds));
    return problemResponse(c, err.status, err.code, err.detail, requestId);
  };

  return new OpenAPIHono<AppEnv>()
    .openapi({ ...listContactsRoute, middleware: auth }, async (c) => {
      c.header('Cache-Control', 'no-store');
      if (db === undefined) return dbNotConfigured(c, c.get('requestId'));
      const items = await listContacts(db, c.get('currentUser').userId);
      return c.json({ items, maxContacts: MAX_CONTACTS }, 200);
    })
    .openapi({ ...createContactRoute, middleware: auth }, async (c) => {
      c.header('Cache-Control', 'no-store');
      const requestId = c.get('requestId');
      if (db === undefined || limiter === undefined) return dbNotConfigured(c, requestId);
      const body = c.req.valid('json');
      // The schema already accepted both; normalising again yields the values to store.
      const name = normalizeContactName(body.name) ?? '';
      const phoneE164 = normalizeContactPhone(body.phone) ?? '';
      const user = c.get('userRecord');
      try {
        await takeUserTokens(limiter, user.id, ['createBurst', 'createDaily']);
      } catch (err) {
        return limited(c, err, requestId);
      }
      const contact = await createContact(db, user, { name, phoneE164 });
      c.get('logger').info({ action: 'contact.created' }, 'contact changed');
      return c.json(contact, 201);
    })
    .openapi({ ...renameContactRoute, middleware: auth }, async (c) => {
      c.header('Cache-Control', 'no-store');
      if (db === undefined) return dbNotConfigured(c, c.get('requestId'));
      const name = normalizeContactName(c.req.valid('json').name) ?? '';
      const contact = await renameContact(
        db,
        c.get('currentUser').userId,
        c.req.valid('param').id,
        name,
      );
      c.get('logger').info({ action: 'contact.renamed' }, 'contact changed');
      return c.json(contact, 200);
    })
    .openapi({ ...deleteContactRoute, middleware: auth }, async (c) => {
      c.header('Cache-Control', 'no-store');
      if (db === undefined) return dbNotConfigured(c, c.get('requestId'));
      await deleteContact(db, c.get('currentUser').userId, c.req.valid('param').id);
      c.get('logger').info({ action: 'contact.deleted' }, 'contact changed');
      return c.body(null, 204);
    })
    .openapi({ ...createContactInviteRoute, middleware: auth }, async (c) => {
      c.header('Cache-Control', 'no-store');
      const requestId = c.get('requestId');
      if (db === undefined || limiter === undefined) return dbNotConfigured(c, requestId);
      const userId = c.get('currentUser').userId;
      try {
        await takeUserTokens(limiter, userId, ['inviteDaily']);
      } catch (err) {
        return limited(c, err, requestId);
      }
      const optOutToken = await createInvite(db, userId, c.req.valid('param').id);
      c.get('logger').info({ action: 'contact.invite_created' }, 'contact changed');
      return c.json({ optOutToken }, 200);
    })
    .openapi({ ...confirmContactInviteRoute, middleware: auth }, async (c) => {
      c.header('Cache-Control', 'no-store');
      if (db === undefined) return dbNotConfigured(c, c.get('requestId'));
      await confirmInvite(db, c.get('currentUser').userId, c.req.valid('param').id);
      c.get('logger').info({ action: 'contact.invited' }, 'contact changed');
      return c.body(null, 204);
    })
    .openapi(getContactOptOutPageRoute, (c) => {
      c.header('Content-Security-Policy', OPT_OUT_PAGE_CSP);
      c.header('Cache-Control', 'no-store');
      c.header('X-Content-Type-Options', 'nosniff');
      c.header('Referrer-Policy', 'no-referrer');
      return c.html(OPT_OUT_PAGE_HTML, 200);
    })
    .openapi(optOutContactRoute, async (c) => {
      c.header('Cache-Control', 'no-store');
      const requestId = c.get('requestId');
      if (db === undefined || limiter === undefined) return dbNotConfigured(c, requestId);
      try {
        await takeOptOutToken(limiter);
      } catch (err) {
        return limited(c, err, requestId);
      }
      if (!(await optOutByToken(db, c.req.valid('json').token))) {
        // One answer for a malformed, unknown or removed token: nothing to tell them apart by.
        return problemResponse(c, 404, 'not_found', 'This link is not active.', requestId);
      }
      c.get('logger').info({ action: 'contact.opted_out' }, 'contact changed');
      return c.json({ optedOut: true }, 200);
    });
}
