// SPDX-License-Identifier: AGPL-3.0-only
import { createRemoteJWKSet, customFetch, type JWTVerifyGetKey } from 'jose';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { AuthError, AuthUnavailableError } from '../../src/modules/auth/errors.js';
import {
  FIREBASE_JWKS_URL,
  FirebaseIdTokenVerifier,
  REMOTE_JWKS_OPTIONS,
} from '../../src/modules/auth/firebase-verifier.js';
import {
  createTestKeys,
  hs256Token,
  nowSeconds,
  phoneClaims,
  signToken,
  TEST_PHONE,
  TEST_PROJECT_ID,
  TEST_UID,
  testVerifier,
  unsignedToken,
  type TestKeys,
} from './tokens.js';

let keys: TestKeys;
let otherKeys: TestKeys;
let verifier: FirebaseIdTokenVerifier;

beforeAll(async () => {
  keys = await createTestKeys();
  otherKeys = await createTestKeys();
  verifier = testVerifier(keys);
});

async function rejection(promise: Promise<unknown>): Promise<unknown> {
  try {
    await promise;
  } catch (err) {
    return err;
  }
  throw new Error('expected the promise to reject');
}

async function expectAuthError(token: string, reason: AuthError['reason']) {
  const err = await rejection(verifier.verify(token));
  expect(err).toBeInstanceOf(AuthError);
  expect((err as AuthError).reason).toBe(reason);
  // Nothing from the token leaks into the error.
  expect((err as Error).message).not.toContain(TEST_PHONE);
  expect((err as Error).message).not.toContain(TEST_UID);
}

describe('FirebaseIdTokenVerifier', () => {
  it('accepts a valid phone-auth token and returns uid and phone', async () => {
    const identity = await verifier.verify(await signToken(keys));
    expect(identity).toMatchObject({
      firebaseUid: TEST_UID,
      phoneE164: TEST_PHONE,
      signInProvider: 'phone',
    });
    expect(identity.expiresAt.getTime()).toBeGreaterThan(identity.issuedAt.getTime());
  });

  it('accepts a token issued within the 10 s clock tolerance', async () => {
    const now = nowSeconds();
    const token = await signToken(keys, {
      claims: phoneClaims({ iat: now + 5, auth_time: now + 5 }),
    });
    await expect(verifier.verify(token)).resolves.toMatchObject({ firebaseUid: TEST_UID });
  });

  describe('rejects as AuthError (401)', () => {
    const now = nowSeconds();
    it.each([
      ['expired', { exp: now - 60, iat: now - 3700 }, 'expired'],
      ['wrong issuer', { iss: 'https://securetoken.google.com/some-other-project' }, 'invalid'],
      ['issuer of another provider', { iss: 'https://accounts.google.com' }, 'invalid'],
      ['wrong audience', { aud: 'some-other-project' }, 'invalid'],
      [
        'sign_in_provider password',
        { firebase: { sign_in_provider: 'password' } },
        'wrong_provider',
      ],
      [
        'sign_in_provider google.com',
        { firebase: { sign_in_provider: 'google.com' } },
        'wrong_provider',
      ],
      [
        'sign_in_provider anonymous',
        { firebase: { sign_in_provider: 'anonymous' } },
        'wrong_provider',
      ],
      ['no firebase claim', { firebase: undefined }, 'wrong_provider'],
      ['missing phone_number', { phone_number: undefined }, 'no_phone'],
      ['malformed phone_number', { phone_number: '9876500000' }, 'no_phone'],
      ['phone_number with a leading zero', { phone_number: '+0910000000001' }, 'no_phone'],
      ['empty sub', { sub: '' }, 'invalid'],
      ['sub longer than 128 characters', { sub: 'u'.repeat(129) }, 'invalid'],
      ['missing sub', { sub: undefined }, 'invalid'],
      ['missing auth_time', { auth_time: undefined }, 'invalid'],
      ['auth_time in the future', { auth_time: now + 600 }, 'invalid'],
      ['iat in the future', { iat: now + 600 }, 'invalid'],
    ] as const)('%s', async (_name, overrides, reason) => {
      await expectAuthError(await signToken(keys, { claims: phoneClaims(overrides) }), reason);
    });

    it('accepts a 128-character sub (the limit)', async () => {
      const token = await signToken(keys, { claims: phoneClaims({ sub: 'u'.repeat(128) }) });
      await expect(verifier.verify(token)).resolves.toMatchObject({ firebaseUid: 'u'.repeat(128) });
    });

    it('signed by another key with the same kid', async () => {
      await expectAuthError(await signToken(keys, { key: otherKeys.privateKey }), 'invalid');
    });

    it('unknown kid', async () => {
      await expectAuthError(await signToken(keys, { kid: 'forged-kid' }), 'invalid');
    });

    it('missing kid', async () => {
      await expectAuthError(await signToken(keys, { kid: null }), 'malformed');
    });

    it('alg "none" (unsigned, as the Firebase emulator produces)', async () => {
      await expectAuthError(unsignedToken(), 'invalid');
    });

    it('HS256 with a shared secret', async () => {
      await expectAuthError(await hs256Token(), 'invalid');
    });

    it('longer than 4096 characters, before any parsing', async () => {
      const resolver = vi.fn<JWTVerifyGetKey>();
      const strict = new FirebaseIdTokenVerifier({
        projectId: TEST_PROJECT_ID,
        keyResolver: resolver,
      });
      const err = await rejection(strict.verify(`${await signToken(keys)}${'A'.repeat(4096)}`));
      expect((err as AuthError).reason).toBe('malformed');
      expect(resolver).not.toHaveBeenCalled();
    });

    it.each([
      ['empty string', ''],
      ['garbage', 'garbage'],
      ['three garbage segments', 'a.b.c'],
      ['two segments', 'eyJhbGciOiJSUzI1NiJ9.e30'],
    ])('%s', async (_name, token) => {
      await expectAuthError(token, 'malformed');
    });
  });

  describe('key problems → AuthUnavailableError (503, client retries)', () => {
    it('resolver throws a network error', async () => {
      const failing = new FirebaseIdTokenVerifier({
        projectId: TEST_PROJECT_ID,
        keyResolver: () => Promise.reject(new TypeError('fetch failed')),
      });
      const err = await rejection(failing.verify(await signToken(keys)));
      expect(err).toBeInstanceOf(AuthUnavailableError);
      expect((err as AuthUnavailableError).causeName).toBe('TypeError');
    });

    /** The real remote key set with an injected fetch (jose's `customFetch` option). */
    function remoteVerifier(fetchImpl: typeof fetch, timeoutDuration = 3_000) {
      const keyResolver = createRemoteJWKSet(new URL(FIREBASE_JWKS_URL), {
        ...REMOTE_JWKS_OPTIONS,
        timeoutDuration,
        [customFetch]: fetchImpl,
      });
      return new FirebaseIdTokenVerifier({ projectId: TEST_PROJECT_ID, keyResolver });
    }

    it('key fetch times out', async () => {
      const hanging: typeof fetch = (_url, init) =>
        new Promise((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => {
            reject(init.signal?.reason as Error);
          });
        });
      const err = await rejection(remoteVerifier(hanging, 50).verify(await signToken(keys)));
      expect(err).toBeInstanceOf(AuthUnavailableError);
      expect((err as AuthUnavailableError).causeName).toBe('JWKSTimeout');
    });

    it('key endpoint answers 500', async () => {
      const failing: typeof fetch = () => Promise.resolve(new Response('oops', { status: 500 }));
      const err = await rejection(remoteVerifier(failing).verify(await signToken(keys)));
      expect(err).toBeInstanceOf(AuthUnavailableError);
    });

    it('key endpoint returns a malformed JWKS', async () => {
      const malformed: typeof fetch = () => Promise.resolve(Response.json({ keys: 'nope' }));
      const err = await rejection(remoteVerifier(malformed).verify(await signToken(keys)));
      expect(err).toBeInstanceOf(AuthUnavailableError);
      expect((err as AuthUnavailableError).causeName).toBe('JWKSInvalid');
    });
  });

  describe('forged kid does not hammer Google (jose cooldown, 30 s)', () => {
    afterEach(() => {
      vi.useRealTimers();
    });

    it('refetches at most once per cooldown window', async () => {
      vi.useFakeTimers({ toFake: ['Date'] });
      let fetches = 0;
      const counting: typeof fetch = () => {
        fetches += 1;
        return Promise.resolve(Response.json(keys.jwks));
      };
      const keyResolver = createRemoteJWKSet(new URL(FIREBASE_JWKS_URL), {
        ...REMOTE_JWKS_OPTIONS,
        [customFetch]: counting,
      });
      const remote = new FirebaseIdTokenVerifier({ projectId: TEST_PROJECT_ID, keyResolver });

      await remote.verify(await signToken(keys));
      expect(fetches).toBe(1);

      for (let i = 0; i < 20; i += 1) {
        const err = await rejection(
          remote.verify(await signToken(keys, { kid: `forged-${String(i)}` })),
        );
        expect((err as AuthError).reason).toBe('invalid');
      }
      expect(fetches).toBe(1);

      // After the cooldown one forged kid may trigger one refetch, then the cooldown applies again.
      vi.setSystemTime(Date.now() + REMOTE_JWKS_OPTIONS.cooldownDuration + 1_000);
      for (let i = 0; i < 5; i += 1) {
        await rejection(remote.verify(await signToken(keys, { kid: 'forged-again' })));
      }
      expect(fetches).toBe(2);

      // Valid tokens keep working from the cache.
      await expect(remote.verify(await signToken(keys))).resolves.toMatchObject({
        firebaseUid: TEST_UID,
      });
      expect(fetches).toBe(2);
    });
  });
});
