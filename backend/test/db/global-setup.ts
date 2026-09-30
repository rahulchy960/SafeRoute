// SPDX-License-Identifier: AGPL-3.0-only
import { PostgreSqlContainer, type StartedPostgreSqlContainer } from '@testcontainers/postgresql';
import type { TestProject } from 'vitest/node';
import { runMigrations } from '../../src/db/migrate.js';
import { POSTGIS_IMAGE } from './image.js';

declare module 'vitest' {
  export interface ProvidedContext {
    /** Connection URL of the migrated test database (throwaway container credentials). */
    databaseUrl: string;
  }
}

/**
 * Starts ONE PostGIS container for the whole db project, applies migrations through the same
 * runMigrations() the Cloud Run job uses, and stops the container afterwards. Without Docker the
 * project fails loudly; it never skips.
 */
export default async function setup(project: TestProject): Promise<() => Promise<void>> {
  let container: StartedPostgreSqlContainer;
  try {
    container = await new PostgreSqlContainer(POSTGIS_IMAGE)
      .withDatabase('saferoute_test')
      .withUsername('saferoute_test')
      .withPassword('test-only-password')
      .start();
  } catch (err) {
    const reason = err instanceof Error ? err.message : String(err);
    throw new Error(
      `The "db" test project needs Docker, but no PostGIS container could be started. ` +
        `Start Docker Desktop (or run only "pnpm test:unit"). Cause: ${reason}`,
      { cause: err },
    );
  }

  const databaseUrl = container.getConnectionUri();
  await runMigrations({ databaseUrl });
  project.provide('databaseUrl', databaseUrl);

  return async () => {
    await container.stop();
  };
}
