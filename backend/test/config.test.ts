import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { ConfigError, parseConfig, parseJobConfig } from '../src/config.js';

/** Obviously fake; a production config needs both. */
const GEOCODING = {
  GEOCODING_API_KEY: 'fake-geocoding-key-for-tests',
  GEOCODING_PROVIDER: 'geoapify',
};

/** Obviously fake; a production config needs both. Hosts under .invalid never resolve. */
const ROUTING = {
  OSRM_WALKING_URL: 'https://osrm-walking.example.invalid',
  OSRM_DRIVING_URL: 'https://osrm-driving.example.invalid',
};

function configError(env: Record<string, string>): ConfigError {
  try {
    parseConfig(env);
  } catch (err) {
    if (err instanceof ConfigError) return err;
    throw err;
  }
  throw new Error('expected parseConfig to throw');
}

describe('parseConfig', () => {
  it('applies defaults for an empty environment', () => {
    expect(parseConfig({})).toEqual({
      NODE_ENV: 'development',
      PORT: 8080,
      LOG_LEVEL: 'info',
      SERVICE_NAME: 'saferoute-api',
      APP_VERSION: 'dev',
      GIT_SHA: 'unknown',
      DB_POOL_MAX: 5,
      DB_STATEMENT_TIMEOUT_MS: 10_000,
      DB_CONNECT_TIMEOUT_MS: 5_000,
      GEOCODING_PROVIDER: 'geoapify',
      SEARCH_GLOBAL_DAILY_LIMIT: 2500,
      SEARCH_PROVIDER_TIMEOUT_MS: 3000,
      ROUTING_AUTH: 'google_id_token',
      ROUTING_TIMEOUT_MS: 25_000,
      ROUTING_ALTERNATIVES: 2,
      ROUTING_GLOBAL_DAILY_LIMIT: 20_000,
    });
  });

  it('reads provided values and ignores unrelated variables', () => {
    const config = parseConfig({
      NODE_ENV: 'production',
      PORT: '3000',
      LOG_LEVEL: 'warn',
      APP_VERSION: '1.2.3',
      GIT_SHA: 'abc1234',
      DATABASE_URL: 'postgres://fake-user:fake-pw@127.0.0.1:5433/fake_db',
      FIREBASE_PROJECT_ID: 'example-staging-1',
      ...GEOCODING,
      ...ROUTING,
      PATH: '/usr/bin',
    });
    expect(config).toMatchObject({ NODE_ENV: 'production', PORT: 3000, LOG_LEVEL: 'warn' });
    expect(config).not.toHaveProperty('PATH');
  });

  it('treats empty strings as unset', () => {
    expect(parseConfig({ PORT: '', LOG_LEVEL: '' })).toMatchObject({
      PORT: 8080,
      LOG_LEVEL: 'info',
    });
  });

  it.each([
    ['PORT', 'not-a-port'],
    ['PORT', '70000'],
    ['PORT', '80.5'],
    ['LOG_LEVEL', 'verbose'],
    ['NODE_ENV', 'staging'],
    ['DB_POOL_MAX', '0'],
    ['DB_POOL_MAX', '21'],
    ['DB_STATEMENT_TIMEOUT_MS', '50'],
    ['DB_CONNECT_TIMEOUT_MS', '120000'],
    ['DATABASE_URL', 'mysql://user:pw@host/db'],
    ['DATABASE_URL', 'not a url'],
  ])('rejects invalid %s', (name, value) => {
    const err = configError({ [name]: value });
    expect(err.issues).toHaveLength(1);
    expect(err.issues[0]).toMatch(new RegExp(`^${name}: `));
  });

  it('lists every invalid variable by name but never echoes values', () => {
    const secretLooking = 'sk_live_DO_NOT_LOG_42';
    const err = configError({ PORT: secretLooking, LOG_LEVEL: secretLooking, NODE_ENV: 'qa-env' });
    expect(err.message).toContain('PORT');
    expect(err.message).toContain('LOG_LEVEL');
    expect(err.message).toContain('NODE_ENV');
    expect(err.message).not.toContain(secretLooking);
    expect(err.message).not.toContain('qa-env');
  });

  describe('database settings', () => {
    const URL_WITH_SECRET = 'postgres://fake-user:fake-secret-pw@127.0.0.1:5433/fake_db';

    it('makes DATABASE_URL optional outside production', () => {
      expect(parseConfig({ NODE_ENV: 'development' }).DATABASE_URL).toBeUndefined();
      expect(parseConfig({ NODE_ENV: 'test' }).DATABASE_URL).toBeUndefined();
    });

    it('requires DATABASE_URL in production', () => {
      const err = configError({
        NODE_ENV: 'production',
        FIREBASE_PROJECT_ID: 'example-staging-1',
        ...GEOCODING,
        ...ROUTING,
      });
      expect(err.issues).toEqual(['DATABASE_URL: required when NODE_ENV=production']);
      expect(
        parseConfig({
          NODE_ENV: 'production',
          DATABASE_URL: URL_WITH_SECRET,
          FIREBASE_PROJECT_ID: 'example-staging-1',
          ...GEOCODING,
          ...ROUTING,
        }).DATABASE_URL,
      ).toBe(URL_WITH_SECRET);
    });

    it('accepts postgres:// and postgresql:// URLs and pool/timeout values in range', () => {
      const config = parseConfig({
        DATABASE_URL: 'postgresql://u:p@localhost/db',
        DB_POOL_MAX: '20',
        DB_STATEMENT_TIMEOUT_MS: '100',
        DB_CONNECT_TIMEOUT_MS: '60000',
      });
      expect(config).toMatchObject({
        DB_POOL_MAX: 20,
        DB_STATEMENT_TIMEOUT_MS: 100,
        DB_CONNECT_TIMEOUT_MS: 60_000,
      });
    });

    it('never echoes a rejected DATABASE_URL (it contains a password)', () => {
      const leaky = 'mysql://fake-user:fake-secret-pw@db.internal.example/fake_db';
      const err = configError({ DATABASE_URL: leaky });
      expect(err.message).toContain('DATABASE_URL');
      expect(err.message).not.toContain('fake-secret-pw');
      expect(err.message).not.toContain('db.internal.example');
    });
  });

  describe('parseJobConfig (migration job, P006)', () => {
    const JOB = { NODE_ENV: 'production', DATABASE_URL: 'postgres://u:p@127.0.0.1:5433/db' };

    it('accepts production without FIREBASE_PROJECT_ID, which the API config rejects', () => {
      expect(parseJobConfig(JOB)).toMatchObject({ NODE_ENV: 'production', LOG_LEVEL: 'info' });
      expect(parseJobConfig(JOB).FIREBASE_PROJECT_ID).toBeUndefined();
      expect(configError(JOB).issues).toEqual([
        'GEOCODING_API_KEY: required when NODE_ENV=production',
        'OSRM_WALKING_URL: required when NODE_ENV=production',
        'OSRM_DRIVING_URL: required when NODE_ENV=production',
        'FIREBASE_PROJECT_ID: required when NODE_ENV=production',
      ]);
    });

    it('leaves the DATABASE_URL presence check to the job', () => {
      expect(parseJobConfig({ NODE_ENV: 'production' }).DATABASE_URL).toBeUndefined();
    });

    it('still validates every value without echoing it', () => {
      const leaky = 'mysql://fake-user:fake-secret-pw@db.internal.example/fake_db';
      expect(() => parseJobConfig({ ...JOB, DATABASE_URL: leaky, LOG_LEVEL: 'verbose' })).toThrow(
        ConfigError,
      );
      try {
        parseJobConfig({ ...JOB, DATABASE_URL: leaky });
      } catch (err) {
        expect((err as ConfigError).issues).toEqual([
          'DATABASE_URL: must be a postgres:// or postgresql:// URL',
        ]);
        expect((err as ConfigError).message).not.toContain('fake-secret-pw');
      }
    });
  });

  describe('geocoding and search settings (ADR 0018)', () => {
    const PROD = {
      NODE_ENV: 'production',
      DATABASE_URL: 'postgres://u:p@127.0.0.1:5433/db',
      FIREBASE_PROJECT_ID: 'example-staging-1',
      ...ROUTING,
    };

    it('the key is optional in development and test', () => {
      for (const NODE_ENV of ['development', 'test']) {
        expect(parseConfig({ NODE_ENV }).GEOCODING_API_KEY).toBeUndefined();
      }
      expect(parseConfig(GEOCODING)).toMatchObject(GEOCODING);
    });

    it('requires the key in production, by name only', () => {
      expect(configError(PROD).issues).toEqual([
        'GEOCODING_API_KEY: required when NODE_ENV=production',
      ]);
      expect(parseConfig({ ...PROD, ...GEOCODING })).toMatchObject(GEOCODING);
    });

    it('geoapify is the default provider; locationiq stays selectable', () => {
      const key = { GEOCODING_API_KEY: 'fake-geocoding-key-for-tests' };
      expect(parseConfig({}).GEOCODING_PROVIDER).toBe('geoapify');
      expect(parseConfig({ ...PROD, ...key }).GEOCODING_PROVIDER).toBe('geoapify');
      expect(
        parseConfig({ ...PROD, ...key, GEOCODING_PROVIDER: 'locationiq' }).GEOCODING_PROVIDER,
      ).toBe('locationiq');
    });

    it.each([
      ['GEOCODING_API_KEY', 'short'],
      ['GEOCODING_API_KEY', 'has spaces in the fake key'],
      ['GEOCODING_PROVIDER', 'some-other-provider'],
      ['SEARCH_GLOBAL_DAILY_LIMIT', '0'],
      ['SEARCH_GLOBAL_DAILY_LIMIT', 'many'],
      ['SEARCH_PROVIDER_TIMEOUT_MS', '50'],
    ])('rejects an invalid %s without echoing it', (name, value) => {
      const err = configError({ ...GEOCODING, [name]: value });
      expect(err.issues).toHaveLength(1);
      expect(err.issues[0]).toMatch(new RegExp('^' + name + ': '));
      expect(err.message).not.toContain(value);
    });

    it('reads the limits', () => {
      expect(
        parseConfig({ SEARCH_GLOBAL_DAILY_LIMIT: '4000', SEARCH_PROVIDER_TIMEOUT_MS: '1500' }),
      ).toMatchObject({ SEARCH_GLOBAL_DAILY_LIMIT: 4000, SEARCH_PROVIDER_TIMEOUT_MS: 1500 });
    });

    it('the migration job needs no geocoding key', () => {
      expect(parseJobConfig(PROD).GEOCODING_API_KEY).toBeUndefined();
    });
  });

  describe('FIREBASE_PROJECT_ID (ADR 0006)', () => {
    const PROD = {
      NODE_ENV: 'production',
      DATABASE_URL: 'postgres://u:p@127.0.0.1:5433/db',
      ...GEOCODING,
      ...ROUTING,
    };

    it('is optional in development and test, and demo- IDs are allowed there', () => {
      expect(parseConfig({ NODE_ENV: 'development' }).FIREBASE_PROJECT_ID).toBeUndefined();
      expect(parseConfig({ NODE_ENV: 'test' }).FIREBASE_PROJECT_ID).toBeUndefined();
      expect(parseConfig({ FIREBASE_PROJECT_ID: 'demo-saferoute' }).FIREBASE_PROJECT_ID).toBe(
        'demo-saferoute',
      );
    });

    it('is required in production', () => {
      expect(configError(PROD).issues).toEqual([
        'FIREBASE_PROJECT_ID: required when NODE_ENV=production',
      ]);
    });

    it('rejects a demo- project in production without echoing it', () => {
      const err = configError({ ...PROD, FIREBASE_PROJECT_ID: 'demo-saferoute' });
      expect(err.issues).toEqual([
        'FIREBASE_PROJECT_ID: a demo- project ID is not allowed when NODE_ENV=production',
      ]);
      expect(err.message).not.toContain('demo-saferoute');
    });

    it('accepts a real-looking project ID in production', () => {
      expect(
        parseConfig({ ...PROD, FIREBASE_PROJECT_ID: 'example-staging-1' }).FIREBASE_PROJECT_ID,
      ).toBe('example-staging-1');
    });

    it.each([
      'Upper-Case-Id',
      'ab',
      '1starts-with-digit',
      'ends-with-hyphen-',
      'has_underscore',
      'way-too-long-project-id-over-30-chars',
      'https://securetoken.google.com/x',
    ])('rejects the invalid ID %s without echoing it', (value) => {
      const err = configError({ FIREBASE_PROJECT_ID: value });
      expect(err.issues).toEqual(['FIREBASE_PROJECT_ID: must be a Firebase project ID']);
      expect(err.message).not.toContain(value);
    });

    it('has no variable that changes keys, issuer, audience or algorithm, and no emulator switch', () => {
      const config = parseConfig({
        FIREBASE_PROJECT_ID: 'demo-saferoute',
        FIREBASE_AUTH_EMULATOR_HOST: '127.0.0.1:9099',
        FIREBASE_JWKS_URL: 'http://127.0.0.1/keys',
        AUTH_BYPASS: 'true',
      });
      // ROUTING_AUTH is not about who may call the API: it says how the API proves itself to the
      // private OSRM services, and `none` is refused in production (see the routing tests below).
      expect(Object.keys(config).filter((key) => /FIREBASE|AUTH|JWK/.test(key))).toEqual([
        'FIREBASE_PROJECT_ID',
        'ROUTING_AUTH',
      ]);
    });

    it('no source file reads an emulator or bypass variable', () => {
      const files = (dir: string): string[] =>
        readdirSync(dir, { withFileTypes: true }).flatMap((entry) =>
          entry.isDirectory() ? files(join(dir, entry.name)) : [join(dir, entry.name)],
        );
      for (const file of files(join(import.meta.dirname, '../src'))) {
        expect(readFileSync(file, 'utf8'), file).not.toMatch(/EMULATOR_HOST|AUTH_BYPASS/);
      }
    });
  });

  describe('routing settings (ADR 0020)', () => {
    const PROD = {
      NODE_ENV: 'production',
      DATABASE_URL: 'postgres://u:p@127.0.0.1:5433/db',
      FIREBASE_PROJECT_ID: 'example-staging-1',
      ...GEOCODING,
    };

    it('the service URLs are optional in development and test, and local http is allowed there', () => {
      expect(parseConfig({ NODE_ENV: 'test' }).OSRM_WALKING_URL).toBeUndefined();
      expect(
        parseConfig({ OSRM_WALKING_URL: 'http://localhost:5000', ROUTING_AUTH: 'none' }),
      ).toMatchObject({ OSRM_WALKING_URL: 'http://localhost:5000', ROUTING_AUTH: 'none' });
    });

    it('requires both URLs in production, by name only: a revision without them never starts', () => {
      expect(configError(PROD).issues).toEqual([
        'OSRM_WALKING_URL: required when NODE_ENV=production',
        'OSRM_DRIVING_URL: required when NODE_ENV=production',
      ]);
      expect(configError({ ...PROD, OSRM_WALKING_URL: ROUTING.OSRM_WALKING_URL }).issues).toEqual([
        'OSRM_DRIVING_URL: required when NODE_ENV=production',
      ]);
      // An empty value (a GitHub secret that was never set) counts as missing.
      expect(configError({ ...PROD, ...ROUTING, OSRM_DRIVING_URL: '' }).issues).toEqual([
        'OSRM_DRIVING_URL: required when NODE_ENV=production',
      ]);
      expect(parseConfig({ ...PROD, ...ROUTING })).toMatchObject(ROUTING);
    });

    it('removes a trailing slash, so the value is also the exact ID-token audience', () => {
      const config = parseConfig({
        ...PROD,
        ...ROUTING,
        OSRM_WALKING_URL: 'https://osrm-walking.example.invalid/',
      });
      expect(config.OSRM_WALKING_URL).toBe('https://osrm-walking.example.invalid');
    });

    it('refuses http and ROUTING_AUTH=none in production', () => {
      expect(
        configError({ ...PROD, ...ROUTING, OSRM_DRIVING_URL: 'http://localhost:5000' }).issues,
      ).toEqual(['OSRM_DRIVING_URL: must be https in production']);
      expect(configError({ ...PROD, ...ROUTING, ROUTING_AUTH: 'none' }).issues).toEqual([
        'ROUTING_AUTH: none is not allowed when NODE_ENV=production',
      ]);
    });

    it.each([
      ['OSRM_WALKING_URL', 'http://osrm-FAKEHOST.example.invalid'],
      ['OSRM_DRIVING_URL', 'https://osrm-FAKEHOST.example.invalid/route/v1'],
      ['OSRM_DRIVING_URL', 'https://user:FAKEPW@osrm-FAKEHOST.example.invalid'],
      ['ROUTING_AUTH', 'bearer'],
      ['ROUTING_TIMEOUT_MS', '100'],
      ['ROUTING_TIMEOUT_MS', '60000'],
      ['ROUTING_ALTERNATIVES', '3'],
      ['ROUTING_GLOBAL_DAILY_LIMIT', '0'],
    ])('rejects an invalid %s without echoing it', (name, value) => {
      const err = configError({ [name]: value });
      expect(err.issues).toHaveLength(1);
      expect(err.issues[0]).toMatch(new RegExp('^' + name + ': '));
      expect(err.message).not.toContain(value);
      expect(err.message).not.toContain('FAKEHOST');
    });

    it('reads the timeout, the alternatives and the daily limit', () => {
      expect(
        parseConfig({
          ROUTING_TIMEOUT_MS: '8000',
          ROUTING_ALTERNATIVES: '0',
          ROUTING_GLOBAL_DAILY_LIMIT: '500',
        }),
      ).toMatchObject({
        ROUTING_TIMEOUT_MS: 8000,
        ROUTING_ALTERNATIVES: 0,
        ROUTING_GLOBAL_DAILY_LIMIT: 500,
      });
    });

    it('the migration job needs no routing service', () => {
      expect(parseJobConfig(PROD).OSRM_WALKING_URL).toBeUndefined();
    });
  });
});
