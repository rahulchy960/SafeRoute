// SPDX-License-Identifier: AGPL-3.0-only
import { createHash } from 'node:crypto';

/**
 * The public opt-out page served at GET /c (ADR 0024). One static document: inline CSS and
 * JavaScript, no external resource, no cookie, no analytics, and nothing about the visitor or
 * the contact in it. Every visitor gets the same bytes.
 *
 * The opt-out token arrives in the URL fragment (`/c#<token>`). A browser never sends a fragment
 * to a server, so the token is in no request URL and in no request log. The script reads it,
 * removes it from the address bar, and sends it in the BODY of POST /v1/public/contacts/opt-out
 * only when the visitor taps the button: opening the link (or a link preview fetching it) opts
 * nobody out.
 *
 * The wording, English and Bengali, is a DRAFT: to be verified by a lawyer, and the Bengali by a
 * native speaker.
 */

const STYLE = `
:root{color-scheme:light dark}
body{margin:0;font-family:system-ui,-apple-system,"Segoe UI",Roboto,"Noto Sans Bengali",sans-serif;line-height:1.5;background:#fff;color:#1b1b1f}
main{max-width:34rem;margin:0 auto;padding:1.5rem 1rem 3rem}
h1{font-size:1.4rem;line-height:1.3;margin:1rem 0}
p{margin:.75rem 0}
button{font:inherit;min-height:48px;padding:.5rem 1.25rem;border-radius:.5rem;cursor:pointer}
#optout{border:0;background:#1b4f9c;color:#fff;font-weight:600;margin-top:.5rem}
#optout:disabled{opacity:.6;cursor:default}
#lang{float:right;border:1px solid #767680;background:transparent;color:inherit}
#status{font-weight:600}
@media (prefers-color-scheme:dark){body{background:#121316;color:#e4e2e6}#optout{background:#a8c8ff;color:#06305f}}
`;

const SCRIPT = `
(function () {
  'use strict';
  var T = {
    en: {
      title: 'You were added as an emergency contact',
      added: 'Someone added your phone number as an emergency contact in SafeRoute, a personal-safety app.',
      when: "You'd only be contacted if that person triggers an emergency alert.",
      notService: 'SafeRoute is not an emergency service. In an emergency, call 112.',
      choice: "If you'd rather not be an emergency contact, you can opt out here. You will then not be alerted through SafeRoute for this person.",
      optOut: 'Opt out',
      retry: 'Try again',
      working: 'Working\\u2026',
      done: 'You have opted out. You will not be alerted through SafeRoute for this person.',
      inactive: 'This link is no longer active. If you still get messages you do not want, ask the person who added you to remove you.',
      error: 'Could not reach SafeRoute. Check your connection and try again.',
      other: '\\u09AC\\u09BE\\u0982\\u09B2\\u09BE'
    },
    bn: {
      title: 'আপনাকে জরুরি যোগাযোগ হিসেবে যোগ করা হয়েছে',
      added: 'কেউ একজন SafeRoute-এ আপনার ফোন নম্বর জরুরি যোগাযোগ হিসেবে যোগ করেছেন। SafeRoute একটি ব্যক্তিগত নিরাপত্তা অ্যাপ।',
      when: 'ওই ব্যক্তি জরুরি সতর্কবার্তা চালু করলে তবেই আপনার সঙ্গে যোগাযোগ করা হবে।',
      notService: 'SafeRoute কোনো জরুরি পরিষেবা নয়। জরুরি অবস্থায় 112 নম্বরে ফোন করুন।',
      choice: 'আপনি জরুরি যোগাযোগ হতে না চাইলে এখানে অপ্ট আউট করতে পারেন। তখন এই ব্যক্তির জন্য SafeRoute-এর মাধ্যমে আপনাকে আর সতর্কবার্তা পাঠানো হবে না।',
      optOut: 'অপ্ট আউট করুন',
      retry: 'আবার চেষ্টা করুন',
      working: 'অনুরোধ পাঠানো হচ্ছে\\u2026',
      done: 'আপনি অপ্ট আউট করেছেন। এই ব্যক্তির জন্য SafeRoute-এর মাধ্যমে আপনাকে আর সতর্কবার্তা পাঠানো হবে না।',
      inactive: 'এই লিংকটি আর সক্রিয় নেই। এরপরও অবাঞ্ছিত বার্তা পেলে, যিনি আপনাকে যোগ করেছেন তাঁকে আপনার নম্বর সরিয়ে দিতে বলুন।',
      error: 'SafeRoute-এ পৌঁছানো যায়নি। ইন্টারনেট সংযোগ দেখে আবার চেষ্টা করুন।',
      other: 'English'
    }
  };
  var match = /^#([A-Za-z0-9_-]{22})$/.exec(location.hash);
  var token = match ? match[1] : '';
  try { history.replaceState(null, '', location.pathname); } catch (e) { /* the page still works */ }
  var lang = /^bn/i.test(navigator.language || '') ? 'bn' : 'en';
  var state = token ? 'ready' : 'inactive';
  function el(id) { return document.getElementById(id); }
  function render() {
    var t = T[lang];
    document.documentElement.lang = lang;
    document.title = t.title + ' \\u00B7 SafeRoute';
    el('title').textContent = t.title;
    el('added').textContent = t.added;
    el('when').textContent = t.when;
    el('not-service').textContent = t.notService;
    el('choice').textContent = t.choice;
    el('choice').hidden = state === 'done' || state === 'inactive';
    el('status').textContent = state === 'ready' ? '' : t[state];
    el('optout').textContent = state === 'error' ? t.retry : t.optOut;
    el('optout').hidden = state === 'done' || state === 'inactive';
    el('optout').disabled = state === 'working';
    el('lang').textContent = t.other;
  }
  function send() {
    state = 'working';
    render();
    fetch('/v1/public/contacts/opt-out', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token: token }),
      credentials: 'omit',
      cache: 'no-store',
      referrerPolicy: 'no-referrer'
    }).then(function (res) {
      state = res.status === 200 ? 'done' : res.status === 404 ? 'inactive' : 'error';
      if (state !== 'error') token = '';
      render();
    }).catch(function () {
      state = 'error';
      render();
    });
  }
  el('optout').addEventListener('click', send);
  el('lang').addEventListener('click', function () {
    lang = lang === 'en' ? 'bn' : 'en';
    render();
  });
  el('lang').hidden = false;
  render();
})();
`;

/** The English text is in the markup, so the page reads correctly before (or without) the script. */
export const OPT_OUT_PAGE_HTML = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<meta name="referrer" content="no-referrer">
<title>You were added as an emergency contact · SafeRoute</title>
<style>${STYLE}</style>
</head>
<body>
<main>
<button type="button" id="lang" hidden>বাংলা</button>
<h1 id="title">You were added as an emergency contact</h1>
<p id="added">Someone added your phone number as an emergency contact in SafeRoute, a personal-safety app.</p>
<p id="when">You'd only be contacted if that person triggers an emergency alert.</p>
<p id="not-service">SafeRoute is not an emergency service. In an emergency, call 112.</p>
<p id="choice">If you'd rather not be an emergency contact, you can opt out here. You will then not be alerted through SafeRoute for this person.</p>
<p id="status" role="status" aria-live="polite"></p>
<button type="button" id="optout">Opt out</button>
<noscript><p>This page needs JavaScript to opt you out. এই পাতায় অপ্ট আউট করতে JavaScript চালু থাকা দরকার।</p></noscript>
</main>
<script>${SCRIPT}</script>
</body>
</html>
`;

const sha256 = (text: string) => createHash('sha256').update(text, 'utf8').digest('base64');

/**
 * Computed once when the module loads. Only the exact inline script and style above may run: a
 * hash, not `'unsafe-inline'`, so injected markup could not execute. `connect-src 'self'` lets
 * the script call this API and nothing else.
 */
export const OPT_OUT_PAGE_CSP = [
  "default-src 'none'",
  `script-src 'sha256-${sha256(SCRIPT)}'`,
  `style-src 'sha256-${sha256(STYLE)}'`,
  "connect-src 'self'",
  "base-uri 'none'",
  "form-action 'none'",
  "frame-ancestors 'none'",
].join('; ');
