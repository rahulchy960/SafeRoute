// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Purposes a user can consent to today (ADR 0010). This allowlist, not the database, decides
 * which purposes exist: the table only checks the shape of the string, so adding a purpose
 * (e.g. `trusted_circle` when that feature ships, ADR 0011) is a code change with no migration.
 *
 * - `account_core`: verify the phone number and keep the account. Recorded at sign-up.
 * - the others are asked for just-in-time, when the feature is first used.
 */
export const CONSENT_PURPOSES = [
  'account_core',
  'sos_alerts',
  'live_sharing',
  'safety_reports',
] as const;

export type ConsentPurpose = (typeof CONSENT_PURPOSES)[number];

/** The account cannot exist without it, so it can only be withdrawn by deleting the account. */
export const ACCOUNT_CORE: ConsentPurpose = 'account_core';

export function isConsentPurpose(value: string): value is ConsentPurpose {
  return (CONSENT_PURPOSES as readonly string[]).includes(value);
}
