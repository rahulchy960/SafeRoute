// SPDX-License-Identifier: AGPL-3.0-only
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { normalizeContactName, normalizeContactPhone } from '../src/modules/contacts/schema.js';
import { accessLines, buildTestApp } from './helpers.js';

const sha256 = (text: string) => createHash('sha256').update(text, 'utf8').digest('base64');

async function page() {
  const { app, logs } = buildTestApp();
  const res = await app.request('/c');
  return { res, html: await res.text(), logs };
}

describe('GET /c (the opt-out page)', () => {
  it('is a public HTML page with the strict headers', async () => {
    const { res } = await page();
    expect(res.status).toBe(200);
    expect(res.headers.get('content-type')).toMatch(/^text\/html; charset=utf-8$/i);
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(res.headers.get('x-content-type-options')).toBe('nosniff');
    expect(res.headers.get('referrer-policy')).toBe('no-referrer');
    expect(res.headers.get('set-cookie')).toBeNull();
    expect(res.headers.get('x-request-id')).toBeTruthy();
  });

  it('allows exactly its own inline script and style by hash, and nothing else', async () => {
    const { res, html } = await page();
    const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1] ?? '');
    const styles = [...html.matchAll(/<style>([\s\S]*?)<\/style>/g)].map((m) => m[1] ?? '');
    expect(scripts).toHaveLength(1);
    expect(styles).toHaveLength(1);
    // No other way to run script or apply style: no attributes, no event handlers.
    expect(html).not.toMatch(/<script[^>]+>/i);
    expect(html).not.toMatch(/\sstyle=/i);
    expect(html).not.toMatch(/\son[a-z]+=/i);

    const csp = res.headers.get('content-security-policy') ?? '';
    expect(csp.split('; ')).toEqual([
      "default-src 'none'",
      `script-src 'sha256-${sha256(scripts[0] ?? '')}'`,
      `style-src 'sha256-${sha256(styles[0] ?? '')}'`,
      "connect-src 'self'",
      "base-uri 'none'",
      "form-action 'none'",
      "frame-ancestors 'none'",
    ]);
    expect(csp).not.toContain('unsafe');
  });

  it('loads nothing from anywhere else and sets no cookie', async () => {
    const { html } = await page();
    expect(html).not.toMatch(/https?:/i);
    expect(html).not.toMatch(/(src|href|action)\s*=/i);
    expect(html).not.toMatch(/<(img|link|iframe|form|object|embed)\b/i);
    expect(html).not.toMatch(/document\.cookie|localStorage|sessionStorage|indexedDB/);
    // The one request it can make: same origin, the token in the body.
    expect(html.match(/fetch\(/g)).toHaveLength(1);
    expect(html).toContain("fetch('/v1/public/contacts/opt-out'");
    expect(html).toContain("credentials: 'omit'");
  });

  it('reads the token from the fragment, removes it from the address bar and never puts it in a URL', async () => {
    const { html } = await page();
    expect(html).toContain('location.hash');
    expect(html).toContain("history.replaceState(null, '', location.pathname)");
    expect(html).toContain('body: JSON.stringify({ token: token })');
    expect(html).not.toMatch(/location\.(href|search|assign|replace)\b/);
  });

  it('has both languages, every state, the 112 line and the "not an emergency service" line', async () => {
    const { html } = await page();
    expect(html).toContain('You were added as an emergency contact');
    expect(html).toContain('SafeRoute is not an emergency service. In an emergency, call 112.');
    expect(html).toContain('আপনাকে জরুরি যোগাযোগ হিসেবে যোগ করা হয়েছে');
    expect(html).toContain('SafeRoute কোনো জরুরি পরিষেবা নয়। জরুরি অবস্থায় 112 নম্বরে ফোন করুন।');
    expect(html).toContain('navigator.language');
    for (const state of ['working:', 'done:', 'inactive:', 'error:', 'retry:']) {
      expect(html.split(state).length - 1, state).toBe(2);
    }
    // Draft wording must not promise safety or call the app an emergency service.
    expect(html.toLowerCase()).not.toMatch(/\bsafe\b|guarantee/);
    expect(html.toLowerCase()).not.toContain('kolkata');
  });

  it('is the same for everyone: no token, id or personal data in it, whatever is asked', async () => {
    const { app } = buildTestApp();
    const plain = await (await app.request('/c')).text();
    const withQuery = await (
      await app.request('/c?x=AAAAAAAAAAAAAAAAAAAAAA', { headers: { 'Accept-Language': 'bn' } })
    ).text();
    expect(withQuery).toBe(plain);
    expect(plain).not.toMatch(/\+\d{7,15}/);
    expect(plain).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-/);
  });

  it('is logged as the path /c and nothing more', async () => {
    const { logs } = await page();
    expect(accessLines(logs())).toMatchObject([{ method: 'GET', path: '/c', status: 200 }]);
  });

  it('POST /v1/public/contacts/opt-out needs no sign-in (503 here: no database in this test)', async () => {
    const { app } = buildTestApp();
    const res = await app.request('/v1/public/contacts/opt-out', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token: 'AAAAAAAAAAAAAAAAAAAAAA' }),
    });
    expect(res.status).toBe(503);
    expect(await res.json()).toMatchObject({ code: 'db_not_configured' });
  });
});

describe('contact name and phone rules', () => {
  it.each([
    ['Test Contact', 'Test Contact'],
    ['  Test Contact  ', 'Test Contact'],
    ['মা', 'মা'],
    ['x'.repeat(80), 'x'.repeat(80)],
  ])('accepts the name %j', (input, expected) => {
    expect(normalizeContactName(input)).toBe(expected);
  });

  it.each([[''], ['   '], ['x'.repeat(81)], ['a\tb'], ['a\nb'], ['a\u0000b'], ['a\u2028b']])(
    'rejects the name %j',
    (input) => {
      expect(normalizeContactName(input)).toBeUndefined();
    },
  );

  it.each([
    ['+910000100001', '+910000100001'],
    [' +910000100001 ', '+910000100001'],
    ['+1000000', '+1000000'],
    ['+100000000000000', '+100000000000000'],
  ])('accepts the number %j', (input, expected) => {
    expect(normalizeContactPhone(input)).toBe(expected);
  });

  it.each([
    ['910000100001'],
    ['+010000100001'],
    ['+91 00001 00001'],
    ['+91-0000-100001'],
    ['+100000'],
    ['+1000000000000000'],
    ['0000100001'],
    ['+91000010000a'],
    [''],
  ])('rejects the number %j', (input) => {
    expect(normalizeContactPhone(input)).toBeUndefined();
  });
});

describe('migration 0004', () => {
  it('is expand-only: it creates and comments, and drops, renames or rewrites nothing', () => {
    const sql = readFileSync(
      new URL('../drizzle/0004_emergency_contacts.sql', import.meta.url),
      'utf8',
    );
    const statements = sql
      .split('--> statement-breakpoint')
      .map((part) => part.replace(/^--.*$/gm, '').trim())
      .filter((part) => part.length > 0);
    expect(statements.length).toBeGreaterThan(0);
    for (const statement of statements) {
      expect(statement).toMatch(
        /^(CREATE TABLE "(emergency_contacts|contact_optout_tokens)"|ALTER TABLE "(emergency_contacts|contact_optout_tokens)" ADD CONSTRAINT|CREATE INDEX|COMMENT ON)/,
      );
    }
    expect(sql).not.toMatch(/\b(DROP|RENAME|TRUNCATE|DELETE FROM|ALTER COLUMN)\b/);
  });
});
