import { describe, expect, it } from 'vitest';
import { REQUEST_ID_PATTERN } from '../src/middleware/request-id.js';
import { accessLines, buildTestApp } from './helpers.js';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

async function idFor(incoming?: string) {
  const { app, logs } = buildTestApp();
  const headers: Record<string, string> =
    incoming === undefined ? {} : { 'X-Request-Id': incoming };
  const res = await app.request('/health', { headers });
  const id = res.headers.get('x-request-id');
  return { id, logs: logs() };
}

describe('request-id middleware', () => {
  it('generates a UUID when the header is absent', async () => {
    const { id } = await idFor();
    expect(id).toMatch(UUID);
  });

  it('generates a different ID for each request', async () => {
    const { app } = buildTestApp();
    const a = (await app.request('/health')).headers.get('x-request-id');
    const b = (await app.request('/health')).headers.get('x-request-id');
    expect(a).not.toBe(b);
  });

  it.each(['abc12345', 'trace-ID_1.2.3', 'a'.repeat(64)])(
    'echoes a valid incoming ID %s',
    async (incoming) => {
      const { id, logs } = await idFor(incoming);
      expect(id).toBe(incoming);
      expect(accessLines(logs)[0]?.request_id).toBe(incoming);
    },
  );

  it.each([
    ['too short', 'abc1234'],
    ['too long', 'a'.repeat(65)],
    ['bad characters', 'abc123<script>'],
    ['contains a space', 'abc 12345'],
    ['contains a colon', 'user:12345678'],
    ['empty', ''],
  ])('replaces an invalid incoming ID (%s)', async (_label, incoming) => {
    const { id } = await idFor(incoming);
    expect(id).toMatch(UUID);
    expect(id).not.toBe(incoming);
  });

  // A request cannot carry a raw newline in a header (the Headers API throws), so this case is
  // checked against the pattern the middleware uses.
  it('rejects IDs containing a newline', () => {
    expect(REQUEST_ID_PATTERN.test('abc12345\nfake-log-line')).toBe(false);
    expect(REQUEST_ID_PATTERN.test('abc12345\n')).toBe(false);
  });
});
