// SPDX-License-Identifier: AGPL-3.0-only
import {
  createLocalJWKSet,
  exportJWK,
  generateKeyPair,
  SignJWT,
  type CryptoKey,
  type JSONWebKeySet,
  type JWTPayload,
} from 'jose';
import { FirebaseIdTokenVerifier } from '../../src/modules/auth/firebase-verifier.js';

/**
 * Test tokens shaped like Firebase phone-auth ID tokens, signed with an RSA key generated for this
 * test run (never committed). The fake project `demo-saferoute` and numbers from +910000000001
 * keep real identities out of the repo.
 */
export const TEST_PROJECT_ID = 'demo-saferoute';
export const TEST_ISSUER = `https://securetoken.google.com/${TEST_PROJECT_ID}`;
export const TEST_KID = 'test-key-1';
export const TEST_PHONE = '+910000000001';
export const TEST_UID = 'test-uid-0000000001';

export interface TestKeys {
  privateKey: CryptoKey;
  jwks: JSONWebKeySet;
}

export async function createTestKeys(kid = TEST_KID): Promise<TestKeys> {
  const { publicKey, privateKey } = await generateKeyPair('RS256', { extractable: true });
  const jwk = { ...(await exportJWK(publicKey)), kid, alg: 'RS256', use: 'sig' };
  return { privateKey, jwks: { keys: [jwk] } };
}

/** The real verifier with a local key resolver: exactly the production code path minus the fetch. */
export function testVerifier(keys: TestKeys): FirebaseIdTokenVerifier {
  return new FirebaseIdTokenVerifier({
    projectId: TEST_PROJECT_ID,
    keyResolver: createLocalJWKSet(keys.jwks),
  });
}

export const nowSeconds = () => Math.floor(Date.now() / 1000);

export function phoneClaims(overrides: JWTPayload = {}): JWTPayload {
  const now = nowSeconds();
  return {
    iss: TEST_ISSUER,
    aud: TEST_PROJECT_ID,
    sub: TEST_UID,
    iat: now - 60,
    exp: now + 3600,
    auth_time: now - 120,
    phone_number: TEST_PHONE,
    firebase: { sign_in_provider: 'phone', identities: { phone: [TEST_PHONE] } },
    ...overrides,
  };
}

export interface SignOptions {
  claims?: JWTPayload;
  /** `null` omits the header's kid. */
  kid?: string | null;
  key?: CryptoKey;
}

export async function signToken(keys: TestKeys, options: SignOptions = {}): Promise<string> {
  const { claims = phoneClaims(), kid = TEST_KID, key = keys.privateKey } = options;
  return new SignJWT(claims)
    .setProtectedHeader({ alg: 'RS256', typ: 'JWT', ...(kid === null ? {} : { kid }) })
    .sign(key);
}

/** Unsigned token (`alg: none`), as the Firebase Auth emulator produces. */
export function unsignedToken(claims: JWTPayload = phoneClaims()): string {
  const encode = (value: unknown) => Buffer.from(JSON.stringify(value)).toString('base64url');
  return `${encode({ alg: 'none', typ: 'JWT', kid: TEST_KID })}.${encode(claims)}.`;
}

/** HS256 token "signed" with a guessable shared secret: must never be accepted. */
export async function hs256Token(claims: JWTPayload = phoneClaims()): Promise<string> {
  return new SignJWT(claims)
    .setProtectedHeader({ alg: 'HS256', typ: 'JWT', kid: TEST_KID })
    .sign(new TextEncoder().encode('not-a-real-secret-but-long-enough-32b'));
}
