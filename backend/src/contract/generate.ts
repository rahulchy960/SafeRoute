// SPDX-License-Identifier: AGPL-3.0-only
/**
 * Writes or checks contracts/openapi.json.
 *
 *   pnpm openapi:generate          regenerate the file (commit the diff)
 *   pnpm openapi:check             exit 1 if the committed file differs from a fresh generation
 *   ... --file <path>              use another file (tests run the check against a temp copy)
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { buildOpenApiDocument, serializeOpenApiDocument } from './openapi.js';

export const DEFAULT_SPEC_PATH = fileURLToPath(
  new URL('../../../contracts/openapi.json', import.meta.url),
);

export const STALE_MESSAGE =
  'contracts/openapi.json is stale: run pnpm openapi:generate and commit';

/** Returns the process exit code. */
export function run(argv: string[]): number {
  const { values } = parseArgs({
    args: argv,
    options: { check: { type: 'boolean', default: false }, file: { type: 'string' } },
  });
  const file = values.file ?? DEFAULT_SPEC_PATH;
  const fresh = serializeOpenApiDocument(buildOpenApiDocument());

  if (values.check) {
    let committed: string | undefined;
    try {
      committed = readFileSync(file, 'utf8');
    } catch {
      committed = undefined;
    }
    if (committed !== fresh) {
      process.stderr.write(`${STALE_MESSAGE}\n`);
      return 1;
    }
    process.stdout.write('contracts/openapi.json is up to date\n');
    return 0;
  }

  writeFileSync(file, fresh, 'utf8');
  process.stdout.write('wrote contracts/openapi.json\n');
  return 0;
}

// Run only when executed directly (tsx src/contract/generate.ts), not when imported by tests.
if (process.argv[1] !== undefined && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  process.exitCode = run(process.argv.slice(2));
}
