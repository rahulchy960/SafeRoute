// SPDX-License-Identifier: AGPL-3.0-only

export const QUERY_MIN_CODE_POINTS = 2;
export const QUERY_MAX_CODE_POINTS = 100;

/** C0 controls (including tab and newline), DEL and C1 controls. */
// eslint-disable-next-line no-control-regex -- matching control characters is the point
const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f-\u009f]/;

/**
 * Normalises what the user typed (ADR 0018). Returns undefined when it is not a usable query.
 *
 * 1. Unicode NFC: the same Bengali word can arrive as different code-point sequences (a letter
 *    plus a combining sign, or one precomposed letter). NFC makes them one.
 * 2. Control characters are rejected, not removed.
 * 3. Trim, and collapse runs of whitespace to one space.
 * 4. Length is counted in CODE POINTS (`countCodePoints`), not UTF-16 units (`text.length`).
 *
 * Zero-width joiner (U+200D) and non-joiner (U+200C) are KEPT: Bengali uses them inside words to
 * choose between letter forms, and removing them changes the spelling.
 */
export function normalizeQuery(raw: string): string | undefined {
  const composed = raw.normalize('NFC');
  if (CONTROL_CHARACTERS.test(composed)) return undefined;
  const text = composed.trim().replace(/\s+/g, ' ');
  const length = countCodePoints(text);
  if (length < QUERY_MIN_CODE_POINTS || length > QUERY_MAX_CODE_POINTS) return undefined;
  return text;
}

/** With the `u` flag `.` matches one code point; `.length` would count UTF-16 units. */
export function countCodePoints(text: string): number {
  return text.match(/./gsu)?.length ?? 0;
}

/**
 * Rounds a coordinate to two decimals, about 1 km. The geocoder only needs to know roughly where
 * the map is; the server coarsens whatever the client sent, so a precise position never leaves
 * SafeRoute (ADR 0018).
 */
export function coarsen(degrees: number): number {
  const rounded = Math.round(degrees * 100) / 100;
  return rounded === 0 ? 0 : rounded;
}
