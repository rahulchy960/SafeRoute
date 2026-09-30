import { describe, expect, it } from 'vitest';
import { accessLines, buildTestApp } from './helpers.js';

describe('access-log middleware', () => {
  it('writes exactly one line per request with the required fields', async () => {
    const { app, logs } = buildTestApp();
    const res = await app.request('/health', { headers: { 'X-Request-Id': 'req-abc-12345' } });
    const lines = accessLines(logs());

    expect(lines).toHaveLength(1);
    const line = lines[0];
    expect(line).toMatchObject({
      severity: 'INFO',
      request_id: 'req-abc-12345',
      method: 'GET',
      path: '/health',
      status: 200,
      service: 'saferoute-api',
      version: 'dev',
    });
    expect(typeof line?.duration_ms).toBe('number');
    expect(typeof line?.timestamp).toBe('string');
    expect(new Date(line?.timestamp as string).toISOString()).toBe(line?.timestamp);
    expect(res.headers.get('x-request-id')).toBe('req-abc-12345');
  });

  it('never logs query strings, credentials, cookies, bodies or client addresses', async () => {
    const { app, lines } = buildTestApp();
    await app.request('/health?lat=22.5726&lng=88.3639&token=share-secret-xyz', {
      headers: {
        Authorization: 'Bearer eyJ-secret-token',
        Cookie: 'session=cookie-secret-value',
        'X-Forwarded-For': '203.0.113.7',
      },
    });
    await app.request('/nope?phone=9876543210', { method: 'POST', body: 'body-secret-text' });

    const raw = lines.join('');
    for (const secret of [
      '?',
      'lat=',
      '22.5726',
      '88.3639',
      'share-secret-xyz',
      'eyJ-secret-token',
      'Bearer',
      'cookie-secret-value',
      '203.0.113.7',
      '9876543210',
      'body-secret-text',
      '/nope',
    ]) {
      expect(raw).not.toContain(secret);
    }
  });

  it('logs the route pattern, not the raw URL', async () => {
    const { app, logs } = buildTestApp();
    app.get('/items/:id', (c) => c.text('ok'));
    await app.request('/items/private-token-123');

    const line = accessLines(logs())[0];
    expect(line?.path).toBe('/items/:id');
    expect(JSON.stringify(logs())).not.toContain('private-token-123');
  });

  it.each([
    ['/health', 200, 'INFO'],
    ['/does-not-exist', 404, 'WARNING'],
    ['/__test/boom', 500, 'ERROR'],
  ])('maps %s (status %i) to severity %s', async (path, status, severity) => {
    const { app, logs } = buildTestApp();
    app.get('/__test/boom', () => {
      throw new Error('boom');
    });
    const res = await app.request(path);
    expect(res.status).toBe(status);

    const lines = accessLines(logs());
    expect(lines).toHaveLength(1);
    expect(lines[0]).toMatchObject({ status, severity });
  });
});
