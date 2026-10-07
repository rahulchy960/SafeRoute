// SPDX-License-Identifier: AGPL-3.0-only
import { z } from 'zod';
import {
  RoutingError,
  type ProviderRoute,
  type RouteQuery,
  type RoutingMode,
  type RoutingProvider,
} from '../types.js';
import type { IdTokenSource } from './id-token.js';

/** Largest response we read. Three statewide routes were measured at 144 KB (ADR 0020). */
export const MAX_ROUTING_RESPONSE_BYTES = 1024 * 1024;
const MAX_GEOMETRY_CHARS = 400_000;

/** Credit required for routes computed from OpenStreetMap data (ODbL 1.0, ADR 0020). */
export const ROUTING_ATTRIBUTION = '© OpenStreetMap contributors';

export interface OsrmOptions {
  /** Base URL per mode, already normalised by src/config.ts (no trailing slash). */
  baseUrls: Record<RoutingMode, string>;
  /** Undefined = no Authorization header (a local OSRM; refused in production by the config). */
  idTokens: IdTokenSource | undefined;
  timeoutMs: number;
  /** Alternatives besides the best route, 0 to 2. */
  alternatives: number;
  fetchImpl?: typeof fetch;
}

const OsrmResponseSchema = z.object({
  code: z.string().max(40),
  routes: z
    .array(
      z.object({
        distance: z.number().min(0).max(10_000_000),
        duration: z.number().min(0).max(10_000_000),
        geometry: z.string().min(1).max(MAX_GEOMETRY_CHARS),
      }),
    )
    .max(3)
    .optional(),
});

async function readBounded(response: Response): Promise<string> {
  const reader = response.body?.getReader() as ReadableStreamDefaultReader<Uint8Array> | undefined;
  if (reader === undefined) return '';
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > MAX_ROUTING_RESPONSE_BYTES) {
      await reader.cancel();
      throw new RoutingError('oversized');
    }
    chunks.push(value);
  }
  return Buffer.concat(chunks).toString('utf8');
}

/**
 * The OSRM adapter (ADR 0020): one GET to the private service of the requested mode.
 *
 * OSRM TAKES THE COORDINATES IN THE URL PATH, and the base URL is itself kept secret. So nothing
 * that leaves this function may carry the URL: every failure becomes a `RoutingError` with a
 * kind only, and the original error (whose message or `cause` can quote the URL) is dropped, not
 * wrapped. Do not add logging here. (That the PLATFORM does not log these URLs either is the job
 * of the log exclusions, docs/runbooks/routing-capacity-staging.md.)
 *
 * One attempt, no retry: a scaled-to-zero service can take longer than the timeout to start, and
 * the app retries (P012c). `Connection: close` because osrm-routed drops idle connections after
 * five seconds, and a reused dead connection would look like an outage.
 */
export function createOsrmRoutingProvider(options: OsrmOptions): RoutingProvider {
  const doFetch = options.fetchImpl ?? fetch;
  const point = (c: { latitude: number; longitude: number }) =>
    `${c.longitude.toFixed(6)},${c.latitude.toFixed(6)}`;

  async function route({ origin, destination, mode }: RouteQuery): Promise<ProviderRoute[]> {
    const base = options.baseUrls[mode];
    const headers: Record<string, string> = { Accept: 'application/json', Connection: 'close' };
    if (options.idTokens !== undefined) {
      headers.Authorization = `Bearer ${await options.idTokens.get(base)}`;
    }
    const alternatives = options.alternatives === 0 ? 'false' : String(options.alternatives);
    const url =
      `${base}/route/v1/${mode}/${point(origin)};${point(destination)}` +
      `?steps=false&overview=full&geometries=polyline6&alternatives=${alternatives}`;

    let response: Response;
    let text: string;
    try {
      response = await doFetch(url, {
        headers,
        redirect: 'error',
        signal: AbortSignal.timeout(options.timeoutMs),
      });
      text = await readBounded(response);
    } catch (err) {
      if (err instanceof RoutingError) throw err;
      const name = err instanceof Error ? err.name : '';
      throw new RoutingError(
        name === 'TimeoutError' || name === 'AbortError' ? 'timeout' : 'network',
      );
    }

    if (response.status === 401 || response.status === 403) throw new RoutingError('auth');
    if (response.status === 429 || response.status >= 500) throw new RoutingError('upstream');

    let parsed: z.infer<typeof OsrmResponseSchema>;
    try {
      parsed = OsrmResponseSchema.parse(JSON.parse(text));
    } catch {
      throw new RoutingError('malformed');
    }
    // OSRM answers these two with HTTP 400 and a code.
    if (parsed.code === 'NoRoute') throw new RoutingError('no_route');
    if (parsed.code === 'NoSegment') throw new RoutingError('not_routable');
    if (response.status !== 200 || parsed.code !== 'Ok' || !parsed.routes?.length) {
      throw new RoutingError('malformed');
    }
    return parsed.routes.map((r) => ({
      distanceMeters: Math.round(r.distance),
      durationSeconds: Math.round(r.duration),
      geometry: r.geometry,
    }));
  }

  return { attribution: ROUTING_ATTRIBUTION, route };
}
