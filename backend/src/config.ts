import { z } from 'zod';

const ConfigSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  PORT: z.coerce.number().int().min(1).max(65535).default(8080),
  LOG_LEVEL: z.enum(['debug', 'info', 'warn', 'error']).default('info'),
  SERVICE_NAME: z.string().min(1).max(100).default('saferoute-api'),
  APP_VERSION: z.string().min(1).max(100).default('dev'),
  GIT_SHA: z.string().min(1).max(100).default('unknown'),
});

export type Config = z.infer<typeof ConfigSchema>;

/**
 * Thrown by {@link parseConfig}. The message lists variable names and what is wrong with them,
 * never the values: a misplaced secret in the wrong variable must not end up in logs.
 */
export class ConfigError extends Error {
  readonly issues: readonly string[];

  constructor(issues: readonly string[]) {
    super(`Invalid configuration:\n${issues.map((issue) => `  - ${issue}`).join('\n')}`);
    this.name = 'ConfigError';
    this.issues = issues;
  }
}

/**
 * Parses and validates the environment once at startup. Pure: pass `process.env` in production and
 * a plain object in tests. Empty strings count as unset, so `PORT=` falls back to the default.
 */
export function parseConfig(env: Record<string, string | undefined>): Config {
  const present = Object.fromEntries(
    Object.entries(env).filter(([, value]) => value !== undefined && value !== ''),
  );
  const result = ConfigSchema.safeParse(present);
  if (!result.success) {
    // Zod 4 issue messages describe the expectation ("expected one of ...") without echoing input.
    throw new ConfigError(
      result.error.issues.map((issue) => `${issue.path.join('.')}: ${issue.message}`),
    );
  }
  return result.data;
}
