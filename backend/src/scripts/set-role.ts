// SPDX-License-Identifier: AGPL-3.0-only
/**
 * Changes one user's role (user | moderator | admin) and writes an audit_log row.
 *
 *   pnpm admin:set-role --user-id <uuid> --role moderator --confirm     (development, via tsx)
 *   node dist/scripts/set-role.js --user-id <uuid> --role moderator --confirm   (Cloud Run Job, P006)
 *
 * Roles live in the database and are checked on every request (ADR 0006), so the change applies
 * to the user's next request; no token refresh is needed. Uses DATABASE_URL only. Prints the user
 * id, the old and new role and a masked database host, nothing else (no phone, no name).
 */
import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import { eq } from 'drizzle-orm';
import { drizzle } from 'drizzle-orm/node-postgres';
import pg from 'pg';
import { safeDbError } from '../db/client.js';
import * as schema from '../db/schema/index.js';
import { isRole, ROLES } from '../modules/auth/roles.js';

export const USAGE = `Usage: set-role --user-id <uuid> --role <${ROLES.join('|')}> --confirm

Changes one user's role and records it in audit_log (actor_type 'system').
  --user-id   internal user id (the "id" field of GET /v1/me), not the Firebase uid
  --role      new role
  --confirm   required; without it nothing is changed
  --help      show this text
Environment: DATABASE_URL (postgres:// or postgresql://).
`;

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** First three characters of the host only, e.g. `127***:5433`, so logs show which database. */
export function maskDatabaseHost(databaseUrl: string): string {
  const url = new URL(databaseUrl);
  const host = url.hostname === '' ? '(socket)' : `${url.hostname.slice(0, 3)}***`;
  return url.port === '' ? host : `${host}:${url.port}`;
}

export interface Output {
  out: (line: string) => void;
  err: (line: string) => void;
}

const stdio: Output = {
  out: (line) => process.stdout.write(`${line}\n`),
  err: (line) => process.stderr.write(`${line}\n`),
};

/** Returns the process exit code: 0 done, 1 failed or user not found, 2 usage error. */
export async function runSetRole(
  argv: string[],
  env: Record<string, string | undefined>,
  io: Output = stdio,
): Promise<number> {
  let values;
  try {
    ({ values } = parseArgs({
      args: argv,
      strict: true,
      options: {
        'user-id': { type: 'string' },
        role: { type: 'string' },
        confirm: { type: 'boolean', default: false },
        help: { type: 'boolean', default: false },
      },
    }));
  } catch {
    io.err(USAGE);
    return 2;
  }
  if (values.help) {
    io.out(USAGE);
    return 0;
  }

  const userId = values['user-id'];
  const role = values.role;
  if (userId === undefined || !UUID_PATTERN.test(userId) || role === undefined || !isRole(role)) {
    io.err(USAGE);
    return 2;
  }
  if (!values.confirm) {
    io.err('Refusing to change a role without --confirm. Nothing was changed.');
    return 2;
  }

  const databaseUrl = env.DATABASE_URL;
  let host: string;
  try {
    if (databaseUrl === undefined) throw new Error('missing');
    host = maskDatabaseHost(databaseUrl);
    if (!databaseUrl.startsWith('postgres://') && !databaseUrl.startsWith('postgresql://')) {
      throw new Error('not postgres');
    }
  } catch {
    // Never echo the value: it contains the password.
    io.err('DATABASE_URL must be set to a postgres:// or postgresql:// URL.');
    return 1;
  }

  const client = new pg.Client({
    connectionString: databaseUrl,
    options: '-c TimeZone=UTC',
    application_name: 'saferoute-set-role',
  });
  try {
    await client.connect();
    const db = drizzle(client, { schema });
    const result = await db.transaction(async (tx) => {
      const rows = await tx
        .select({ role: schema.users.role })
        .from(schema.users)
        .where(eq(schema.users.id, userId))
        .for('update');
      const from = rows[0]?.role;
      if (from === undefined) return undefined;
      if (from !== role) {
        // Drizzle update builder, so updated_at is set by $onUpdate (see src/db/schema/users.ts).
        await tx.update(schema.users).set({ role }).where(eq(schema.users.id, userId));
        await tx.insert(schema.auditLog).values({
          actorType: 'system',
          action: 'user.role_changed',
          entity: 'user',
          entityId: userId,
          metadata: { from, to: role },
        });
      }
      return { from };
    });

    if (result === undefined) {
      io.err(`No user with id ${userId} (database ${host}). Nothing was changed.`);
      return 1;
    }
    io.out(
      result.from === role
        ? `User ${userId}: role is already ${role} (database ${host}). Nothing was changed.`
        : `User ${userId}: role ${result.from} → ${role} (database ${host}).`,
    );
    return 0;
  } catch (err) {
    const { name, code } = safeDbError(err);
    io.err(`Failed to change the role (database ${host}): ${name}${code ? ` ${code}` : ''}`);
    return 1;
  } finally {
    await client.end().catch(() => undefined);
  }
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = await runSetRole(process.argv.slice(2), process.env);
}
