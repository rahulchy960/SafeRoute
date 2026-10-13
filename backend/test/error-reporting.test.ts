// SPDX-License-Identifier: AGPL-3.0-only
import { describe, expect, it } from 'vitest';
import { createLogger, safeError } from '../src/lib/logger.js';
import { AppError } from '../src/lib/problem.js';
import { buildTestApp } from './helpers.js';

// Everything below is made up: the number is in the contacts test range, the coordinates are
// round figures, the token is a fake.
const SECRETS = {
  query: 'pharmacy near the old bridge',
  coordinates: '22.5,88.25',
  name: 'Test Contact 1',
  phone: '+910000100001',
  token: 'FAKE.id-token.abcdef0123456789',
};

/** An error whose message quotes everything a request could carry, as a database driver might. */
function leakyError(): Error {
  const cause = new TypeError(`cannot parse "${SECRETS.coordinates}" for ${SECRETS.name}`);
  const err = new RangeError(
    `invalid input: q=${SECRETS.query}\nphone=${SECRETS.phone}\n    at fake (frame-in-message:1:1)\ntoken=${SECRETS.token}`,
    { cause },
  );
  Object.assign(err, {
    code: '22P02',
    detail: `Key (phone)=(${SECRETS.phone})`,
    input: SECRETS.token,
  });
  return err;
}

function expectNoSecrets(text: string): void {
  for (const [kind, value] of Object.entries(SECRETS)) {
    expect(text, `the log holds the ${kind}`).not.toContain(value);
  }
  expect(text).not.toContain('frame-in-message');
}

describe('errors in the log (Cloud Error Reporting shape, P014d2)', () => {
  it('keeps the class, a short code and the stack frames, and drops the message', () => {
    const safe = safeError(leakyError());

    expect(safe.type).toBe('RangeError');
    expect(safe.code).toBe('22P02');
    const lines = safe.stack_trace.split('\n');
    expect(lines[0]).toBe('RangeError: 22P02');
    expect(lines.length).toBeGreaterThan(1);
    // Every other line is a real frame: "    at function (file:line:column)".
    for (const line of lines.slice(1)) expect(line).toMatch(/^\s+at .+:\d+:\d+\)?$/);
    expectNoSecrets(JSON.stringify(safe));
  });

  it('refuses a code or a class name that is not a short machine word', () => {
    const odd = new Error('x');
    odd.name = `Error for ${SECRETS.phone}`;
    Object.assign(odd, { code: `q=${SECRETS.query}` });

    const safe = safeError(odd);

    expect(safe.type).toBe('Error');
    expect(safe.code).toBeUndefined();
    expect(safe.stack_trace.split('\n')[0]).toBe('Error: message not logged');
    expectNoSecrets(JSON.stringify(safe));
  });

  it('handles a thrown value that is not an Error', () => {
    expect(safeError(SECRETS.phone)).toEqual({
      type: 'NonError',
      stack_trace: 'NonError: a value that is not an Error was thrown',
    });
    expect(safeError(undefined).type).toBe('NonError');
  });

  it('an unhandled error is logged once, groupable, tied to the request, with nothing personal', async () => {
    const { app, lines, logs } = buildTestApp();
    app.post('/__test/leak', () => {
      throw leakyError();
    });

    const res = await app.request('/__test/leak', {
      method: 'POST',
      headers: { 'X-Request-Id': 'req-leak-abcdef' },
    });

    expect(res.status).toBe(500);
    expectNoSecrets(await res.text());
    // The whole captured output of the API's own logger for this request. It says nothing
    // about Cloud Run's request log, which the API does not write (ADR 0019).
    expectNoSecrets(lines.join('\n'));

    const errorLines = logs().filter((line) => line.severity === 'ERROR' && 'stack_trace' in line);
    expect(errorLines).toHaveLength(1);
    expect(errorLines[0]).toMatchObject({
      message: 'unhandled error',
      request_id: 'req-leak-abcdef',
      err: { type: 'RangeError', code: '22P02' },
      serviceContext: { service: 'saferoute-api' },
    });
    expect(Object.keys(errorLines[0]?.err as object).sort()).toEqual(['code', 'type']);
    expect(String(errorLines[0]?.stack_trace)).toMatch(/^RangeError: 22P02\n\s+at /);
  });

  it('an AppError with a 5xx status is logged the same way, with its code', async () => {
    const { app, lines, logs } = buildTestApp();
    app.get('/__test/down', () => {
      throw new AppError(503, 'search_unavailable', `provider said: ${SECRETS.query}`);
    });

    await app.request('/__test/down');

    const line = logs().find((entry) => entry.message === 'request failed');
    expect(line).toMatchObject({
      severity: 'ERROR',
      code: 'search_unavailable',
      err: { type: 'AppError' },
    });
    expect(String(line?.stack_trace).split('\n')[0]).toBe('AppError: search_unavailable');
    // The detail goes to the client, as before; it is not repeated in the log.
    expect(lines.join('\n')).not.toContain(SECRETS.query);
  });

  it('a line without an error is written as before', () => {
    const lines: string[] = [];
    const logger = createLogger(
      { LOG_LEVEL: 'info', SERVICE_NAME: 'saferoute-api', APP_VERSION: 'test' },
      { write: (chunk: string) => void lines.push(chunk) },
    );

    logger.info({ outcome: 'ok' }, 'routing call');

    const line = JSON.parse(lines[0] ?? '{}') as Record<string, unknown>;
    expect(line).toMatchObject({ severity: 'INFO', message: 'routing call', outcome: 'ok' });
    expect(line).not.toHaveProperty('stack_trace');
    expect(line).not.toHaveProperty('serviceContext');
  });
});
