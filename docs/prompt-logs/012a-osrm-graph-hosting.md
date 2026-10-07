# P012a: West Bengal OSRM graphs, images and measurements

| Field | Value |
| --- | --- |
| Prompt | P012 · part a of three (P012a graphs and hosting, P012b routing API, P012c Android directions). **Part a is split in two**; this is the first half |
| Milestone | M4 (depends on P011d, merged as `05d6b7b`) |
| Branch | `feat/012a-osrm-graph-hosting` |
| PR title | `feat(routing): West Bengal OSRM graphs, images and measurements [P012a]` |
| Notion | [P012a row in the Prompt Log](https://app.notion.com/p/3f207370772081a9bacbd8867f694ec8) |
| Date | 2026-10-07 |
| Plan refs | Plan v7 §3.2 F-04, §12.2, §13.1, §14.1–14.3; addendum v7.2 §D; ADRs 0007, 0013, 0016, 0019 |

> **Not done in this pull request:** the `osrm-staging` workflow, the `bootstrap-staging.ps1`
> changes with their Pester tests, and the log exclusion (A1's workflow and all of A2). They
> are the second half, a separate pull request, because this half alone is over the size limit
> (see "Deviations"). **Nothing is deployed and nothing was measured on staging.**
>
> **Not verified here:** cold start, memory and latency on Cloud Run; that a GitHub runner can
> build the state graph; the `gcloud` commands in sections 5 to 7 of the runbook.

## 1. Objective

Measure what statewide walking and driving routing on self-hosted OSRM really needs before
anything depends on it, decide the extent and the hosting from those numbers, and deliver the
image, the build, smoke and measurement scripts and a CI pipeline on a small fixture.

## 2. Context & prerequisites

- P011d merged (PR #28, `05d6b7b`); no open pull requests; clean tree; hooks active.
- Docker Desktop 28.3.3 with 7.5 GiB and 16 CPUs, 331 GB free disk.
- The budget ceiling (US$30 a month for both profiles) is the prompt's proposal; Rahul
  confirms it in the review.
- ADR number: the prompt says 0019; that is "Privacy in URLs" since P011d, so this is 0020.

## 3. Workflow executed

1. `/start-prompt`: main synced, PR #28 confirmed merged, Notion P011d set to Merged, branch,
   Notion page.
2. **Source.** Geofabrik "Eastern Zone" of India, `eastern-zone-261006.osm.pbf` (data up to
   2026-10-06T20:21:06Z, 247 199 715 bytes, MD5 matches the published one), ODbL 1.0.
3. **Cut.** West Bengal along OSM relation 1960177 (found in the extract by
   `ISO3166-2=IN-WB`; assembled by osmium into one multipolygon of 92 rings and 96 278
   points, unsimplified): 110 MB. A Kolkata metropolitan box 88.00, 22.25 to 88.70, 23.05:
   37 MB.
4. **Graphs.** OSRM 26.10.0, MLD, stock `foot` and `car`, for both extents; then start-up,
   memory and latency with the server limited to 1 CPU (`measure.mjs`, 200 to 300 seeded
   random pairs per case).
5. **Image and scripts** (`infra/osrm/`), the fixture, `osrm-ci`. First checkpoint: `fa567c5`.
6. **Quality check** on the state images: 16 pairs of public places, coordinates read from
   the extract by name.
7. **Prices** read from Google's Cloud Run and Artifact Registry pages; cost per option.
8. ADR 0020, the runbook, the diagram, this log, Notion follow-ups, `/ship-prompt`.

Found on the way, each fixed and kept as a check or a comment:

| Finding | What was done |
| --- | --- |
| At its default log level `osrm-routed` writes **every request path, coordinates included** (150 lines for 150 requests) | The image runs at `WARNING` (0 lines); the smoke test fails on a request path or a coordinate in the log |
| `osrm-extract` writes `.fileIndex` readable by its owner only, and the server maps it read-write | The graph belongs to the unprivileged user (uid 10001) |
| `osrm-routed` exits with code 1 and no message when the route, table, trip or matching size is 1 or 2 | Sizes set to 3, the smallest accepted; the smoke test checks that four points are refused |
| The stock walking profile does not use `highway=trunk` | Recorded in ADR 0020; follow-up; a walking distance limit is proposed for P012b |
| One of about 2 400 measurement requests failed on a reused connection (`osrm-routed` closes idle connections after 5 s) | Follow-up for the P012b adapter |

## 4. Changes

| Area | What |
| --- | --- |
| `infra/osrm/Dockerfile` | Three stages: cut (osmium 1.18.0), graph (extract, partition, customize; build-only files removed), runtime (`osrm-routed` on `$PORT`, uid 10001, `WARNING`, limits). Base images pinned by tag and digest |
| `infra/osrm/scripts/` | `build-image.mjs`, `local-smoke.mjs`, `measure.mjs`, `make-extent.mjs`, `cut-extent.sh`, `entrypoint.sh`, `scripts.test.mjs` |
| `infra/osrm/config.json` | Allowed extract URL prefix, licence, credit, profiles, extents (`state`, `metro`, `sample`) |
| `infra/osrm/sample/` | `sample.osm.pbf` (79 KB: roads only, no contributor metadata, about 3 km across) and `route-pairs.json` |
| `infra/osrm/extents/state-extent.json` | The state outline simplified to 5 150 points (103 KB) for P012b's "outside covered area" check; agrees with the full boundary on 99.92% of 20 000 random points |
| `.github/workflows/osrm-ci.yml` | New workflow, path-filtered: shellcheck, hadolint, unit tests, "no routing data tracked", and build plus smoke test of both profiles on the fixture |
| Docs | ADR 0020, `docs/runbooks/routing-capacity-staging.md`, diagram, index rows, `COPYRIGHT.md` row for the OpenStreetMap-derived files, `infra/README.md` |
| Repo | `.gitignore`: `infra/osrm/data/`; `.gitattributes`: `*.pbf binary` |

No backend, contract, Android or migration change. No existing workflow changed.

**Deviations from the prompt:**

- **Part a is split.** This half is about 1 000 lines of code and configuration before
  documentation (limit: about 800). The second half is cloud-facing (workflow, setup script,
  log exclusion) and depends on Rahul accepting the hosting numbers, so it is a clean cut.
  Proposed name: **P012a2**, branch `feat/012a2-osrm-staging-services`; Rahul decides.
- **ADR 0020**, not 0019.
- **`sa-api-runtime` invoker "MISSING until the services exist"** (A2) will need care in the
  second half: `-Verify` treats MISSING as "not ready", and the workflow that creates the
  services is itself gated on deploys being enabled. To be solved there, not here.
- **Request limits are 3, not 1 or 2** (finding above).
- **No hosting measurement on Cloud Run** (A0 "cold start with the graph baked into the
  image" was measured locally only: 1.6 to 4.8 s).
- **The VM price is from a third-party list**, marked as such in the ADR.

## 5. Diagram

[`docs/diagrams/012a-routing-infra.svg`](../diagrams/012a-routing-infra.svg): extract → build
script → cut → graph → image, the checks, and the planned private services with their callers.

## 6. Quality gate & test results

| Command | Result |
| --- | --- |
| `build-image.mjs` + `local-smoke.mjs`, fixture, foot and car | 7 of 7 checks each |
| Same, state images | walking 11 of 11, driving 22 of 22 (also within `--memory 1g`) |
| `node --test infra/osrm/scripts/scripts.test.mjs` | 6 tests, 0 failures |
| hadolint 2.15.1 · shellcheck · actionlint 1.7.12 (all workflows) | clean |
| `pnpm generate` · `pnpm check` in `tools/diagrams` | 17 diagrams, up to date |
| markdownlint-cli2 · JSON validity · gitleaks 8.30.1 | 85 files, 0 errors · 54 files valid · no leaks |
| `git ls-files` for `.pbf` / `.osrm` | only `infra/osrm/sample/sample.osm.pbf` |

Measurements: the table in [ADR 0020](../adr/0020-routing-osrm.md). The short version, state
graphs, 1 CPU: memory 754 MB (walking) and 907 MB (driving); p95 with two alternatives 24.8
and 22.8 ms; 108 and 101 ms with 8 requests at once; build peak 3.0 and 3.7 GB.

**What the tests cannot see:** Cloud Run. The smoke test proves that the container log holds
no request path; it says nothing about the platform's request log, which exists only once a
service is deployed and is checked by Rahul's query (runbook section 7).

**SOS failure matrix (Plan v7 §7.5):** not applicable.

## 7. Decisions & ADRs

[ADR 0020](../adr/0020-routing-osrm.md), Accepted: OSRM 26.10.0 with MLD and stock profiles;
OpenStreetMap from Geofabrik under ODbL; **the whole state**; one image per profile; two
private Cloud Run services that **scale to zero**; cold start handled by the API and the app;
log level `WARNING` and a log exclusion; no demo server, no other provider.

The decision in one line: within US$30 a month the choice is the whole state with cold
starts (about US$0 to 2) or the metro box always warm (US$19.71). Two warm state services
cost US$39.42.

Proposed for P012b from these numbers: walking limited to 30 km in a straight line; driving
not limited inside the state; the timeout set after the staging cold start is known.

## 8. Security & privacy notes

- **OSRM would have logged every origin and destination** at its default level. The image
  cannot start at that level without changing `entrypoint.sh`, and the smoke test guards it.
- The platform request log of the future services holds the same coordinates in URL paths.
  The exclusion is the second half's work; **no user request may reach an OSRM service
  before it exists and is verified.**
- Test points are random (seeded) or public places (stations, monuments, an airport) read
  from OpenStreetMap. No coordinate of a person. The scripts print counts and timings only.
- The fixture has no contributor names or ids (`add_metadata=false`).
- Images run as uid 10001; nothing is exposed; the scripts bind to `127.0.0.1` only.
- Public-repository check: no project identifier, URL of a service, token or local path in
  the diff. Licences: OSRM BSD-2-Clause, osmium-tool GPL-3.0 (used at build time in its own
  stage, not shipped in the runtime image), data ODbL 1.0 (`COPYRIGHT.md`).

## 9. Known issues & risks

- **Cold start on Cloud Run is unknown.** Loading about 1 GB from a lazily fetched image may
  take much longer than the 2 to 5 s seen locally.
- Walking routes fail between places separated by trunk-only roads or bridges.
- Routes cannot leave the state and return.
- Driving times assume a car and free-flowing roads; no traffic.
- A dated Geofabrik file disappears after some time; a rebuild then uses a newer date.
- `apt` pins `osmium-tool=1.18.0-1`; a Debian update of that package breaks the build until
  the pin is changed.
- hadolint publishes no checksum file; the pinned SHA-256 was computed from the download.

## 10. Follow-ups & prerequisites for next prompt

New in Notion (*Follow-ups*, source P012a):

- Measure the OSRM services on staging: cold start, memory, p95 (High).
- Walking profile ignores trunk roads; India profile tuning and two-wheelers.
- Graph refresh cadence.
- Ferries, footway gaps and one-way data quality.
- Exposure metric latency budget (P019).
- Production sizing and minimum instances.
- OSRM closes idle connections after 5 s (P012b adapter).
- Update the capacity model with these numbers (v8).

**Next: the second half of part a** (workflow `osrm-staging.yml`, `bootstrap-staging.ps1`
with Pester tests for `sa-osrm-runtime`, the invoker and `serviceAccountUser` bindings and the
log exclusion, the deploy steps in the runbook). Needs from Rahul: the budget confirmed, the
hosting choice accepted or changed, and the name of that part. Then P012b.

## 11. How Rahul can verify

1. Read [ADR 0020](../adr/0020-routing-osrm.md): the measurement table, the cost table and
   the decision. **Confirm or change the US$30 ceiling and "whole state, scale to zero".**
2. With Docker running, from the repository root (a few seconds):

   ```powershell
   node infra/osrm/scripts/build-image.mjs --profile foot --extent sample
   node infra/osrm/scripts/local-smoke.mjs --image saferoute-osrm:foot-sample-2026-10-06 --profile foot
   ```

   Expected: `All 7 checks passed.`
3. Optional, about 10 minutes and 250 MB: build and check the state images
   ([runbook](../runbooks/routing-capacity-staging.md), section 2).
4. `osrm-ci` and `repo-checks` green on the pull request, then squash and merge. Nothing
   deploys: the change touches no `backend/` file.
5. Tell Claude Code how to name the second half, then paste it.

## 12. Learning notes

No Android in this part. The ideas behind it:

- **Road graph.** A map for drawing is pictures; a map for routing is a graph: junctions are
  nodes, road pieces are edges with a cost (time). A route is the cheapest path through it.
- **Why the graph is built ahead of time.** Searching millions of edges per request would
  take seconds. OSRM spends minutes once (extract, partition, customize) to precompute
  shortcuts, and then answers in milliseconds. The price is memory: the prepared graph sits
  in RAM.
- **Profile.** The rules that turn map tags into costs: which roads a walker or a car may
  use and how fast. One prepared graph per profile.
- **Extract.** A cut of the worldwide OpenStreetMap database. Smaller extract, smaller graph.
- **Multi-stage Docker build.** Heavy tools run in early stages; only the result is copied
  into the final image, so what is deployed stays small and has fewer tools in it.
- **Scale to zero and cold start.** Cloud Run can stop a service that nobody calls and bill
  nothing. The next request waits while a new instance starts: the cold start.
- **p50 and p95.** Half of the requests are faster than p50; 95 of 100 are faster than p95.
  Targets use p95 because averages hide the slow ones.
