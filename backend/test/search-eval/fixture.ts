// SPDX-License-Identifier: AGPL-3.0-only
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { z } from 'zod';

export const SCRIPTS = ['en', 'bn', 'translit'] as const;
export type Script = (typeof SCRIPTS)[number];

/** Who vouches for an entry. Every `claude-known` entry is reviewed by Rahul in the pull request. */
export const ADDED_BY = ['claude-known', 'rahul', 'contributor'] as const;

/**
 * What a local-intent query asks for. `generic`: a category or a chain that exists in many
 * towns ("bank", "bus stand"), where only a nearby one is a good answer. `named`: one
 * particular place, searched from nearby.
 */
export const INTENTS = ['generic', 'named'] as const;
export type Intent = (typeof INTENTS)[number];

/** Two decimals at most (about 1 km): a town, not a spot in it. */
const coarseDegrees = (limit: number) =>
  z
    .number()
    .min(-limit)
    .max(limit)
    .refine((value) => Math.abs(value * 100 - Math.round(value * 100)) < 1e-6, {
      message: 'at most two decimals',
    });

export const FixtureEntrySchema = z
  .strictObject({
    id: z.string().regex(/^[a-z0-9]+(-[a-z0-9]+)*$/),
    /** What a person would type. A PUBLIC place only: never a home, a person or a private address. */
    query: z.string().trim().min(2).max(100),
    script: z.enum(SCRIPTS),
    /** West Bengal district the place is in (for the per-district hit rate). */
    district: z.string().trim().min(3).max(40),
    /** A result counts as a hit when its name or label contains ANY of these (case-insensitive). */
    expectedNameContains: z.array(z.string().trim().min(2).max(80)).min(1).max(8),
    /** Optional, and only from a cited public source. Never guessed. */
    expected: z
      .strictObject({
        latitude: z.number().min(-90).max(90),
        longitude: z.number().min(-180).max(180),
        toleranceMeters: z.number().int().min(50).max(50_000),
        source: z.string().trim().min(5).max(200),
      })
      .optional(),
    /**
     * Local intent only: where the search is made from. The coarse centre of a town or another
     * PUBLIC place in `district`, never a home or anybody's position. Set together with `intent`.
     */
    near: z.strictObject({ latitude: coarseDegrees(90), longitude: coarseDegrees(180) }).optional(),
    intent: z.enum(INTENTS).optional(),
    addedBy: z.enum(ADDED_BY),
  })
  .refine((entry) => (entry.near === undefined) === (entry.intent === undefined), {
    message: 'near and intent go together',
  });

export const FixtureSchema = z.strictObject({
  description: z.string(),
  version: z.literal(1),
  entries: z.array(FixtureEntrySchema).min(1).max(2000),
});

export type FixtureEntry = z.infer<typeof FixtureEntrySchema>;
export type Fixture = z.infer<typeof FixtureSchema>;

export const FIXTURE_PATH = join(import.meta.dirname, 'fixture.json');

export function loadFixture(path: string = FIXTURE_PATH): Fixture {
  return FixtureSchema.parse(JSON.parse(readFileSync(path, 'utf8')));
}
