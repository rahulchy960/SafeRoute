// SPDX-License-Identifier: AGPL-3.0-only
import { z } from '@hono/zod-openapi';
import { BootstrapConsentSchema } from '../consents/schema.js';

/**
 * Zod schemas of the users API (contract). The `users` table itself is defined in
 * src/db/schema/users.ts (P003), where drizzle-kit reads all tables.
 */

/** C0/C1 control characters (newlines, tabs, NUL, ...). Not allowed in a display name. */
const CONTROL_CHARACTERS = /\p{Cc}/u;

export const BootstrapMeRequestSchema = z
  .object({
    locale: z
      .enum(['en', 'bn'])
      .optional()
      .openapi({
        description: 'UI language for the new account. Default `en`. Ignored if it already exists.',
        examples: ['bn'],
      }),
    displayName: z
      .string()
      .trim()
      .min(1)
      .max(80)
      .refine((value) => !CONTROL_CHARACTERS.test(value), { message: 'control characters' })
      .optional()
      .openapi({
        description:
          "Name shown to the user's emergency contacts. Trimmed; 1–80 characters; no control " +
          'characters. Ignored if the account already exists.',
        examples: ['Sample Name'],
      }),
    consent: BootstrapConsentSchema.optional(),
  })
  .openapi('BootstrapMeRequest', {
    description:
      'Settings and consent for a new account. `consent` is needed to create an account ' +
      '(optional in the schema only because an existing account does not need it). The phone ' +
      'number is never sent: the server takes it from the verified ID token. Unknown ' +
      'properties are ignored.',
  });

export const MeSchema = z
  .object({
    id: z.uuid().openapi({
      description: 'Stable account ID.',
      examples: ['0f8c2a4e-6b1d-4c3a-9e7f-2d5b8a1c4e60'],
    }),
    phoneE164: z
      .string()
      .nullable()
      .openapi({
        description: 'Verified phone number in E.164 form, from the sign-in token.',
        examples: ['+910000000001'],
      }),
    displayName: z
      .string()
      .nullable()
      .openapi({ examples: ['Sample Name'] }),
    locale: z.string().openapi({
      description: 'UI language: `en` or `bn`. Clients must tolerate unknown values.',
      examples: ['en'],
    }),
    role: z.string().openapi({
      description: '`user`, `moderator` or `admin`. Clients must tolerate unknown values.',
      examples: ['user'],
    }),
    createdAt: z.iso.datetime().openapi({
      description: 'RFC 3339 UTC timestamp.',
      examples: ['2026-10-01T09:30:00.000Z'],
    }),
  })
  .openapi('Me', { description: "The signed-in user's account." });

export type Me = z.infer<typeof MeSchema>;
