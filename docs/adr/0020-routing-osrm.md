# ADR 0020: Routing on self-hosted OSRM (West Bengal extent, private services, log privacy)

- **Status:** Accepted
- **Date:** 2026-10-07
- **Prompt:** P012a
- **Plan refs:** Plan v7 §3.2 F-04, §12.2, §13.1, §14.1–14.3; [addendum v7.2](../plan/addendum-v7.2.md) §D; [ADR 0007](0007-gcp-staging-topology.md), [ADR 0013](0013-regions-and-expansion.md), [ADR 0016](0016-statewide-coverage-layers.md), [ADR 0019](0019-privacy-in-urls.md)

> The prompt asked for this decision as "ADR 0019". That number was taken by "Privacy in URLs"
> (P011d), so this is 0020.
>
> **What is measured and what is not.** Every number here was measured on 2026-10-07 on the
> maintainer's PC with Docker (16 CPUs, 7.5 GiB given to Docker), with the container limited
> to 1 CPU. **Nothing has been measured on staging**: no service is deployed yet. Cold start
> on Cloud Run is the open number; the hosting choice is to be confirmed with it
> ([runbook](../runbooks/routing-capacity-staging.md)).

## Context

- Plan v7 §13.1 puts routing on self-hosted OSRM. Addendum v7.2 §D asks for a West Bengal
  extract with its source and licence recorded, measurements for the Kolkata metropolitan
  area and for the whole state, a hosting decision from those measurements, and the largest
  extent that passes if the state does not.
- Targets: p95 at or under 2 s for a route including the later exposure metric (Plan v7
  §14.3); a monthly staging ceiling of US$30 for both profiles together, proposed by the
  prompt and **to be confirmed by Rahul in the review**.
- A route request holds a precise origin and destination. OSRM's HTTP interface puts both in
  the URL **path**, and Cloud Run's request log stores every URL (ADR 0019).

## Decision

1. **Engine.** OSRM 26.10.0 (`osrm-backend`, BSD-2-Clause), the latest stable release on
   2026-10-07, from the project's own image pinned by tag and digest. Algorithm **MLD**.
   The stock `foot` and `car` profiles are used unchanged: no custom Lua.
2. **Data.** OpenStreetMap, from Geofabrik's "Eastern Zone" extract of India
   (`eastern-zone-261006.osm.pbf`, data up to 2026-10-06T20:21:06Z, 247 199 715 bytes).
   Licence: Open Database License 1.0. Credit: **"© OpenStreetMap contributors"**, linked to
   <https://www.openstreetmap.org/copyright>; Geofabrik's page adds "Data processed by
   Geofabrik GmbH and created by OpenStreetMap Contributors". Extract URLs must start with a
   prefix listed in `infra/osrm/config.json`; the download is compared with the published MD5
   (a transfer check, not proof of origin) and its SHA-256 is recorded in the image.
3. **Extent: the whole state.** The road graph is cut with osmium along OpenStreetMap's
   administrative boundary relation 1960177 (West Bengal, `admin_level` 4), assembled from
   the extract itself and used unsimplified, with strategy `smart` so that roads crossing the
   line stay whole. The extent is a parameter (`state`, `metro`, `sample`), not an identifier.
   The metropolitan comparison is a rectangle (88.00, 22.25 to 88.70, 23.05), not an official
   boundary.
4. **One image per profile, graph baked in.** `infra/osrm/Dockerfile` cuts, builds and runs
   `osrm-routed` on `$PORT` as uid 10001. Source, date, checksum, licence and credit are image
   labels and `/graph/metadata.json`. Extracts and graphs are never committed.
5. **Hosting: two private Cloud Run services that scale to zero**, one per profile, 1 vCPU
   and 2 GiB each, request-based billing, `--no-allow-unauthenticated`, running as a
   service account with no roles (`sa-osrm-runtime`). Only the API's account may invoke them
   (`roles/run.invoker`), with a Google ID token. No public access, ever. The workflow and
   the setup-script changes that create this are the next pull request.
6. **Cold start is handled, not hidden.** A scaled-to-zero service must load 0.9 to 1.0 GB
   before it answers. The API's timeout will include a cold start, a failure becomes
   `503 routing_unavailable` with `Retry-After`, and the app says that the routing service is
   starting (P012b, P012c). Setting minimum instances to 1 is the documented switch for a
   pilot or a demo; it is a cost decision (below).
7. **Log privacy.**
   - `osrm-routed` runs at log level `WARNING`. At its default level it writes one line per
     request **with the full path, coordinates included** (seen in the spike: 150 lines for 150
     requests; 0 at `WARNING`). The smoke test fails if a request path or a coordinate appears.
   - The platform's request log for the two OSRM services must be excluded before they serve
     a request from a user; the check is a logging query that returns nothing after a test
     route. Our own API takes coordinates in a POST body only.
   - Origins, destinations and routes are never stored or cached anywhere.
8. **Request limits in the engine.** Two points per route, at most three results; the table,
   trip, matching and nearest services are shrunk to the smallest sizes the server accepts.
9. **Not used:** the public OSRM demo server (its policy allows only "reasonable, non-commercial"
   use at no more than 1 request per second, with no guarantee of uptime, and it would receive
   every user's origin and destination), Google or any other routing provider (cost,
   terms, and the same disclosure), custom profiles.
10. **Rebuild and rollback.** A new graph is a new image tagged
    `osrm-<profile>-<extract date>`; rolling back is deploying the previous tag. The refresh
    cadence is a follow-up; nothing rebuilds automatically.

## Measurements (2026-10-07, local Docker, server limited to 1 CPU)

| | Metro box, walking | Metro box, driving | State, walking | State, driving |
| --- | --- | --- | --- | --- |
| Cut extract | 37 MB | 37 MB | 110 MB | 110 MB |
| Build: extract / partition / customize | 24 / 10 / 6 s | 26 / 12 / 6 s | 89 / 38 / 9 s | 105 / 41 / 14 s |
| Build: peak memory (extract step) | 0.94 GB | 1.27 GB | 3.01 GB | 3.65 GB |
| Whole image build with the script | not recorded | not recorded | 162 s | 180 s |
| Graph files (state: as in the image, with a gzip estimate; metro: with build-only files) | 0.34 GB | 0.42 GB | 0.90 GB (0.32 GB) | 1.05 GB (0.41 GB) |
| Server memory idle / after load | 215 / 237 MB | 293 / 313 MB | 716 / 754 MB | 868 / 907 MB |
| Start to first answer, graph on a mounted folder | 4.3 s | 5.4 s | 8.0 to 10.3 s | 8.9 to 9.4 s |
| Start to first answer, graph in the image | not recorded | not recorded | 1.6 to 3.3 s | 2.2 to 4.8 s |
| 200 random pairs, one result: p50 / p95 | 5.5 / 8.1 ms | 5.0 / 6.9 ms | 6.6 / 13.1 ms | 7.0 / 11.6 ms |
| Same with `alternatives=2`: p50 / p95 | 8.2 / 14.6 ms | 7.2 / 11.0 ms | 9.8 / 24.8 ms | 10.7 / 22.8 ms |
| 8 at once, `alternatives=2`: p95, requests/s | 66 ms, 154 | 53 ms, 213 | 108 ms, 118 | 101 ms, 111 |
| Routes per answer with `alternatives=2` | 2.2 | 2.2 | 2.1 | 1.9 |
| `NoRoute` / `NoSegment` | 0 / 0 | 0 / 0 | 49 of 200 / 0 | 1 of 200 / 0 |
| Largest response | 46 KB | 35 KB | 144 KB | 136 KB |

- Random points are drawn from a seeded generator inside the area and snapped to a road
  (points further than 500 m from one are dropped: 8 to 9% in the metro box, 30% in the
  state). No point comes from a person.
- On the **state** graphs, pairs inside the metro box: walking p95 9.9 ms, driving 10.2 ms,
  no failures (200 pairs each). This is the M4 case ("two Kolkata points"), warm.
- **Walking fails over long distances**: 49 of 200 statewide pairs (mean route 223 km) have
  no route, but 5 of 300 pairs up to 25 km apart, and 0 of 200 inside the metro box. The
  stock walking profile does not use `highway=trunk`, which is how national highways and
  many of their bridges are mapped here, so the walking network falls into pieces.
- Quality check on 16 pairs of public places across 15 districts (stations, monuments, the
  airport; `infra/osrm/sample/route-pairs.json`): all 16 driving and all 5 walking routes are
  plausible in length (1.1 to 2.0 times the straight line) and speed (walking 5.0 km/h,
  driving 33 to 79 km/h). Sealdah to New Jalpaiguri: 572 km, 7 h 48 min.

**Against the targets.** Warm latency is under 2% of the 2 s budget for both extents, which
leaves the budget to the exposure metric (P019). Memory and cold start, not latency, decide
the hosting.

## Cost (prices read on 2026-10-07)

From the official Cloud Run pricing page, which lists `asia-south1` under Tier 1:
request-based billing costs US$0.000024 per vCPU-second and US$0.0000025 per GiB-second
while an instance handles requests, US$0.0000025 per vCPU-second and per GiB-second for an
idle minimum instance, and US$0.40 per million requests. Artifact Registry storage is
US$0.000136986 per GiB-hour above 0.5 GiB (about US$0.10 per GiB-month). A month is taken as
730 hours. Free tiers are not subtracted (they are shared with the API). Tax is not included.

| Option | Per month, both profiles |
| --- | --- |
| **State, scale to zero** (chosen): pay for the seconds that handle requests and for starts | **about US$0 to 2** at test volumes, plus about US$0.10 per stored image pair |
| State, 1 warm instance each, 1 vCPU and 2 GiB | US$19.71 × 2 = **US$39.42** (over the ceiling) |
| State, one profile warm, the other scaling to zero | about US$19.71 |
| State, 1 warm instance each at 1 GiB (walking) and 1.5 GiB (driving) | US$13.14 + US$16.43 = US$29.57; measured use is then about 70% (walking) and 56% (driving) of the limit |
| Metro box only, 1 warm instance each, 1 vCPU and 512 MiB | US$9.86 × 2 = US$19.71 |
| One small VM for both profiles (4 GB, `e2-medium`) | about US$29 plus disk, **from a third-party price list, not checked on Google's page** |

So within US$30 there are two honest choices: **the whole state with cold starts**, or **the
metro box always warm**. We choose the whole state: the launch geography is the state
(ADR 0016), the app already has to handle "starting", and warm instances can be switched on
later without rebuilding anything.

## Alternatives considered

- **Metro box only.** Cheapest warm option and a third of the memory. Rejected for now: it
  would shrink layer 2 of the coverage statement to one area although the state fits the
  budget when scaling to zero. It stays the fallback if cold starts on staging are too slow.
- **A small VM.** Always warm, and it has no platform request log at all. Rejected: a
  machine to patch, a private network path from Cloud Run to build, and no IAM check on the
  caller; the price is at the ceiling and unverified.
- **One Cloud Run service with both profiles** behind a small proxy. One warm instance
  (about US$26 at 3 GiB) instead of two. Rejected: a custom proxy in front of a safety app's
  routing for a saving that scaling to zero already gives.
- **Contraction Hierarchies (CH).** Faster queries. Not needed: MLD answers in milliseconds,
  supports alternatives directly and customizes in seconds.
- **`--mmap`** (serve the graph from the file cache). Not measured; Cloud Run counts an
  in-memory file system against the same limit.

## Consequences

- **Statewide routing is not yet a public claim.** Layer 2 of the coverage statement changes
  only after the services run on staging and the measurements there are recorded
  (ADR 0016). Until then: "not recorded".
- The first route after a quiet period waits for a cold start of unknown length on Cloud
  Run. If it is too long: one warm instance (about US$19.71 a month each), or the metro box.
- The build needs about 4 GB of memory and 3 minutes per profile; a GitHub-hosted runner is
  expected to be enough (to be confirmed in the next pull request).
- Routes cannot leave the state and come back, because the graph ends at the boundary.
- India-specific limits of the stock profiles, recorded as follow-ups: walking does not use
  trunk roads (above); driving assumes a car (two-wheelers dominate and may use other roads,
  speeds reach 79 km/h on trunk roads); footways are sparse outside central Kolkata; one-way
  and turn data vary; ferries are routed at 5 km/h where mapped. P012b limits a walking
  request to a straight-line distance; 30 km is proposed from the failure rates above.
- The log exclusion is a setting per project. A new environment needs it before it serves
  users (ADR 0019).
- Attribution: the app shows "© OpenStreetMap contributors" with a route (P012c), in
  addition to the map credit.
- Revisit when staging measurements exist, when the exposure metric arrives (P019), before
  production sizing, and when OSRM or the extract is updated.

## Implementation note of 2026-10-07 (P012a2)

The decision above is unchanged. How it is carried out:

- **Deploy path:** `.github/workflows/osrm-staging.yml`, started by hand, once per profile. It
  builds and smoke-tests the image **before** it logs in to Google Cloud, pushes it as
  `osrm-<profile>-<extract date>`, deploys with `--no-allow-unauthenticated` (1 vCPU, 2 GiB,
  concurrency 8, timeout 30 s, at most 2 instances, minimum 0 unless 1 is chosen), and fails
  unless a call without credentials gets 403.
- **Setup:** `bootstrap-staging.ps1 -Apply` creates `sa-osrm-runtime` with no role (the one
  account this script creates; existing accounts are still never changed), lets `sa-deploy`
  use it, and lets `sa-api-runtime` invoke each service once it exists. A service that is
  not deployed yet is reported as PENDING, which does not fail `-Verify`; a public OSRM
  service is reported as WRONG and never touched.
- **Log exclusion:** one per service on the `_Default` sink, for every entry that has a
  request URL. Lines written by the container are kept, because the image writes no request
  line and start-up errors are needed. The exclusions are created before the first deploy.
  The filter is written without quotes so that it survives `gcloud` on Windows; whether Cloud
  Logging accepts it is **not verified** until Rahul runs it (the runbook has the console form).
- Service and account names are constants shared by the workflow and the script, not GitHub
  variables; a test compares them.
- Still open: whether a GitHub-hosted runner can build the state graph (the first run
  decides), and every staging measurement.

## Implementation note of 2026-10-08 (P012b)

The decision above is unchanged. How the API uses the services:

- **Endpoint:** `POST /v1/routes`, positions in the body (ADR 0019), contract 0.6.0.
- **Service-to-service authentication without a library:** the API asks the metadata server
  for an ID token of its own service account with **audience = the service URL exactly as
  Cloud Run reports it** (no trailing slash, no path), and caches it until five minutes before
  it expires. The two URLs reach the API as GitHub environment secrets (masked in logs)
  turned into env vars; a missing one makes the candidate revision fail to start.
- **Cold start (decision 6):** one attempt per request, no retry in the API. A timeout or any
  failure of the service is `503 routing_unavailable` with `Retry-After: 10`; the app retries.
- **Timeout: 25 s, not measured.** Staging measurements are still pending, so the default is
  chosen from one fact only: the API's own Cloud Run request timeout is 60 s. It is read from
  the optional GitHub environment variable `ROUTING_TIMEOUT_MS`, to be tuned from the
  runbook's measurements without a code change (follow-up).
- **Covered area:** the outline from P012a, copied into the API image. A point up to 500 m
  outside the simplified outline counts as inside, because the simplification can lie about
  220 m inside the real line; the routing engine then has the last word.
- **Distance limits:** walking 30 km in a straight line (from the failure rates above);
  driving 1 000 km, which only bounds nonsense.
- **`Connection: close` on every call**, because `osrm-routed` drops idle connections after
  five seconds (found in P012a).
- **Mapping:** OSRM `NoRoute` → 404 `no_route_found`; `NoSegment` → 422
  `location_not_routable`; everything else → 503. An authentication failure is logged with
  `alert: routing_auth_failed` and never shown as 401 or 403.

## References

- Plan v7 §13.1, §14.3; addendum v7.2 §D.
- Code: [`infra/osrm/`](../../infra/osrm/README.md); CI: `.github/workflows/osrm-ci.yml`.
- Runbook: [`docs/runbooks/routing-capacity-staging.md`](../runbooks/routing-capacity-staging.md).
- Diagram: [`docs/diagrams/012a-routing-infra.svg`](../diagrams/012a-routing-infra.svg).
- Geofabrik download page for the Eastern Zone of India; OpenStreetMap Foundation attribution
  guidelines; Cloud Run and Artifact Registry pricing pages (all read on 2026-10-07).
- Prompt log: [`docs/prompt-logs/012a-osrm-graph-hosting.md`](../prompt-logs/012a-osrm-graph-hosting.md).
