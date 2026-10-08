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
  '/v1/routes',
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

  it('documents place search as a POST with a body (P011a, P011d; ADR 0018, ADR 0019)', () => {
    const paths = doc.paths as Record<string, Record<string, Json>>;
    // The search travels in a request body. There is no GET: a URL would carry the text.
    expect(Object.keys(paths['/v1/search'] ?? {})).toEqual(['post']);
    const op = paths['/v1/search']?.post ?? {};
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
    expect(op.parameters ?? []).toEqual([]);
    const requestBody = op.requestBody as { required: boolean; content: Record<string, Json> };
    expect(requestBody.required).toBe(true);
    expect(requestBody.content['application/json']?.schema).toEqual({
      $ref: '#/components/schemas/SearchRequest',
    });
    const request = components.schemas?.SearchRequest as {
      properties: Record<string, Json>;
      required: string[];
    };
    expect(Object.keys(request.properties).sort()).toEqual([
      'category',
      'language',
      'limit',
      'nearLatitude',
      'nearLongitude',
      'q',
    ]);
    // Since 0.8.0 (P011f1) nothing is required by the schema: `q` or `category` must be sent,
    // which the server checks. A client generated from 0.7.0 always sends `q` and keeps working.
    expect(request.required ?? []).toEqual([]);
    // An open string, and no radius: how far the server looks is the server's decision.
    expect(request.properties.category).not.toHaveProperty('enum');
    expect(Object.keys(request.properties)).not.toContain('radiusKm');
    // Coordinates are JSON numbers, explicit latitude and longitude (ADR 0004).
    expect(request.properties.nearLatitude?.type).toBe('number');
    expect(request.properties.nearLongitude?.type).toBe('number');

    const place = components.schemas?.Place as { properties: Record<string, Json> };
    expect(Object.keys(place.properties).sort()).toEqual([
      'distanceMeters',
      'id',
      'kind',
      'label',
      'latitude',
      'longitude',
      'matchType',
      'name',
    ]);
    // Added in 0.8.0 (P011f1): optional open strings and an optional number.
    expect((place as unknown as { required: string[] }).required).not.toContain('matchType');
    expect(place.properties.matchType).not.toHaveProperty('enum');
    // Added in 0.7.0 (P011e): optional, so a client generated from 0.6.0 keeps working.
    expect((place as unknown as { required: string[] }).required).not.toContain('distanceMeters');
    expect(place.properties.distanceMeters?.type).toBe('integer');
    expect(place.properties.kind).not.toHaveProperty('enum');
    const results = components.schemas?.SearchResults as { properties: Json; required: string[] };
    expect(Object.keys(results.properties).sort()).toEqual([
      'attribution',
      'results',
      'searchedAround',
      'searchedRadiusKm',
    ]);
    expect(results.required.sort()).toEqual(['attribution', 'results']);
    expect((results.properties as Record<string, Json>).searchedRadiusKm).toMatchObject({
      type: 'number',
      maximum: 25,
    });

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

  describe('privacy in URLs (ADR 0019)', () => {
    /**
     * Platform request logs record every URL with its query string. So nothing a person typed,
     * no position and no credential may be a path or query parameter. Such values go in a
     * request body or a header. This test reads the committed contract and fails on a name that
     * looks like one of them.
     */
    const DENIED = [
      /^q$/i,
      /^query$/i,
      /^text$/i,
      /^search/i,
      /^lat$/i,
      /^lng$/i,
      /^lon$/i,
      /latitude/i,
      /longitude/i,
      /^near/i,
      /^bbox$/i,
      /phone/i,
      /token/i,
      /^key$/i,
      /apikey/i,
      /password/i,
      /secret/i,
    ];

    /**
     * Exceptions. Each entry needs a reason and the ADR that accepts it. Empty today: add an
     * entry only together with that ADR.
     */
    const ALLOWED: { operationId: string; parameter: string; adr: string; reason: string }[] = [];

    interface UrlParameter {
      operationId: string;
      in: string;
      name: string;
    }

    function urlParameters(document: Json): UrlParameter[] {
      const found: UrlParameter[] = [];
      const parameterComponents = (document.components as Record<string, Json> | undefined)
        ?.parameters as Record<string, Json> | undefined;
      const resolve = (parameter: Json): Json => {
        const ref = parameter.$ref;
        if (typeof ref !== 'string') return parameter;
        return parameterComponents?.[ref.replace('#/components/parameters/', '')] ?? {};
      };
      for (const [path, item] of Object.entries(document.paths as Record<string, Json>)) {
        const shared = (item.parameters as Json[] | undefined) ?? [];
        for (const method of HTTP_METHODS.filter((name) => name in item)) {
          const op = item[method] as Json;
          const operationId = String(op.operationId);
          const declared = [...shared, ...((op.parameters as Json[] | undefined) ?? [])].map(
            resolve,
          );
          for (const parameter of declared) {
            if (parameter.in === 'path' || parameter.in === 'query') {
              found.push({ operationId, in: parameter.in, name: String(parameter.name) });
            }
          }
          // A path variable that is not declared as a parameter is still part of the URL.
          for (const match of path.matchAll(/\{([^}]+)\}/g)) {
            const name = match[1] ?? '';
            if (
              !found.some(
                (p) => p.operationId === operationId && p.in === 'path' && p.name === name,
              )
            ) {
              found.push({ operationId, in: 'path', name });
            }
          }
        }
      }
      return found;
    }

    const violations = (document: Json) =>
      urlParameters(document)
        .filter((p) => DENIED.some((pattern) => pattern.test(p.name)))
        .filter(
          (p) => !ALLOWED.some((a) => a.operationId === p.operationId && a.parameter === p.name),
        )
        .map((p) => `${p.operationId}: ${p.in} parameter "${p.name}"`);

    it('the committed contract puts no user text, position or credential in a path or query', () => {
      const committed = JSON.parse(readFileSync(DEFAULT_SPEC_PATH, 'utf8')) as Json;
      expect(violations(committed)).toEqual([]);
    });

    it('every allowlist entry names an ADR and still matches a real parameter', () => {
      const real = urlParameters(doc);
      for (const entry of ALLOWED) {
        expect(entry.adr, entry.parameter).toMatch(/^ADR \d{4}$/);
        expect(entry.reason.length, entry.parameter).toBeGreaterThan(10);
        expect(
          real.some((p) => p.operationId === entry.operationId && p.name === entry.parameter),
          `${entry.operationId} ${entry.parameter} no longer exists; remove the entry`,
        ).toBe(true);
      }
    });

    it('the check catches what it is meant to catch', () => {
      const spec = (path: string, parameters: Json[]): Json => ({
        paths: { [path]: { get: { operationId: 'fakeOperation', parameters } } },
      });
      const query = (name: string): Json => ({ name, in: 'query' });
      // The old search endpoint, the planned cells query and a share link would all fail.
      expect(violations(spec('/v1/search', [query('q'), query('nearLatitude')]))).toEqual([
        'fakeOperation: query parameter "q"',
        'fakeOperation: query parameter "nearLatitude"',
      ]);
      expect(violations(spec('/v1/safety/cells', [query('bbox')]))).toHaveLength(1);
      expect(violations(spec('/v/{token}', []))).toEqual(['fakeOperation: path parameter "token"']);
      expect(violations(spec('/v1/x', [query('phoneNumber'), query('apiKey')]))).toHaveLength(2);
      // Headers and bodies are fine, and so are ordinary identifiers and paging.
      expect(
        violations(
          spec('/v1/me/consents/{purpose}', [
            { name: 'Idempotency-Key', in: 'header' },
            { name: 'purpose', in: 'path' },
            query('limit'),
            query('cursor'),
            query('language'),
          ]),
        ),
      ).toEqual([]);
    });
  });

  it('documents routes as a POST with a body and the routing problem codes (P012b, ADR 0020)', () => {
    expect((doc.info as Json).version).toBe('0.8.0');
    const paths = doc.paths as Record<string, Record<string, Json>>;
    // Origin and destination travel in a request body. No GET, no parameters (ADR 0019).
    expect(Object.keys(paths['/v1/routes'] ?? {})).toEqual(['post']);
    const op = paths['/v1/routes']?.post ?? {};
    expect(op.operationId).toBe('createRoutes');
    expect(op.tags).toEqual(['routing']);
    expect(op.security).toEqual([{ firebaseBearer: [] }]);
    expect(op.parameters ?? []).toEqual([]);
    expect(Object.keys(op.responses as Json).sort()).toEqual([
      '200',
      '400',
      '401',
      '403',
      '404',
      '422',
      '429',
      '500',
      '503',
    ]);
    const requestBody = op.requestBody as { required: boolean; content: Record<string, Json> };
    expect(requestBody.required).toBe(true);
    expect(requestBody.content['application/json']?.schema).toEqual({
      $ref: '#/components/schemas/RouteRequest',
    });
    const request = components.schemas?.RouteRequest as {
      properties: Record<string, Json>;
      required: string[];
    };
    expect(Object.keys(request.properties).sort()).toEqual([
      'departAt',
      'destination',
      'mode',
      'origin',
    ]);
    expect(request.required.sort()).toEqual(['destination', 'mode', 'origin']);
    expect(request.properties.mode).toMatchObject({ enum: ['walking', 'driving'] });
    const route = components.schemas?.Route as {
      properties: Record<string, Json>;
      required: string[];
    };
    expect(route.required.sort()).toEqual([
      'bbox',
      'distanceMeters',
      'durationSeconds',
      'geometry',
      'id',
    ]);
    expect(route.properties.geometry).toMatchObject({
      properties: { encoding: { enum: ['polyline6'] } },
    });
    expect((components.schemas?.Routes as { required: string[] }).required.sort()).toEqual([
      'attribution',
      'routes',
    ]);

    // The codes the app switches on are all named in the description, and 503 and 429 carry
    // Retry-After: the first request after a quiet period may get 503 while the service starts.
    for (const code of [
      'outside_covered_area',
      'route_too_long',
      'location_not_routable',
      'no_route_found',
      'routing_unavailable',
      'rate_limited',
    ]) {
      expect(op.description as string).toContain(`\`${code}\``);
      expect(serialized).toContain(code);
    }
    expect(op.description as string).toContain('Retry-After');
    const unavailable = components.responses?.ServiceUnavailable as { headers: Json };
    expect(unavailable.headers).toHaveProperty('Retry-After');
    expect(components.responses).toHaveProperty('UnprocessableContent');
    // A route never claims safety.
    expect(JSON.stringify(components.schemas?.Route).toLowerCase()).not.toMatch(
      /safe(st|r)? route|safety score/,
    );
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
