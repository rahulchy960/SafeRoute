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
    });
  });

  it('reads provided values and ignores unrelated variables', () => {
    const config = parseConfig({
      NODE_ENV: 'production',
      PORT: '3000',
      LOG_LEVEL: 'warn',
      APP_VERSION: '1.2.3',
      GIT_SHA: 'abc1234',
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
});
