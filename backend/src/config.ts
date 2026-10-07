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

/** Geocoding providers that have an adapter (src/modules/search/providers, ADR 0018). */
export const GEOCODING_PROVIDERS = ['geoapify', 'locationiq'] as const;
export type GeocodingProviderName = (typeof GEOCODING_PROVIDERS)[number];

/** How the API proves who it is to the routing services (ADR 0020). */
export const ROUTING_AUTH_MODES = ['google_id_token', 'none'] as const;

/**
 * Base URL of one OSRM service, or undefined when the value is not usable.
 *
 * The value Cloud Run reports for a service: scheme and host, nothing else. A trailing slash is
 * accepted and removed, because the result is also the AUDIENCE of the ID token the API sends,
 * and Cloud Run compares that with the service URL without a trailing slash.
 * http is accepted for a local OSRM only. Never throws: the value is treated as a secret (a Cloud
 * Run URL contains the project number), so no message may quote it.
 */
export function parseOsrmBaseUrl(value: string): string | undefined {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return undefined;
  }
  const local = ['localhost', '127.0.0.1'].includes(url.hostname);
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && local)) return undefined;
  if (url.username !== '' || url.password !== '' || url.search !== '' || url.hash !== '') {
    return undefined;
  }
  if (url.pathname !== '/' && url.pathname !== '') return undefined;
  return url.origin;
}

const osrmBaseUrl = z
  .string()
  .max(300)
  .transform((value, ctx) => {
    const url = parseOsrmBaseUrl(value);
    if (url === undefined) {
      ctx.addIssue({
        code: 'custom',
        message: 'must be https://<host> with no path (http only for localhost)',
      });
      return z.NEVER;
    }
    return url;
  });

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
  // Server-side key of the geocoding provider (ADR 0018). A SECRET: never logged, never in an
  // error message, never in /health. Optional outside production: search then answers 503.
  GEOCODING_API_KEY: z
    .string()
    .regex(/^[\x21-\x7e]{8,200}$/, { message: 'must be 8-200 printable characters' })
    .optional(),
  // Which adapter the key belongs to. Not a secret. Geoapify is the default since the first
  // evaluation (ADR 0018, note of 2026-10-07); `locationiq` stays selectable as a spare.
  GEOCODING_PROVIDER: z.enum(GEOCODING_PROVIDERS).default('geoapify'),
  // Provider calls per day across all users and instances. Keep it under the provider plan's
  // daily quota; revisit whenever the plan or the provider changes (ADR 0018).
  SEARCH_GLOBAL_DAILY_LIMIT: z.coerce.number().int().min(1).max(10_000_000).default(2500),
  SEARCH_PROVIDER_TIMEOUT_MS: z.coerce.number().int().min(200).max(20_000).default(3000),
  // Local-first search (ADR 0018, "Local ranking"): the nearby pass looks this far around the
  // area the app sent; with fewer nearby results than the minimum, a second, wide provider call
  // follows. Both calls spend the rate limits and SEARCH_GLOBAL_DAILY_LIMIT. Defaults:
  // LOCAL_SEARCH_DEFAULTS (src/modules/search/local-first.ts).
  SEARCH_NEARBY_RADIUS_KM: z.coerce.number().min(1).max(500).default(50),
  SEARCH_MIN_LOCAL_RESULTS: z.coerce.number().int().min(1).max(10).default(3),
  // Base URLs of the two private OSRM services (ADR 0020). Treated as SECRETS: never logged,
  // never in an error or in /health. Optional outside production: routing then answers 503.
  OSRM_WALKING_URL: osrmBaseUrl.optional(),
  OSRM_DRIVING_URL: osrmBaseUrl.optional(),
  // `google_id_token`: an ID token from the metadata server, audience = the service URL.
  // `none`: no Authorization header, for a local OSRM only; refused in production.
  ROUTING_AUTH: z.enum(ROUTING_AUTH_MODES).default('google_id_token'),
  // One attempt per request, including a cold start of a scaled-to-zero service. NOT measured on
  // staging yet (P012b): 25 s stays under the API's own 60 s Cloud Run timeout. Tune it from
  // docs/runbooks/routing-capacity-staging.md through the GitHub variable of the same name.
  ROUTING_TIMEOUT_MS: z.coerce.number().int().min(500).max(55_000).default(25_000),
  // Alternatives asked of OSRM besides the best route: 2 gives up to 3 routes.
  ROUTING_ALTERNATIVES: z.coerce.number().int().min(0).max(2).default(2),
  // Route requests per day across all users and instances: a cost guard for the OSRM services.
  ROUTING_GLOBAL_DAILY_LIMIT: z.coerce.number().int().min(1).max(10_000_000).default(20_000),
});

/** What the API additionally needs before it may serve requests in production. */
const ConfigSchema = BaseSchema.superRefine((config, ctx) => {
  if (config.NODE_ENV !== 'production') return;
  // Without the key a production revision must not start: the deploy then fails at the candidate
  // stage and traffic never shifts (docs/runbooks/rollback-staging.md).
  if (config.GEOCODING_API_KEY === undefined) {
    ctx.addIssue({
      code: 'custom',
      path: ['GEOCODING_API_KEY'],
      message: 'required when NODE_ENV=production',
    });
  }
  // Same reasoning for routing: a revision without its OSRM services must not take traffic.
  for (const name of ['OSRM_WALKING_URL', 'OSRM_DRIVING_URL'] as const) {
    if (config[name] === undefined) {
      ctx.addIssue({ code: 'custom', path: [name], message: 'required when NODE_ENV=production' });
    } else if (!config[name].startsWith('https://')) {
      ctx.addIssue({ code: 'custom', path: [name], message: 'must be https in production' });
    }
  }
  if (config.ROUTING_AUTH === 'none') {
    ctx.addIssue({
      code: 'custom',
      path: ['ROUTING_AUTH'],
      message: 'none is not allowed when NODE_ENV=production',
    });
  }
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
