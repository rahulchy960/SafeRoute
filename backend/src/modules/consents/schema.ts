// SPDX-License-Identifier: AGPL-3.0-only
import { z } from '@hono/zod-openapi';
import { ACCOUNT_CORE, CONSENT_PURPOSES, isConsentPurpose } from './purposes.js';

/**
 * Zod schemas of the consent API (contract). The `consent_records` table is defined in
 * src/db/schema/consents.ts.
 *
 * `purpose` is an open string in the contract (a pattern, not an enum), so adding a purpose is a
 * non-breaking change. The server allowlist is checked with `refine`, which produces a 400
 * `validation_error` that names the field and never echoes the submitted value.
 */

const purposeList = CONSENT_PURPOSES.map((purpose) => `\`${purpose}\``).join(', ');

export const ConsentPurposeSchema = z
  .string()
  .regex(/^[a-z][a-z0-9_]{2,40}$/)
  .refine(isConsentPurpose, { message: 'unknown purpose' })
  .openapi({
    description: `What the consent is for. Open set; currently accepted: ${purposeList}.`,
    examples: ['account_core'],
  });

export const NoticeVersionSchema = z
  .string()
  .regex(/^[A-Za-z0-9._-]{1,40}$/)
  .openapi({
    description: 'Version of the consent notice the user was shown.',
    examples: ['2026-10-v1'],
  });

export const NoticeLocaleSchema = z.enum(['en', 'bn']).openapi({
  description: 'Language the notice was shown in.',
  examples: ['en'],
});

/** Part of the bootstrap request (src/modules/users/schema.ts). */
export const BootstrapConsentSchema = z
  .object({
    ageConfirmed: z
      .boolean()
      .optional()
      .openapi({
        description:
          'True when the user declared "I am 18 or older". Anything else → 403 `adult_required`. ' +
          'No date of birth is collected.',
      }),
    noticeVersion: NoticeVersionSchema,
    noticeLocale: NoticeLocaleSchema,
    purposes: z
      .array(ConsentPurposeSchema)
      .min(1)
      .max(CONSENT_PURPOSES.length)
      .refine((purposes) => purposes.includes(ACCOUNT_CORE), { message: 'account_core missing' })
      .openapi({
        description: `Purposes the user agreed to. Must contain \`${ACCOUNT_CORE}\`.`,
        examples: [[ACCOUNT_CORE]],
      }),
  })
  .openapi('BootstrapConsent', {
    description:
      'The age declaration and the consent the user gave on the onboarding notice. Needed to ' +
      'create an account; ignored when the account already exists.',
  });

export type BootstrapConsent = z.infer<typeof BootstrapConsentSchema>;

export const ConsentSchema = z
  .object({
    purpose: z.string().openapi({
      description: 'What the consent is for. Clients must tolerate unknown values.',
      examples: ['account_core'],
    }),
    status: z.string().openapi({
      description: '`granted` or `withdrawn`. Clients must tolerate unknown values.',
      examples: ['granted'],
    }),
    noticeVersion: z.string().openapi({
      description: 'Version of the notice this decision was made under.',
      examples: ['2026-10-v1'],
    }),
    decidedAt: z.iso.datetime().openapi({
      description: 'RFC 3339 UTC timestamp of the decision.',
      examples: ['2026-10-06T09:30:00.000Z'],
    }),
  })
  .openapi('Consent', { description: "The user's latest decision for one purpose." });

export type Consent = z.infer<typeof ConsentSchema>;

export const ConsentListSchema = z
  .object({
    items: z.array(ConsentSchema).openapi({
      description: 'One entry per purpose the user has ever decided on, ordered by purpose.',
    }),
  })
  .openapi('ConsentList', { description: "The signed-in user's current consents." });

export const ConsentPurposeParamSchema = z.object({
  purpose: ConsentPurposeSchema.openapi({ param: { name: 'purpose', in: 'path' } }),
});

export const SetConsentRequestSchema = z
  .object({
    status: z.enum(['granted', 'withdrawn']).openapi({ examples: ['granted'] }),
    noticeVersion: NoticeVersionSchema,
    noticeLocale: NoticeLocaleSchema,
  })
  .openapi('SetConsentRequest', {
    description: "The user's decision for the purpose in the path.",
  });
