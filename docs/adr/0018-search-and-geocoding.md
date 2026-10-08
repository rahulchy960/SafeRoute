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
- **Changed on 2026-10-08 (P011e2): the area can be the user's position.** The line above is
  kept as the record of P011b. Since P011e2, in this order:
  1. the user's position, when the location permission is granted at that moment and the
     phone's position is at most 5 minutes old (by the clock, not by how the map labels it);
  2. otherwise the centre of the map, when the map is zoomed in on an area (P012e1);
  3. otherwise no area: the server uses its default bias and no area filter.
  - **Why:** P011e1 made the server put nearby places first, but "nearby" was near the map's
    centre. Someone who searches "bank" means near themselves, wherever the map looks.
  - **What does not change:** the point is rounded to two decimals (about 1 km) on the phone and
    again on the server; search asks for no permission and starts no location updates (it reads
    the position Home already has, or uses none); nothing about a search is stored or logged.
  - **What does change, for privacy:** a search now sends the user's own rounded position to
    SafeRoute's server, which passes that rounded point to the geocoding provider as the area
    of the request. Before, this happened only when the map was centred on the user. The
    location disclosure says so since this prompt (**wording to be verified by a lawyer**).
  - **Distances.** Each row shows the server's `distanceMeters`: "under 1 km" below a
    kilometre (the server measures from the rounded point, so finer figures would be false
    precision), one decimal up to 10 km, whole kilometres beyond. A line above the list and the
    row's spoken text say whether they are measured from the user's location or from the
    centre of the map. The label belongs to the answer: it is what that search was sent with.
  - **Not changed:** ranking on the server. Searches that still go wrong (brand aliases,
    category words) are collected as examples first; no fix is guessed.
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

## Local ranking: note of 2026-10-08 (P011e)

**Evidence.** On a phone against staging, in a small town in Uttar Dinajpur, a generic or chain
name ("SBI Bank") returned a place in Darjeeling and no nearby one; a full address had to be
typed. Many small-town places were not found at all. One report from one phone; no numbers.

**Diagnosis** (read from the code; the provider's own ranking was not observed):

- **What the app sent as `near`:** the centre of the map when it last came to rest, never the
  user's position. The map opens on the default region centre and moves only when the user
  pans it or taps "my location". A user who opened search without doing either sent the default
  centre, or nothing (then the server used the same default, `LAUNCH_REGION_CENTER`).
- **What the adapter did with it:** a proximity **bias** only (`bias=proximity:lon,lat`), plus
  the country filter. Nothing limited results to an area.
- **Re-ranking:** none. The provider's order was returned as it came.
- So a search made far from the default centre was biased towards the wrong place, and even a
  correct bias only nudges the provider's ranking: a generic name can still be won by a
  well-known place elsewhere.

**Decision.**

1. **Two passes, when the request carries an area** (`src/modules/search/local-first.ts`):
   - Pass 1: the provider is asked only for places within `SEARCH_NEARBY_RADIUS_KM` (default
     50) of the coarse `near` point, with the proximity bias as before.
   - If pass 1 returns fewer than `SEARCH_MIN_LOCAL_RESULTS` (default 3; never more than the
     requested limit), pass 2 asks again without the area filter (bias only).
   - Results: the nearby ones first in the provider's order, then the wide ones that are not
     already there, cut to the requested limit. Two results are the same place when they share
     an id, or a name and a position to four decimals.
   - Without an area in the request there is one plain call, as before. The default centre is
     a guess about where the user is: good enough for a bias, wrong for a filter.
2. **Both passes count.** Each provider call takes a token from the user's burst and daily
   buckets and from `SEARCH_GLOBAL_DAILY_LIMIT`, so the shared budget still caps provider
   credits exactly. When a limit refuses the second call, or the provider fails on it, the
   nearby results are returned alone; with no nearby results the search fails as it would have.
3. **Distance.** Each result of a search with an area carries `distanceMeters`: the straight
   line from the **coarse** `near` point (two decimals), rounded to 100 m. It is approximate by
   about 1 km either way, and it never reveals more about the user than the coarse point the
   provider already receives. Contract 0.7.0, additive and optional.
4. **Provider-neutral.** The interface gains one optional field, `withinMeters`. Each adapter
   turns it into its own parameter, and `local-first.ts` checks the distance itself, so a
   provider that can only filter by a box, or ignores the filter, cannot put a far place first.
5. **Logging.** Still one line per search: `outcome`, `latency_ms`, `result_count`, and now
   `local_count`, `provider_calls` (1 or 2) and `wide_pass` (a fixed word). No query,
   coordinate, distance or result. As before, this is about the API's own log lines.

**Provider parameters, checked in the documentation on 2026-10-08:**

| Provider | Page | What it says, in our words |
| --- | --- | --- |
| Geoapify | Address Autocomplete API | `filter` and `bias` are separate parameters; a bias changes ranking without excluding matches; results carry a `distance` to a proximity bias; for the filter syntax it points to the Forward Geocoding page |
| Geoapify | Forward Geocoding API, "Location filters" and "Location bias" | A circle filter is `circle:lon,lat,radiusMeters`; a country filter is `countrycode:` with lower-case codes; several filters, one of each type, are joined with `\|` and all must hold; a proximity bias is `proximity:lon,lat`. An example uses a filter and a bias in one request |
| LocationIQ | Autocomplete API reference | `viewbox` is the preferred area, longitude first; `bounded=1` restricts results to it |

- Geoapify, pass 1: `filter=circle:<lon>,<lat>,<metres>|countrycode:in` and
  `bias=proximity:<lon>,<lat>`.
- LocationIQ, pass 1: the square around the circle as `viewbox`, with `bounded=1`.
- We do not use Geoapify's own `distance` field: computing it ourselves keeps the contract the
  same for every provider.
- **Not verified:** that the live services behave as documented. Claude Code has no key. That
  the autocomplete endpoint accepts the circle filter is taken from the Autocomplete page's
  pointer to the Forward Geocoding page, not from an example on the Autocomplete page itself.

**Cost.** A search with an area costs one provider credit when at least 3 places are found
nearby and two when not. In areas with thin map data most searches will cost two. The shared
budget (2500 a day by default, under the free plan's 3,000 credits) is then used up after fewer
searches; how many is **not recorded** until it is measured on staging. The user's burst of 30
is spent twice as fast in the same case.

**What this does not fix.** A place that is missing from OpenStreetMap is still not found:
ranking can only order what the provider has. The radius of 50 km and the minimum of 3 are
first values, chosen without a measurement.

**Evaluation.** The fixture gains local-intent entries (`near` and `intent`): a generic or
named query asked from a town, a hit only when a top-3 result has the right name within 25 km.
They have their own table and stay out of the earlier tables and thresholds. No threshold is
set for them yet; the first run is the baseline. No local-intent hit rate is recorded here:
none has been measured.

**Android** (the next part, P011e2): the app will send the user's current position, rounded to
two decimals, as `near` when the location permission is granted and the fix is recent, and the
map centre otherwise, and will show the distance. That changes the "Android behaviour" section
above ("never the user's position"), the location disclosure and the `CLAUDE.md` search rule,
in that prompt (**the disclosure wording is to be verified by a lawyer**).

## Category and brand search: note of 2026-10-08 (P011f1)

**Evidence.** On Rahul's phone against staging, in a small town in Uttar Dinajpur: "bank"
returned places whose names merely resemble the word (Banka, Bankura); "sbi" with the town's
name found nothing; "pharmacy" and a pharmacy chain's name returned distant results; many small
shops were not found. One phone, no numbers. Rahul is checking OpenStreetMap coverage
separately. **No cause is assumed here**: this section changes how such words are searched and
gives the evaluation a way to tell a gap in the map from a failure of the search.

**Why the name search could not do it.** A geocoder matches text against names. "bank" is not
the name of any bank, so the best textual matches are places called something like it. The
local-first passes of P011e order what the name search finds; they cannot make it find places
by what they are.

### Spike: the provider's Places API

Read on 2026-10-08. Summaries in our own words; not legal advice, **to be verified by a lawyer**
before commercial use. Nothing was called: Claude Code has no key.

| Page | What it says |
| --- | --- |
| Geoapify API docs, "Places API" | `GET https://api.geoapify.com/v2/places`. `categories` is required (comma-separated; a parent key includes its children). One of `filter` or `bias` is required. Filters: `circle:lon,lat,radiusMeters`, `rect:lon1,lat1,lon2,lat2`, `place:`, `geometry:`. Bias: `proximity:lon,lat` (orders by distance). `limit`, `offset`, `lang`, `conditions`, and `name` ("places matching a given name", no matching rules given). The answer is GeoJSON; each feature has `name`, `formatted`, `address_line1`, `address_line2`, `categories`, `lat`, `lon`, `place_id` and `distance` (metres to the bias point) |
| Same page, "Supported categories" | The keys used in the adapter: `service.financial.bank`, `service.financial.atm`, `healthcare.pharmacy`, `healthcare.hospital`, `healthcare.clinic_or_praxis`, `service.vehicle.fuel`, `catering.restaurant`, `commercial.supermarket`, `commercial.convenience`, `public_transport.bus`, `public_transport.train`, `service.police`, `service.post.office`, `education.school`, `education.college`, `education.university`. No separate key for a bus station was found |
| Geoapify "Pricing details" | A Places request costs 1 credit while the limit is 20 places or fewer; above that, one more credit per 20 places. Geocoding and autocomplete: 1 credit per request |
| Geoapify "Pricing" | Free plan: 3,000 credits a day, up to 5 requests a second, soft limits, commercial use in production allowed within the limits and with attribution |
| Geoapify "Places API" product page, FAQ | OpenStreetMap is the primary data source. Results may be cached, stored and redistributed, with attribution to OpenStreetMap, and to Geoapify on the free plan |
| Geoapify Terms and Conditions, version 5 of 2 February 2024 | Unchanged since ADR 0018 read them. Nothing specific to the Places API; no clause on proxying, on caching or on safety-critical use |
| Geoapify API docs, "Forward Geocoding" and "Address Autocomplete" | A `type` parameter restricts results to one of `country`, `state`, `city`, `postcode`, `street`, `amenity`, `locality`. Nothing says several can be given, and there is no way to exclude a type |

- **Certain (from the documentation):** the endpoint, the circle filter, the category keys as
  listed, one credit per request at a limit of 20 or less, the same attribution as today, and
  that storing results is allowed (we store nothing anyway).
- **Not certain:**
  - that the live service behaves as documented, including every category key. A key it
    refuses makes the request fail; the user then sees "search is temporarily unavailable";
  - the rate limit of the Places API: the product page mentions up to 30 requests a second
    "varying by plan", the pricing page 5 a second for the free plan. We assume 5;
  - how the `name` parameter matches. It is **not used** for that reason;
  - **how often the provider's data is refreshed from OpenStreetMap: not stated on any page
    read; not recorded.** A place added to OpenStreetMap appears in search after an unknown
    delay (follow-up);
  - whether a request that returns nothing is charged. We count it as charged.
- **Verdict:** the terms and the cost do not stand in the way. The Places API is used through
  the existing adapter boundary.

### Decision

1. **A classifier decides what a query asks for** (`src/modules/search/intents.ts`, pure
   functions; the dictionary is `intents.json`, version 1).
   - The text is folded (Unicode NFC, lower case, punctuation to spaces) and cut into words.
     Bengali combining marks and the zero-width joiners are kept.
   - **Whole words only, and no spelling tolerance** beyond the few misspellings listed in the
     dictionary. Fuzzy matching is what turns "bank" into Bankura; with whole words "bankura"
     is simply not in the dictionary and stays a name search.
   - Three outcomes: a **category** (a kind of place), a **brand** (a canonical name with a
     category and aliases, for example "sbi"), or a **name** (everything else).
   - A brand may be followed by a category word ("sbi atm": that brand's cash machines).
   - A **place hint** may follow: after a joining word for a category ("pharmacy near
     exampletown"), or directly after a brand ("sbi exampletown"). A bare word after a category
     is **not** a hint, because streets and neighbourhoods are named that way ("college
     street", "hospital road"): those stay name searches.
   - Words such as "near me" and "nearest" are dropped.
2. **The dictionary is small and reviewable.** Every entry says who added it; all are
   `claude-known` (written by Claude Code from general knowledge) until Rahul reviews them.
   Bengali-script and transliterated entries carry `review: native-speaker`. A phrase belongs
   in it only when people type it and it can mean nothing else. A brand alias must be
   unmistakable alone, which is why "axis" and "apollo" are not aliases.
3. **A category or brand is searched inside a circle** (`intent-search.ts`).
   - Centre: the coarse `near` point of the request (two decimals, as before), or the place
     hint's point, also rounded to two decimals.
   - First radius `SEARCH_CATEGORY_RADIUS_KM` (default 10). With fewer than
     `SEARCH_MIN_LOCAL_RESULTS` (3) places it is searched **once** more at 25 km. **Never
     beyond 25 km**; the configuration refuses a first radius above it.
   - Results are sorted by distance and cut to the limit (at most 10). Each carries
     `distanceMeters` from the centre and `matchType`.
   - A brand search asks for 20 places of the brand's category (the most one credit buys) and
     keeps those whose name carries the brand's name or an alias, as whole words. A name that
     carries two brands' names belongs to the longer one, so "State Bank of India" is not a
     result for "Bank of India".
   - The provider-neutral interface gains an optional `searchCategory`. A provider without it
     (LocationIQ today) is asked for the category's English word, by name, inside the circle
     and among places only.
4. **No far fuzzy fallback. An empty list is a valid answer.**
   - A category or brand search never adds anything from outside its circle, and never falls
     back to a name search over the whole country.
   - **Why:** a result 200 km away for "bank" is not help, it is noise that looks like an
     answer, and a place whose name resembles the word is worse. "Nothing within 25 km" is
     true and lets the app say something honest: the map data may be incomplete here.
   - An empty list means **nothing of that kind is on the map there**. It does not mean there
     is none. The app's wording must say so (P011f2).
5. **Without a centre there is no circle.** When the app sent no point and the text has no
   hint, the word is searched by name, restricted to named places (`type=amenity` at
   Geoapify), so an administrative area is not an answer. The default area stays a bias only,
   as in P011e. Results then carry `matchType: name` and no radius.
6. **A hint the geocoder does not know** means the words were part of a name after all: the
   whole text is then searched by name.
7. **Every provider call is charged.** The user's burst and daily buckets and
   `SEARCH_GLOBAL_DAILY_LIMIT` are taken before each call: the hint, the first circle, the
   wider circle. A search costs one to three provider credits. When a limit refuses the
   widening, or the provider fails on it, the first circle's places are returned alone; with
   none, the search fails as before (429 or 503). **No new storage, no cache.**
8. **Contract 0.8.0, additive** (oasdiff: no breaking change).
   - Request: optional `category` (open string). With it `q` may be left out. An unknown value
     is a 400. No radius is exposed: how far the server looks is the server's decision.
   - Each result: `matchType` (`name`, `category`, `brand`; open string).
   - Response: `searchedRadiusKm`, and `searchedAround` (`near` or `placeHint`). The second one
     was not in the prompt. It is there so that the app never says "within 10 km of your
     location" about a circle drawn around a town the user typed.
   - All new fields are optional in the schema, so a client generated from 0.7.0 keeps working.
9. **Logging.** Still one line per search: `outcome`, `match_type`, `latency_ms`,
   `result_count`, `provider_calls`; for a name search `local_count` and `wide_pass` as before;
   for a circle search `searched_radius_km`, which is a configured number. Never the text, the
   category, the brand, the hint, a coordinate, a distance or a result. As in the correction of
   2026-10-07, this is about the API's own log lines; the platform's request log holds the URL
   `/v1/search` and no body.

### Cost

| Search | Provider credits |
| --- | --- |
| Category or brand, at least 3 places within 10 km | 1 |
| Category or brand, fewer | 2 |
| With a place hint | 1 more |
| Name search | 1 or 2, as before |

In small towns most category searches will cost two. How many searches the shared budget
(2,500 a day) then allows is **not recorded** until it is measured on staging.

### Known limits

- **A brand in a dense area.** Only the 20 nearest places of the category are looked at. Where
  more than 20 banks lie within 10 km, a brand's branch beyond the twentieth is not found, and
  the wider circle asks for the same 20 nearest. To be measured in Kolkata; asking for more
  costs more credits.
- **Unnamed places.** A cash machine or shop without a name on the map is shown with its
  address line as its name, and can never match a brand.
- **A brand followed by other words** ("sbi life insurance") is read as a brand with a place
  hint; an unknown hint falls back to the name search (decision 6), at the cost of one call.
- **Bengali place hints** are not recognised: in Bengali the place comes first and carries a
  case ending. Such a query is a name search, as before.
- **What is not on OpenStreetMap is still not found.** Nothing here changes that.

### Evaluation

- The fixture's `intent` gains `category`, `brand` and `name`; entries with them may say
  `inOsm` (`yes`, `no`, `unknown`: whether someone looked on OpenStreetMap) and `radiusKm`.
  The earlier `generic` and `named` entries and their table are unchanged.
- Such an entry is searched through the same function as the endpoint. When that finds no
  place with a matching name, one plain name search without any area follows.
- A new table, by district: found within the radius; found only far; not found; and, among
  the entries where someone looked, **data gap** (not on the map) and **search failure** (on
  the map, not found).
- `test/search-eval/small-town-template.json` holds 27 placeholder rows in 11 towns of 9
  districts, all `claude-known` and `inOsm: unknown`. It is a template for Rahul to review,
  not a measurement set.
- **What it proves:** for the reviewed rows, whether a place with a matching name came back
  inside the circle; and, where `inOsm` is filled in, how many misses are gaps in the map.
- **What it does not prove:** that the result is the right place (a name match is weak, and
  for a category the expected words are generic); anything about towns that are not in the
  fixture; anything about the rows nobody reviewed. No hit rate is recorded here: none has
  been measured.

### Wording

Public text may say: "Maps and search cover West Bengal; local shops and small businesses are
incomplete because the map data is community-maintained." It may not say or imply that search
finds every bank, pharmacy or shop (addendum v7.3, section I; `CLAUDE.md` "Coverage claims").

## References

- Plan v7 §3.2, §4, §6.2, §6.3, §12.2; addendum v7.2 E.
- Provider documentation as read on 2026-10-08 for P011f1: Geoapify Places API (API docs and
  product page), pricing and pricing details, Terms and Conditions version 5, Forward Geocoding
  and Address Autocomplete pages (the `type` parameter).
- Provider documentation as read on 2026-10-08 (parameters only): Geoapify Address
  Autocomplete API and Forward Geocoding API pages; LocationIQ Autocomplete API reference.
- Provider terms and documentation as read on 2026-10-07: MapTiler Cloud Special Terms, pricing
  and Geocoding API reference; OpenStreetMap Foundation Nominatim Usage Policy; Geoapify Terms
  and Conditions, pricing and Address Autocomplete API; LocationIQ Terms of Use, pricing,
  Autocomplete API and error reference; Stadia Maps Terms of Service.
- Code: `backend/src/modules/search/`, `backend/src/lib/rate-limit.ts`,
  `backend/test/search-eval/`.
- Diagram: [`011a-search-request-flow.svg`](../diagrams/011a-search-request-flow.svg);
  category and brand search:
  [`011f1-category-search-flow.svg`](../diagrams/011f1-category-search-flow.svg).
- Prompt log: [`docs/prompt-logs/011a-search-backend.md`](../prompt-logs/011a-search-backend.md).
