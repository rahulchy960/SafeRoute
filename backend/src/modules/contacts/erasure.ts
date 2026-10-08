// SPDX-License-Identifier: AGPL-3.0-only
import { eq } from 'drizzle-orm';
import type { Db } from '../../db/client.js';
import { auditLog, emergencyContacts } from '../../db/schema/index.js';

/**
 * Deletes every contact row of a user, tombstones of opted-out contacts included, and with them
 * their opt-out tokens (ON DELETE CASCADE). Called inside the transaction that records the
 * withdrawal of `sos_alerts` (ADR 0024): without that consent nothing about a contact is kept.
 *
 * Lives in its own file so the consents module can use it without importing the contacts service
 * (which imports the consents module).
 */
export async function eraseContacts(tx: Pick<Db, 'delete' | 'insert'>, userId: string) {
  const erased = await tx
    .delete(emergencyContacts)
    .where(eq(emergencyContacts.userId, userId))
    .returning({ id: emergencyContacts.id });
  if (erased.length === 0) return 0;

  await tx.insert(auditLog).values({
    actorType: 'user',
    actorUserId: userId,
    action: 'contacts.erased',
    entity: 'contact',
    entityId: userId,
    // A count only: no name, phone number or token (Plan v7 §11).
    metadata: { reason: 'consent_withdrawn', count: erased.length },
  });
  return erased.length;
}
