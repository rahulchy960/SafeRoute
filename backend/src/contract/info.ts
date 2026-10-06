// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Top-level metadata of the generated OpenAPI document (ADR 0004).
 *
 * `CONTRACT_VERSION` versions the HTTP contract, not the build: it never contains a git SHA or
 * build number. Bump it in the same PR that changes the spec: minor for additive changes, and a
 * breaking change also needs an ADR plus a new path version or a coordinated app release.
 */
export const CONTRACT_VERSION = '0.4.0';

export const OPENAPI_VERSION = '3.1.0';

export const API_DESCRIPTION =
  'SafeRoute is a personal-safety navigation API. Safety information is context, never a ' +
  'guarantee that a route or area is safe. SafeRoute is not an emergency service; in an ' +
  'emergency call your local emergency number (112 in India).';

/** Tags group operations in the spec and in the generated client. */
export const TAGS = [
  {
    name: 'operational',
    description: 'Unversioned probes for the platform (liveness, readiness). Not for app features.',
  },
  {
    name: 'me',
    description:
      "The signed-in user's own account and consents. Every operation needs a Firebase ID " +
      'token (phone sign-in).',
  },
  {
    name: 'search',
    description:
      'Place search through a geocoding provider. Queries are rate-limited and are not stored ' +
      'or logged.',
  },
];

/**
 * Passed to `getOpenAPI31Document`. No contact block (no personal data in a public contract) and
 * a relative server URL, so the spec names no real host.
 */
export const OPENAPI_OBJECT_CONFIG = {
  openapi: OPENAPI_VERSION,
  info: {
    title: 'SafeRoute API',
    version: CONTRACT_VERSION,
    description: API_DESCRIPTION,
    license: { name: 'AGPL-3.0-only', identifier: 'AGPL-3.0-only' },
  },
  servers: [{ url: '/' }],
  tags: TAGS,
};
