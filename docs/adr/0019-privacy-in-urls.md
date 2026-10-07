# ADR 0019: Privacy in URLs (search becomes POST)

- **Status:** Accepted
- **Date:** 2026-10-07
- **Prompt:** P011d
- **Plan refs:** Plan v7 §6.2, §6.3, §12.2; [ADR 0004](0004-api-contract-and-conventions.md), [ADR 0007](0007-gcp-staging-topology.md), [ADR 0018](0018-search-and-geocoding.md)

## Context

- Cloud Run writes its own request log for every request to a service (log
  `run.googleapis.com/requests`). Each entry has an `httpRequest` object whose `requestUrl` is
  the full URL, **query string included**. The platform writes it; our code cannot change or
  switch off what it contains.
- `GET /v1/search?q=…&nearLatitude=…&nearLongitude=…` (P011a) therefore stored the search text
  and the coarse map area in Cloud Logging, for as long as the log bucket keeps entries.
- Plan v7 §12.2 says logs never contain precise locations. ADR 0018 said search queries are
  never logged. Both were true for the API's own log lines, which record the route pattern and
  never the URL, and both were wrong for the platform's log.
- The tests of P011a captured the API's log lines only. They could not see a log written by
  the platform, so they passed.
- Only Rahul's own test queries exist so far. There are no real users and no released app.
- The same mistake is waiting in endpoints the plan lists for later:
  `/v1/safety/cells?bbox=…` (an area), and the share viewer `/v/{token}` and
  `/v1/public/shares/{token}/latest` (a capability token in the path).

## Decision

1. **Nothing a person typed, no position, no phone number and no credential goes in a path or
   a query string of our API.** Such values travel in a request body or a header. A URL may
   carry opaque server-made identifiers (a UUID), fixed names (a consent purpose), paging and
   display options.
2. **Search is `POST /v1/search`** with the JSON body
   `{ q, nearLatitude?, nearLongitude?, language, limit }`. Validation, normalisation,
   coarsening, rate limits, the response, the errors and `Cache-Control: no-store` are unchanged.
   - POST here means "the input is in the body", not "this changes something". The call
     changes nothing and is safe to repeat, so it takes no `Idempotency-Key` (ADR 0004).
3. **`GET /v1/search` is removed in the same change.** It is a breaking contract change, made
   deliberately and at once: no released client exists, and keeping the GET would keep the
   leak. `info.version` goes from 0.4.0 to 0.5.0, and the pull request carries the
   `breaking-api-change` label.
4. **A contract test enforces rule 1.** It reads `contracts/openapi.json` and fails when a path
   or query parameter name looks like user text, a position or a credential (`q`, `query`,
   `text`, `search…`, `lat`, `lng`, `lon`, `…latitude…`, `…longitude…`, `near…`, `bbox`,
   `…phone…`, `…token…`, `key`, `apikey`, `…password…`, `…secret…`). An exception needs an entry
   in the test's allowlist that names the operation, the reason and the ADR that accepts it.
   The allowlist is empty.
5. **The name check is a net, not the rule.** A sensitive value under an innocent name passes
   the test and still breaks rule 1. Every new endpoint is checked by a person against rule 1.
6. **Platform request logs for search URLs are excluded** from the log bucket by an exclusion
   filter on the `_Default` sink, set by Rahul
   ([observability runbook](../runbooks/observability-staging.md)). After this change those
   URLs carry no query; the exclusion also covers an old client or a mistyped request.
7. **Entries already written are not removed by this change.** They expire with the bucket's
   retention.
8. **Outbound URLs are a separate matter.** The API calls the geocoding provider with the
   query in the provider's URL, because that is the provider's interface. Those requests are
   never logged by us (the adapter's errors carry no URL, ADR 0018). The provider's own logs are
   governed by its terms and belong in the privacy policy.

## Alternatives considered

- **Keep GET and only exclude the URLs from the log.** No contract change. Rejected: the
  protection would be a setting in one project that someone must remember to recreate in
  every environment, and a URL also reaches proxies, crash reports and browser histories that
  no exclusion covers.
- **Keep GET and switch the platform request log off.** Not available: the platform writes it.
  Excluding all request logs would also hide the 5xx responses the platform sees and the API
  never does.
- **Keep GET beside POST for a while.** The usual way to change a contract. Rejected here: no
  released client needs it, and the old form is the leak.
- **Encrypt or hash the query in the URL.** Still user data in a URL, with a key to manage.
  Rejected.

## Consequences

- Search responses could not be cached by URL anyway (`no-store`), so nothing is lost there.
- An app built before this change gets 404 from search. None is released; Rahul's own build
  must be rebuilt.
- The planned endpoints need a decision before they are built, each with its own ADR if it
  wants an exception: the safety cells area (a POST body, or an accepted coarse area), the
  share viewer's token (a flow in which the token is not logged, or an exclusion), and the
  routing engine's request URLs (P012).
- Every existing endpoint is to be audited once against rule 1 (follow-up). Today's contract
  passes the test: its only path parameter is the consent `purpose`, a fixed name.
- The tests of a privacy rule must name which logs they can see. A test of our own log lines
  says nothing about a log the platform writes.
- Revisit when an endpoint asks for an allowlist entry, and when a second environment
  (production) is created: its log exclusion must exist before it serves users.

## References

- Plan v7 §12.2; [ADR 0018](0018-search-and-geocoding.md), "Logging" correction.
- Cloud Run logging documentation (request logs, `run.googleapis.com/requests`); Cloud Logging
  routing overview (exclusion filters apply after an entry is received).
- Test: `backend/test/contract.test.ts`, "privacy in URLs (ADR 0019)".
- Prompt log: [`docs/prompt-logs/011d-no-sensitive-urls.md`](../prompt-logs/011d-no-sensitive-urls.md).
