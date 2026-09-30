// SPDX-License-Identifier: AGPL-3.0-only
import { describe, expect, it, vi } from 'vitest';
import type { ReadinessCheck } from '../src/routes/ready.js';
import { accessLines, buildTestApp } from './helpers.js';

const FAKE_URL = 'postgres://fake-user:fake-secret-pw@db.internal.example:5432/fake_db';

describe('GET /health/ready (injected check, no database)', () => {
  it('returns 200 ready when the check resolves', async () => {
    const check = vi.fn<ReadinessCheck>().mockResolvedValue(undefined);
    const { app } = buildTestApp({}, check);
    const res = await app.request('/health/ready');

    expect(res.status).toBe(200);
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(await res.json()).toEqual({ status: 'ready', checks: { database: 'ok' } });
    expect(check).toHaveBeenCalledOnce();
  });

  it('returns 503 db_unavailable when the check throws, without leaking connection details', async () => {
    const check: ReadinessCheck = () =>
      Promise.reject(new Error(`connect ECONNREFUSED while using ${FAKE_URL}`));
    const { app, lines, logs } = buildTestApp({}, check);
    const res = await app.request('/health/ready', {
      headers: { 'X-Request-Id': 'req-ready-abcdef' },
    });

    expect(res.status).toBe(503);
    expect(res.headers.get('content-type')).toBe('application/problem+json');
    expect(res.headers.get('cache-control')).toBe('no-store');
    const body = await res.text();
    expect(JSON.parse(body)).toMatchObject({
      code: 'db_unavailable',
      status: 503,
      requestId: 'req-ready-abcdef',
    });

    const failure = logs().find((line) => line.message === 'readiness check failed');
    expect(failure).toMatchObject({ severity: 'WARNING', request_id: 'req-ready-abcdef' });
    for (const text of [body, lines.join('')]) {
      expect(text).not.toContain('fake-secret-pw');
      expect(text).not.toContain('db.internal.example');
    }
    expect(accessLines(logs())[0]).toMatchObject({ path: '/health/ready', status: 503 });
  });

  it('returns 503 db_unavailable when the check exceeds the timeout', async () => {
    vi.useFakeTimers();
    try {
      const never: ReadinessCheck = () => new Promise(() => undefined);
      const { app } = buildTestApp({}, never);
      const pending = app.request('/health/ready');
      await vi.advanceTimersByTimeAsync(2_000);
      const res = await pending;
      expect(res.status).toBe(503);
      expect(await res.json()).toMatchObject({ code: 'db_unavailable' });
    } finally {
      vi.useRealTimers();
    }
  });

  it('returns 503 db_not_configured when no database is configured', async () => {
    const { app } = buildTestApp();
    const res = await app.request('/health/ready');
    expect(res.status).toBe(503);
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(await res.json()).toMatchObject({ code: 'db_not_configured' });
  });

  it('GET /health never calls the database check', async () => {
    const check = vi.fn<ReadinessCheck>().mockResolvedValue(undefined);
    const { app } = buildTestApp({}, check);
    expect((await app.request('/health')).status).toBe(200);
    expect(check).not.toHaveBeenCalled();
  });
});
