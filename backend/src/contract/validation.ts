// SPDX-License-Identifier: AGPL-3.0-only
import type { Hook } from '@hono/zod-openapi';
import type { ValidationTargets } from 'hono';
import { problemResponse } from '../lib/problem.js';
import type { AppEnv } from '../types.js';
import type { ValidationIssue } from './problem.js';

/** Contract-facing names for Hono's validation targets (`json` is the request body). */
const TARGET_NAMES: Record<keyof ValidationTargets, string> = {
  json: 'body',
  form: 'body',
  query: 'query',
  param: 'path',
  header: 'header',
  cookie: 'cookie',
};

/**
 * Default hook for every `createRoute` route: turns a failed Zod validation into a 400
 * `validation_error` problem with one `{ path, code }` entry per issue.
 *
 * It never echoes submitted values: request bodies can hold locations, tokens or phone numbers
 * (Plan v7 §12.2). Only the issue path and Zod's issue code leave the server; Zod's `message`
 * and `input` are dropped. Nothing is logged here beyond the usual access-log line.
 */
export const validationHook: Hook<unknown, AppEnv, string, Response | undefined> = (result, c) => {
  if (result.success) return undefined;
  const target = TARGET_NAMES[result.target];
  const errors: ValidationIssue[] = result.error.issues.map((issue) => ({
    path: [target, ...issue.path.map(String)].join('.'),
    code: issue.code,
  }));
  return problemResponse(
    c,
    400,
    'validation_error',
    'The request is invalid. See `errors` for the fields to fix.',
    c.get('requestId'),
    errors,
  );
};
