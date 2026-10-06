// SPDX-License-Identifier: AGPL-3.0-only
import type { GeocodingProviderName } from '../../../config.js';
import type { GeocoderProvider } from '../types.js';
import { createGeoapifyGeocoder } from './geoapify.js';
import type { ProviderHttpOptions } from './http.js';
import { createLocationIqGeocoder } from './locationiq.js';

/**
 * The adapter for a provider name. The endpoint and the evaluation harness both come through
 * here, so a provider is always chosen by name (GEOCODING_PROVIDER) and a new one is added in
 * this folder and in `GEOCODING_PROVIDERS` (src/config.ts) without touching the endpoint.
 */
export function createGeocoder(
  name: GeocodingProviderName,
  apiKey: string,
  http: ProviderHttpOptions,
): GeocoderProvider {
  switch (name) {
    case 'geoapify':
      return createGeoapifyGeocoder(apiKey, http);
    case 'locationiq':
      return createLocationIqGeocoder(apiKey, http);
  }
}
