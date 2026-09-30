import { serve } from '@hono/node-server';
import { createApp } from './app.js';
import { ConfigError, parseConfig, type Config } from './config.js';
import { createLogger } from './lib/logger.js';

/** Cloud Run sends SIGTERM and waits 10 s before SIGKILL; finish in-flight requests within that. */
const SHUTDOWN_TIMEOUT_MS = 10_000;

function loadConfig(): Config {
  try {
    return parseConfig(process.env);
  } catch (err) {
    if (err instanceof ConfigError) {
      process.stderr.write(`${err.message}\n`);
      process.exit(1);
    }
    throw err;
  }
}

const config = loadConfig();
const logger = createLogger(config);
const app = createApp({ config, logger });

const server = serve({ fetch: app.fetch, port: config.PORT }, (info) => {
  logger.info(
    { port: info.port, node_env: config.NODE_ENV, git_sha: config.GIT_SHA },
    'server listening',
  );
});

let shuttingDown = false;

/**
 * Stops accepting new connections, lets in-flight requests finish, then exits 0. If that takes
 * longer than SHUTDOWN_TIMEOUT_MS, exits 1 so the platform does not hang.
 * On Windows, Ctrl+C delivers SIGINT; SIGTERM is what Cloud Run and Docker send.
 */
function shutdown(signal: NodeJS.Signals): void {
  if (shuttingDown) return;
  shuttingDown = true;
  logger.info({ signal }, 'shutdown started');

  setTimeout(() => {
    logger.error({ timeout_ms: SHUTDOWN_TIMEOUT_MS }, 'shutdown timed out; forcing exit');
    process.exit(1);
  }, SHUTDOWN_TIMEOUT_MS).unref();

  // Node's server.close() also closes idle keep-alive connections, so only active requests delay it.
  server.close((err) => {
    if (err) {
      logger.error({ err }, 'error while closing server');
      process.exit(1);
    }
    logger.info('shutdown complete');
    process.exit(0);
  });
}

process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
