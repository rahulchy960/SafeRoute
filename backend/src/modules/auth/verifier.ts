// SPDX-License-Identifier: AGPL-3.0-only

/** Who the caller is, as proven by a verified Firebase ID token. PERSONAL DATA: never log it. */
export interface VerifiedIdentity {
  /** Firebase Auth user id (`sub`). Stored as users.firebase_uid; never exposed by the API. */
  firebaseUid: string;
  /** Verified phone number in E.164 form (`phone_number` claim). */
  phoneE164: string;
  /** Always `phone` today (`firebase.sign_in_provider`). */
  signInProvider: 'phone';
  issuedAt: Date;
  expiresAt: Date;
}

/**
 * Checks an ID token and returns the identity it proves.
 * Rejects with AuthError (client error, 401) or AuthUnavailableError (keys unreachable, 503).
 * An interface so tests can inject failures; production uses FirebaseIdTokenVerifier.
 */
export interface TokenVerifier {
  verify(idToken: string): Promise<VerifiedIdentity>;
}
