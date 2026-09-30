import { describe, expect, it } from 'vitest';
import { buildTestApp } from './helpers.js';

describe('GET /health', () => {
  it('returns 200 with status, service, version and uptime', async () => {
    const { app } = buildTestApp({ APP_VERSION: '9.9.9' });
    const res = await app.request('/health');

    expect(res.status).toBe(200);
    expect(res.headers.get('content-type')).toMatch(/^application\/json/);
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(res.headers.get('x-request-id')).toBeTruthy();

    const body = (await res.json()) as Record<string, unknown>;
    expect(Object.keys(body).sort()).toEqual(['service', 'status', 'uptime_s', 'version']);
    expect(body).toMatchObject({ status: 'ok', service: 'saferoute-api', version: '9.9.9' });
    expect(Number.isInteger(body.uptime_s)).toBe(true);
    expect(body.uptime_s).toBeGreaterThanOrEqual(0);
  });
});
