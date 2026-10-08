# ADR 0024: Emergency contacts and opt-out

- **Status:** Accepted
- **Date:** 2026-10-09
- **Prompt:** P013a
- **Plan refs:** Plan v7 §3.2 F-07, §6.2, §6.3, §7.5, §11, §12.1, §12.2; [ADR 0004](0004-api-contract-and-conventions.md), [ADR 0006](0006-authentication-and-roles.md), [ADR 0010](0010-adults-only-and-consent-records.md), [ADR 0019](0019-privacy-in-urls.md)

Legal statements here are drafts, **to be verified by a lawyer**.

## Context

- A user keeps up to 5 emergency contacts. In P014 the phone texts them during an SOS, with no
  server and no mobile data, so the app needs the list offline.
- A contact is a person who is **not a user** and never agreed to anything. Plan v7 §12.1 asks
  that they are told when they are added and are given a way to opt out.
- The hosting platform logs every request URL ([ADR 0019](0019-privacy-in-urls.md)). An opt-out
  link sits in a person's SMS inbox for years and is opened in a browser that has no account.
- Plan v7 §6.3 listed the opt-out as `/v1/contacts/{id}/opt-out`. That design puts the only
  secret of a public endpoint in the path.
- There is no released app and there are no real contacts yet.

## Decision

1. **The server is the source of truth, the phone keeps a copy.** `emergency_contacts` holds
   the list; the app replaces its local copy after each successful fetch and reads the copy when
   there is no network (P013b). Changing the list needs the network.
2. **`sos_alerts` consent gates everything.** `POST /v1/contacts` requires the user's latest
   `sos_alerts` decision to be `granted` (403 `consent_required` otherwise), checked inside the
   transaction that inserts. Without that consent nothing about a contact is stored.
3. **Withdrawal erases.** `PUT /v1/me/consents/sos_alerts` with `withdrawn` deletes every
   contact row of the user, tombstones included, and every opt-out token, in the same
   transaction as the consent record, with an audit row that carries a count only. Creating a
   contact and changing consent both lock the user's row, so neither can slip past the other.
4. **The server sends no message.** No SMS, WhatsApp, push or call to a contact in this
   prompt. The user sends each invite from their own phone, in their own SMS app, and sees the
   text before sending. `invited_at` records that the user said it was sent; it is not a
   delivery receipt.
5. **The opt-out token travels in the URL fragment and is POSTed.** The link is
   `<API address>/c#<token>`. `GET /c` returns one static page, the same for everyone. Its
   script reads the fragment, removes it from the address bar and, only when the visitor taps
   "Opt out", sends `POST /v1/public/contacts/opt-out` with `{ token }` in the body.
   - A browser does not send a fragment to a server: it is not part of the request target
     (RFC 9110 §7.1, RFC 3986 §3.5). So the token is in no request URL, and the platform's
     request log shows `/c` and `/v1/public/contacts/opt-out` and nothing else.
   - Opening the link changes nothing. A link preview or a scanner that fetches the URL gets
     the static page; it cannot opt anybody out.
   - The page has a strict Content-Security-Policy: `default-src 'none'`, its one script and
     one style allowed by SHA-256 hash (computed when the server starts), `connect-src 'self'`,
     no framing, no forms. It loads nothing from anywhere, sets no cookie, has no analytics,
     and is sent with `Cache-Control: no-store` and `Referrer-Policy: no-referrer`.
6. **Tokens are 128 random bits; only their SHA-256 is stored.** The plaintext is returned
   once by `POST /v1/contacts/{id}/invite` and lives afterwards only in the SMS. The lookup is
   an equality on the primary key (the hash), so the application compares no secret. A
   malformed, unknown and no-longer-active token all get the same 404.
7. **Several valid links per contact.** Each invite mints a new token and older ones keep
   working, because an SMS already sent must not stop working when the user taps "send invite
   again". A contact can have at most 10; they do not expire while the contact exists. They are
   deleted with the contact.
8. **Opting out is final for that user, through a tombstone.** An opted-out contact is never
   alerted and cannot be invited again. When the user removes an opted-out contact, the row
   stays with `deleted_at` set, the name replaced and the tokens deleted: it keeps the number
   only, so that this user cannot add the number again and undo the opt-out. Every other
   delete is a hard delete. A tombstone is erased with the account and on withdrawal of
   `sos_alerts`. *How long a number may be kept for this purpose, and on what legal basis, is
   to be verified by a lawyer.*
9. **Whether a number belongs to a registered user is never exposed.** `has_app_user_id`
   stays NULL and appears in no response. Matching contacts to users, and its privacy design,
   is decided in P015.
10. **Limits.** 5 contacts per user, enforced under the row lock. Per user: 5 creates at once
    then one per minute, 20 creates a day, 30 invite links a day. One shared bucket for the
    public opt-out call (60, refilled one per second), with no per-caller data: no IP address
    is read, stored or logged. The numbers live in `CONTACT_LIMITS`.
11. **No `Idempotency-Key`.** `UNIQUE (user_id, phone_e164)` makes a retried create safe: it
    gets 409 `contact_exists` and the app reads the list again.
12. **Logs and audit.** Names, phone numbers and tokens never appear in a log line, an error
    body or `audit_log.metadata`. Each mutation writes one info line with `action` (plus the
    request id and, when signed in, the internal user id) and one audit row with the contact id.

## Deviation from Plan v7 §6.3

The opt-out is `GET /c` plus `POST /v1/public/contacts/opt-out`, not
`/v1/contacts/{id}/opt-out`. The plan's path would have carried the contact id, and any secret
added to it, in a URL that the platform logs. The addenda are not edited for this; this ADR is
the record.

## What is certain, and what is not

- **Certain:** a fragment is not sent in an HTTP request; the API's own access log records the
  route pattern (`/c`), never the URL; the CSP header and its hashes are checked by tests
  against the bytes the route serves.
- **Certain from the platform's documentation, not observed by us:** Cloud Run's request log
  records the request URL as the server received it, so it cannot contain a fragment.
- **Not verified:** the page in real browsers (old Android WebView versions, in-app browsers of
  SMS apps), and that no browser reports a CSP violation. Tests here run without a browser.
  Rahul checks it on a phone after the deploy.
- **Outside our control:** an SMS app or a messaging service that uploads whole message texts
  (for link previews or spam scanning) sees the full link, fragment included. The token only
  allows opting out, which limits the harm to an unwanted opt-out.

## Alternatives considered

- **Token in the path or the query (`/c/<token>`, `/c?t=`).** Simplest, works without
  JavaScript. Rejected: the token would be in every request log ([ADR 0019](0019-privacy-in-urls.md)).
- **Opt out on GET.** One tap fewer. Rejected: link previews and scanners would opt people
  out, and a GET must not change state.
- **Server-sent invite SMS.** Reliable delivery and a known sender. Rejected for now: it costs
  money, needs sender registration and a lawyer's view on messaging people who did not ask,
  and makes the server a channel for harassment.
- **Expiring tokens.** Smaller window for a leaked link. Rejected: the contact may read the
  SMS months later, and the only thing the token can do is opt them out.
- **Hard-delete an opted-out contact.** Less data kept. Rejected: the user could add the
  number again at once, which makes the opt-out meaningless. A hash of the number instead of
  the number was considered; phone numbers are too few for a hash to protect them.
- **A global block list (an opted-out number can be added by nobody).** Stronger for the
  contact. Rejected: one person could then stop everybody from listing a number, including a
  family member's; and it needs an identity check that does not exist.

## Consequences

- The opt-out page needs JavaScript. Without it the page says so and nothing happens.
- **The link's host must stay stable.** Links live in people's SMS for years. Staging uses the
  platform's address; a custom domain is needed before a public beta, and the old address must
  keep serving `/c` afterwards.
- Anyone who knows a user's number cannot learn whether they are somebody's contact, and the
  API never says whether a number has an account.
- Abuse is possible: a user can add a person who does not want it and send them texts from
  their own phone. The limits bound it; the opt-out ends it for that user. Monitoring for it
  is a follow-up.
- The tombstone keeps a third person's number after the user removed them. That is the price
  of a lasting opt-out, and the part most in need of a lawyer's review.
- P014 must read `optedOutAt` and never alert such a contact (failure matrix).
- P020 (export and erasure) must cover both tables; the cascades exist.

## Addendum (P013a): Cloud Run request logs

The request log entries for this feature contain the paths `/c`, `/v1/contacts`,
`/v1/contacts/<uuid>`, `/v1/contacts/<uuid>/invite`, `/v1/contacts/<uuid>/invite/confirm` and
`/v1/public/contacts/opt-out`, with no query string. The UUID is a server-made id of a contact
row and means nothing outside the database. Names, phone numbers and tokens travel in request
and response bodies only. This statement is about the platform's request log and the API's own
log lines; it was derived from the contract and the tests, not read from the staging logs.

## References

- Plan v7 §6.3, §7.5, §11, §12.1; `backend/src/modules/contacts/`;
  `backend/src/db/schema/contacts.ts`; migration `0004_emergency_contacts`.
- Diagram: [`docs/diagrams/013a-contacts-and-optout.svg`](../diagrams/013a-contacts-and-optout.svg).
- Prompt log: [`docs/prompt-logs/013a-contacts-backend.md`](../prompt-logs/013a-contacts-backend.md).
