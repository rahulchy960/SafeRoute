// SPDX-License-Identifier: AGPL-3.0-only
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DEFAULT_SPEC_PATH, run, STALE_MESSAGE } from '../src/contract/generate.js';
import { buildOpenApiDocument, serializeOpenApiDocument } from '../src/contract/openapi.js';
import { PROBLEM_MEDIA_TYPE } from '../src/contract/responses.js';
import { buildTestApp } from './helpers.js';

/**
 * Every path in the contract. A prompt that adds routes extends this list in the same PR, so a
 * route can't appear (or vanish) without a reviewer seeing it here.
 */
const DOCUMENTED_PATHS = ['/health', '/health/ready', '/v1/me', '/v1/me/bootstrap'];

/** Operations that are public by design; every other operation must require firebaseBearer. */
const PUBLIC_OPERATIONS = ['getHealth', 'getReadiness'];

const HTTP_METHODS = ['get', 'put', 'post', 'delete', 'options', 'head', 'patch', 'trace'];

type Json = Record<string, unknown>;

const doc = buildOpenApiDocument() as unknown as Json;
const serialized = serializeOpenApiDocument(buildOpenApiDocument());
const components = doc.components as Record<string, Json>;

function operations(): { method: string; path: string; op: Json }[] {
  return Object.entries(doc.paths as Record<string, Json>).flatMap(([path, item]) =>
    HTTP_METHODS.filter((method) => method in item).map((method) => ({
      method,
      path,
      op: item[method] as Json,
    })),
  );
}

/** Follows `#/components/responses/<Name>` references. */
function resolveResponse(response: Json): Json {
  const ref = response.$ref;
  if (typeof ref !== 'string') return response;
  const name = ref.replace('#/components/responses/', '');
  return components.responses?.[name] as Json;
}

describe('generated OpenAPI document', () => {
  it('is deterministic', () => {
    expect(serializeOpenApiDocument(buildOpenApiDocument())).toBe(serialized);
    expect(serialized.endsWith('}\n')).toBe(true);
    expect(serialized).not.toContain('\r');
  });

  it('matches the committed contracts/openapi.json (run pnpm openapi:generate if not)', () => {
    expect(readFileSync(DEFAULT_SPEC_PATH, 'utf8')).toBe(serialized);
  });

  it('has the expected top-level shape', () => {
    expect(doc.openapi).toMatch(/^3\.1\.\d+$/);
    const info = doc.info as Json;
    expect(info.title).toBe('SafeRoute API');
    expect(info.version).toMatch(/^\d+\.\d+\.\d+$/);
    expect(info).not.toHaveProperty('contact');
    expect(info.license).toEqual({ name: 'AGPL-3.0-only', identifier: 'AGPL-3.0-only' });
    expect(doc.servers).toEqual([{ url: '/' }]);
    expect(Object.keys(doc.paths as Json).sort()).toEqual([...DOCUMENTED_PATHS].sort());
  });

  it('declares the shared components', () => {
    expect(components.schemas).toHaveProperty('ProblemDetails');
    expect(components.parameters).toHaveProperty('IdempotencyKey');
    expect(components.securitySchemes).toHaveProperty('firebaseBearer');
    for (const name of ['BadRequest', 'Unauthorized', 'NotFound', 'InternalError']) {
      expect(components.responses).toHaveProperty(name);
    }
  });

  it('gives every operation a unique operationId, a tag, a summary and problem+json errors', () => {
    const ids = operations().map(({ op }) => op.operationId);
    expect(new Set(ids).size).toBe(ids.length);

    for (const { method, path, op } of operations()) {
      const where = `${method.toUpperCase()} ${path}`;
      expect(op.operationId, where).toMatch(/^[a-z][A-Za-z0-9]+$/);
      expect((op.tags as string[] | undefined)?.length, where).toBeGreaterThan(0);
      expect(op.summary, where).toBeTruthy();

      const responses = Object.entries(op.responses as Record<string, Json>);
      const errors = responses.filter(([status]) => Number(status) >= 400);
      expect(errors.length, where).toBeGreaterThan(0);
      for (const [status, response] of errors) {
        const content = resolveResponse(response).content as Json;
        expect(Object.keys(content), `${where} ${status}`).toEqual([PROBLEM_MEDIA_TYPE]);
      }
    }
  });

  it('contains no absolute URLs, hosts, emails or local paths', () => {
    expect(serialized).not.toMatch(/https?:\/\//i);
    expect(serialized).not.toMatch(/[\w.+-]+@[\w-]+\.[\w.]+/);
    expect(serialized).not.toMatch(/[A-Za-z]:\\\\|\/Users\/|\/home\//);
  });

  it('protects every non-public operation with firebaseBearer (ADR 0006)', () => {
    for (const { method, path, op } of operations()) {
      const where = `${method.toUpperCase()} ${path}`;
      if (PUBLIC_OPERATIONS.includes(op.operationId as string)) {
        expect(op.security, where).toEqual([]);
      } else {
        expect(op.security, where).toEqual([{ firebaseBearer: [] }]);
        const responses = op.responses as Record<string, Json>;
        expect(responses['401'], where).toEqual({ $ref: '#/components/responses/Unauthorized' });
      }
    }
  });

  it('documents the /v1/me operations (P005)', () => {
    const paths = doc.paths as Record<string, Record<string, Json>>;
    expect(paths['/v1/me']?.get?.operationId).toBe('getMe');
    expect(paths['/v1/me/bootstrap']?.post?.operationId).toBe('bootstrapMe');
    expect(Object.keys(paths['/v1/me/bootstrap']?.post?.responses as Json).sort()).toEqual([
      '200',
      '201',
      '400',
      '401',
      '403',
      '409',
      '500',
      '503',
    ]);
    const me = components.schemas?.Me as { properties: Json; required: string[] };
    expect(Object.keys(me.properties).sort()).toEqual([
      'createdAt',
      'displayName',
      'id',
      'locale',
      'phoneE164',
      'role',
    ]);
    expect(serialized).not.toMatch(/firebaseUid|firebase_uid|deletedAt/);
    const unauthorized = components.responses?.Unauthorized as { headers: Json };
    expect(unauthorized.headers).toHaveProperty('WWW-Authenticate');
  });

  it('is city-neutral (ADR 0005)', () => {
    expect(serialized.toLowerCase()).not.toContain('kolkata');
  });
});

describe('no undocumented routes', () => {
  it('every route on the production app is in the spec, and vice versa', () => {
    const { app } = buildTestApp();
    const registered = app.routes
      // Middleware registered with app.use('*') shows up as method ALL; it is not an endpoint.
      .filter((route) => route.method !== 'ALL')
      .map((route) => `${route.method} ${route.path.replace(/:(\w+)/g, '{$1}')}`);
    const documented = operations().map(({ method, path }) => `${method.toUpperCase()} ${path}`);

    expect([...new Set(registered)].sort()).toEqual(documented.sort());
  });
});

describe('pnpm openapi:check', () => {
  const dirs: string[] = [];
  afterEach(() => {
    vi.restoreAllMocks();
    for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true });
  });

  function tempCopy(content: string): string {
    const dir = mkdtempSync(join(tmpdir(), 'openapi-check-'));
    dirs.push(dir);
    const file = join(dir, 'openapi.json');
    writeFileSync(file, content, 'utf8');
    return file;
  }

  it('passes on an up-to-date file', () => {
    vi.spyOn(process.stdout, 'write').mockReturnValue(true);
    expect(run(['--check', '--file', tempCopy(serialized)])).toBe(0);
  });

  it('fails with the documented message on an edited file', () => {
    const stderr = vi.spyOn(process.stderr, 'write').mockReturnValue(true);
    const edited = serialized.replace('Liveness probe', 'Liveness probe (edited by hand)');
    expect(edited).not.toBe(serialized);

    expect(run(['--check', '--file', tempCopy(edited)])).toBe(1);
    expect(stderr).toHaveBeenCalledWith(`${STALE_MESSAGE}\n`);
  });

  it('fails when the file is missing', () => {
    vi.spyOn(process.stderr, 'write').mockReturnValue(true);
    const dir = mkdtempSync(join(tmpdir(), 'openapi-check-'));
    dirs.push(dir);
    expect(run(['--check', '--file', join(dir, 'missing.json')])).toBe(1);
  });
});
