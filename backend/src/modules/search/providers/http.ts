// SPDX-License-Identifier: AGPL-3.0-only
import { GeocoderError } from '../types.js';

/** Largest provider response we read. Ten results are a few kilobytes. */
export const MAX_PROVIDER_RESPONSE_BYTES = 256 * 1024;

export interface ProviderHttpOptions {
  timeoutMs: number;
  /** Injected in tests; the global `fetch` otherwise. */
  fetchImpl?: typeof fetch;
}

export interface ProviderResponse {
  status: number;
  /** Parsed JSON body, or undefined when the body was empty or not JSON. */
  json: unknown;
}

/** Seconds from a `Retry-After` header, when it is a small whole number. */
function retryAfter(headers: Headers): number | undefined {
  const value = Number(headers.get('retry-after'));
  return Number.isInteger(value) && value > 0 && value <= 86_400 ? value : undefined;
}

async function readBounded(response: Response): Promise<string> {
  const reader = response.body?.getReader() as ReadableStreamDefaultReader<Uint8Array> | undefined;
  if (reader === undefined) return '';
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > MAX_PROVIDER_RESPONSE_BYTES) {
      await reader.cancel();
      throw new GeocoderError('oversized');
    }
    chunks.push(value);
  }
  return Buffer.concat(chunks).toString('utf8');
}

/**
 * One GET to a geocoding provider. No retries: the user is typing and the next keystroke is the
 * retry.
 *
 * THE KEY TRAVELS IN THE URL (both providers take it as a query parameter). So nothing that
 * leaves this function may carry the URL: every failure becomes a `GeocoderError` with a kind
 * only, and the original error (whose message or `cause` can quote the URL) is dropped, not
 * wrapped. Do not add logging here.
 */
export async function providerGet(
  url: URL,
  options: ProviderHttpOptions,
): Promise<ProviderResponse> {
  const doFetch = options.fetchImpl ?? fetch;
  let response: Response;
  let text: string;
  try {
    response = await doFetch(url, {
      method: 'GET',
      headers: { Accept: 'application/json' },
      redirect: 'error',
      signal: AbortSignal.timeout(options.timeoutMs),
    });
    text = await readBounded(response);
  } catch (err) {
    if (err instanceof GeocoderError) throw err;
    const name = err instanceof Error ? err.name : '';
    throw new GeocoderError(
      name === 'TimeoutError' || name === 'AbortError' ? 'timeout' : 'network',
    );
  }

  if (response.status === 401 || response.status === 403) throw new GeocoderError('auth');
  if (response.status === 429)
    throw new GeocoderError('rate_limited', retryAfter(response.headers));
  if (response.status >= 500) throw new GeocoderError('upstream');

  let json: unknown;
  try {
    json = text === '' ? undefined : (JSON.parse(text) as unknown);
  } catch {
    json = undefined;
  }
  return { status: response.status, json };
}

/** Text with whitespace collapsed and a length cap, so a provider cannot send us a novel. */
export function clean(value: string | undefined, max = 300): string {
  return (value ?? '').replace(/\s+/g, ' ').trim().slice(0, max);
}
