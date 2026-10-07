# ADR 0018: Place search and geocoding

- **Status:** Accepted (provider chosen on 2026-10-07: Geoapify; see the note at the end)
- **Date:** 2026-10-07
- **Prompt:** P011a
- **Plan refs:** Plan v7 §3.2 (F-03), §4, §6.2, §6.3, §12.2, §14.3; [addendum v7.2](../plan/addendum-v7.2.md) E; [addendum v7.3](../plan/addendum-v7.3.md) I; [ADR 0004](0004-api-contract-and-conventions.md), [ADR 0006](0006-authentication-and-roles.md), [ADR 0007](0007-gcp-staging-topology.md), [ADR 0013](0013-regions-and-expansion.md), [ADR 0015](0015-map-stack-and-location-policy.md), [ADR 0016](0016-statewide-coverage-layers.md)

## Context

- Users must be able to search for places anywhere in West Bengal, in English, in Bengali
  script and in Bengali typed with Latin letters (Plan v7 §3.2 F-03, addendum v7.2 E).
- Plan v7 asks for a backend geocoding proxy: the provider key never ships in the app, and each
  route has a Postgres-backed rate limit (§6.2).
- What a person searches for, and where their map is, says where they are or mean to go. It is
  personal data in practice (Plan v7 §12.2).
- No geocoder has been tried against real queries yet. Nothing below about quality is measured.
- Claude Code holds no provider key. Everything about the live services comes from their
  published terms and documentation, read on 2026-10-07. Terms are summarised here in our own
  words with section references; they are not legal advice and are **to be verified by a
  lawyer** before any commercial use.

## Provider findings

### MapTiler (the map-tile provider): not usable for a server-side proxy

MapTiler Cloud Special Terms, as served on 2026-10-07 (the page carries no date or version):

- **Section 6, "Usage Requiring Custom Agreement", item "Proxy":** end users are expected to
  send their requests straight to MapTiler's own infrastructure. Using the service through a
  customer's proxy server is possible only on request, with MapTiler's approval and extra fees.
  Our design, in which the API forwards each search with a server key, is such a proxy.
- **Section 1, "Free Plan":** the free plan is for non-commercial use and for research and
  development of commercial products; going over its limits can suspend the account. The
  pricing page adds that a free account is paused until the next month at the limit.
- **Section 5 and 7:** results may be cached on the end user's own device; storing or
  redistributing map content from a server-side cache is prohibited.
- **Section 7, "Irresponsible Use Of Service":** the service must not be used to run a product
  or service whose failure or use could lead to death, personal injury, or damage to property
  or the environment. This concerns the map tiles already in use (ADR 0015) as much as search.
  No conclusion is drawn here; it is a question for MapTiler and for the lawyer (follow-up).
- The Geocoding API takes the key as a query parameter and returns an attribution field.

Decision: **no MapTiler adapter.** The earlier idea of one MapTiler key shared by tiles and
search is dropped with it; see "Shared key" below.

### The public OpenStreetMap Nominatim service: not usable

Its usage policy caps use at one request per second, says a client must not build
autocomplete on it, and allows end-user-triggered use only for a moderate number of users.

### Candidates

| | Geoapify | LocationIQ | Stadia Maps |
| --- | --- | --- | --- |
| Terms read | Terms and Conditions, version 5 of 2 February 2024; pricing page and its FAQ | Terms of Use, updated 31 March 2026; pricing page and its FAQ | Terms of Service, effective 18 March 2026 |
| Commercial use on the free plan | Allowed, in production too, within the quota and with attribution (pricing FAQ). The terms ("Plans and usage limits") say "with some limitations" in production and to contact them | "Limited commercial use", on condition of a prominent link back (pricing page) | **Not allowed** (section 13: free tier for non-commercial or evaluation use only) |
| Server-side proxy with one key | Not restricted by the terms | Not restricted. "Developer API Key": the key must be kept secret and not passed to anyone else, which a server-side key satisfies | **Prohibited** (section 8: no proxying or caching of the services) |
| Caching and storage | Not addressed in the terms for geocoding | "Acceptable Use": a free account may cache request and response pairs for up to 48 hours; response data may be stored | Client-side cache only; server-side caching prohibited (section 8) |
| Attribution | "Attribution": OpenStreetMap credit always; Geoapify credit mandatory on the free plan, as a link near the results | A prominent link "Search by LocationIQ.com" on the free plan; OpenStreetMap data | Required |
| Safety-critical or high-risk clause | None found. General "as is" disclaimers and a liability cap only | "Location Data": the data is not meant to be relied on where precise location is needed or where wrong, late or missing data may lead to death, injury or damage. Worded as a reliance disclaimer, not as a ban on a kind of product | Only in an addendum for traffic data |
| Free-plan limits | 3,000 credits a day, up to 5 requests a second; limits are "soft" (they write before blocking) | 5,000 requests a day, 2 a second, **60 a minute**, one access token | 200,000 credits a month |
| Autocomplete-style requests | Allowed: a dedicated Address Autocomplete API. One request is one credit | Allowed: a dedicated Autocomplete API meant to be called as the user types. One request is one request credit; failed requests are not charged | (not assessed) |
| India and Bengali | OpenStreetMap-based. `lang` accepts `bn`. Coverage and Bengali-script matching: **not recorded**, to be measured | OpenStreetMap-based. Autocomplete result languages are a fixed list without Bengali; `native` asks for local names. Coverage and Bengali-script matching: **not recorded**, to be measured | (not assessed) |
| Other | Splitting requests across accounts to fit a cheaper plan is forbidden | A competitor may not be given access, and the service may not be used to build a competing one. "Nothing found" is an HTTP 404 | Benchmarking against competing services needs written permission (section 8) |
| Adapter | Written | Written | **Not written** |

**Stadia Maps fails the terms check twice** (proxying, and non-commercial free tier). Its
benchmarking clause also rules it out as a quality yardstick. It is not used at all.

**Geoapify and LocationIQ pass.** Both have an adapter. Neither adapter has called the live
service: they are written from the documentation and tested with a fake `fetch`.

## Decision

1. **Search goes through our API.** `GET /v1/search` (operationId `searchPlaces`) needs a signed-in
   user (`requireUser`, ADR 0006). The provider key is a server secret
   (`GEOCODING_API_KEY`, Secret Manager → Cloud Run). The provider sees SafeRoute's server, never
   the user's IP address or token. The key is **separate from the app's map key**.
2. **Adapter boundary.** `GeocoderProvider.search({ query, nearLatitude, nearLongitude, language,
   limit }) → PlaceResult[]`, with `PlaceResult { id, name, label, latitude, longitude, kind }`.
   Only `src/modules/search/providers/` knows a provider. The provider is **chosen by name**
   (`GEOCODING_PROVIDER`: `geoapify` or `locationiq`) for the endpoint and for the evaluation
   harness alike, so a second provider is compared without touching the endpoint. The contract
   names no provider.
3. **The key never leaves the adapter.** Both providers take the key in the URL. The HTTP
   wrapper therefore turns every failure into an error that carries a kind and nothing else:
   no URL, no response body, no original error. Nothing in that file logs. A test feeds it a
   network error that quotes the URL and checks that the key is gone.
4. **No retries, a timeout, a size bound, strict parsing.** One attempt
   (`SEARCH_PROVIDER_TIMEOUT_MS`, default 3 s): the next keystroke is the retry. Responses over
   256 KB are refused. The body is parsed with Zod; unknown fields are ignored, a wrong shape is
   an error.
5. **Normalisation of `q`:** Unicode NFC; control characters (C0, DEL, C1) rejected; trim;
   whitespace runs collapsed; length 2 to 100 **code points**. Zero-width joiner and non-joiner
   are kept, because Bengali uses them inside words.
6. **Coarsened proximity.** `nearLatitude` and `nearLongitude` are rounded to two decimals (about
   1 km) **on the server**, whatever the client sent, so a precise position never reaches a
   third party. Without them the bias is `LAUNCH_REGION_CENTER` (`src/regions/defaults.ts`),
   which the regions configuration will replace (ADR 0013, ADR 0017).
7. **A bias, never a hard area filter.** Results are limited to India by country code and
   merely biased towards the position: a user in one district must be able to find a place in
   another. This matches the statewide navigation layer of ADR 0016.
8. **Rate limits** (token buckets in `rate_limit_buckets`, one atomic SQL statement per take,
   database time):

   | Bucket | Capacity | Refill | On denial |
   | --- | --- | --- | --- |
   | Per user, burst | 30 | 1 per second | 429 `rate_limited` + `Retry-After` |
   | Per user, daily | 1000 | 1000 per 24 h | 429 `rate_limited` + `Retry-After` |
   | All users, provider budget | `SEARCH_GLOBAL_DAILY_LIMIT` (default 2500) | that many per 24 h | 503 `search_unavailable` + `Retry-After` |

   The order is user burst, user daily, shared budget, so a user over their own limit does not
   spend the shared one. The default 2500 sits under the smaller free daily quota of the two
   candidates (3,000) and leaves room for a harness run. **It must be revisited when the
   provider or the plan changes.** The shared bucket does not smooth requests per second or per
   minute; a provider's own 429 is answered as 503.
9. **Errors.** A key the provider refuses (401 or 403 from it) is our fault: it is logged as an
   error with `alert: geocoder_key_rejected` and answered as 503 `search_unavailable`, never as
   401 or 403, which the app would read as "sign in again". Provider 429, timeout, network
   failure, a malformed or oversized body: 503 `search_unavailable`. No key configured (dev and
   test only): 503 `search_not_configured`. In production the API refuses to start without the
   key and the provider name, so a deploy without the secret fails at the candidate stage.
10. **Nothing about a search is logged or stored.** Not the query, the coordinates, the results
    or the key. One log line per provider call: outcome, latency, result count (plus the request
    and user ids the request logger adds). The access log records the route pattern, not the
    URL. A test captures every log line of a Bengali search and looks for all of them.
11. **No server cache.** One provider's terms are silent on it and the benefit is small.
12. **No recent searches and no saved places.** A search history reveals where a person goes.
    It needs its own consent purpose and a retention rule (ADR 0010) before it is built.
13. **Attribution.** The response carries `attribution` (text). The app shows it next to the
    results. Both free plans ask for a *link*; P011b must render one (follow-up).
14. **Contract.** `info.version` 0.4.0, additive. Plan v7 §6.3 wrote the bias as one `near`
    parameter; the contract uses `nearLatitude` and `nearLongitude`, following the explicit
    latitude and longitude rule of ADR 0004.

## Evaluation and the provider-swap trigger

- A committed fixture (`backend/test/search-eval/fixture.json`) holds real-world queries for
  public places: 74 to start with, in 23 districts, in three scripts. The target is at least 100
  in at least 10 districts (addendum v7.2 E). The starter entries were written from general
  knowledge and are reviewed by Rahul; none has coordinates.
- `pnpm search:eval` runs them through the real adapter and prints the hit rate overall, by
  district and by script. It is a measurement, not a gate, and it never runs in CI.
- **Proposed thresholds, to be confirmed by Rahul in the review of this pull request:** overall
  top-3 name match at least 80%; no district under 60%; Bengali-script queries at least 70%.
  The confirmed values and the measured table are recorded in a note at the end of this ADR.
- **Trigger:** if the chosen provider is under a threshold, the other adapter is measured, and
  if both fail, further providers are assessed (terms first) before launch.
- A name match is a weak test: it does not prove the result is the right place.

## Shared key

An amendment to this prompt planned to use one MapTiler key for tiles and for search, because
the free MapTiler plan gives one key per account. **That plan is void**: MapTiler is not used
for search. What remains true and is recorded here:

- **The map key is extractable.** It ships in the APK (ADR 0015). Anyone can use it directly
  against MapTiler; our rate limits protect only our own proxy, not that key. Abuse can exhaust
  the monthly quota and pause the map.
- **Search does not share that fate.** The geocoding key is a different key, at a different
  provider, held on the server. Losing the map does not stop search, and the reverse.
- **Mitigations for the map key** stay as in ADR 0015: restriction in the dashboard, a usage
  alert, rotation.
- **Triggers to revisit:** before the closed test (check both providers' quotas against expected
  use); before any open beta or commercial use (a paid plan, or self-hosted tiles, and the
  provider's terms for commercial and safety-related use confirmed in writing).

## Alternatives considered

- **MapTiler geocoding through our proxy.** One provider for map and search. Rejected: its
  terms need a custom agreement for a proxy.
- **The app calls a geocoder directly.** No backend work. Rejected: the key would ship in the
  app, there would be no server-side rate limit, and the provider would see each user's IP
  address.
- **Self-hosted geocoder (Photon, Pelias, Nominatim).** No provider terms beyond the
  OpenStreetMap licence. Not now: hosting cost and operations, and a non-goal of this prompt. It
  stays the fallback if no hosted provider passes.
- **A server-side cache of identical requests.** Rejected for now (decision 11).

## Consequences

- Easier: the provider can be swapped by configuration plus one file. The app never holds a
  geocoding key.
- Harder: every search costs a database round trip for three buckets before the provider call.
- **The adapters are unverified against the live services** until Rahul runs the harness.
  Field names, the 404-for-nothing rule and the language parameters come from documentation.
- LocationIQ's free plan allows 60 requests a minute for all users together; a handful of
  people typing at once would hit it. Geoapify's 5 a second is roomier.
- The shared bucket is one hot row updated by every search (Stage 1 follow-up). Buckets of
  deleted users go with the user; other stale buckets are not purged yet (P020).
- Search latency and the provider error rate must join the capacity dashboard; a "search p95"
  row is proposed for the SLO table of Plan v7 §14.3 in v8.
- Deploy: the staging secret `saferoute-staging-geocoding-key` must exist before this is
  merged, and its key must belong to the provider named by `GEOCODING_PROVIDER` in
  `deploy-staging.yml` ([runbook, step 5b](../runbooks/gcp-staging-setup.md)). The name is a
  constant in the workflow, so a provider change is a reviewed commit.
- Revisit when the evaluation is in, when a provider's terms or plan change, and before any
  commercial use.

## Android behaviour

Added in P011b.

- **Debounce.** The app waits 300 ms after the last keystroke before it searches, and needs at
  least 2 code points. The keyboard's Search action searches at once.
- **Only the newest answer counts.** Starting a new search cancels the one in flight, so a slow
  answer to an older query can never replace a newer one. Leaving the screen cancels it too.
  This is done with `collectLatest`, which is a stable coroutine API; `debounce` and
  `flatMapLatest` would need an opt-in to experimental APIs, which ADR 0008 does not allow in
  production code.
- **Coarse area.** `near` is the centre of the map, rounded to two decimals on the phone as
  well as on the server. No location permission is needed or asked for; the user's own position
  is never sent for a search.
- **Language** follows the app's language (`en` or `bn`); the limit is 6.
- **No history.** The typed text lives in the screen's saved state (it survives a rotation) and
  nowhere else: not in a file, a log or analytics. There are no recent searches or saved places.
- **Selection.** A tapped result is held in memory for the Home screen, which moves the camera,
  draws a pin through an overlay description (the map library stays inside `core/map`) and
  shows a place card. Closing the card, or the back gesture, clears it.
- **Attribution** from the API is shown under the results as text. Geoapify's free plan asks
  for a link to its site; a link needs a field in the contract and is a follow-up.
- **Errors** are shown calmly and never touch the session: no connection, "please wait a
  moment" for 429, "temporarily unavailable" for 503. A 401 or 403 is left to the session
  machine, as for every other call.

## Logging: correction of 2026-10-07 (P011d)

**Decisions 1, 10 and 14 above were wrong in one respect, and this section corrects them.**

- Decision 10 says nothing about a search is logged. That was true of the API's own log lines
  and **false for the platform**: Cloud Run's request log records the full URL of every
  request, query string included. With `GET /v1/search?q=…&nearLatitude=…&nearLongitude=…`
  the search text and the coarse area were written to Cloud Logging and kept for the log
  bucket's retention.
- The test named in decision 10 captured our log lines only. It could not see the platform's
  log, and passed.
- Only Rahul's own test queries were recorded. There are no real users.
- **Correction:** search is now `POST /v1/search` with the same fields in a JSON body; the GET
  is removed; the contract is 0.5.0. Decision 14's `nearLatitude` and `nearLongitude` are body
  fields, not query parameters. Everything else in this ADR stands.
- The general rule, its contract test and the log exclusion are in
  [ADR 0019](0019-privacy-in-urls.md). Entries already written expire with retention.
- What is now true: the API's log lines hold no query, coordinate, result or key (tested); the
  platform's request log holds the URL `/v1/search` without a query; the request body is not
  logged by either.

## Note of 2026-10-07 (P011b): evaluation, provider and thresholds

Rahul ran `pnpm search:eval` with the 74-query starter fixture and reported these aggregate
top-3 name-match rates:

| Provider | Overall | Bengali script | English | Transliteration |
| --- | --- | --- | --- | --- |
| Geoapify | 81% | 78% | 84% | 75% |
| LocationIQ | 73% | 22% | 91% | 83% |

Per-district rates and top-1 rates were not reported and are **not recorded**.

- **Provider: Geoapify is the default**, in the API's configuration (`GEOCODING_PROVIDER`
  defaults to `geoapify`) and in the deploy workflow. **LocationIQ stays as a spare adapter**,
  selectable by name. It is better for English and transliterated queries in this run, and far
  worse for Bengali script, which decides it for a Bengali and English app.
- **Thresholds confirmed:** overall top-3 at least 80%; Bengali-script top-3 at least 70%; top-3
  at least 60% **for each district that has at least 3 queries**, until the fixture grows. A
  district with one or two queries is listed but not judged: one miss would decide it.
- Geoapify meets the overall and the Bengali threshold in this run (81% and 78%).
- **How much this proves: little.** The starter set is small (74 queries), was written by
  Claude Code from memory and has not been checked against a map; a name match does not prove
  the right place. The entries for Jalpaiguri and Malda are being reviewed, and failures in
  Kolkata are to be looked at. The evaluation is repeated when the fixture reaches 100 reviewed
  queries with at least 3 per district, and before each region is published.

Geoapify's terms were read again in full for this decision (Terms and Conditions, version 5 of
2 February 2024, and the pricing and Geocoding API pages), summarised in our own words:

- **Attribution** (terms, "Attribution"): OpenStreetMap must always be credited; Geoapify's own
  credit is mandatory on the free plan; single APIs may add requirements in their documentation.
  The pricing FAQ asks for a link such as "Powered by Geoapify" near the map or the information
  shown. Our adapter returns "Powered by Geoapify · © OpenStreetMap contributors" in the
  response's `attribution` field, and the Android search screen shows it under the results. It
  is text, not yet a link (follow-up).
- **Caching and storage:** the terms do not mention it. The Geocoding API page says results may
  be stored without restriction, provided the data-source attribution is kept with the stored
  data or shown when it is reused. We store and cache nothing anyway.
- **Safety-critical or high-risk use:** no such clause. The terms have general "as is"
  disclaimers ("No Warranties", "Disclaimer") and a liability cap ("Limitation of liability").
- **Rate limits:** the free plan allows up to 5 requests a second and 3,000 credits a day
  (pricing page); one autocomplete request is one credit. The limits are described as soft:
  they write before restricting an account that stays above its plan. "Rules and Conduct"
  forbids unreasonable load and splitting requests across accounts to fit a cheaper plan.
- **Commercial use:** the terms ("Plans and usage limits") allow the free plan in development
  and "with some limitations" in production and ask to be contacted; the pricing FAQ says the
  free plan may be used in production within its limits and with attribution. To be confirmed
  in writing before commercial use (**to be verified by a lawyer**).

## References

- Plan v7 §3.2, §4, §6.2, §6.3, §12.2; addendum v7.2 E.
- Provider terms and documentation as read on 2026-10-07: MapTiler Cloud Special Terms, pricing
  and Geocoding API reference; OpenStreetMap Foundation Nominatim Usage Policy; Geoapify Terms
  and Conditions, pricing and Address Autocomplete API; LocationIQ Terms of Use, pricing,
  Autocomplete API and error reference; Stadia Maps Terms of Service.
- Code: `backend/src/modules/search/`, `backend/src/lib/rate-limit.ts`,
  `backend/test/search-eval/`.
- Diagram: [`011a-search-request-flow.svg`](../diagrams/011a-search-request-flow.svg).
- Prompt log: [`docs/prompt-logs/011a-search-backend.md`](../prompt-logs/011a-search-backend.md).
