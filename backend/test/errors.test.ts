import { describe, expect, it } from 'vitest';
import { AppError } from '../src/lib/problem.js';
import { accessLines, buildTestApp } from './helpers.js';

// HTTP bodies use camelCase `requestId` (ADR 0004); log lines keep snake_case `request_id`.
const PROBLEM_KEYS = ['code', 'detail', 'requestId', 'status', 'title', 'type'];

async function problemOf(res: Response) {
  expect(res.headers.get('content-type')).toBe('application/problem+json');
  const body = (await res.json()) as Record<string, unknown>;
  expect(Object.keys(body).sort()).toEqual(PROBLEM_KEYS);
  return body;
}

describe('problem+json errors', () => {
  it('404 for unknown routes', async () => {
    const { app } = buildTestApp();
    const res = await app.request('/nope', { headers: { 'X-Request-Id': 'req-404-abcdef' } });

    expect(res.status).toBe(404);
    expect(res.headers.get('x-request-id')).toBe('req-404-abcdef');
    expect(await problemOf(res)).toEqual({
      type: 'about:blank',
      title: 'Not Found',
      status: 404,
      detail: 'No route matches this request.',
      code: 'not_found',
      requestId: 'req-404-abcdef',
    });
  });

  it('500 internal_error for unhandled errors without leaking the message or stack', async () => {
    const { app, logs } = buildTestApp();
    app.get('/__test/boom', () => {
      throw new Error('db password is hunter2');
    });

    const res = await app.request('/__test/boom', {
      headers: { 'X-Request-Id': 'req-500-abcdef' },
    });
    expect(res.status).toBe(500);
    expect(res.headers.get('x-request-id')).toBe('req-500-abcdef');

    const text = await res.clone().text();
    expect(text).not.toContain('hunter2');
    expect(text).not.toContain('Error:');
    expect(text).not.toMatch(/\bat .+:\d+:\d+/); // stack frame
    expect(await problemOf(res)).toEqual({
      type: 'about:blank',
      title: 'Internal Server Error',
      status: 500,
      detail: 'An unexpected error occurred.',
      code: 'internal_error',
      requestId: 'req-500-abcdef',
    });

    // The full error is logged server-side, tied to the request.
    const errorLine = logs().find((line) => line.message === 'unhandled error');
    expect(errorLine).toMatchObject({ severity: 'ERROR', request_id: 'req-500-abcdef' });
    const err = errorLine?.err as Record<string, unknown> | undefined;
    expect(err?.message).toBe('db password is hunter2');
    expect(err?.stack).toEqual(expect.stringContaining('Error: db password is hunter2'));
    expect(accessLines(logs())).toHaveLength(1);
  });

  it('AppError maps to the same problem+json shape', async () => {
    const { app } = buildTestApp();
    app.post('/__test/conflict', () => {
      throw new AppError(409, 'conflict', 'This resource already exists.');
    });

    const res = await app.request('/__test/conflict', {
      method: 'POST',
      headers: { 'X-Request-Id': 'req-409-abcdef' },
    });
    expect(res.status).toBe(409);
    expect(await problemOf(res)).toEqual({
      type: 'about:blank',
      title: 'Conflict',
      status: 409,
      detail: 'This resource already exists.',
      code: 'conflict',
      requestId: 'req-409-abcdef',
    });
  });

  it('AppError with a 5xx status is logged', async () => {
    const { app, logs } = buildTestApp();
    app.get('/__test/unavailable', () => {
      throw new AppError(503, 'provider_unavailable', 'Try again shortly.');
    });

    const res = await app.request('/__test/unavailable');
    expect(res.status).toBe(503);
    expect((await problemOf(res)).code).toBe('provider_unavailable');
    expect(logs().some((line) => line.message === 'request failed')).toBe(true);
  });
});
