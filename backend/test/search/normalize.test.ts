// SPDX-License-Identifier: AGPL-3.0-only
import { describe, expect, it } from 'vitest';
import { coarsen, countCodePoints, normalizeQuery } from '../../src/modules/search/normalize.js';

const ZWJ = '‍';
const ZWNJ = '‌';

describe('normalizeQuery', () => {
  it('trims and collapses whitespace', () => {
    expect(normalizeQuery('   main   railway   station  ')).toBe('main railway station');
  });

  it.each([
    ['one code point', 'a', undefined],
    ['two code points', 'ab', 'ab'],
    ['100 code points', 'a'.repeat(100), 'a'.repeat(100)],
    ['101 code points', 'a'.repeat(101), undefined],
    ['whitespace only', '     ', undefined],
    ['empty', '', undefined],
    ['one letter padded with spaces', '   a   ', undefined],
  ])('%s', (_name, input, expected) => {
    expect(normalizeQuery(input)).toBe(expected);
  });

  it('counts code points, not UTF-16 units', () => {
    // U+1F600 is one code point and two UTF-16 units.
    const astral = '\u{1F600}';
    expect(astral.length).toBe(2);
    expect(countCodePoints(astral)).toBe(1);
    expect(normalizeQuery(astral)).toBeUndefined();
    expect(normalizeQuery(astral.repeat(100))).toBe(astral.repeat(100));
    expect(normalizeQuery(astral.repeat(101))).toBeUndefined();
  });

  it.each(['\u0000', '\u0007', '\t', '\n', '\r', '\u001f', '\u007f', '\u0085', '\u009f'])(
    'rejects the control character %j',
    (control) => {
      expect(normalizeQuery(`rail${control}way`)).toBeUndefined();
    },
  );

  it('applies NFC, so two spellings of the same Bengali word become one', () => {
    // U+09CB (one vowel sign) and U+09C7 U+09BE (two signs) render the same.
    const precomposed = 'কোন পথ';
    const decomposed = 'কোন পথ';
    expect(decomposed).not.toBe(precomposed);
    expect(normalizeQuery(decomposed)).toBe(precomposed);
    expect(normalizeQuery(precomposed)).toBe(precomposed);
  });

  it('keeps zero-width joiner and non-joiner inside Bengali words', () => {
    const withZwj = `র${ZWJ}্যাব স্টেশন`;
    const withZwnj = `হাওড়া${ZWNJ} স্টেশন`;
    expect(normalizeQuery(withZwj)).toBe(withZwj);
    expect(normalizeQuery(withZwj)).toContain(ZWJ);
    expect(normalizeQuery(withZwnj)).toContain(ZWNJ);
  });
});

describe('coarsen', () => {
  it.each([
    [10.123456, 10.12],
    [20.987654, 20.99],
    [-10.126, -10.13],
    [10.1, 10.1],
    [0.001, 0],
    [-0.001, 0],
  ])('%d → %d', (input, expected) => {
    expect(coarsen(input)).toBe(expected);
    expect(Object.is(coarsen(input), -0)).toBe(false);
  });
});
