// SPDX-License-Identifier: AGPL-3.0-only
import { and, desc, eq, sql } from 'drizzle-orm';
import type { Db } from '../../db/client.js';
import { auditLog, consentRecords, users } from '../../db/schema/index.js';
import { AppError } from '../../lib/problem.js';
import { eraseContacts } from '../contacts/erasure.js';
import { ACCOUNT_CORE, type ConsentPurpose } from './purposes.js';
import type { Consent } from './schema.js';

/** A transaction or the plain client: both can insert. */
type Writer = Pick<Db, 'insert'>;

interface ConsentRow {
  purpose: string;
  status: string;
  noticeVersion: string;
  decidedAt: Date;
}

const consentColumns = {
  purpose: consentRecords.purpose,
  status: consentRecords.status,
  noticeVersion: consentRecords.noticeVersion,
  decidedAt: consentRecords.decidedAt,
};

function toConsent(row: ConsentRow): Consent {
  return {
    purpose: row.purpose,
    status: row.status,
    noticeVersion: row.noticeVersion,
    decidedAt: row.decidedAt.toISOString(),
  };
}

export interface InitialConsent {
  purposes: readonly ConsentPurpose[];
  noticeVersion: string;
  noticeLocale: 'en' | 'bn';
}

/**
 * Writes the consents given at sign-up: one `granted` row per purpose plus one audit row. The
 * caller (users.bootstrapUser) passes its transaction, so the account and its consents are
 * created together or not at all.
 */
export async function recordInitialConsents(
  tx: Writer,
  userId: string,
  consent: InitialConsent,
): Promise<void> {
  const purposes = [...new Set(consent.purposes)];
  await tx.insert(consentRecords).values(
    purposes.map((purpose) => ({
      userId,
      purpose,
      status: 'granted',
      noticeVersion: consent.noticeVersion,
      noticeLocale: consent.noticeLocale,
    })),
  );
  await tx.insert(auditLog).values({
    actorType: 'user',
    actorUserId: userId,
    action: 'consent.recorded',
    entity: 'consent',
    entityId: userId,
    metadata: { purposes, noticeVersion: consent.noticeVersion },
  });
}

/** The newest decision per purpose, ordered by purpose. */
export async function listLatestConsents(db: Pick<Db, 'selectDistinctOn'>, userId: string) {
  const rows = await db
    .selectDistinctOn([consentRecords.purpose], consentColumns)
    .from(consentRecords)
    .where(eq(consentRecords.userId, userId))
    .orderBy(consentRecords.purpose, desc(consentRecords.decidedAt));
  return rows.map(toConsent);
}

/**
 * The status of the newest decision for one purpose (`granted` or `withdrawn`), or undefined
 * when the user was never asked. A feature gate calls this inside its own transaction, after
 * locking the user's row, so the answer cannot change under it.
 */
export async function latestConsentStatus(
  db: Pick<Db, 'select'>,
  userId: string,
  purpose: ConsentPurpose,
): Promise<string | undefined> {
  const [latest] = await db
    .select({ status: consentRecords.status })
    .from(consentRecords)
    .where(and(eq(consentRecords.userId, userId), eq(consentRecords.purpose, purpose)))
    .orderBy(desc(consentRecords.decidedAt))
    .limit(1);
  return latest?.status;
}

export interface ConsentDecision {
  purpose: ConsentPurpose;
  status: 'granted' | 'withdrawn';
  noticeVersion: string;
  noticeLocale: 'en' | 'bn';
}

export interface SetConsentResult {
  /** False when the latest row already said the same thing and nothing was written. */
  changed: boolean;
  consent: Consent;
}

/**
 * Records a decision for one purpose. The table is append-only: a change is a new row.
 *
 * - Withdrawing `account_core` → 409 `account_deletion_required`: the account cannot exist
 *   without it, so the only way to withdraw is to delete the account (P020).
 * - Same status and notice version as the latest row → nothing is written (a retry or a double
 *   tap must not grow the history).
 * - Withdrawing `sos_alerts` deletes every emergency contact of the user, with the opt-out
 *   tokens, in this same transaction (ADR 0024): the withdrawal and the erasure happen together
 *   or not at all. It runs on a repeated withdrawal too, so a retry finishes the job.
 *
 * The user's row is locked (`FOR UPDATE`) for the transaction, so two concurrent calls for the
 * same user run one after the other and cannot both insert the "same" decision. `decided_at`
 * uses `clock_timestamp()` (the time of the insert) instead of the default `now()` (the time
 * the transaction started), so the row written last is also the newest.
 */
export async function setConsent(
  db: Db,
  userId: string,
  decision: ConsentDecision,
): Promise<SetConsentResult> {
  if (decision.purpose === ACCOUNT_CORE && decision.status === 'withdrawn') {
    throw new AppError(
      409,
      'account_deletion_required',
      'This consent is needed for the account to exist. Delete the account to withdraw it.',
    );
  }

  return db.transaction(async (tx) => {
    await tx.select({ id: users.id }).from(users).where(eq(users.id, userId)).for('update');

    const [latest] = await tx
      .select(consentColumns)
      .from(consentRecords)
      .where(and(eq(consentRecords.userId, userId), eq(consentRecords.purpose, decision.purpose)))
      .orderBy(desc(consentRecords.decidedAt))
      .limit(1);
    if (decision.purpose === 'sos_alerts' && decision.status === 'withdrawn') {
      await eraseContacts(tx, userId);
    }
    if (latest?.status === decision.status && latest.noticeVersion === decision.noticeVersion) {
      return { changed: false, consent: toConsent(latest) };
    }

    const [inserted] = await tx
      .insert(consentRecords)
      .values({
        userId,
        purpose: decision.purpose,
        status: decision.status,
        noticeVersion: decision.noticeVersion,
        noticeLocale: decision.noticeLocale,
        decidedAt: sql`clock_timestamp()`,
      })
      .returning(consentColumns);
    if (inserted === undefined) throw new Error('consent insert returned no row');

    await tx.insert(auditLog).values({
      actorType: 'user',
      actorUserId: userId,
      action: 'consent.changed',
      entity: 'consent',
      entityId: userId,
      metadata: { purpose: decision.purpose, status: decision.status },
    });
    return { changed: true, consent: toConsent(inserted) };
  });
}
