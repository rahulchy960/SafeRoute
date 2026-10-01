// SPDX-License-Identifier: AGPL-3.0-only
import { eq } from 'drizzle-orm';
import type { Db } from '../../db/client.js';
import { auditLog, users } from '../../db/schema/index.js';
import { AppError } from '../../lib/problem.js';
import type { Me } from './schema.js';

/** Row fields other modules may use. firebase_uid stays inside this module and auth. */
export interface UserRecord {
  id: string;
  phoneE164: string | null;
  displayName: string | null;
  locale: string;
  role: string;
  createdAt: Date;
  deletedAt: Date | null;
}

const userColumns = {
  id: users.id,
  phoneE164: users.phoneE164,
  displayName: users.displayName,
  locale: users.locale,
  role: users.role,
  createdAt: users.createdAt,
  deletedAt: users.deletedAt,
};

/** Includes soft-deleted users; callers decide what deleted_at means for them. */
export async function findUserByFirebaseUid(
  db: Pick<Db, 'select'>,
  firebaseUid: string,
): Promise<UserRecord | undefined> {
  const rows = await db
    .select(userColumns)
    .from(users)
    .where(eq(users.firebaseUid, firebaseUid))
    .limit(1);
  return rows[0];
}

export interface BootstrapInput {
  firebaseUid: string;
  /** From the VERIFIED token, never from the request body. */
  phoneE164: string;
  locale: 'en' | 'bn';
  displayName?: string | undefined;
}

export interface BootstrapResult {
  created: boolean;
  user: UserRecord;
}

/**
 * Idempotent "create my account if it doesn't exist", in one transaction:
 *
 *   INSERT ... ON CONFLICT DO NOTHING RETURNING ...   (no conflict target)
 *   - a row came back → new account: write the `user.created` audit row, return created=true;
 *   - nothing came back → a unique index matched: read the row by firebase_uid.
 *     Found → existing account, returned unchanged (body values ignored). Not found → the phone
 *     number belongs to another account → 409 `phone_already_registered`, nothing written.
 *
 * Without a conflict target every unique index (firebase_uid and the partial phone index) is an
 * arbiter, so concurrent bootstraps of the same person never raise a unique-violation error:
 * Postgres makes the losers wait for the winner and then do nothing.
 * A soft-deleted account → 403 `account_deleted`.
 */
export async function bootstrapUser(db: Db, input: BootstrapInput): Promise<BootstrapResult> {
  return db.transaction(async (tx) => {
    const inserted = await tx
      .insert(users)
      .values({
        firebaseUid: input.firebaseUid,
        phoneE164: input.phoneE164,
        locale: input.locale,
        displayName: input.displayName ?? null,
      })
      .onConflictDoNothing()
      .returning(userColumns);

    const created = inserted[0];
    if (created !== undefined) {
      // No phone, name or uid in audit metadata (Plan v7 §11).
      await tx.insert(auditLog).values({
        actorType: 'user',
        actorUserId: created.id,
        action: 'user.created',
        entity: 'user',
        entityId: created.id,
        metadata: {},
      });
      return { created: true, user: created };
    }

    const existing = await findUserByFirebaseUid(tx, input.firebaseUid);
    if (existing === undefined) {
      throw new AppError(
        409,
        'phone_already_registered',
        'This phone number is already linked to another account.',
      );
    }
    if (existing.deletedAt !== null) {
      throw new AppError(403, 'account_deleted', 'This account has been deleted.');
    }
    return { created: false, user: existing };
  });
}

/** API view of a user: no firebase_uid, deleted_at or updated_at. */
export function toMe(user: UserRecord): Me {
  return {
    id: user.id,
    phoneE164: user.phoneE164,
    displayName: user.displayName,
    locale: user.locale,
    role: user.role,
    createdAt: user.createdAt.toISOString(),
  };
}
