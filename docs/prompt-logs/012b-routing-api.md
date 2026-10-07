# P012b: POST /v1/routes with the OSRM adapter, coverage checks and rate limits

| Field | Value |
| --- | --- |
| Prompt | P012 · part b (P012a, P012a2, P012a3 infrastructure; **P012b routing API**; P012c Android directions) |
| Milestone | M4 (depends on P012a3, merged as `edfe418`) |
| Branch | `feat/012b-routing-api` |
| PR title | `feat(routing): POST /v1/routes with OSRM adapter, coverage checks and rate limits [P012b]` |
| Notion | [P012b row in the Prompt Log](https://app.notion.com/p/3f2073707720818f96bed7a096913d41) |
| Date | 2026-10-08 |
| Plan refs | Plan v7 §3.2 F-04, §6.2, §12.2, §13.1, §14.3; addendum v7.2 §D; ADRs 0004, 0006, 0019, 0020 |

> **The timeout is not measured.** Rahul's message had the measured option unfilled, so
> option B applies: `ROUTING_TIMEOUT_MS` defaults to 25 000 and is read from a GitHub
> environment variable. Cold start and latency on staging are still "not recorded".
>
> **Not verified here:** anything against the real OSRM services or the real metadata server.
> The adapter and the ID-token code were tested against local fake HTTP servers. That the
> services are deployed, private and callable by `sa-api-runtime` is Rahul's statement
> (`VERIFY: OK`); Claude Code cannot see it. The staging deploy of this change is unverified
> until Rahul reports the Actions run.
>
> **Before merging:** the two GitHub environment secrets `OSRM_WALKING_URL` and
> `OSRM_DRIVING_URL` must exist, or the deploy fails at the candidate step (safely).

## 1. Objective

Give the app one endpoint for walking and driving routes with up to two alternatives, served
from the private OSRM services, with the covered-area answer, rate limits, strict privacy for
positions, and a deploy that cannot shift traffic to a revision without its routing services.

## 2. Context & prerequisites

- **B0 checked:** P011d (PR #28), P012a (#29), P012a2 (#30) and P012a3 (#31) are merged; no
  open pull requests; ADR 0020 is Accepted on `main` with the extent and hosting decision;
  `infra/osrm/extents/state-extent.json` exists.
- Stated by Rahul, not checked by Claude Code: both OSRM services are deployed, private, and
  `sa-api-runtime` is their invoker.
- Docker was running for the database tests and the container smoke test.

## 3. Workflow executed

1. `/start-prompt`: main synced, PR #31 confirmed merged, Notion P012a3 set to Merged,
   branch, Notion page.
2. Config, routing module, extent, wiring, contract (`c0bed89`).
3. Deploy wiring: workflow, container smoke test, setup script and Pester tests (`359bd07`).
4. READMEs, ADR 0020 note, runbook section 9, diagram, this log; `/ship-prompt`.

## 4. Changes

| Area | What |
| --- | --- |
| `backend/src/config.ts` | `OSRM_WALKING_URL`, `OSRM_DRIVING_URL` (https, no path; a trailing slash is removed; required in production), `ROUTING_AUTH` (`google_id_token` or `none`; `none` refused in production), `ROUTING_TIMEOUT_MS` (25 000), `ROUTING_ALTERNATIVES` (2), `ROUTING_GLOBAL_DAILY_LIMIT` (20 000). Messages name variables only |
| `backend/src/modules/routing/` | `types.ts` (`RoutingProvider`, `RoutingError` with a kind only), `providers/osrm.ts`, `providers/id-token.ts`, `polyline.ts` (bounding box of a polyline6), `schema.ts`, `service.ts`, `routes.ts` |
| `backend/src/regions/` | `routing-extent.json` (a byte-for-byte copy of the P012a outline) and `routing-extent.ts` (inside/outside with a 500 m edge tolerance, distance) |
| Contract | `info.version` 0.5.0 → 0.6.0, **additive**: `POST /v1/routes` (`createRoutes`, tag `routing`), schemas `RouteRequest`, `RoutePoint`, `Route`, `Routes`, shared response 422, six problem codes. oasdiff: no breaking change |
| Deploy | `deploy-staging.yml` passes the two URL secrets, `ROUTING_AUTH=google_id_token` and the optional `ROUTING_TIMEOUT_MS` variable to the candidate revision. `container-smoke.mjs`: fake OSRM values, two new startup guards, `POST /v1/routes` → 401, no URL in the logs |
| `infra/staging/` | `-SetGithubSecrets` fills the two secrets from what Cloud Run reports (standard input, never shown); `ROUTING_TIMEOUT_MS` is optional (a NOTE, never set). 4 new Pester tests, 4 adjusted |
| Docs | Backend, contracts and modules READMEs; ADR 0020 implementation note; runbook section 9 and a pointer in the setup runbook; diagram |

No migration. No new dependency. `tsconfig.json` gets `resolveJsonModule` (the extent is
imported as JSON and copied into `dist/` by the compiler).

**Deviations from the prompt:**

- **One pull request above the size guide.** About 960 lines of source and 1 100 of tests.
  It is not split: the production config guard, the endpoint and the deploy wiring only work
  together (a guard without the wiring breaks the deploy; an adapter without the endpoint is
  dead code), and Rahul asked for part b as one pull request. Say so if you want it split.
- **A sixth problem code, `routing_not_configured`** (503, dev/test only), like
  `search_not_configured`.
- **A point up to 500 m outside the simplified outline counts as inside**, because the
  outline can lie about 220 m inside the real line.
- **`Connection: close`** on every OSRM call (the idle-connection finding of P012a).
- **Route ids are random UUIDs**, new on every response and meaningless afterwards.
- **The setup script sets the secrets on standard input**, as it does for every secret; the
  runbook also has the `gh secret set --body` snippet Rahul asked for.
- **A test that lists variables matching `AUTH` now expects `ROUTING_AUTH`** besides
  `FIREBASE_PROJECT_ID`. It concerns how the API authenticates to OSRM, not who may call the
  API; no variable changes user authentication.
- **A second bounded response reader** in the routing adapter, next to the one in search:
  modules do not import each other's internals. Follow-up to share one.

## 5. Diagram

[`docs/diagrams/012b-routing-request-flow.svg`](../diagrams/012b-routing-request-flow.svg):
app → validation → sign-in → rate limiter → extent check → ID token → OSRM → mapped answer.

## 6. Quality gate & test results

| Command | Result |
| --- | --- |
| Backend `pnpm typecheck` · `lint` · `format:check` · `build` · `db:check` | clean |
| Backend `pnpm test` | **492 tests, 0 failures** (310 unit, 182 db; 400 before) |
| `pnpm openapi:check` · `pnpm openapi:lint` | up to date · valid |
| oasdiff 1.32.1 `breaking main…HEAD --fail-on ERR` | no breaking changes |
| `node scripts/container-smoke.mjs` | 39 checks passed (36 before) |
| `Invoke-InfraCheck.ps1`, Windows PowerShell 5.1 | PSScriptAnalyzer 0 findings; Pester 110 passed, 0 failed (106 before). PowerShell 7 runs in CI |
| actionlint 1.7.12 | clean |
| `pnpm generate` · `pnpm check` in `tools/diagrams` | 18 diagrams, up to date |
| markdownlint-cli2 · JSON validity · gitleaks 8.30.1 | 88 files, 0 errors · 57 files valid · no leaks |

What the new tests prove:

| Tests | What they prove |
| --- | --- |
| Adapter, against a real local HTTP server (`test/routing/osrm.test.ts`) | the exact request (`lon,lat` with six decimals, `steps=false`, `overview=full`, `geometries=polyline6`, `alternatives`), the right service per mode, `Connection: close`; 11 answer shapes mapped to a kind; the timeout fires once; a refused connection and an oversized answer; **always one request, never a retry**; no URL, coordinate or token in any error |
| ID token, against a fake metadata server | audience equals the service URL exactly, **without a trailing slash**; `Metadata-Flavor: Google`; cached and reused; replaced inside the last five minutes; one fetch for parallel requests; a failure is kind `auth` and OSRM is not called |
| URL format | what Cloud Run reports is accepted and a trailing slash removed; http (except localhost), a path, a query and credentials are refused |
| Extent and polyline (`test/routing/extent.test.ts`) | the copy equals the P012a file; public places inside; far away, in-box and polar points outside; the edge tolerance on both sides; the textbook polyline and four malformed inputs |
| Endpoint, with PostgreSQL (`test/db/routes.test.ts`, 35 tests) | 401, 403; both modes; up to three routes with bbox and unique ids; `no-store`; 11 validation cases without echo and without spending a token; 20 m rule; GET is 404; outside area ×3; too long; 8 engine failures → code and `Retry-After: 10`; burst, daily and global limits; only three bucket rows are stored |
| Log capture | no coordinate, geometry, route id or service host in the API's log lines in four outcomes; exactly one `routing call` line with the allowed keys |
| Config | production needs both URLs and https, refuses `none`; nothing invalid is echoed |
| Contract | `/v1/routes` is POST only, no parameters, the six codes named, 503 documents `Retry-After` |
| Deploy readiness | production config builds the app and answers 401 on `/v1/routes` without any network call |

**What these tests cannot see:** Cloud Run. The log test captures the API's own lines; the
platform's request log is safe for our endpoint because positions are in the body, and for
the OSRM services only through the exclusions Rahul set up (runbook section 7).

**SOS failure matrix (Plan v7 §7.5):** not applicable.

## 7. Decisions & ADRs

No new ADR. [ADR 0020](../adr/0020-routing-osrm.md) has an "Implementation note of 2026-10-08
(P012b)": authentication without a library, one attempt and `Retry-After: 10`, the 25 s
placeholder, the edge tolerance, the distance limits, the error mapping.

**Where 25 000 ms comes from:** not from a measurement. The API's Cloud Run request timeout
is 60 s; the answer must leave well before it, and one attempt may include a cold start.

## 8. Security & privacy notes

- Positions reach our API in a body only; the contract test for URLs still passes.
- Nothing about a route is logged, stored or cached: no table, no cache, one log line with
  outcome, latency, count and mode. Error details never repeat a coordinate.
- The OSRM URLs are treated as secrets (they contain the project number): never logged, never
  in an error, masked by GitHub. ID tokens never leave the adapter.
- The API can call OSRM only as its own service account; there is no key and no way to turn
  authentication off in production.
- The metadata server address is a constant. It is not configurable from the environment.
- Outbound: the API sends the coordinates to OSRM in a URL path, because that is OSRM's
  interface. Those services are ours, private, and their request logs are excluded.
- For the lawyer (already a follow-up of P012, to be recorded in P012c): route requests send
  a precise start and destination to SafeRoute's servers.
- Public-repository check: fixture places are public landmarks or round invented values;
  hosts end in `.invalid` or `.example`; no project identifier, URL or local path in the diff.

## 9. Known issues & risks

- **The timeout is a guess** until staging is measured.
- **The merge deploys to staging.** Without the two secrets the candidate fails to start and
  traffic stays on the previous revision.
- The first request after a quiet period may get 503; no app handles that before P012c.
- The extent is a copy; a test fails if the two files differ, nothing syncs them.
- `Connection: close` costs a new connection per request. Fine at this volume.
- The global daily limit (20 000) is a cost guard chosen without usage data.
- Walking routes still fail where only trunk roads connect two places (ADR 0020).

## 10. Follow-ups & prerequisites for next prompt

New in Notion (*Follow-ups*, source P012b):

- Tune `ROUTING_TIMEOUT_MS` from the staging measurements (High).
- Move the routing extent into the regions configuration (P017).
- Share one bounded HTTP response reader between search and routing (Low).

Closed: "OSRM closes idle connections after 5 s (P012b adapter)".

**Next: P012c** (`feat/012c-routes-ui`). Preconditions (C0): this pull request merged and
deployed; `POST /v1/routes` without a token → 401 on staging; the client regenerated from
contract 0.6.0 (`createRoutes`).

## 11. How Rahul can verify

1. Set the two secrets (runbook `routing-capacity-staging.md`, section 9): either
   `.\infra\staging\bootstrap-staging.ps1 -SetGithubSecrets` or the snippet there. Then
   `gh secret list --env staging --repo rahulchy960/SafeRoute` shows `OSRM_WALKING_URL` and
   `OSRM_DRIVING_URL`.
2. In `backend/`, with Docker running: `pnpm test` (492 tests) and
   `node scripts/container-smoke.mjs` (39 checks).
3. CI green, staging Cloud SQL running, squash and merge, and check the `deploy-staging` run.
4. After the deploy: `POST /v1/routes` on staging without a token must answer 401.
5. Measure (runbook sections 6 and 7), report cold start and p95, and set
   `ROUTING_TIMEOUT_MS` if 25 s is wrong.

## 12. Learning notes

No Android in this part. The ideas behind it:

- **Service-to-service authentication.** The API does not hold a password for OSRM. It asks
  Google's metadata server, which only code running as the API's account can reach, for a
  short-lived signed token saying "I am that account". Cloud Run checks the token and the
  account's permission before the request reaches OSRM.
- **Audience.** A token is made out to one receiver, like a cheque to one name. If the name
  differs by a single trailing slash, the receiver refuses it. That is why the URL's exact
  form matters.
- **Cold start and "retry later".** A service that scaled to zero needs time to wake. Instead
  of letting the user wait an unknown time, the server answers quickly "not now, try in 10
  seconds" (`503` with `Retry-After`), and the app decides how to show that.
- **One attempt on the server.** Retrying inside the server hides how long things take and
  can multiply load exactly when a service is struggling. The client, which knows what the
  user is doing, retries.
- **Bounding box.** The smallest rectangle around a route. The app needs it to fit the camera
  without decoding the whole line first.
- **Polyline encoding.** A compact text form of a line: each point is stored as the
  difference to the previous one, so a long route is a few kilobytes.
