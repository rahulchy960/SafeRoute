# P011a: geocoding adapters, GET /v1/search, rate limits and search-quality evaluation

| Field | Value |
| --- | --- |
| Prompt | P011 · the first of two parts (**P011a backend**, P011b Android) |
| Milestone | M4 (depends on P005, P010; P010d merged as `0a8f8a2`) |
| Branch | `feat/011a-search-backend` |
| PR title | `feat(search): geocoding adapter, GET /v1/search, rate limits and search-quality evaluation [P011a]` |
| Notion | [P011a row in the Prompt Log](https://app.notion.com/p/3f107370772081139b56d6a500d40e14) (umbrella row: [P011](https://app.notion.com/p/3eb0737077208103a853eb74e9d1a84d)) |
| Date | 2026-10-07 |
| Plan refs | Plan v7 §3.2 F-03, §4, §6.2–6.3, §12.2, §14.3; addenda v7.1–v7.3; ADRs 0004, 0006, 0007, 0013, 0015, 0016 |

> **No provider has been called.** Claude Code holds no geocoding key. Both adapters are written
> from the providers' documentation and tested with a fake `fetch`; field names, error codes and
> language parameters are unconfirmed until Rahul runs `pnpm search:eval`. **No search hit rate
> is measured**, and **the staging deploy is unverified** until Rahul reports the Actions run.

## 1. Objective

Let signed-in users search for places anywhere in West Bengal (and India). The API proxies a
third-party geocoder behind an adapter, so the provider key never ships in the app, queries are
rate-limited and never logged, and result quality can be measured per district against a
committed fixture before launch (addendum v7.2 §E).

## 2. Context & prerequisites

- P010d merged (PR #25, `0a8f8a2`). No open pull requests. Hooks active.
- The tree had one uncommitted change, `backend/.env.example`, which Claude Code cannot read
  (deny rule). Rahul confirmed it is his own edit for this prompt. It is **not committed** in
  this pull request (section 9).
- The prompt named MapTiler as the provider, and an amendment planned one MapTiler key for
  tiles and search. The A0 spike found that MapTiler's terms do not allow that design; work
  stopped and Rahul decided to assess Geoapify, Stadia Maps and LocationIQ instead, and to
  email MapTiler.
- The Plan PDF cannot be read on this machine; the plan text quoted in the prompt was used.

## 3. Workflow executed

1. `/start-prompt`: main synced, PR #25 confirmed merged, Notion P010d set to Merged, branch
   `feat/011a-search-backend`, Notion page.
2. **A0 spike, round 1 (MapTiler).** Geocoding API reference, Cloud Special Terms and pricing
   page fetched and read. Finding: a customer proxy needs a custom agreement. **Stopped and
   reported**, with four options.
3. **A0 spike, round 2** after Rahul's decision: the full terms, pricing and autocomplete
   documentation of Geoapify, LocationIQ and Stadia Maps downloaded with `curl`, converted to
   text and read. Results in section 7 and in ADR 0018.
4. Migration and rate limiter (`100a2e8`).
5. Config, adapters, endpoint, contract 0.4.0, tests (`24fb017`).
6. Evaluation fixture and harness (`3edc4dc`).
7. Deploy wiring, container smoke test, runbooks, READMEs (`badcf65`).
8. ADR 0018, diagram, `CLAUDE.md` rules (`6aca4c5`).
9. Notion: 13 follow-ups, ADR row.
10. `/ship-prompt`: quality gate, this log, push, pull request, Notion.

Commands whose behaviour was verified rather than assumed:

- `gcloud run deploy --help` (local, read-only): `--set-secrets` "All existing secrets will be
  removed first"; `--update-secrets` adds to the list; the two cannot be combined. The workflow
  already used `--set-secrets`, so both secrets are now named in that one flag.
- `pnpm db:generate --name rate_limit_buckets` wrote the migration; the header and the
  `COMMENT ON TABLE` were added by hand, as in migration 0002.

## 4. Changes

| Area | What |
| --- | --- |
| Migration `0003_rate_limit_buckets` | `rate_limit_buckets(key, user_id → users ON DELETE CASCADE, tokens, refilled_at, updated_at)`, index on `user_id`, table comment. Expand-only |
| `src/lib/rate-limit.ts` | `take(key, userId, capacity, refillPerSecond)`: one `INSERT … ON CONFLICT DO UPDATE … WHERE` statement, database time, one row per key |
| `src/config.ts` | `GEOCODING_API_KEY` and `GEOCODING_PROVIDER` (both required in production, always together), `SEARCH_GLOBAL_DAILY_LIMIT` (2500), `SEARCH_PROVIDER_TIMEOUT_MS` (3000). Names-only errors |
| `src/modules/search/` | `GeocoderProvider`, `PlaceResult`, `GeocoderError` (kind only); `providers/geoapify.ts`, `providers/locationiq.ts`, `providers/http.ts` (timeout, size bound, no URL in errors), `providers/index.ts` (choose by name); `normalize.ts`; `schema.ts`; `service.ts` (limits, one log line); `routes.ts` |
| `src/regions/defaults.ts` | `LAUNCH_REGION_CENTER`, the default search bias; `LAUNCH_COUNTRY_CODE` |
| Contract | `GET /v1/search` (`searchPlaces`, tag `search`), `Place`, `SearchResults`, `Retry-After` header on 429 and 503, codes `search_unavailable` and `search_not_configured`. `info.version` 0.3.0 → 0.4.0 |
| `test/search-eval/` | `fixture.json` (74 queries, 23 districts), schema, harness, `pnpm search:eval`, README |
| Deploy | `deploy-staging.yml`: the candidate revision mounts `saferoute-staging-geocoding-key` as `GEOCODING_API_KEY` and gets `GEOCODING_PROVIDER` from a constant in the workflow. `container-smoke.mjs`: fake settings, a missing-key guard check, `/v1/search` → 401 |
| Docs | ADR 0018, diagram, runbook step 5b and rollback rows, backend, contracts and modules READMEs, `CLAUDE.md` "Search and geocoding rules", this log |

**API contract diff:** additive. oasdiff reports no breaking change. Plan v7 §6.3 wrote the
bias as `near=`; the contract uses `nearLatitude` and `nearLongitude` (ADR 0004's explicit
latitude and longitude rule). Recorded in ADR 0018.

**Deviations from the prompt:**

- **Provider.** No MapTiler adapter. Adapters for Geoapify and LocationIQ; none for Stadia Maps.
- **`GEOCODING_PROVIDER`** is a new variable (Rahul's instruction to select the provider by
  name). For the deploy it is a constant in `deploy-staging.yml`, set to `geoapify` (the
  recommendation in section 7); Rahul changes that line if the evaluation favours LocationIQ.
  The first push read it from a GitHub environment variable, which failed `infra-ci`:
  `bootstrap-staging.ps1` checks that it can fill in every `vars.` name the workflow uses.
  Teaching the script a new name is a follow-up, so the workflow was changed instead.
- **`SEARCH_GLOBAL_DAILY_LIMIT` defaults to 2500**, not 5000 (prompt) or 1500 (amendment). The
  amendment's reason, a quota shared with map tiles, no longer applies; 2500 sits under the
  smaller free daily quota of the two candidates (3,000).
- **`--set-secrets` with both secrets**, not `--update-secrets` (section 3).
- **No server cache** (optional in the prompt).
- **`backend/.env.example` is not in the pull request** (section 9).
- **Size.** About 3,340 changed lines without generated files and the fixture: source 920, tests
  1,850, documents 490, the rest workflow and scripts. Far over the ~800-line guide. Not split:
  the endpoint is unusable without the limiter and the adapters, the deploy must change in the
  same merge as the new required setting or staging stops deploying, and Rahul needs the
  harness in the same pull request to choose the provider before merging.

## 5. Diagram

[`docs/diagrams/011a-search-request-flow.svg`](../diagrams/011a-search-request-flow.svg)
(14 nodes): app → `requireUser` → validation and normalisation → coarsened `near` → rate limiter
(three buckets in PostgreSQL) → provider adapter → geocoding provider; the 429 and 503 paths back
to the app; the secret; the local evaluation harness; and a red node for what is never logged.

## 6. Quality gate & test results

| Command | Result |
| --- | --- |
| `pnpm typecheck` · `pnpm lint` · `pnpm format:check` | clean |
| `pnpm test` (unit + db, Docker) | **395 tests, 0 failures** (250 unit, 145 db; 250 before) |
| `pnpm build` | clean |
| `pnpm db:check` · `pnpm db:generate` | consistent · no schema changes |
| `pnpm openapi:check` · `pnpm openapi:lint` | up to date · valid |
| oasdiff 1.32.1 `breaking main…HEAD --fail-on ERR` | no breaking changes (one endpoint and one response header added) |
| `node scripts/container-smoke.mjs` | 36 checks passed |
| actionlint 1.7.12 with shellcheck | clean |
| `infra/staging/tests/Invoke-InfraCheck.ps1` (the workflow file is one of its inputs) | Pester 87 passed, 0 failed (Windows PowerShell 5.1, mocked `gcloud` and `gh`) |
| `pnpm generate` · `pnpm check` in `tools/diagrams` | 16 diagrams, up to date |
| markdownlint-cli2 · JSON validity · gitleaks 8.30.1 | 78 files, 0 errors · 49 files valid · no leaks |
| "kolkata" in `contracts/openapi.json` | 0 occurrences |

What the new tests prove:

| Test | What it proves |
| --- | --- |
| `test/db/search.test.ts` (42) | 401 without a token with `WWW-Authenticate: Bearer`; 403 `bootstrap_required` and `account_deleted`; the mapped shape and `Cache-Control: no-store`; Bengali query with a zero-width non-joiner preserved; NFC; `q` at 1, 2, 100 and 101 code points, whitespace only, control characters; latitude without longitude; out-of-range and non-numeric coordinates; language and limit bounds; no echo of submitted values; `near` rounded to two decimals before the adapter sees it; 503 `search_not_configured` |
| Provider failures | every failure kind → 503 `search_unavailable`, never 401 or 403; `Retry-After` passed on; a refused key is logged as an error with an alert field |
| Rate limits | 30 then 429 with `Retry-After: 1`; another user unaffected; parallel requests never exceed the burst; daily bucket; shared budget → 503 for everyone; three rows per user plus one shared; cascade on user deletion; a 400 spends no token |
| Logging | every log line of a Bengali search with fake coordinates is captured, in three outcomes: no query (raw or URL-encoded), coordinate (precise or coarse), result name, place id, key or query string appears; the provider line has exactly outcome, latency and count |
| `test/db/rate-limit.test.ts` (11) | burst, refill, cap, one row per key, 40 parallel takes allow exactly 10, key isolation, daily refill, a clock ahead of the database does not refill early, cascade, index and table comment |
| `test/search/providers.test.ts` (40) | both adapters: mapping, unknown fields ignored, country filter and bias sent, no hard area filter, at most `limit` results; 401, 403, 429, 5xx, timeout, network, not JSON, wrong shape, bad coordinates, oversized; **a network error that quotes the URL leaves no trace of the key**; LocationIQ's 404 means "no results" |
| `test/search/normalize.test.ts` (26) | trimming, code points, controls, NFC, joiners kept, coarsening |
| `test/search-eval/search-eval.test.ts` (14) | fixture rules (unique ids, at least 40 entries and 10 districts, scripts labelled truthfully, nothing that looks like personal data, no coordinates without a source); scoring; the report with a fake provider; refusal without a key; the key and the queries never printed |
| `test/config.test.ts` (+11) | required only in production, key and provider always together, names-only messages |
| Contract and migration tests | paths allowlist, version 0.4.0, no `near`, no provider name in the spec, codes documented; migration applies once and is then a no-op |

**SOS failure matrix (Plan v7 §7.5):** not applicable; no SOS, live-location or contacts code.

**Not run:** `pnpm search:eval` (needs a key; Rahul's step). No request reached any provider.

## 7. Decisions & ADRs

[ADR 0018](../adr/0018-search-and-geocoding.md), Accepted; the choice between the two adapters
is open until the evaluation.

### A0 findings

Terms as read on 2026-10-07, in our own words. Not legal advice; **to be verified by a lawyer**.

**MapTiler** (Cloud Special Terms; the page has no date):

- **Proxy (section 6, "Usage Requiring Custom Agreement"):** end users are expected to send
  requests straight to MapTiler; use through a customer's proxy server is possible only on
  request, with MapTiler's approval and extra fees. Our server-side proxy needs that agreement.
- **Safety (section 7, "Irresponsible Use Of Service"):** the service must not be used to run a
  product or service whose failure or use could lead to death, personal injury, or damage to
  property or the environment. This concerns the tiles already in use (ADR 0015) too.
- **Free plan (section 1):** non-commercial use and R&D for commercial products; over the limit
  the account can be suspended. Pricing page: 100k API requests and 1k search sessions a month,
  a geocoding query is one request, a free account pauses until the next month at the limit.
  How a server-side call counts against search sessions is not stated.
- **Caching (sections 5 and 7):** on the end user's device only; no server-side cache.
- **API:** `GET /geocoding/{query}.json`; the key is a query parameter; 400 and 403 are
  documented, 429 is not; the response carries an attribution field.

**Nominatim (public OSM service):** at most one request a second; a client must not build
autocomplete on it. Not used.

| | Geoapify | LocationIQ | Stadia Maps |
| --- | --- | --- | --- |
| Commercial use, free plan | Allowed in production within quota and with attribution (pricing FAQ); the terms say "with some limitations" and to contact them | "Limited commercial use" with a prominent link back | Not allowed (section 13) |
| Server-side proxy, one key | Not restricted | Not restricted; the key must stay secret | Prohibited (section 8) |
| Caching and storage | Not addressed for geocoding | Free: request and response pairs up to 48 h; response data may be stored | Client-side only |
| Attribution | OpenStreetMap always; Geoapify link on the free plan | "Search by LocationIQ.com" link on the free plan | Required |
| Safety or high-risk clause | None found | A reliance disclaimer for location data ("Location Data"), not a ban on a kind of product | Only in a traffic-data addendum |
| Limits, free plan | 3,000 credits a day, 5 a second, soft | 5,000 a day, 2 a second, 60 a minute, one token | 200,000 credits a month |
| Autocomplete requests | Allowed; one credit each | Allowed; one request credit each | not assessed |
| India and Bengali | OSM-based; `lang=bn` accepted; quality **not recorded** | OSM-based; result languages exclude Bengali, `native` asks for local names; quality **not recorded** | not assessed |
| Adapter effort | Small; written | Small; written. No proximity point, so a preferred box is used; 404 means "nothing found" | Not written |
| Terms check | **Pass** | **Pass** | **Fail** |

Stadia Maps was also not usable as a benchmark: its terms forbid benchmarking against competing
services without written permission.

**Recommendation, before any measurement: start with Geoapify.** Its terms are the plainest for
our use (commercial use allowed on the free plan, no safety-related clause found, autocomplete
has its own API), its per-second limit is roomier than LocationIQ's 60 a minute, and it accepts
Bengali as a result language. This is a reading of terms and limits only. **Search quality in
West Bengal, the reason for the evaluation, is unknown for both**, and the measured table
decides.

### Decisions made while building

- **The adapter errors carry a kind and nothing else**, because both providers put the key in
  the request URL and Node's `fetch` errors can quote it.
- **A refused key is a 503 to the client and an error with an alert field in the log.**
- **Buckets are taken in the order burst, daily, shared**, so a user over their own limit never
  spends the shared budget. A user who passes the first and fails the second loses one burst
  token; accepted.
- **A denied take writes nothing**: the `WHERE` on the `DO UPDATE` returns no row, which is the
  "denied" signal. No extra column was needed.
- **Control characters are rejected, including tab and newline**, before whitespace is collapsed.
- **The harness scores by name match** and leaves provider errors out of the hit rate.
- **Thresholds proposed, not confirmed:** overall top-3 ≥ 80%, no district < 60%, Bengali script
  ≥ 70%. Rahul confirms in the review; the values go into ADR 0018 with the measured table.

## 8. Security & privacy notes

- **The geocoding key is a server secret** and is not in the repository, the workflow, the
  diagram, this log or Notion. Claude Code never had one. Tests use obviously fake values.
- **The key travels in the request URL** at both providers. No code path logs or returns a URL;
  a test checks that a URL-quoting network error leaves no key behind.
- **Search queries and map areas are personal data in practice.** They are not logged, stored
  or cached; `near` is coarsened on the server; the provider sees our server's address only.
  The provider does receive the query text and the coarse area: this must be named in the
  privacy policy, the consent notice and the Data safety form (follow-up, lawyer).
- **No new consent purpose** and no stored search history.
- `rate_limit_buckets` holds no personal data except `user_id`.
- The fixture contains public places only; a test rejects phone-like numbers, e-mail addresses
  and address words. No coordinates.
- Public-repository check: no keys, project ids, service URLs or local paths in the diff.

## 9. Known issues & risks

- **The adapters have never called the live services.** A wrong field name would show as empty
  or malformed results in the first harness run.
- **No hit rate is measured**, and the starter fixture is unreviewed general knowledge.
- **`backend/.env.example` is not updated in this pull request.** Claude Code cannot read the
  file, and Rahul's uncommitted edit was written when the plan was a shared MapTiler key, so
  committing it unseen could publish a wrong note. Lines to use are in section 11.
- **The staging deploy will fail at the candidate stage** unless the secret and its binding
  exist before the merge. Traffic stays on the old revision. A key that does not belong to the
  provider named in the workflow deploys cleanly and then makes every search answer 503.
- **The MapTiler safety clause is an open question for the map itself** (follow-up; not changed
  here).
- **LocationIQ's 60 requests a minute** for all users is not smoothed by our daily budget.
- **Attribution is text**; both free plans ask for a link (P011b).
- **A name match does not prove the right place.**
- The shared bucket is one hot row; stale buckets are not purged.
- Terms summaries are Claude Code's reading, not legal advice.
- Over the size guide (section 4).

## 10. Follow-ups & prerequisites for next prompt

New in Notion (*Follow-ups*, source P011a):

- Run the search evaluation per provider and confirm the thresholds (High).
- Grow the search fixture to at least 100 reviewed queries.
- Provider swap evaluation if search is below the thresholds; re-test before each region is
  published.
- Tile provider: MapTiler safety clause and proxy question (email, then decide) (High).
- MapTiler quota, plan and usage alerts before the closed test.
- Self-hosted tiles spike (format support in MapLibre Android, hosting cost).
- Geocoding provider terms for commercial use, before open beta (lawyer) (High).
- `bootstrap-staging.ps1` should know the geocoding secret and its binding.
- Rate limiter: hot shared bucket, per-minute provider limits, purge of stale buckets.
- Recent searches and saved places need a consent and retention design; privacy policy wording.
- Reverse geocoding, if reports need it (P017).
- Search on the capacity dashboard and a "search p95" SLO row.
- P011b: show the provider attribution as a link.

**Text for Rahul's email to MapTiler** (a summary of two clauses of the MapTiler Cloud Special
Terms as they read on 2026-10-07; check the wording against the live page before sending):

> 1. Section 7, "Prohibited Usage", item "Irresponsible Use Of Service", says the service must
>    not be used in the operation of a product or service where failure or use could result in
>    death, personal injury, or environmental or property damage. We are building a navigation
>    app for personal safety. It shows your map tiles as a base map; its emergency features
>    (SMS to contacts, dialling 112) do not depend on the map. Does this clause allow that use,
>    on the free plan during development and on a paid plan afterwards?
> 2. Section 6, "Usage Requiring Custom Agreement", item "Proxy", says end users must send
>    requests directly to MapTiler unless agreed otherwise in writing, and that use through a
>    customer's proxy server needs your approval and additional fees. We would like our own
>    backend to call the Geocoding API on behalf of signed-in users, so that the key stays on
>    the server and users' IP addresses are not sent to you. Is that possible, on which plan,
>    and at what cost? How would such server-side requests be counted (requests or search
>    sessions)?

**Next: P011b** (`feat/011b-search-ui`), when Rahul says "P011b". Not started. Before it:

- This pull request merged **and deployed to staging**; `/v1/search` without a token returns
  401, not 404.
- The provider chosen from the evaluation, and its attribution wording.

## 11. How Rahul can verify

1. Read the pull request, [ADR 0018](../adr/0018-search-and-geocoding.md) and the A0 findings
   above. Send the email to MapTiler if you agree with the two questions.
2. Review every `claude-known` entry in
   [`backend/test/search-eval/fixture.json`](../../backend/test/search-eval/fixture.json):
   district, spelling, Bengali text. Delete or fix anything doubtful; add your own.
3. `backend/.env.example`: replace your P011a edit with these lines (fake values only), then
   say "env example done" and Claude Code stages the file unseen:

   ```text
   # Geocoding provider for place search (ADR 0018). A server-only SECRET; put the real key in .env.
   # GEOCODING_PROVIDER is "geoapify" or "locationiq" and must match the key.
   GEOCODING_PROVIDER=
   GEOCODING_API_KEY=
   # SEARCH_GLOBAL_DAILY_LIMIT=2500
   # SEARCH_PROVIDER_TIMEOUT_MS=3000
   ```

4. In `backend/`: `pnpm install`, then `pnpm test` with Docker running (395 tests).
5. Create free accounts at Geoapify and LocationIQ. For each, put `GEOCODING_PROVIDER` and
   `GEOCODING_API_KEY` in `backend/.env`, run `pnpm search:eval`, and send **the aggregate
   tables only**. If a run shows only provider errors or zero results, say so: the adapter's
   field names are then wrong and need fixing before the merge.
6. Choose the provider and confirm or change the thresholds.
7. **Before merging:** follow
   [runbook step 5b](../runbooks/gcp-staging-setup.md#step-5b-the-geocoding-key-in-secret-manager-since-p011a):
   create `saferoute-staging-geocoding-key` with the chosen provider's key and let
   `sa-api-runtime` read it. If you chose LocationIQ, say so: `GEOCODING_PROVIDER` in
   `deploy-staging.yml` must be changed in this pull request first. Make sure the staging Cloud
   SQL instance is running.
8. CI green, squash and merge. Check the `deploy-staging` run, then that
   `GET <staging>/v1/search?q=station` without a token answers 401. Then say "P011b".

## 12. Learning notes

No Android code in this part. Backend ideas used here; debounce and cancellation come with
P011b.

- **Why proxy the provider.** If the app called the geocoder itself, the key would sit in the
  APK, where anyone can read it, and the provider would see every user's IP address. Through
  our API the key stays on the server, the provider sees one server, and we can limit use.
- **Rate limit and token bucket.** A bucket holds a number of tokens and is refilled at a steady
  rate; each request takes one, and an empty bucket means "wait". It allows short bursts (typing)
  but caps the average. Ours live in one database table so that every API instance sees the same
  bucket.
- **Atomic.** Two requests arriving together must not both take the last token. Doing the check
  and the update in one SQL statement makes the database decide, one request at a time.
- **`Retry-After`.** A response header that tells the client how many seconds to wait, so the
  app can show "please wait a moment" instead of retrying blindly.
- **Why a key in a URL is delicate.** Error messages and logs often quote the URL of a failed
  request. When the key is part of the URL, every such message would leak it, so the adapter
  throws away the original error and keeps only its kind.
- **Unicode normalisation (NFC).** The same Bengali letter with a vowel sign can be stored as
  one code point or as two. They look identical and compare as different. NFC converts both to
  one form, so a search works however the keyboard produced the text.
- **Code points, not characters or bytes.** JavaScript's `.length` counts UTF-16 units, which
  miscounts some scripts and emoji. Limits on text should count code points.
- **Zero-width joiner and non-joiner.** Invisible characters that tell Bengali letters whether
  to combine. Deleting them, as naive "clean-up" code does, changes the spelling.
- **Coarsening.** Rounding a position to two decimals keeps "roughly which part of town" and
  drops "which building". The geocoder needs only the first.
