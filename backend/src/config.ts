import { z } from 'zod';

/** Accepts postgres:// or postgresql:// URLs. The message never includes the value (it holds a password). */
const postgresUrl = z.string().refine(
  (value) => {
    try {
      return ['postgres:', 'postgresql:'].includes(new URL(value).protocol);
    } catch {
      return false;
    }
  },
  { message: 'must be a postgres:// or postgresql:// URL' },
);

const ConfigSchema = z
  .object({
    NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
    PORT: z.coerce.number().int().min(1).max(65535).default(8080),
    LOG_LEVEL: z.enum(['debug', 'info', 'warn', 'error']).default('info'),
    SERVICE_NAME: z.string().min(1).max(100).default('saferoute-api'),
    APP_VERSION: z.string().min(1).max(100).default('dev'),
    GIT_SHA: z.string().min(1).max(100).default('unknown'),
    // Optional outside production so `pnpm dev` and the unit tests run without a database.
    DATABASE_URL: postgresUrl.optional(),
    // Small per-instance pool: many Cloud Run instances share one Cloud SQL (Plan v7 §14.2).
    DB_POOL_MAX: z.coerce.number().int().min(1).max(20).default(5),
    DB_STATEMENT_TIMEOUT_MS: z.coerce.number().int().min(100).max(300_000).default(10_000),
    DB_CONNECT_TIMEOUT_MS: z.coerce.number().int().min(100).max(60_000).default(5_000),
  })
  .superRefine((config, ctx) => {
    if (config.NODE_ENV === 'production' && config.DATABASE_URL === undefined) {
      ctx.addIssue({
        code: 'custom',
        path: ['DATABASE_URL'],
        message: 'required when NODE_ENV=production',
      });
    }
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
