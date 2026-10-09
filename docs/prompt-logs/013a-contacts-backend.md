# P013a: Emergency contacts API, SOS consent gating and the fragment-token opt-out page

| Field | Value |
| --- | --- |
| Prompt | P013 · part a (**P013a backend**; P013b Android) |
| Milestone | M5 (depends on P009, P011f2, P012c2: all merged; previous prompt P012g merged as `4b9cf71`) |
| Branch | `feat/013a-contacts-backend` |
| PR title | `feat(contacts): emergency contacts API, SOS consent gating and fragment-token opt-out page [P013a]` |
| Notion | [P013a row in the Prompt Log](https://app.notion.com/p/3eb0737077208180b88bf54a3e5db5df) |
| Date | 2026-10-09 |
| Plan refs | Plan v7 §3.2 F-07, §6.2, §6.3, §7.5, §11, §12.1, §12.2; addenda v7.1, v7.3, v7.4; ADRs 0004, 0006, 0010, 0019, 0024 |

> **Not verified here:** the opt-out page in a real browser. The tests check the bytes and the
> headers the route serves, without a browser, so they cannot see a CSP violation or an old
> WebView that lacks an API. Rahul checks the page on a phone after the deploy (section 11).
>
> **The staging deploy is unverified** until Rahul reports the Actions run. A merge deploys:
> it runs migration `0004_emergency_contacts`, so the staging database must be running.
>
> **Size:** about 2,900 added lines outside generated files (source 1,190, tests 1,050,
> documents 650), far over the ~800-line guide. Not split: the only clean cut is "contacts
> first, opt-out second", and the first half alone would store other people's phone numbers
> with no way for them to opt out, which Plan v7 §12.1 does not allow.
>
> **Wording is a draft.** The page text, English and Bengali, and the tombstone retention are
> to be verified by a lawyer; the Bengali also by a native speaker.

## 1. Objective

Let a signed-in adult keep up to 5 emergency contacts on the server, only with the
`sos_alerts` consent, and let each contact opt out through a public page without an account.
The server sends no message to anyone. No SOS, no alerts, no live sharing.

## 2. Context & prerequisites

- Preflight: `main` synced; P012g (PR #47) merged; no open pull requests; hook path `.githooks`.
- `sos_alerts` was already in the purposes allowlist (since P009a). Nothing to add there.
- Docker was running for the database tests.
- The Plan PDF cannot be read on this machine; the plan text quoted in the prompt was used.

## 3. Workflow executed

1. `/start-prompt`: sync, previous PR check, Notion (P012g set to Merged), branch.
2. A0 spike (below), then A1 schema and migration (`pnpm db:generate --name emergency_contacts`).
3. A2 to A5: the `contacts` module, the consent gate and the erasure on withdrawal, the page.
4. A7 tests; contract regenerated (`pnpm openapi:generate`), linted, compared with `main`.
5. A6: ADR 0024, the diagram, READMEs, a rules section in `CLAUDE.md`.
6. `/ship-prompt`: gate, log, commit, push, PR, Notion.

### A0 spike: what is certain

| Question | Answer | How sure |
| --- | --- | --- |
| Can Hono serve an inline-script page under a hash-based CSP? | Yes. The script and the style are constants; their SHA-256 is computed when the module loads and put in the header. A test recomputes both from the served HTML. | Certain for the header and the bytes; **not run in a browser** |
| Are fragments sent to servers? | No. The fragment is not part of the request target (RFC 9110 §7.1, RFC 3986 §3.5). | Certain (protocol) |
| What do Cloud Run request logs record? | The request URL as received: path and query. So `/c`, never the fragment. The API's own access log records the route pattern only. | From the platform's documentation and ADR 0019; **not read from the staging logs** |
| Can a link preview opt somebody out? | No. Fetching `/c` returns the static page; the opt-out needs the script and a tap. | Certain (design, tested) |

## 4. Changes

**Database** (`backend/drizzle/0004_emergency_contacts.sql`, expand-only)

- `emergency_contacts`: as specified, with `UNIQUE (user_id, phone_e164)`, the two CHECKs,
  `ON DELETE CASCADE` to the user and `SET NULL` for `has_app_user_id`. Comments mark `name`
  and `phone_e164` as personal data for P020.
- `contact_optout_tokens`: `token_hash bytea` primary key, `contact_id` with cascade, an index.

**Backend** (`backend/src/modules/contacts/`)

- `GET /v1/contacts`, `POST /v1/contacts`, `PATCH` and `DELETE /v1/contacts/{id}`,
  `POST /v1/contacts/{id}/invite`, `POST /v1/contacts/{id}/invite/confirm`: all behind
  `requireUser`, `Cache-Control: no-store`.
- Create runs in one transaction that locks the user's row, then checks: consent, own number,
  duplicate or opted-out number, the limit of 5.
- Delete is a hard delete, except for an opted-out contact, which becomes a tombstone that
  keeps the number only (the name is replaced, the tokens are deleted).
- Invite mints 128 random bits (22 base64url characters), stores the SHA-256, returns the
  plaintext once; at most 10 links per contact; older links stay valid.
- `GET /c`: the static opt-out page (English and Bengali, toggle, five states).
  `POST /v1/public/contacts/opt-out`: the token in the body, one shared rate limit, the same
  404 for a malformed, unknown or removed token.
- `consents`: withdrawing `sos_alerts` deletes every contact, tombstone and token in the same
  transaction (`contacts/erasure.ts`); `latestConsentStatus` for feature gates.
- Rate limits in `CONTACT_LIMITS`: create 5 at once then 1 a minute and 20 a day; invite links
  30 a day; public opt-out 60 with 1 a second, shared.

Decisions made while building, beyond the prompt's text:

- `invite/confirm` answers 409 `conflict` when no invite link exists for the contact, and 409
  `contact_opted_out` for an opted-out one. Otherwise "Invited" could be recorded for a contact
  who was never sent a link.
- An invalid phone number or name is 400 `validation_error`; 409 `invalid_contact` is only the
  user's own number.
- A tombstone does not keep the contact's name.
- A name is at most 80 UTF-16 units (the same rule as the display name), so it always passes
  the table's `char_length` check.
- The tenth-link limit answers 429 `rate_limited` without `Retry-After`: waiting does not help.

**Contract** (`contracts/openapi.json`, 0.8.0 → 0.9.0, additive)

- 6 new paths, 8 operations; tags `contacts` and `public`; codes `invalid_contact`,
  `contact_exists`, `contact_opted_out`, `contact_limit_reached`.
- `.redocly.lint-ignore.yaml`: `/c` joins the two probes as an operation with no 4xx (it takes
  no input).

**Docs**: ADR 0024; `docs/diagrams/013a-contacts-and-optout.*`; `contracts/README.md`;
`backend/README.md`; `backend/src/modules/README.md`; "Emergency contacts rules" in `CLAUDE.md`.

## 5. Diagram

[`docs/diagrams/013a-contacts-and-optout.svg`](../diagrams/013a-contacts-and-optout.svg): the
data model and the opt-out flow, with the fragment shown as staying on the contact's phone.
16 nodes.

## 6. Quality gate & test results

Run inside `backend/` on 2026-10-09:

| Command | Result |
| --- | --- |
| `pnpm typecheck` | pass |
| `pnpm lint` | pass, 0 problems |
| `pnpm format:check` | pass |
| `pnpm test` | **703 passed, 0 failed** (34 files; unit and db projects, PostGIS in Docker) |
| `pnpm build` | pass |
| `pnpm db:check` | pass |
| `pnpm openapi:check` | up to date |
| `pnpm openapi:lint` | valid, 0 warnings, 3 documented exceptions |
| oasdiff 1.32.1 `breaking` against `main` | no breaking changes |
| `tools/diagrams`: `pnpm generate` | 1 diagram, no warnings |
| markdownlint (Docker, 109 files) | 0 errors |
| gitleaks (Docker) | no leaks found |
| JSON validity (diagram spec, contract) | pass |

Not run: the container smoke test (no change to the Dockerfile or the smoke script; CI runs it).

New tests: `test/db/contacts.test.ts` (CRUD; the limit of 5 with 10 parallel creates; duplicates;
own number; consent missing, granted, withdrawn with the cascade; other users' ids; invite tokens
and "no plaintext in the database"; the 10-link cap; confirm; opt-out valid, repeated, invalid,
removed contact, rate limit; tombstone; user deletion cascade; rate limits; log capture; CHECKs
and comments), `test/contacts-page.test.ts` (headers, CSP hashes, no external URL, no cookie,
both languages, the same bytes for everyone, name and phone rules, migration expand-only), and
one contract test.

**Failure matrix (Plan v7 §7.5), the row this part covers:** "an opted-out contact must never
be alerted", at the data level. `optedOutAt` is set by the opt-out, is returned in the list, and
an opted-out contact cannot be invited, confirmed, or added again, before or after removal
(tests under "an opted-out contact"). The device-side rows (contacts available offline, empty
at SOS time, never synced) belong to P013b and P014.

## 7. Decisions & ADRs

[ADR 0024](../adr/0024-emergency-contacts-and-opt-out.md), Accepted: server as the source of
truth with an offline copy; consent gating and erasure on withdrawal; no server messaging; the
token in the URL fragment, then POSTed; several valid links per contact; the tombstone; no
exposure of whether a number is a registered user; the deviation from Plan v7 §6.3; link host
stability; the limits.

## 8. Security & privacy notes

- New personal data: names and phone numbers of people who are not users. Stored only with the
  user's `sos_alerts` consent; erased on withdrawal and with the account (cascade).
- Names, numbers and tokens are in no URL, log line, error body or audit row. A log-capture
  test runs the whole flow and searches every line. **It sees the API's own log lines only.**
  That the platform's request log is clean follows from the contract (no such value is in a
  path or a query) and was not read from staging.
- Tokens: 128 bits from `crypto.randomBytes`; only SHA-256 stored; a test dumps the four
  tables and searches for the plaintext.
- The public endpoint stores and logs nothing about the caller, no IP address included.
- No new secret, environment variable, permission or dependency.
- All test data is fake: `+91000000NNNN`, `+91000010NNNN`, "Test Contact N".

## 9. Known issues & risks

- The page is untested in real browsers (see the note at the top).
- The link's host is the staging service address. Links in people's SMS break if it changes;
  a custom domain is needed before a public beta.
- A user can add somebody who does not want it and text them from their own phone. The limits
  bound it and the opt-out ends it for that user; nothing detects it yet.
- An SMS app that uploads message texts sees the whole link. The token can only opt out.
- The burst limit equals the contact limit (5): a user who adds five contacts within a minute
  and tries a sixth gets `rate_limited`, not `contact_limit_reached`. The app disables "Add" at
  five, so this should not be seen.
- The shared opt-out bucket can be exhausted by one abusive caller, which delays real opt-outs
  (the page then shows "try again"). Acceptable at pilot volume; revisit with real traffic.

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P013a):

- Lawyer review: the opt-out page wording and the tombstone retention (period, legal basis).
- Bengali review of the opt-out page by a native speaker.
- Link host stability: a custom domain before a public beta; keep `/c` on the old address.
- Abuse monitoring: somebody adding a person who does not want it.
- Matching contacts to registered users and its privacy design (P015).
- Revisit the shared opt-out rate limit with real traffic.

For P013b: part a merged **and deployed**; the client regenerated from contract 0.9.0; the app
builds the link as `<API base>/c#<token>`; treat `contact_exists` on a retried create as
"read the list again"; an item with `optedOutAt` is shown as opted out and is never invited.

## 11. How Rahul can verify

1. Start the staging database, then merge. Watch Actions → deploy-staging: the migration job
   applies `0004_emergency_contacts`, then the smoke tests pass.
2. `GET <service address>/c` → 200, HTML, with the headers `Content-Security-Policy`
   (`default-src 'none'`, two `sha256-` entries), `Cache-Control: no-store`,
   `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`.
3. `POST /v1/contacts` without a token → 401.
4. `POST /v1/public/contacts/opt-out` with the body `{"token":"AAAAAAAAAAAAAAAAAAAAAA"}` → 404
   `not_found`.
5. On a phone, open `<service address>/c#AAAAAAAAAAAAAAAAAAAAAA`: the address bar loses the
   `#...` part at once; tap "Opt out" → "This link is no longer active"; the language button
   switches to Bengali and back. In desktop Chrome, the DevTools console shows no CSP error.
6. Open `<service address>/c` with no `#...` → "This link is no longer active", no button.
7. Cloud Logging, the request log of the API service: the entries for step 5 show `/c` and
   `/v1/public/contacts/opt-out`, with no `AAAA...` anywhere.

## 12. Learning notes

No Android in this part. Web and database concepts used:

- **URL fragment.** The part after `#`. The browser keeps it and uses it on the page; it is not
  in the request, so no server and no server log ever sees it. See MDN, "URL fragment".
- **Content-Security-Policy with hashes.** A response header that tells the browser what the
  page may load and run. Listing the SHA-256 of the one inline script means only that exact
  script runs; anything injected is refused. See MDN, "Content-Security-Policy: script-src".
- **Storing a hash of a token.** Like a password: the database keeps a one-way hash, so a copy
  of the database does not give working links. A random 128-bit token needs no salt.
- **Row lock (`SELECT ... FOR UPDATE`).** Two requests of the same user wait for each other,
  so "count, then insert" cannot run twice at once and pass the limit of 5.
- **Tombstone.** A row kept only to remember that something was removed, here so that an
  opted-out number cannot be added again.

## Revision (P014a1, 2026-10-09): staging checks reported

Reported by Rahul in the P014 prompt; Claude Code cannot see the deployed service and did not
repeat the checks.

- `GET /c` returned 200 with the `Content-Security-Policy` header and the other headers of
  section 11, step 2.
- `POST /v1/contacts` without a token returned 401.
- `POST /v1/public/contacts/opt-out` with a made-up token returned 404.
- On the page, the fragment left the address bar.
- The request-log paths held no token.

Not recorded: the date of the checks, the browser and phone used, and the Bengali switch of the
page. The phone checks of P013b2 (its section 11) are **not recorded**: the P014 prompt left
their place empty.
