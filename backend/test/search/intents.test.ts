// SPDX-License-Identifier: AGPL-3.0-only
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  CATEGORY_KEYS,
  classifier,
  createClassifier,
  foldTokens,
  IntentDictionarySchema,
  type QueryIntent,
} from '../../src/modules/search/intents.js';
import { GEOAPIFY_CATEGORIES } from '../../src/modules/search/providers/geoapify.js';

const dictionary = IntentDictionarySchema.parse(
  JSON.parse(
    readFileSync(join(import.meta.dirname, '../../src/modules/search/intents.json'), 'utf8'),
  ),
);

/** `kind category[/brand name][ @hint]`, short enough to read in a table. */
function show(intent: QueryIntent): string {
  if (intent.kind === 'name') return 'name';
  const hint = intent.placeHint === undefined ? '' : ` @${intent.placeHint}`;
  return intent.kind === 'brand'
    ? `brand ${intent.category}/${intent.brand.name}${hint}`
    : `category ${intent.category}${hint}`;
}

describe('the intent dictionary file', () => {
  it('is small, every entry is vouched for, and Bengali entries await a native speaker', () => {
    const entries = [...dictionary.categories, ...dictionary.words];
    expect(entries.length + dictionary.brands.length).toBeLessThan(200);
    for (const entry of [...entries, ...dictionary.brands]) {
      expect(entry.addedBy).toBe('claude-known');
    }
    for (const entry of entries) {
      const bengali = /[ঀ-৿]/.test(entry.phrase);
      expect(entry.script === 'bn', entry.phrase).toBe(bengali);
      expect(entry.review, entry.phrase).toBe(entry.script === 'en' ? undefined : 'native-speaker');
    }
  });

  it('has no phrase twice, and every typed category has an English and a Bengali phrase', () => {
    const phrases = [
      ...dictionary.categories.map((entry) => entry.phrase),
      ...dictionary.brands.flatMap((entry) => [entry.name, ...entry.aliases]),
      ...dictionary.words.map((entry) => entry.phrase),
    ].map((text) => foldTokens(text).join(' '));
    expect(new Set(phrases).size).toBe(phrases.length);
    for (const key of CATEGORY_KEYS.filter((item) => item !== 'public_transport')) {
      const scripts = dictionary.categories.filter((e) => e.category === key).map((e) => e.script);
      expect(scripts, key).toContain('en');
      expect(scripts, key).toContain('bn');
    }
  });

  it('every category has a provider mapping and an English label', () => {
    for (const key of CATEGORY_KEYS) {
      expect(GEOAPIFY_CATEGORIES[key]).toMatch(/^[a-z_.]+(,[a-z_.]+)*$/);
      expect(classifier.labelOf(key).length).toBeGreaterThan(1);
    }
    expect(classifier.labelOf('public_transport')).toBe('public transport');
  });

  it('refuses a malformed dictionary', () => {
    expect(() => createClassifier({ ...dictionary, version: 2 })).toThrow();
    expect(() =>
      createClassifier({
        ...dictionary,
        categories: [{ phrase: 'x', category: 'casino', script: 'en', addedBy: 'rahul' }],
      }),
    ).toThrow();
  });
});

describe('classify', () => {
  it.each([
    // Category words: case, spaces and punctuation do not matter.
    ['bank', 'category bank'],
    ['  BANK  ', 'category bank'],
    ['Bank.', 'category bank'],
    ['atm', 'category atm'],
    ['medicine shop', 'category pharmacy'],
    ['Chemist', 'category pharmacy'],
    ['doctor', 'category clinic'],
    ['petrol pump', 'category fuel'],
    ['bus stand', 'category bus'],
    ['railway station', 'category train'],
    ['police station', 'category police'],
    ['post-office', 'category post_office'],
    ['school', 'category school'],
    ['college', 'category college'],
    ['grocery', 'category grocery'],
    ['restaurant', 'category restaurant'],
    ['hospital', 'category hospital'],
    // Bengali script and the informal Latin spelling.
    ['ব্যাংক', 'category bank'],
    ['ব্যাঙ্ক', 'category bank'],
    ['হাসপাতাল', 'category hospital'],
    ['ওষুধের দোকান', 'category pharmacy'],
    ['থানা', 'category police'],
    ['ডাকঘর', 'category post_office'],
    ['haspatal', 'category hospital'],
    ['thana', 'category police'],
    // The misspellings that are listed, and no others.
    ['pharmecy', 'category pharmacy'],
    ['resturant', 'category restaurant'],
    // Words around the category.
    ['pharmacy near me', 'category pharmacy'],
    ['nearest atm', 'category atm'],
    ['ব্যাংক কাছাকাছি', 'category bank'],
    // Brands, with and without the category word.
    ['sbi', 'brand bank/State Bank of India'],
    ['SBI Bank', 'brand bank/State Bank of India'],
    ['state bank of india', 'brand bank/State Bank of India'],
    ['sbi atm', 'brand atm/State Bank of India'],
    ['atm sbi', 'brand atm/State Bank of India'],
    ['sbi ব্যাংক', 'brand bank/State Bank of India'],
    ['pnb', 'brand bank/Punjab National Bank'],
    ['hdfc', 'brand bank/HDFC Bank'],
    ['icici bank', 'brand bank/ICICI Bank'],
    ['axis bank', 'brand bank/Axis Bank'],
    ['bank of baroda', 'brand bank/Bank of Baroda'],
    ['Apollo Pharmacy', 'brand pharmacy/Apollo Pharmacy'],
    ['medplus', 'brand pharmacy/MedPlus'],
    // Place hints: after a joiner for a category, bare after a brand.
    ['pharmacy near exampletown', 'category pharmacy @exampletown'],
    ['bank in example town', 'category bank @example town'],
    ['sbi exampletown', 'brand bank/State Bank of India @exampletown'],
    ['sbi atm near exampletown', 'brand atm/State Bank of India @exampletown'],
    ['apollo pharmacy exampletown', 'brand pharmacy/Apollo Pharmacy @exampletown'],
  ])('%s → %s', (query, expected) => {
    expect(show(classifier.classify(query))).toBe(expected);
  });

  it.each([
    // Names that merely resemble a category word: whole words only, no fuzzy matching.
    'Bankura',
    'Banka',
    'bankra',
    'atmaram',
    'schoolpara',
    // Streets and neighbourhoods named after a kind of place.
    'college street',
    'hospital road',
    'police line',
    'bank more',
    'school para',
    'station road',
    // A brand's word inside another name.
    'axis mall',
    'apollo hospital',
    'central bank of india',
    // Ordinary names, a bare joiner, misspellings that are not listed.
    'Howrah',
    'near me',
    'in exampletown',
    'bnak',
    'hospitl',
    'pharmacy near x',
    '',
  ])('"%s" stays a name search', (query) => {
    expect(classifier.classify(query)).toEqual({ kind: 'name' });
  });

  it('a decomposed Bengali phrase matches its composed dictionary entry', () => {
    // U+09DF (য়) written as U+09AF U+09BC.
    expect(show(classifier.classify('বিদ্যালয়'))).toBe('category school');
  });

  it('decides which brand a place name belongs to by the longest term', () => {
    const brand = (query: string) => {
      const intent = classifier.classify(query);
      if (intent.kind !== 'brand') throw new Error('not a brand');
      return intent.brand;
    };
    const sbi = brand('sbi');
    const boi = brand('bank of india');
    expect(classifier.isBrand('SBI', sbi)).toBe(true);
    expect(classifier.isBrand('State Bank Of India, Example Branch', sbi)).toBe(true);
    expect(classifier.isBrand('State Bank of India', boi)).toBe(false);
    expect(classifier.isBrand('Union Bank of India', boi)).toBe(false);
    expect(classifier.isBrand('Bank of India ATM', boi)).toBe(true);
    expect(classifier.isBrand('Example Co-operative Bank', sbi)).toBe(false);
    expect(classifier.isBrand('Sbimal Stores', sbi)).toBe(false);
  });
});
