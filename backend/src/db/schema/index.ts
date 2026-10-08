// SPDX-License-Identifier: AGPL-3.0-only
// Barrel for drizzle-kit (drizzle.config.ts) and the typed Drizzle client. One file per table group.
export { auditLog } from './audit.js';
export { consentRecords } from './consents.js';
export { contactOptoutTokens, emergencyContacts } from './contacts.js';
export { devices } from './devices.js';
export { idempotencyKeys } from './idempotency.js';
export { rateLimitBuckets } from './rate-limits.js';
export { users } from './users.js';
