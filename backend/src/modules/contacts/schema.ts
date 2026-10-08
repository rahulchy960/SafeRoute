// SPDX-License-Identifier: AGPL-3.0-only
import { z } from '@hono/zod-openapi';

/**
 * Zod schemas of the emergency contacts API (contract, ADR 0024). The tables are defined in
 * src/db/schema/contacts.ts.
 */

export const MAX_CONTACTS = 5;
export const CONTACT_NAME_MAX = 80;

/** Same rule as the CHECK on `emergency_contacts.phone_e164`. */
const E164_PATTERN = /^\+[1-9][0-9]{6,14}$/;
/** Control characters and the line and paragraph separators: never part of a name. */
const NAME_FORBIDDEN = /[\p{Cc}\u2028\u2029]/u;
/** 128 random bits in base64url. */
export const OPT_OUT_TOKEN_PATTERN = /^[A-Za-z0-9_-]{22}$/;

/** Trimmed name, or undefined when it is empty, too long or has control characters. */
export function normalizeContactName(value: string): string | undefined {
  const name = value.trim();
  // UTF-16 units, like the display name: never more than char_length() in the table's CHECK.
  if (name.length < 1 || name.length > CONTACT_NAME_MAX || NAME_FORBIDDEN.test(name)) {
    return undefined;
  }
  return name;
}

/** Trimmed E.164 number, or undefined. Nothing else is rewritten: the app sends E.164. */
export function normalizeContactPhone(value: string): string | undefined {
  const phone = value.trim();
  return E164_PATTERN.test(phone) ? phone : undefined;
}

const ContactNameSchema = z
  .string()
  .max(200)
  .refine((value) => normalizeContactName(value) !== undefined, { message: 'invalid name' })
  .openapi({
    description:
      `What the user calls this contact: 1 to ${String(CONTACT_NAME_MAX)} characters after ` +
      'trimming, no control characters.',
    examples: ['Example Contact'],
  });

export const ContactSchema = z
  .object({
    id: z.uuid().openapi({ examples: ['7c0e1a52-3f7b-4c58-9d0e-2a4b6c8d0e1f'] }),
    name: z.string().openapi({ examples: ['Example Contact'] }),
    phoneE164: z.string().openapi({
      description: 'Phone number in E.164 form.',
      examples: ['+910000000000'],
    }),
    createdAt: z.iso.datetime().openapi({ examples: ['2026-10-09T09:30:00.000Z'] }),
    invitedAt: z.iso
      .datetime()
      .nullable()
      .openapi({
        description:
          'When the user confirmed that they sent the invite SMS from their own phone; null ' +
          'until then. The server sends no message.',
        examples: [null],
      }),
    optedOutAt: z.iso
      .datetime()
      .nullable()
      .openapi({
        description:
          'When the contact opted out through the public page; null otherwise. An opted-out ' +
          'contact must never be alerted and cannot be invited again.',
        examples: [null],
      }),
  })
  .openapi('Contact', { description: 'One emergency contact of the signed-in user.' });

export type Contact = z.infer<typeof ContactSchema>;

export const ContactListSchema = z
  .object({
    items: z.array(ContactSchema).openapi({ description: 'Oldest first.' }),
    maxContacts: z
      .number()
      .int()
      .openapi({
        description: 'How many contacts a user can have.',
        examples: [MAX_CONTACTS],
      }),
  })
  .openapi('ContactList', { description: "The signed-in user's emergency contacts." });

export const CreateContactRequestSchema = z
  .object({
    name: ContactNameSchema,
    phone: z
      .string()
      .max(40)
      .refine((value) => normalizeContactPhone(value) !== undefined, { message: 'invalid phone' })
      .openapi({
        description:
          'Phone number in E.164 form: `+`, country code, number, digits only (7 to 15 digits).',
        examples: ['+910000000000'],
      }),
  })
  .openapi('CreateContactRequest', { description: 'A new emergency contact.' });

export const RenameContactRequestSchema = z
  .object({ name: ContactNameSchema })
  .openapi('RenameContactRequest', { description: 'The new name of the contact.' });

export const ContactIdParamSchema = z.object({
  id: z.uuid().openapi({
    param: { name: 'id', in: 'path' },
    description: 'Id of the contact, as returned by the server.',
    examples: ['7c0e1a52-3f7b-4c58-9d0e-2a4b6c8d0e1f'],
  }),
});

export const ContactInviteSchema = z
  .object({
    optOutToken: z.string().openapi({
      description:
        'A new opt-out token for this contact, returned once and never stored in plaintext. The ' +
        'app puts it in the FRAGMENT of the opt-out link (`<API address>/c#<token>`) inside the ' +
        'SMS the user sends. Keep it in memory only: never log or save it.',
      examples: ['AAAAAAAAAAAAAAAAAAAAAA'],
    }),
  })
  .openapi('ContactInvite', { description: 'What the app needs to build an invite SMS.' });

export const OptOutRequestSchema = z
  .object({
    // The shape is checked in the handler: a malformed token gets the same 404 as an unknown one.
    token: z
      .string()
      .min(1)
      .max(64)
      .openapi({
        description: 'The token from the fragment of the opt-out link.',
        examples: ['AAAAAAAAAAAAAAAAAAAAAA'],
      }),
  })
  .openapi('ContactOptOutRequest', { description: 'An opt-out by the contact.' });

export const OptOutResultSchema = z
  .object({ optedOut: z.boolean().openapi({ examples: [true] }) })
  .openapi('ContactOptOutResult', {
    description: 'The contact is opted out. Says nothing about the contact or who added them.',
  });
