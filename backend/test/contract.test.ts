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
const DOCUMENTED_PATHS = [
  '/health',
  '/health/ready',
  '/v1/me',
  '/v1/me/bootstrap',
  '/v1/me/consents',
  '/v1/me/consents/{purpose}',
  '/v1/search',
];

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

  it('documents consent and the age declaration (P009a, ADR 0010)', () => {
    const paths = doc.paths as Record<string, Record<string, Json>>;
    expect(paths['/v1/me/consents']?.get?.operationId).toBe('getMyConsents');
    expect(paths['/v1/me/consents/{purpose}']?.put?.operationId).toBe('setMyConsent');
    expect(Object.keys(paths['/v1/me/consents/{purpose}']?.put?.responses as Json).sort()).toEqual([
      '200',
      '400',
      '401',
      '403',
      '409',
      '500',
      '503',
    ]);

    // Additive: `consent` is optional in the bootstrap request, and nothing became required.
    const request = components.schemas?.BootstrapMeRequest as {
      properties: Json;
      required?: string[];
    };
    expect(request.properties).toHaveProperty('consent');
    expect(request.required ?? []).toEqual([]);
    const consent = components.schemas?.BootstrapConsent as {
      properties: Json;
      required: string[];
    };
    expect(Object.keys(consent.properties).sort()).toEqual([
      'ageConfirmed',
      'noticeLocale',
      'noticeVersion',
      'purposes',
    ]);

    // `purpose` and `status` stay open strings in responses: a new purpose is not a breaking change.
    const item = components.schemas?.Consent as { properties: Record<string, Json> };
    expect(item.properties.purpose).not.toHaveProperty('enum');
    expect(item.properties.status).not.toHaveProperty('enum');
    expect(Object.keys(item.properties).sort()).toEqual([
      'decidedAt',
      'noticeVersion',
      'purpose',
      'status',
    ]);

    const code = (components.schemas?.ProblemDetails as { properties: Record<string, Json> })
      .properties.code?.description as string;
    for (const name of ['consent_required', 'adult_required', 'account_deletion_required']) {
      expect(code).toContain(`\`${name}\``);
    }
    // No date-of-birth or age field anywhere in the contract (ADR 0010).
    expect(serialized).not.toMatch(/"(dateOfBirth|birthDate|birthday|dob|age)"\s*:/i);
  });

  it('documents place search (P011a, ADR 0018)', () => {
    expect((doc.info as Json).version).toBe('0.4.0');
    const paths = doc.paths as Record<string, Record<string, Json>>;
    const op = paths['/v1/search']?.get ?? {};
    expect(op.operationId).toBe('searchPlaces');
    expect(op.tags).toEqual(['search']);
    expect(Object.keys(op.responses as Json).sort()).toEqual([
      '200',
      '400',
      '401',
      '403',
      '429',
      '500',
      '503',
    ]);
    const parameters = op.parameters as { name: string; in: string; required?: boolean }[];
    expect(parameters.map((p) => `${p.in}:${p.name}`).sort()).toEqual([
      'query:language',
      'query:limit',
      'query:nearLatitude',
      'query:nearLongitude',
      'query:q',
    ]);
    // Only `q` is required: everything else has a default or is optional.
    expect(parameters.filter((p) => p.required).map((p) => p.name)).toEqual(['q']);
    // Explicit latitude/longitude, never a bare `near` pair (ADR 0004).
    expect(parameters.map((p) => p.name)).not.toContain('near');

    const place = components.schemas?.Place as { properties: Record<string, Json> };
    expect(Object.keys(place.properties).sort()).toEqual([
      'id',
      'kind',
      'label',
      'latitude',
      'longitude',
      'name',
    ]);
    expect(place.properties.kind).not.toHaveProperty('enum');
    const results = components.schemas?.SearchResults as { properties: Json; required: string[] };
    expect(Object.keys(results.properties).sort()).toEqual(['attribution', 'results']);

    const code = (components.schemas?.ProblemDetails as { properties: Record<string, Json> })
      .properties.code?.description as string;
    for (const name of ['rate_limited', 'search_unavailable', 'search_not_configured']) {
      expect(code).toContain('`' + name + '`');
    }
    for (const name of ['TooManyRequests', 'ServiceUnavailable']) {
      expect((components.responses?.[name] as { headers: Json }).headers).toHaveProperty(
        'Retry-After',
      );
    }
    // No provider is named in the contract: swapping it is not a contract change.
    expect(serialized.toLowerCase()).not.toMatch(/geoapify|locationiq|maptiler|nominatim/);
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
