import { describe, expect, it } from 'vitest';
import { ConfigError, parseConfig } from '../src/config.js';

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
      const err = configError({ NODE_ENV: 'production' });
      expect(err.issues).toEqual(['DATABASE_URL: required when NODE_ENV=production']);
      expect(
        parseConfig({ NODE_ENV: 'production', DATABASE_URL: URL_WITH_SECRET }).DATABASE_URL,
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
});
