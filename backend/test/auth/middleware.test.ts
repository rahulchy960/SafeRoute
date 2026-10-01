// SPDX-License-Identifier: AGPL-3.0-only
import { Hono } from 'hono';
import { createMiddleware } from 'hono/factory';
import { beforeAll, describe, expect, it } from 'vitest';
import { AuthUnavailableError } from '../../src/modules/auth/errors.js';
import {
  authenticate,
  requireRole,
  requireUser,
  type UserEnv,
} from '../../src/modules/auth/middleware.js';
import { ROLES, type Role } from '../../src/modules/auth/roles.js';
import type { TokenVerifier } from '../../src/modules/auth/verifier.js';
import { requestId } from '../../src/middleware/request-id.js';
import { createLogger } from '../../src/lib/logger.js';
import { parseConfig } from '../../src/config.js';
import { accessLines, buildTestApp } from '../helpers.js';
import {
  createTestKeys,
  signToken,
  TEST_PHONE,
  TEST_UID,
  testVerifier,
  type TestKeys,
} from './tokens.js';

let keys: TestKeys;
let validToken: string;

beforeAll(async () => {
  keys = await createTestKeys();
  validToken = await signToken(keys);
});

/**
 * The production app (request-id, access log, error handlers) plus two test-only routes, since no
 * production route uses the middleware until P005b: `/test/authn` (authenticate) and `/test/user`
 * (requireUser, no database). The real verifier gets a local key resolver.
 */
function appWith(verifier: TokenVerifier | undefined) {
  const built = buildTestApp();
  built.app.get('/test/authn', authenticate(verifier), (c) =>
    c.json({ provider: c.get('identity').signInProvider }),
  );
  built.app.get('/test/user', requireUser({ verifier, db: undefined }), (c) => c.text('ok'));
  return built;
}

const appWithVerifier = (verifier?: TokenVerifier) => appWith(verifier ?? testVerifier(keys));

describe('authenticate / requireUser: rejected credentials → identical 401', () => {
  const cases: [string, () => Headers | Record<string, string>][] = [
    ['no Authorization header', () => ({})],
    ['wrong scheme', () => ({ Authorization: `Basic ${validToken}` })],
    ['scheme without token', () => ({ Authorization: 'Bearer' })],
    ['empty token', () => ({ Authorization: 'Bearer ' })],
    ['two spaces', () => ({ Authorization: `Bearer  ${validToken}` })],
    ['garbage token', () => ({ Authorization: 'Bearer garbage' })],
    [
      'duplicate Authorization headers',
      () => {
        const headers = new Headers();
        headers.append('Authorization', `Bearer ${validToken}`);
        headers.append('Authorization', `Bearer ${validToken}`);
        return headers;
      },
    ],
  ];

  it.each(cases)('%s', async (_name, headers) => {
    for (const path of ['/test/authn', '/test/user']) {
      const { app, lines, logs } = appWithVerifier();
      const res = await app.request(path, { headers: headers() });
      expect(res.status).toBe(401);
      expect(res.headers.get('www-authenticate')).toBe('Bearer');
      expect(res.headers.get('content-type')).toBe('application/problem+json');
      const body = (await res.json()) as Record<string, unknown>;
      expect(body).toEqual({
        type: 'about:blank',
        title: 'Unauthorized',
        status: 401,
        detail: 'Missing or invalid credentials.',
        code: 'unauthorized',
        requestId: res.headers.get('x-request-id'),
      });

      // One WARNING with the reason category only; the token never reaches logs.
      const warnings = logs().filter((line) => line.message === 'authentication failed');
      expect(warnings).toHaveLength(1);
      expect(Object.keys(warnings[0] ?? {}).sort()).toEqual([
        'auth_failure',
        'message',
        'request_id',
        'service',
        'severity',
        'timestamp',
        'version',
      ]);
      expect(lines.join('')).not.toContain(validToken.slice(0, 40));
      expect(lines.join('')).not.toContain('garbage');
    }
  });

  it('accepts a valid token, with the scheme in any letter case', async () => {
    const { app } = appWithVerifier();
    for (const scheme of ['Bearer', 'bearer', 'bEARER']) {
      const res = await app.request('/test/authn', {
        headers: { Authorization: `${scheme} ${validToken}` },
      });
      expect(res.status).toBe(200);
      expect(await res.json()).toEqual({ provider: 'phone' });
    }
  });

  it('requireUser without a database answers 503 db_not_configured after authenticating', async () => {
    const { app } = appWithVerifier();
    const res = await app.request('/test/user', {
      headers: { Authorization: `Bearer ${validToken}` },
    });
    expect(res.status).toBe(503);
    expect(await res.json()).toMatchObject({ code: 'db_not_configured' });
  });

  it('the access log keeps the P002 rules for a 401', async () => {
    const { app, logs } = appWithVerifier();
    await app.request('/test/user', { headers: { Authorization: `Bearer ${validToken}x` } });
    const [line] = accessLines(logs());
    expect(line).toMatchObject({
      severity: 'WARNING',
      method: 'GET',
      path: '/test/user',
      status: 401,
    });
  });

  it('never logs or returns the token, phone number or uid for a valid token', async () => {
    const { app, lines } = appWithVerifier();
    const res = await app.request('/test/user', {
      headers: { Authorization: `Bearer ${validToken}` },
    });
    const text = `${await res.text()}${lines.join('')}`;
    for (const secret of [validToken.slice(0, 40), TEST_PHONE, TEST_UID]) {
      expect(text).not.toContain(secret);
    }
  });
});

describe('verifier states → 503', () => {
  it('no verifier configured → auth_not_configured', async () => {
    const { app } = appWith(undefined);
    for (const path of ['/test/authn', '/test/user']) {
      const res = await app.request(path, { headers: { Authorization: `Bearer ${validToken}` } });
      expect(res.status).toBe(503);
      expect(await res.json()).toMatchObject({ code: 'auth_not_configured' });
    }
  });

  it('keys unavailable → auth_unavailable, not 401', async () => {
    const unavailable: TokenVerifier = {
      verify: () => Promise.reject(new AuthUnavailableError('JWKSTimeout')),
    };
    const { app, logs } = appWithVerifier(unavailable);
    const res = await app.request('/test/user', {
      headers: { Authorization: `Bearer ${validToken}` },
    });
    expect(res.status).toBe(503);
    expect(res.headers.get('www-authenticate')).toBeNull();
    expect(await res.json()).toMatchObject({ code: 'auth_unavailable' });
    expect(logs().find((line) => line.auth_failure === 'unavailable')).toMatchObject({
      severity: 'WARNING',
      cause: 'JWKSTimeout',
    });
  });

  it('an unexpected verifier error becomes a generic 500', async () => {
    const broken: TokenVerifier = { verify: () => Promise.reject(new Error('bug')) };
    const { app } = appWithVerifier(broken);
    const res = await app.request('/test/user', {
      headers: { Authorization: `Bearer ${validToken}` },
    });
    expect(res.status).toBe(500);
    expect(await res.json()).toMatchObject({ code: 'internal_error' });
  });
});

describe('requireRole (test-only route; no production route uses it before P018)', () => {
  function roleApp(role: string, required: Role) {
    const config = parseConfig({ NODE_ENV: 'test', LOG_LEVEL: 'error' });
    const logger = createLogger(config, { write: () => undefined });
    // Stands in for requireUser, which is covered against a real database in test/db/users.test.ts.
    const fakeUser = createMiddleware<UserEnv>(async (c, next) => {
      c.set('currentUser', { userId: '00000000-0000-4000-8000-000000000001', role, locale: 'en' });
      await next();
    });
    return new Hono<UserEnv>()
      .use('*', requestId(logger))
      .get('/test/role', fakeUser, requireRole(required), (c) => c.text('ok'));
  }

  const allowed: Record<string, Role[]> = {
    user: ['user'],
    moderator: ['user', 'moderator'],
    admin: ['user', 'moderator', 'admin'],
    superuser: [],
  };

  for (const [role, grants] of Object.entries(allowed)) {
    for (const required of ROLES) {
      const ok = grants.includes(required);
      it(`${role} ${ok ? 'passes' : 'fails'} requireRole('${required}')`, async () => {
        const res = await roleApp(role, required).request('/test/role');
        expect(res.status).toBe(ok ? 200 : 403);
        if (!ok) expect(await res.json()).toMatchObject({ code: 'forbidden' });
      });
    }
  }
});
