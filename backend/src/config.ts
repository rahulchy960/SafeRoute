import { z } from 'zod';

/** Where a database URL points. `hostname` is '' when it connects through a unix socket. */
export interface PostgresUrlTarget {
  hostname: string;
  port: string;
}

/**
 * Checks a database URL and returns its host and port, or undefined if it is not a postgres URL.
 * Never throws: a URL error carries its input, which holds the password.
 *
 * Besides ordinary URLs this accepts the Cloud SQL unix-socket form (ADR 0007), which has no host
 * in the authority: `postgresql://user:pass@/db?host=/cloudsql/<instance connection name>`.
 * `new URL` rejects that (credentials but no host), while node-postgres accepts it and takes the
 * socket directory from the `host` query parameter. It is parsed here with a placeholder host,
 * and only when `host` is an absolute path.
 */
export function parsePostgresUrl(value: string): PostgresUrlTarget | undefined {
  let url: URL;
  let socket = false;
  try {
    url = new URL(value);
  } catch {
    try {
      url = new URL(value.replace('@/', '@socket.invalid/'));
    } catch {
      return undefined;
    }
    if (!url.searchParams.get('host')?.startsWith('/')) return undefined;
    socket = true;
  }
  if (!['postgres:', 'postgresql:'].includes(url.protocol)) return undefined;
  return { hostname: socket ? '' : url.hostname, port: url.port };
}

/** The message never includes the value (it holds a password). */
const postgresUrl = z.string().refine((value) => parsePostgresUrl(value) !== undefined, {
  message: 'must be a postgres:// or postgresql:// URL',
});

/** Firebase/GCP project ID rules: 6–30 characters, lowercase letters, digits and hyphens. */
export const FIREBASE_PROJECT_ID_PATTERN = /^[a-z][a-z0-9-]{4,28}[a-z0-9]$/;

const BaseSchema = z.object({
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
  // Firebase project whose ID tokens the API accepts (ADR 0006). Not a secret, but kept out of
  // tracked files. Issuer, audience, key URL and algorithm are derived in code: no variable can
  // change them, and there is deliberately no emulator or bypass switch.
  FIREBASE_PROJECT_ID: z
    .string()
    .regex(FIREBASE_PROJECT_ID_PATTERN, { message: 'must be a Firebase project ID' })
    .optional(),
});

/** What the API additionally needs before it may serve requests in production. */
const ConfigSchema = BaseSchema.superRefine((config, ctx) => {
  if (config.NODE_ENV !== 'production') return;
  if (config.DATABASE_URL === undefined) {
    ctx.addIssue({
      code: 'custom',
      path: ['DATABASE_URL'],
      message: 'required when NODE_ENV=production',
    });
  }
  if (config.FIREBASE_PROJECT_ID === undefined) {
    ctx.addIssue({
      code: 'custom',
      path: ['FIREBASE_PROJECT_ID'],
      message: 'required when NODE_ENV=production',
    });
  } else if (config.FIREBASE_PROJECT_ID.startsWith('demo-')) {
    // `demo-` IDs are Firebase's offline/emulator-only projects; production must use a real one.
    ctx.addIssue({
      code: 'custom',
      path: ['FIREBASE_PROJECT_ID'],
      message: 'a demo- project ID is not allowed when NODE_ENV=production',
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
  return parseWith(ConfigSchema, env);
}

/**
 * Config for one-off jobs that serve no requests (the migration runner, a Cloud Run Job). Same
 * variables and validation as {@link parseConfig}, without the API's production requirements: a
 * job has no use for FIREBASE_PROJECT_ID, and checks for DATABASE_URL itself.
 */
export function parseJobConfig(env: Record<string, string | undefined>): Config {
  return parseWith(BaseSchema, env);
}

function parseWith(schema: z.ZodType<Config>, env: Record<string, string | undefined>): Config {
  const present = Object.fromEntries(
    Object.entries(env).filter(([, value]) => value !== undefined && value !== ''),
  );
  const result = schema.safeParse(present);
  if (!result.success) {
    // Zod 4 issue messages describe the expectation ("expected one of ...") without echoing input.
    throw new ConfigError(
      result.error.issues.map((issue) => `${issue.path.join('.')}: ${issue.message}`),
    );
  }
  return result.data;
}
