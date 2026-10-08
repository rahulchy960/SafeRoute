// SPDX-License-Identifier: AGPL-3.0-only
import { z } from 'zod';
import dictionary from './intents.json' with { type: 'json' };
import { countCodePoints } from './normalize.js';

/**
 * Kinds of place a search can ask for (ADR 0018, "Category and brand search"). Our own names:
 * each adapter maps them to its provider's categories. `public_transport` is bus and train
 * together, for the app's quick-search chip; nobody types it.
 */
export const CATEGORY_KEYS = [
  'bank',
  'atm',
  'pharmacy',
  'hospital',
  'clinic',
  'fuel',
  'restaurant',
  'grocery',
  'bus',
  'train',
  'public_transport',
  'police',
  'post_office',
  'school',
  'college',
] as const;
export type CategoryKey = (typeof CATEGORY_KEYS)[number];

export function isCategoryKey(value: string): value is CategoryKey {
  return (CATEGORY_KEYS as readonly string[]).includes(value);
}

const phrase = z.string().trim().min(2).max(40);
const origin = {
  script: z.enum(['en', 'bn', 'translit']),
  /** Who vouches for the entry. `claude-known`: written from general knowledge, to be reviewed. */
  addedBy: z.enum(['claude-known', 'rahul', 'contributor']),
  /** Set while a native Bengali speaker has not confirmed the entry. */
  review: z.literal('native-speaker').optional(),
};

export const IntentDictionarySchema = z.strictObject({
  description: z.string(),
  version: z.literal(1),
  categories: z
    .array(z.strictObject({ phrase, category: z.enum(CATEGORY_KEYS), ...origin }))
    .max(300),
  brands: z
    .array(
      z.strictObject({
        /** Canonical name: what is searched and what a result must carry. */
        name: phrase,
        category: z.enum(CATEGORY_KEYS),
        /** Other ways people type it. Each must be unmistakable on its own. */
        aliases: z.array(phrase).max(6),
        addedBy: origin.addedBy,
      }),
    )
    .max(100),
  words: z.array(z.strictObject({ phrase, role: z.enum(['joiner', 'filler']), ...origin })).max(50),
});
export type IntentDictionary = z.infer<typeof IntentDictionarySchema>;

export interface Brand {
  /** Canonical name, as written in the dictionary. */
  name: string;
  /** The name and its aliases, folded: a result is this brand when its name contains one. */
  terms: string[][];
}

export type QueryIntent =
  /** Not a category or brand: the name search, as before. */
  | { kind: 'name' }
  | { kind: 'category'; category: CategoryKey; placeHint?: string }
  | { kind: 'brand'; category: CategoryKey; brand: Brand; placeHint?: string };

/**
 * Folds text for matching: NFC, lower case, everything that is not a letter, a mark or a digit
 * becomes a space. Marks and the zero-width joiners stay: Bengali words are built with them.
 */
export function foldTokens(text: string): string[] {
  return text
    .normalize('NFC')
    .toLowerCase()
    .replace(/[^\p{L}\p{M}\p{N}‌‍]+/gu, ' ')
    .trim()
    .split(' ')
    .filter((token) => token !== '');
}

const startsWith = (tokens: string[], at: number, phraseTokens: string[]) =>
  phraseTokens.length > 0 &&
  at + phraseTokens.length <= tokens.length &&
  phraseTokens.every((token, index) => tokens[at + index] === token);

/** Length of the longest of the brand's terms that `tokens` contains as whole words; 0 for none. */
function brandMatch(tokens: string[], brand: Brand): number {
  return Math.max(
    0,
    ...brand.terms
      .filter((term) => tokens.some((_, at) => startsWith(tokens, at, term)))
      .map((term) => term.length),
  );
}

export interface Classifier {
  classify(query: string): QueryIntent;
  /**
   * True when a place with this name belongs to the brand. A name that carries several brands'
   * terms belongs to the one with the longest: "State Bank of India" is not "Bank of India".
   */
  isBrand(name: string, brand: Brand): boolean;
  /** An English phrase for a category, for a provider that can only search by text. */
  labelOf(category: CategoryKey): string;
}

/**
 * Builds the classifier from a dictionary. Pure: no clock, no network, no state.
 *
 * WHOLE WORDS ONLY, and no spelling tolerance beyond the misspellings listed in the dictionary.
 * A fuzzy match is exactly what turned "bank" into Banka and Bankura; here "bankura" is one
 * token that is not in the dictionary, so it stays a name search.
 *
 * Shape of a classified query: [filler] (brand | category){1,2} [joiner] [place hint] [filler].
 * A place hint WITHOUT a joiner is accepted only after a brand ("sbi exampletown"). After a
 * bare category word it is not, because that is how streets and neighbourhoods are named
 * ("college street", "hospital road", "police line"): those stay name searches.
 */
export function createClassifier(input: unknown): Classifier {
  const parsed = IntentDictionarySchema.parse(input);
  const categories = parsed.categories.map((entry) => ({
    tokens: foldTokens(entry.phrase),
    category: entry.category,
  }));
  const brands = parsed.brands.map((entry) => ({
    category: entry.category,
    brand: {
      name: entry.name,
      terms: [entry.name, ...entry.aliases].map(foldTokens),
    } satisfies Brand,
  }));
  const wordsOf = (role: 'joiner' | 'filler') =>
    parsed.words.filter((word) => word.role === role).map((word) => foldTokens(word.phrase));
  const joiners = wordsOf('joiner');
  const fillers = wordsOf('filler');

  /** Length of the longest phrase that starts at `at`, with what it stands for. */
  function longest<T>(tokens: string[], at: number, candidates: { tokens: string[]; value: T }[]) {
    let best: { length: number; value: T } | undefined;
    for (const candidate of candidates) {
      if (
        startsWith(tokens, at, candidate.tokens) &&
        candidate.tokens.length > (best?.length ?? 0)
      ) {
        best = { length: candidate.tokens.length, value: candidate.value };
      }
    }
    return best;
  }

  function stripFillers(tokens: string[]): string[] {
    let rest = tokens;
    for (let changed = true; changed;) {
      changed = false;
      for (const filler of fillers) {
        if (startsWith(rest, 0, filler)) {
          rest = rest.slice(filler.length);
          changed = true;
        } else if (startsWith(rest, rest.length - filler.length, filler)) {
          rest = rest.slice(0, rest.length - filler.length);
          changed = true;
        }
      }
    }
    return rest;
  }

  return {
    isBrand(name, brand) {
      const tokens = foldTokens(name);
      const own = brandMatch(tokens, brand);
      return (
        own > 0 &&
        brands.every(
          (other) => other.brand.name === brand.name || brandMatch(tokens, other.brand) <= own,
        )
      );
    },

    labelOf: (category) =>
      parsed.categories.find((entry) => entry.category === category && entry.script === 'en')
        ?.phrase ?? category.replace(/_/g, ' '),

    classify(query) {
      const tokens = stripFillers(foldTokens(query));
      let at = 0;
      let brand: (typeof brands)[number] | undefined;
      let category: CategoryKey | undefined;
      for (let round = 0; round < 2; round += 1) {
        const brandHit =
          brand === undefined
            ? longest(
                tokens,
                at,
                brands.flatMap((entry) =>
                  entry.brand.terms.map((term) => ({ tokens: term, value: entry })),
                ),
              )
            : undefined;
        const categoryHit =
          category === undefined
            ? longest(
                tokens,
                at,
                categories.map((entry) => ({ tokens: entry.tokens, value: entry.category })),
              )
            : undefined;
        // "bank of baroda" is a brand, not the category "bank" with a place called "of baroda".
        if (brandHit !== undefined && brandHit.length >= (categoryHit?.length ?? 0)) {
          brand = brandHit.value;
          at += brandHit.length;
        } else if (categoryHit !== undefined) {
          category = categoryHit.value;
          at += categoryHit.length;
        } else {
          break;
        }
      }
      let rest = tokens.slice(at);
      const joined = joiners.find((joiner) => startsWith(rest, 0, joiner));
      if (joined !== undefined) rest = rest.slice(joined.length);
      if (rest.length > 0 && joined === undefined && brand === undefined) return { kind: 'name' };
      const hint = rest.join(' ');
      // One letter is not a place; the geocoder needs two code points anyway.
      if (rest.length > 0 && countCodePoints(hint) < 2) return { kind: 'name' };
      const placeHint = rest.length > 0 ? { placeHint: hint } : {};

      if (brand !== undefined) {
        // "sbi atm": the word after the brand says which kind of its places is meant.
        return {
          kind: 'brand',
          category: category ?? brand.category,
          brand: brand.brand,
          ...placeHint,
        };
      }
      return category === undefined
        ? { kind: 'name' }
        : { kind: 'category', category, ...placeHint };
    },
  };
}

/** The committed dictionary (`intents.json`). */
export const INTENT_DICTIONARY_VERSION = dictionary.version;
export const classifier = createClassifier(dictionary);
