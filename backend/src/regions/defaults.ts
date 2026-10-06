// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Where search looks first when the app sends no map position (ADR 0018): the default search
 * BIAS, never a filter. Results from anywhere in the country are still returned.
 *
 * Coarse on purpose (two decimals, about 1 km) and the same place the app's map opens on. It is a
 * fact about the launch region, so it lives here as data; the regions configuration replaces it
 * (ADR 0013, ADR 0017). No region name in the identifier (ADR 0005).
 */
export const LAUNCH_REGION_CENTER = { latitude: 22.57, longitude: 88.36 } as const;

/** ISO 3166-1 alpha-2 code the geocoder is limited to. */
export const LAUNCH_COUNTRY_CODE = 'in';
