// SPDX-License-Identifier: AGPL-3.0-only
import { createHash, randomBytes } from 'node:crypto';
import { and, asc, count, eq, isNull, sql } from 'drizzle-orm';
import type { Db } from '../../db/client.js';
import { auditLog, contactOptoutTokens, emergencyContacts, users } from '../../db/schema/index.js';
import { AppError } from '../../lib/problem.js';
import type { RateLimiter } from '../../lib/rate-limit.js';
import { latestConsentStatus } from '../consents/service.js';
import { MAX_CONTACTS, OPT_OUT_TOKEN_PATTERN, type Contact } from './schema.js';

const SECONDS_PER_DAY = 86_400;

/** Opt-out links one contact can have at a time. */
export const MAX_TOKENS_PER_CONTACT = 10;

/** The only place the contacts rate-limit numbers live (ADR 0024). */
export const CONTACT_LIMITS = {
  /** Adding: 5 at once, then one per minute. */
  createBurst: { capacity: 5, refillPerSecond: 1 / 60 },
  /** 20 added contacts per user per day. */
  createDaily: { capacity: 20, refillPerSecond: 20 / SECONDS_PER_DAY },
  /** 30 invite links per user per day. */
  inviteDaily: { capacity: 30, refillPerSecond: 30 / SECONDS_PER_DAY },
  /** Opt-out calls from everyone together: the endpoint has no account to count by. */
  optOutGlobal: { capacity: 60, refillPerSecond: 1 },
} as const;

/** 429 with the wait the limiter computed; the route turns it into a `Retry-After` header. */
export class ContactRateLimitError extends AppError {
  readonly retryAfterSeconds: number;

  constructor(retryAfterSeconds: number) {
    super(429, 'rate_limited', 'Too many requests. Wait a moment and try again.');
    this.name = 'ContactRateLimitError';
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

/** Takes one token from each named bucket of the user, or throws a 429. */
export async function takeUserTokens(
  limiter: RateLimiter,
  userId: string,
  buckets: readonly ('createBurst' | 'createDaily' | 'inviteDaily')[],
): Promise<void> {
  for (const name of buckets) {
    const { capacity, refillPerSecond } = CONTACT_LIMITS[name];
    const decision = await limiter.take(
      `contacts:${name}:${userId}`,
      userId,
      capacity,
      refillPerSecond,
    );
    if (!decision.allowed) throw new ContactRateLimitError(decision.retryAfterSeconds);
  }
}

/** One shared bucket and no per-caller data: no IP address is read, stored or logged. */
export async function takeOptOutToken(limiter: RateLimiter): Promise<void> {
  const { capacity, refillPerSecond } = CONTACT_LIMITS.optOutGlobal;
  const decision = await limiter.take('contacts:optout:global', null, capacity, refillPerSecond);
  if (!decision.allowed) throw new ContactRateLimitError(decision.retryAfterSeconds);
}

const contactColumns = {
  id: emergencyContacts.id,
  name: emergencyContacts.name,
  phoneE164: emergencyContacts.phoneE164,
  createdAt: emergencyContacts.createdAt,
  invitedAt: emergencyContacts.invitedAt,
  optedOutAt: emergencyContacts.optedOutAt,
};

interface ContactRow {
  id: string;
  name: string;
  phoneE164: string;
  createdAt: Date;
  invitedAt: Date | null;
  optedOutAt: Date | null;
}

/** API view of a contact: never `has_app_user_id`, `deleted_at` or `user_id`. */
function toContact(row: ContactRow): Contact {
  return {
    id: row.id,
    name: row.name,
    phoneE164: row.phoneE164,
    createdAt: row.createdAt.toISOString(),
    invitedAt: row.invitedAt?.toISOString() ?? null,
    optedOutAt: row.optedOutAt?.toISOString() ?? null,
  };
}

/** A contact the caller owns and has not removed. Another user's id is simply not found. */
const ownedBy = (userId: string, contactId: string) =>
  and(
    eq(emergencyContacts.id, contactId),
    eq(emergencyContacts.userId, userId),
    isNull(emergencyContacts.deletedAt),
  );

const notFound = () => new AppError(404, 'not_found', 'No such contact.');
const optedOut = () =>
  new AppError(
    409,
    'contact_opted_out',
    'This contact has opted out and cannot be added or invited.',
  );

type Tx = Parameters<Parameters<Db['transaction']>[0]>[0];

/** Audit rows carry the contact id only: never a name, a phone number or a token. */
async function audit(tx: Tx, userId: string, action: string, contactId: string) {
  await tx.insert(auditLog).values({
    actorType: 'user',
    actorUserId: userId,
    action,
    entity: 'contact',
    entityId: contactId,
    metadata: { contactId },
  });
}

export async function listContacts(db: Db, userId: string): Promise<Contact[]> {
  const rows = await db
    .select(contactColumns)
    .from(emergencyContacts)
    .where(and(eq(emergencyContacts.userId, userId), isNull(emergencyContacts.deletedAt)))
    .orderBy(asc(emergencyContacts.createdAt), asc(emergencyContacts.id));
  return rows.map(toContact);
}

export interface NewContact {
  /** Already normalised (schema.ts). */
  name: string;
  phoneE164: string;
}

/**
 * Adds a contact, in one transaction that first locks the user's row (`FOR UPDATE`). Every create
 * and every consent change of the same user takes that lock, so they run one after the other:
 * parallel creates cannot pass the limit of 5 together, and a contact cannot slip in next to a
 * withdrawal of the consent.
 *
 * Checks, in order: `sos_alerts` consent (403 `consent_required`: nothing about a contact is
 * processed without it); the user's own number (409 `invalid_contact`); the number is already
 * there (409 `contact_exists`, or `contact_opted_out` when that contact opted out, removed or
 * not); the limit (409 `contact_limit_reached`).
 *
 * No Idempotency-Key: UNIQUE (user_id, phone_e164) makes a retry safe. The retry gets
 * `contact_exists` and the app reads the list again.
 */
export async function createContact(
  db: Db,
  user: { id: string; phoneE164: string | null },
  input: NewContact,
): Promise<Contact> {
  return db.transaction(async (tx) => {
    await tx.select({ id: users.id }).from(users).where(eq(users.id, user.id)).for('update');

    if ((await latestConsentStatus(tx, user.id, 'sos_alerts')) !== 'granted') {
      throw new AppError(
        403,
        'consent_required',
        'Adding emergency contacts needs the `sos_alerts` consent.',
      );
    }
    if (input.phoneE164 === user.phoneE164) {
      throw new AppError(409, 'invalid_contact', 'You cannot add your own number as a contact.');
    }

    const [existing] = await tx
      .select({ optedOutAt: emergencyContacts.optedOutAt })
      .from(emergencyContacts)
      .where(
        and(
          eq(emergencyContacts.userId, user.id),
          eq(emergencyContacts.phoneE164, input.phoneE164),
        ),
      );
    if (existing !== undefined) {
      if (existing.optedOutAt !== null) throw optedOut();
      throw new AppError(409, 'contact_exists', 'This number is already one of your contacts.');
    }

    const [{ total } = { total: 0 }] = await tx
      .select({ total: count() })
      .from(emergencyContacts)
      .where(and(eq(emergencyContacts.userId, user.id), isNull(emergencyContacts.deletedAt)));
    if (total >= MAX_CONTACTS) {
      throw new AppError(
        409,
        'contact_limit_reached',
        `You can have up to ${String(MAX_CONTACTS)} emergency contacts.`,
      );
    }

    const [created] = await tx
      .insert(emergencyContacts)
      .values({ userId: user.id, name: input.name, phoneE164: input.phoneE164 })
      .returning(contactColumns);
    if (created === undefined) throw new Error('contact insert returned no row');
    await audit(tx, user.id, 'contact.created', created.id);
    return toContact(created);
  });
}

export async function renameContact(
  db: Db,
  userId: string,
  contactId: string,
  name: string,
): Promise<Contact> {
  return db.transaction(async (tx) => {
    const [updated] = await tx
      .update(emergencyContacts)
      .set({ name })
      .where(ownedBy(userId, contactId))
      .returning(contactColumns);
    if (updated === undefined) throw notFound();
    await audit(tx, userId, 'contact.renamed', contactId);
    return toContact(updated);
  });
}

/**
 * Removes a contact. A contact that has NOT opted out is deleted for good, with its tokens. An
 * opted-out contact becomes a tombstone: the row stays with `deleted_at` set, so this user cannot
 * add the number again and so undo the opt-out. The tombstone keeps the number only (the name is
 * replaced and the tokens are deleted) and goes away with the account or when the user withdraws
 * `sos_alerts`. How long a tombstone may be kept is to be verified by a lawyer (ADR 0024).
 */
export async function deleteContact(db: Db, userId: string, contactId: string): Promise<void> {
  await db.transaction(async (tx) => {
    const [row] = await tx
      .select({ optedOutAt: emergencyContacts.optedOutAt })
      .from(emergencyContacts)
      .where(ownedBy(userId, contactId))
      .for('update');
    if (row === undefined) throw notFound();

    if (row.optedOutAt === null) {
      await tx.delete(emergencyContacts).where(eq(emergencyContacts.id, contactId));
    } else {
      await tx
        .update(emergencyContacts)
        .set({ deletedAt: sql`now()`, name: '-', invitedAt: null })
        .where(eq(emergencyContacts.id, contactId));
      await tx.delete(contactOptoutTokens).where(eq(contactOptoutTokens.contactId, contactId));
    }
    await audit(tx, userId, 'contact.deleted', contactId);
  });
}

/** Only this hash is stored. A 128-bit random token needs no salt or slow hash. */
export function hashOptOutToken(token: string): Buffer {
  return createHash('sha256').update(token, 'utf8').digest();
}

/**
 * Mints a new opt-out token for a contact and returns the plaintext, once. Older tokens stay
 * valid: an SMS already sent must keep working. The contact's row is locked, so the cap of
 * tokens per contact holds with parallel calls.
 */
export async function createInvite(db: Db, userId: string, contactId: string): Promise<string> {
  return db.transaction(async (tx) => {
    const [row] = await tx
      .select({ optedOutAt: emergencyContacts.optedOutAt })
      .from(emergencyContacts)
      .where(ownedBy(userId, contactId))
      .for('update');
    if (row === undefined) throw notFound();
    if (row.optedOutAt !== null) throw optedOut();

    const [{ total } = { total: 0 }] = await tx
      .select({ total: count() })
      .from(contactOptoutTokens)
      .where(eq(contactOptoutTokens.contactId, contactId));
    if (total >= MAX_TOKENS_PER_CONTACT) {
      throw new AppError(
        429,
        'rate_limited',
        'This contact already has the most invite links allowed.',
      );
    }

    const token = randomBytes(16).toString('base64url');
    await tx.insert(contactOptoutTokens).values({ tokenHash: hashOptOutToken(token), contactId });
    await audit(tx, userId, 'contact.invite_created', contactId);
    return token;
  });
}

/**
 * Records that the user said the invite SMS was sent. The server cannot know whether it was:
 * `invited_at` is the user's statement, not a delivery receipt. Needs an invite link to exist
 * (409 `conflict` otherwise).
 */
export async function confirmInvite(db: Db, userId: string, contactId: string): Promise<void> {
  await db.transaction(async (tx) => {
    const [row] = await tx
      .select({ optedOutAt: emergencyContacts.optedOutAt })
      .from(emergencyContacts)
      .where(ownedBy(userId, contactId))
      .for('update');
    if (row === undefined) throw notFound();
    if (row.optedOutAt !== null) throw optedOut();

    const [minted] = await tx
      .select({ contactId: contactOptoutTokens.contactId })
      .from(contactOptoutTokens)
      .where(eq(contactOptoutTokens.contactId, contactId))
      .limit(1);
    if (minted === undefined) {
      throw new AppError(409, 'conflict', 'Create an invite link for this contact first.');
    }

    await tx
      .update(emergencyContacts)
      .set({ invitedAt: sql`now()` })
      .where(eq(emergencyContacts.id, contactId));
    await audit(tx, userId, 'contact.invited', contactId);
  });
}

/**
 * The contact opts out with a token from their SMS. Returns false when the token is malformed,
 * unknown or belongs to a removed contact: the caller answers all three with the same 404.
 * Repeating it changes nothing and returns true.
 *
 * The token is never compared in application code: the lookup is an equality on the primary key
 * (the SHA-256 of the token), so response time does not depend on how much of a guess matched.
 */
export async function optOutByToken(db: Db, token: string): Promise<boolean> {
  if (!OPT_OUT_TOKEN_PATTERN.test(token)) return false;
  const tokenHash = hashOptOutToken(token);

  return db.transaction(async (tx) => {
    const [found] = await tx
      .select({ contactId: emergencyContacts.id, optedOutAt: emergencyContacts.optedOutAt })
      .from(contactOptoutTokens)
      .innerJoin(emergencyContacts, eq(emergencyContacts.id, contactOptoutTokens.contactId))
      .where(and(eq(contactOptoutTokens.tokenHash, tokenHash), isNull(emergencyContacts.deletedAt)))
      .for('update', { of: emergencyContacts });
    if (found === undefined) return false;
    if (found.optedOutAt !== null) return true;

    await tx
      .update(emergencyContacts)
      .set({ optedOutAt: sql`now()` })
      .where(eq(emergencyContacts.id, found.contactId));
    await tx.insert(auditLog).values({
      actorType: 'system',
      action: 'contact.opted_out',
      entity: 'contact',
      entityId: found.contactId,
      metadata: { contactId: found.contactId },
    });
    return true;
  });
}
