// SPDX-License-Identifier: AGPL-3.0-only

/** Values of users.role (check constraint users_role_check). */
export const ROLES = ['user', 'moderator', 'admin'] as const;
export type Role = (typeof ROLES)[number];

/** admin ⊇ moderator ⊇ user. */
const RANK: Record<Role, number> = { user: 0, moderator: 1, admin: 2 };

export function isRole(value: string): value is Role {
  return (ROLES as readonly string[]).includes(value);
}

/**
 * True if `actual` (the role stored in the database) includes the privileges of `required`.
 * An unknown value (impossible today because of the check constraint) fails every check.
 */
export function hasRole(actual: string, required: Role): boolean {
  return isRole(actual) && RANK[actual] >= RANK[required];
}
