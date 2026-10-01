// SPDX-License-Identifier: AGPL-3.0-only
import { eq } from 'drizzle-orm';
import type { Db } from '../../db/client.js';
import { users } from '../../db/schema/index.js';

/**
 * Users module service (Plan v7 §4). P005a adds only the lookup that auth's `requireUser` needs;
 * `POST /v1/me/bootstrap` and `GET /v1/me` follow in P005b.
 */

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
