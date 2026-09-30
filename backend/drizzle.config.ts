// SPDX-License-Identifier: AGPL-3.0-only
import { defineConfig } from 'drizzle-kit';

/**
 * drizzle-kit reads the TypeScript schema and writes SQL migrations to drizzle/.
 * `db:generate` and `db:check` need no database. Migrations are applied only by src/db/migrate.ts.
 */
export default defineConfig({
  dialect: 'postgresql',
  schema: './src/db/schema/index.ts',
  out: './drizzle',
  strict: true,
  verbose: true,
});
