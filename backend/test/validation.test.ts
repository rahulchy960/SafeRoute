// SPDX-License-Identifier: AGPL-3.0-only
import { createRoute, z } from '@hono/zod-openapi';
import { describe, expect, it } from 'vitest';
import { ProblemDetailsSchema } from '../src/contract/problem.js';
import { errorResponses } from '../src/contract/responses.js';
import { buildTestApp } from './helpers.js';

// Test-only route: registered on a test app instance, never on the production app.
const echoRoute = createRoute({
  method: 'post',
  path: '/__test/validate',
  operationId: 'testValidate',
  tags: ['operational'],
  summary: 'Test-only route with a body schema',
  request: {
    body: {
      required: true,
      content: {
        'application/json': {
          schema: z.object({
            latitude: z.number().min(-90).max(90),
            longitude: z.number().min(-180).max(180),
            phone: z.string().regex(/^\+\d{8,15}$/),
          }),
        },
      },
    },
  },
  responses: {
    204: { description: 'Valid.' },
    ...errorResponses(400),
  },
});

function appWithTestRoute() {
  const test = buildTestApp();
  test.app.openapi(echoRoute, (c) => c.body(null, 204));
  return test;
}

// Values that must never come back in a response body or a log line.
const SUBMITTED = {
  latitude: 222.567891,
  longitude: 'eighty-eight',
  phone: 'call +91 00000 00000',
};

describe('validation errors (defaultHook)', () => {
  it('returns 400 validation_error with { path, code } entries and no submitted values', async () => {
    const { app, lines } = appWithTestRoute();
    const res = await app.request('/__test/validate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Request-Id': 'req-400-abcdef' },
      body: JSON.stringify(SUBMITTED),
    });

    expect(res.status).toBe(400);
    expect(res.headers.get('content-type')).toBe('application/problem+json');
    expect(res.headers.get('x-request-id')).toBe('req-400-abcdef');

    const text = await res.text();
    const body = ProblemDetailsSchema.parse(JSON.parse(text));
    expect(body).toMatchObject({
      type: 'about:blank',
      title: 'Bad Request',
      status: 400,
      code: 'validation_error',
      requestId: 'req-400-abcdef',
    });
    expect(body.errors).toEqual(
      expect.arrayContaining([
        { path: 'body.latitude', code: 'too_big' },
        { path: 'body.longitude', code: 'invalid_type' },
        { path: 'body.phone', code: 'invalid_format' },
      ]),
    );
    for (const issue of body.errors ?? []) {
      expect(Object.keys(issue).sort()).toEqual(['code', 'path']);
    }

    for (const output of [text, lines.join('')]) {
      for (const value of ['222.567891', 'eighty-eight', '00000 00000', 'call +91']) {
        expect(output).not.toContain(value);
      }
    }
  });

  it('lets a valid body through to the handler', async () => {
    const { app } = appWithTestRoute();
    const res = await app.request('/__test/validate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ latitude: 22.5726, longitude: 88.3639, phone: '+910000000000' }),
    });
    expect(res.status).toBe(204);
  });
});

describe('problem bodies use requestId', () => {
  it('the generated id in the body matches the X-Request-Id header', async () => {
    const { app } = buildTestApp();
    const res = await app.request('/nope');

    const header = res.headers.get('x-request-id');
    expect(header).toBeTruthy();
    const body = ProblemDetailsSchema.parse(await res.json());
    expect(body.requestId).toBe(header);
    expect(body).not.toHaveProperty('request_id');
  });
});
