// SPDX-License-Identifier: AGPL-3.0-only
import { Writable } from 'node:stream';
import { createApp } from '../app.js';
import { parseConfig } from '../config.js';
import { createLogger } from '../lib/logger.js';
import { OPENAPI_OBJECT_CONFIG } from './info.js';

/**
 * Builds the app with dummy dependencies and returns its OpenAPI 3.1 document. Needs no
 * DATABASE_URL, no network and no environment: fixed config, a logger that discards output, and
 * a readiness probe that is never called. So `pnpm openapi:generate` runs the same in CI and on
 * any laptop.
 */
export function buildOpenApiDocument() {
  const config = parseConfig({ NODE_ENV: 'test', LOG_LEVEL: 'error' });
  const silent = new Writable({
    write: (_chunk, _encoding, done) => {
      done();
    },
  });
  const logger = createLogger(config, silent);
  const app = createApp({ config, logger, readiness: () => Promise.resolve() });
  return app.getOpenAPI31Document(OPENAPI_OBJECT_CONFIG);
}

/**
 * The exact bytes committed as contracts/openapi.json: key order as emitted by the generator
 * (registration order, stable), 2-space indent, LF line endings, trailing newline, no timestamps.
 */
export function serializeOpenApiDocument(document: ReturnType<typeof buildOpenApiDocument>) {
  return `${JSON.stringify(document, null, 2)}\n`;
}
