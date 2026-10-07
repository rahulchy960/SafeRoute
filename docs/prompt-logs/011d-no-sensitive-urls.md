# P011d: keep user text and locations out of URLs

| Field | Value |
| --- | --- |
| Prompt | P011 · a fix after its two parts (P011a backend, P011b Android, **P011d URL privacy**). There is no P011c in this repository; the prompt's own name was kept |
| Milestone | M4 (depends on P011b, merged as `11579a3`) |
| Branch | `fix/011d-no-sensitive-urls` |
| PR title | `fix(search): move search text out of URLs and add a URL-privacy rule [P011d]` |
| Notion | [P011d row in the Prompt Log](https://app.notion.com/p/3f207370772081a0a8fbf1a3f3cd7cd8) |
| Date | 2026-10-07 |
| Plan refs | Plan v7 §6.2, §6.3, §12.2; ADRs 0004, 0007, 0018 |

> **This fixes a mistake of P011a.** Its log and ADR 0018 said that search queries are never
> logged. That was true of the API's own log lines and false for Cloud Run's request log, which
> records every URL with its query string. The P011a tests could not see that log.
>
> **Not verified here:** the log exclusion was written, not run (Claude Code has no cloud
> access); nothing was run on a phone; the staging deploy is unverified until Rahul reports it.

## 1. Objective

Stop search text and the coarse map area from being written to Cloud Logging through request
URLs: move the search from a GET with query parameters to a POST with a JSON body, make "nothing
sensitive in a URL" a rule with a test, and give Rahul the commands to exclude search URLs from
the platform's request log.

## 2. Context & prerequisites

- P011b merged (PR #27, `11579a3`). No open pull requests. Clean tree, hooks active. The
  `deploy-staging` run for `11579a3` shows `conclusion: success`.
- Evidence, from Rahul: Cloud Run's platform request log (`run.googleapis.com/requests`) stores
  `httpRequest.requestUrl` including the query string. Only his own test searches are in it.
- Checked against the Cloud Run logging documentation: request logs are created automatically
  for services under that log name with resource type `cloud_run_revision`.
- The prompt is named P011d "use the next free letter if P011c exists". No P011c log or pull
  request exists in the repository; the prompt's name and branch were kept as written.

## 3. Workflow executed

1. `/start-prompt`: main synced, PR #27 confirmed merged, Notion P011b set to Merged, branch
   `fix/011d-no-sensitive-urls`, Notion page.
2. Backend: route, schema, tests, contract test, smoke checks (`2a1da56`).
3. Android: regenerated client, repository, tests (`df0d529`).
4. ADR 0019, ADR 0018 correction, `CLAUDE.md`, runbook, READMEs, diagram label (`6a20d52`).
5. Notion: six follow-ups, ADR row.
6. `/ship-prompt`: quality gate, this log, push, pull request with the `breaking-api-change`
   label, Notion.

What was verified rather than assumed, and how:

| Item | How |
| --- | --- |
| `gcloud logging sinks update --add-exclusion` takes `name` and `filter` (both required), `description`, `disabled`; `--remove-exclusions` takes names | local `gcloud` 587.0.0 `--help` |
| `gcloud logging sinks describe SINK_NAME`; `gcloud logging buckets describe BUCKET_ID --location=…`; `gcloud logging read` has `--freshness` and `--limit` | local `--help` |
| The request log's name and resource type | Cloud Run logging documentation |
| Exclusion filters are applied after an entry is received by the Logging API | Cloud Logging routing overview |
| The PowerShell 5.1 quoting delivers the filter with its double quotes | both commands run against a stand-in program that prints its arguments |
| **Not verified** | that `gcloud` accepts this filter inside `--add-exclusion` (not executed); that `requestUrl:"/v1/search"` matches on staging; the `--format` paths; the bucket's retention (the prompt says 30 days by default; the runbook has a command that prints it); the console's menu names |

## 4. Changes

| Area | What |
| --- | --- |
| `backend/src/modules/search` | `POST /v1/search` with `SearchRequest` `{ q, nearLatitude?, nearLongitude?, language, limit }` as JSON. The GET is removed. Coordinates and the limit are JSON numbers. Everything else (normalisation, coarsening, limits, response, errors, `no-store`) is unchanged |
| Contract | `info.version` 0.4.0 → 0.5.0. **Breaking:** oasdiff reports `api-removed-without-deprecation` for `GET /v1/search` |
| `backend/test/contract.test.ts` | "privacy in URLs (ADR 0019)": three tests over the committed contract, with a deny-list and an (empty) allowlist |
| `backend/test/db/search.test.ts` | All calls are POST with a body; new: the GET answers 404, a query string on a POST is ignored and never logged, a body that is not JSON is a 400 without echo |
| Smoke | `container-smoke.mjs` and the deploy-readiness test call `POST /v1/search` |
| Android | `ApiSearchRepository` sends the generated `SearchRequest` body; two new tests |
| Docs | ADR 0019 (new), ADR 0018 "Logging" correction, `CLAUDE.md` "Privacy in URLs", observability runbook section, backend, contracts and Android READMEs, the 011a diagram's first node, this log |

No migration. No new dependency. No workflow change.

**Deviations from the prompt:**

- **A new ADR file (0019), not only a note.** `contracts-ci` lets a breaking change pass only
  with the label **and** a new file under `docs/adr/`. The "Logging" correction asked for is in
  ADR 0018 as well.
- **The deny-list is longer than the prompt's**: it also has `search…`, `lon`, `bbox`,
  `apikey`, `…password…` and `…secret…`.
- **Claude Code added the `breaking-api-change` label** when opening the pull request, because
  the prompt lists it as part of the policy to follow. It is normally Rahul's sign-off; remove
  it if you disagree.

## 5. Diagram

No new diagram. [`011a-search-request-flow.svg`](../diagrams/011a-search-request-flow.svg) was
regenerated: its first node now says POST with the search in the body.

## 6. Quality gate & test results

| Command | Result |
| --- | --- |
| Backend `pnpm typecheck` · `lint` · `format:check` · `build` · `db:check` | clean |
| Backend `pnpm test` | **400 tests, 0 failures** (253 unit, 147 db; 395 before) |
| `pnpm openapi:check` · `pnpm openapi:lint` | up to date · valid |
| oasdiff 1.32.1 `breaking main…HEAD --fail-on ERR` | **1 error, as intended:** `api-removed-without-deprecation`, `GET /v1/search`. Needs the label and the new ADR to pass in CI |
| `node scripts/container-smoke.mjs` | 36 checks passed, including `POST /v1/search` without a token → 401 |
| Android `./gradlew lint testDebugUnitTest assembleDebug` | BUILD SUCCESSFUL; **488 tests, 0 failures** (486 before); lint 0 errors, 2 known warnings |
| `pnpm generate` · `pnpm check` in `tools/diagrams` | 16 diagrams, up to date |
| markdownlint-cli2 · JSON validity · gitleaks 8.30.1 | 81 files, 0 errors · 49 files valid · no leaks |

What the new and changed tests prove:

| Test | What it proves |
| --- | --- |
| Contract: "the committed contract puts no user text, position or credential in a path or query" | reads `contracts/openapi.json`; no path or query parameter matches the deny-list |
| Contract: "the check catches what it is meant to catch" | the old search parameters, `bbox`, a `{token}` path variable, `phoneNumber` and `apiKey` are flagged; headers, a consent `purpose`, `limit`, `cursor` and `language` are not |
| Contract: allowlist | every entry must name an ADR and still match a real parameter (empty today) |
| Contract: search | `/v1/search` has only `post`, no parameters, a required JSON body `SearchRequest`, number coordinates; version 0.5.0 |
| Endpoint | all 44 endpoint tests pass over POST; `GET /v1/search?…` → 404; `POST /v1/search?q=…` with an empty body → 400 on `body.q`, the provider is not called, and neither the query-string text nor a `?` is in the API's log lines |
| Android | the body on the wire is `{"q":…,"nearLatitude":10.12,"nearLongitude":20.99,"language":"bn"}`: numbers, two decimals, nothing finer; the generated call is `@POST("v1/search")` with one `@Body` parameter and no `@Query` |

**What these tests cannot see:** the platform's request log. They prove that our API and our
app put nothing in the URL; that Cloud Logging then holds nothing is checked by Rahul's
verification query on staging.

**SOS failure matrix (Plan v7 §7.5):** not applicable.

## 7. Decisions & ADRs

- [ADR 0019](../adr/0019-privacy-in-urls.md), Accepted: no user text, position, phone number
  or credential in a path or query of our API; search is a POST; the GET is removed at once;
  the contract test and its allowlist; the log exclusion; what stays open.
- [ADR 0018](../adr/0018-search-and-geocoding.md): "Logging: correction of 2026-10-07".

Decided while building:

- **The name check is a net, not the rule.** A sensitive value under an innocent name passes
  it. `CLAUDE.md` says never to rename a parameter to get past the test.
- **POST without `Idempotency-Key`:** the call changes nothing and is safe to repeat.
- **The exclusion is still worth having after the fix**, as a second line of defence for an old
  build or a mistyped request, and it costs the search rows of query 6 only.
- **The filter has no comma**, because `gcloud` splits `--add-exclusion` at commas.

Two endpoints the plan lists for later **need a decision before they are built** (recorded as
follow-ups; nothing was built or changed for them here):

- `/v1/safety/cells?bbox=…`: the area the user's map shows, in a URL.
- `/v/{token}` and `/v1/public/shares/{token}/latest`: a capability token for a live location,
  in a path. The contract test would fail on both.

## 8. Security & privacy notes

- **What was exposed:** search text and a map area rounded to about 1 km, in the staging
  project's Cloud Logging, readable by the project's owner. Rahul's own test searches only; no
  real users, no released app. No token, phone number or precise position was in a URL.
- **What changes:** new requests carry nothing in the URL. The body is not logged by the API
  or by the platform.
- **What does not change:** entries already written stay until the log bucket's retention
  ends. An exclusion does not remove them.
- **Never print `httpRequest.requestUrl` for search entries**; the runbook's verification query
  prints timestamps and status codes only.
- Outbound: the API still sends the query to the geocoding provider in the provider's URL.
  We do not log those requests; the provider's handling belongs in the privacy policy.
- Public-repository check: no real query, coordinate, key or project identifier in the diff.
  Test values are invented (`URLSECRET`, `BODYSECRET`, round coordinates).

## 9. Known issues & risks

- **The exclusion command was not executed.** If `gcloud` rejects the filter inside the flag,
  use the console alternative in the runbook.
- **Old log entries remain** until retention ends.
- **Rahul's installed app build stops finding places** after the deploy (it still sends a GET
  and gets 404, shown as "Something went wrong"). It must be rebuilt from this branch or from
  `main` after the merge.
- **The merge deploys a breaking API change** to staging. No other client exists.
- The contract test checks names, not values.
- Production does not exist; its log exclusion is a follow-up.
- The P011b phone checks are still owed, now against the POST endpoint.

## 10. Follow-ups & prerequisites for next prompt

New in Notion (*Follow-ups*, source P011d):

- Run the log exclusion for search URLs on staging and verify it (High).
- Audit every endpoint for sensitive values in URLs.
- Safety cells: `bbox` in the URL needs a decision and an ADR (P019).
- Live-share viewer: capability token in the URL (P016) (High).
- OSRM request URLs carry coordinates (P012a).
- Production: log exclusions must exist before it serves users.

**Next: P012** (`feat/012-osrm-routing`). Not started. Besides the inputs listed in the P011b
log: routing requests carry coordinates, so its endpoint takes them in a POST body and the
routing engine's own request log needs the same care (follow-up above).

## 11. How Rahul can verify

1. Read the pull request, [ADR 0019](../adr/0019-privacy-in-urls.md) and the "Logging"
   correction in ADR 0018. Keep or remove the `breaking-api-change` label: with it, and with
   the new ADR file, `contracts-ci` passes.
2. In `backend/`: `pnpm test` with Docker running (400 tests). In `android/`:
   `./gradlew lint testDebugUnitTest assembleDebug` (488 tests).
3. CI green, make sure the staging Cloud SQL instance is running, squash and merge, and check
   the `deploy-staging` run.
4. Rebuild the app from `main` and search once from the phone: it must still find places.
5. Run the exclusion: [observability runbook](../runbooks/observability-staging.md), "Request
   URLs in the platform log", steps 1 to 3. Tell me whether `gcloud` accepted the command and
   what the retention is.
6. Run the verification query (step 4): **no rows** for requests after the exclusion.

## 12. Learning notes

No new Android concept; the app's behaviour is unchanged. Ideas behind this fix:

- **A URL is not private.** The address of a request, including everything after the `?`, is
  written down by many things on the way: the hosting platform's request log, proxies, crash
  reports, a browser's history. A request **body** is not. So anything personal goes in the
  body or a header.
- **Platform logs and application logs are two different logs.** Our API writes its own lines
  and chooses what goes in them. Cloud Run also writes one entry per request by itself, and we
  cannot edit it. A promise like "never logged" has to cover both.
- **GET and POST.** GET puts its input in the URL and is meant for fetching; POST carries a
  body. Using POST for a search is a common choice exactly when the input must stay out of the
  URL. It still changes nothing on the server.
- **A test only proves what it can see.** The P011a test captured our own log lines and found
  no query, correctly, while the platform was recording it. Saying which logs a test covers is
  part of the result.
- **Log exclusion and retention.** An exclusion tells Cloud Logging not to store entries that
  match a filter from now on. Entries stored earlier stay until the bucket's retention period
  ends.
- **Breaking change.** Removing an endpoint breaks every client that still calls it. The
  project allows it only with a label, an ADR and a version bump, so that it is always a
  decision and never an accident.
