import type { Context } from 'hono';
import type { ContentfulStatusCode } from 'hono/utils/http-status';
import type { ProblemDetails, ValidationIssue } from '../contract/problem.js';

/**
 * RFC 9457 problem details plus the stable `code` the app switches on (Plan v7 §6.2). The
 * contract schema is `ProblemDetailsSchema` in src/contract/problem.ts. HTTP bodies use camelCase
 * (`requestId`); log lines keep snake_case (`request_id`).
 */
export type Problem = ProblemDetails & { status: ContentfulStatusCode };

const STATUS_TITLES: Partial<Record<number, string>> = {
  400: 'Bad Request',
  401: 'Unauthorized',
  403: 'Forbidden',
  404: 'Not Found',
  405: 'Method Not Allowed',
  409: 'Conflict',
  413: 'Content Too Large',
  415: 'Unsupported Media Type',
  422: 'Unprocessable Content',
  429: 'Too Many Requests',
  500: 'Internal Server Error',
  502: 'Bad Gateway',
  503: 'Service Unavailable',
  504: 'Gateway Timeout',
};

export function statusTitle(status: number): string {
  return STATUS_TITLES[status] ?? 'Error';
}

/**
 * An expected failure a route or service raises on purpose. `detail` is sent to the client, so it
 * must be safe to show: no internals, no personal data.
 */
export class AppError extends Error {
  readonly status: ContentfulStatusCode;
  readonly code: string;
  readonly detail: string;

  constructor(status: ContentfulStatusCode, code: string, detail: string, options?: ErrorOptions) {
    super(detail, options);
    this.name = 'AppError';
    this.status = status;
    this.code = code;
    this.detail = detail;
  }
}

/**
 * Builds an `application/problem+json` response. `type` is `about:blank`, so `title` is the HTTP
 * status phrase (RFC 9457 §4.2.1) and `code` carries the application-specific meaning.
 */
export function problemResponse(
  c: Context,
  status: ContentfulStatusCode,
  code: string,
  detail: string,
  requestId: string,
  errors?: ValidationIssue[],
): Response {
  const body: Problem = {
    type: 'about:blank',
    title: statusTitle(status),
    status,
    detail,
    code,
    requestId,
    ...(errors ? { errors } : {}),
  };
  return c.body(JSON.stringify(body), status, { 'Content-Type': 'application/problem+json' });
}
