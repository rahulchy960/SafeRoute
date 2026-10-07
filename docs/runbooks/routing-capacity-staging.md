# Routing capacity on staging (OSRM)

How the routing graphs are built, what they were measured to need, what they cost and how to
check them. Decision and numbers: [ADR 0020](../adr/0020-routing-osrm.md). Code:
[`infra/osrm/`](../../infra/osrm/README.md).

> **Status (P012a2, 2026-10-07): ready to deploy, not deployed.** Sections 1 to 3 work on a PC
> with Docker and were run. Section 5 is the deploy procedure; nobody has run it yet, and
> nothing has been measured on staging. "How this runbook was checked" lists what is verified.
>
> Never paste a service URL, a token, a project identifier or a coordinate from real use into
> a pull request, an issue, Notion or a chat. Every script here prints numbers only.

## 1. What was measured

The table in ADR 0020, "Measurements". In short, on a PC, server limited to 1 CPU:

| | State, walking | State, driving |
| --- | --- | --- |
| Memory after load | 754 MB | 907 MB |
| Start to first answer, graph in the image | 1.6 to 3.3 s | 2.2 to 4.8 s |
| p95 with `alternatives=2`, one request at a time | 24.8 ms | 22.8 ms |
| p95 with 8 requests at once | 108 ms | 101 ms |
| Build: peak memory, time | 3.0 GB, 162 s | 3.7 GB, 180 s |

**Not recorded:** anything on staging (cold start on Cloud Run, memory as Cloud Run counts
it, latency through the API).

## 2. Rebuild the graphs

Needs Docker with at least 8 GB of memory and 5 GB of free disk, and Node. One build at a
time.

1. Pick the extract. Open <https://download.geofabrik.de/asia/india/eastern-zone.html> and
   copy the link of a **dated** file (`eastern-zone-YYMMDD.osm.pbf`), not `latest`: a dated
   file can be rebuilt and compared later. Geofabrik keeps dated files for a limited time.
2. Build both profiles (about 3 minutes each; the first one downloads about 250 MB into
   `infra/osrm/data/`, which git ignores):

   ```powershell
   $Extract = 'https://download.geofabrik.de/asia/india/eastern-zone-261006.osm.pbf'
   node infra/osrm/scripts/build-image.mjs --profile foot --extent state --extract-url $Extract --extract-date 2026-10-06
   node infra/osrm/scripts/build-image.mjs --profile car --extent state --extract-url $Extract --extract-date 2026-10-06
   ```

   `--extract-date` is the date in the file name (`261006` is 2026-10-06). The script refuses a URL outside the allowlist in
   `infra/osrm/config.json` and a file that does not match its published MD5.
3. Check each image (16 driving and 5 walking routes between public places, limits, user,
   labels, and that the log holds no request path):

   ```powershell
   node infra/osrm/scripts/local-smoke.mjs --image saferoute-osrm:foot-state-2026-10-06 --profile foot --pairs state
   node infra/osrm/scripts/local-smoke.mjs --image saferoute-osrm:car-state-2026-10-06 --profile car --pairs state
   ```

   Expected: `All 11 checks passed.` and `All 22 checks passed.`
4. If the state boundary changed noticeably, regenerate the API's outline (two osmium
   commands from `infra/osrm/scripts/cut-extent.sh` give `boundary.geojson`):

   ```powershell
   node infra/osrm/scripts/make-extent.mjs --boundary <path>\boundary.geojson --extract-date 2026-10-06
   ```

**Cadence.** Not decided (follow-up). Nothing rebuilds by itself. A sensible start is every
three months and after a large road change is reported.

## 3. Measure latency locally

```powershell
docker run --detach --name osrm-measure --cpus 1 --memory 2g --publish 127.0.0.1:5000:5000 saferoute-osrm:car-state-2026-10-06
node infra/osrm/scripts/measure.mjs --bbox 85.82,21.55,89.88,27.22 --pairs 200 --label state-car
docker exec osrm-measure grep -E "VmRSS|VmHWM" /proc/1/status
docker rm --force osrm-measure
```

- For walking, add `--max-km 25`: beyond that many pairs have no walking route (ADR 0020).
- `--polygon <boundary.geojson>` keeps the random points inside the state instead of its box.
- The output is one JSON object: counts by result code, p50, p95, largest response, and the
  same by straight-line distance. It contains no coordinate.

## 4. Budget and cost

**Ceiling: US$30 a month for both routing services together** (proposed in P012 and recorded in
ADR 0020; change it there first if it changes). The deployed setting, scale to zero, is far below it; the table shows
what each switch costs so that the ceiling can be checked before it is flipped.

Prices read on 2026-10-07 (Cloud Run pricing page, Tier 1, which includes `asia-south1`;
Artifact Registry pricing page). Read them again before a decision. Per month of 730 hours,
free tiers not subtracted, tax not included:

| Setting | Per service | Both services | Under US$30? |
| --- | --- | --- | --- |
| **Scale to zero** (minimum instances 0; what the workflow deploys) | US$0.000029 for each second that handles a request or starts | **about US$0 to 2** at test volumes | yes |
| One profile warm (minimum instances 1), the other at zero | US$19.71 for the warm one | about US$19.71 | yes |
| Both warm, 1 vCPU and 2 GiB | US$19.71 | **US$39.42** | **no** |
| Stored images | about US$0.05 (about 0.5 GiB compressed each, US$0.10 per GiB-month above the first 0.5 GiB of the account) | about US$0.10 per pair kept | yes |

- Check: 2 628 000 s × (1 vCPU × US$0.0000025 + 2 GiB × US$0.0000025) = US$19.71 idle per
  warm service; US$0.000024 + 2 × US$0.0000025 = US$0.000029 per active second.
- **Do not set both services to 1 warm instance without a new budget decision.**
- **Routing images are kept by the registry cleanup policy** (since P012a3). The policy
  deletes images older than 7 days and keeps the 10 newest versions of each image; without
  a third rule it would delete older routing images too.
  - A revision that **serves** traffic is not affected either way: Cloud Run's documentation
    says the image "is imported by Cloud Run when deployed", that Cloud Run "keeps this copy
    of the container image as long as it is used by a serving revision", and that images
    "are not pulled from their container repository when a new Cloud Run instance is started".
  - The risk was the **rollback target**: a previous revision that no longer serves is not
    covered by that sentence, and an old graph cannot be rebuilt once Geofabrik has removed
    its dated extract.
  - So `infra/artifact-registry-cleanup-policy.json` has the rule `keep-osrm-routing-images`:
    keep every image whose tag starts with `osrm-`. The workflow tags every routing image
    `osrm-<profile>-<extract date>`.
  - `bootstrap-staging.ps1` reports "Registry cleanup keeps routing images (tag osrm-)". If
    it is a NOTE, row 2 of section 5 (`-Apply`) offers to set the policy file again. Do this
    before the first routing image is 7 days old.
  - **Cost:** routing images are now never deleted automatically, about US$0.05 a month for
    each one kept. Delete old ones by hand in the console (Artifact Registry → the
    repository → `saferoute-osrm`) once no revision you might roll back to uses them.
  - Not verified: how the cleanup job treats this rule on the real repository. After the
    first deploy, `gcloud artifacts repositories list-cleanup-policies saferoute --location=asia-south1`
    must list three policies.
- The budget alert of the staging project
  ([setup runbook](gcp-staging-setup.md), step 11) covers these services too.

## 5. Deploy to staging: the commands, in order

Run from the repository root in PowerShell, signed in to `gcloud` and `gh`, with the staging
project active. Claude Code runs none of these. Cloud SQL does not need to be running: the
routing services use no database.

| # | Command | What it does | Cost |
| --- | --- | --- | --- |
| 1 | `.\infra\staging\bootstrap-staging.ps1` | Audit, read-only. Expect four `Routing:` rows MISSING and four PENDING | none |
| 2 | `.\infra\staging\bootstrap-staging.ps1 -Apply` | Asks before each step: creates `sa-osrm-runtime` (no roles), lets `sa-deploy` use it, adds one request-log exclusion per OSRM service. Prints `LATER:` for the two invoker bindings. If the registry has the earlier cleanup policy, also offers to set it again so that routing images are kept (section 4) | none |
| 3 | `gh workflow run osrm-staging.yml --ref main -f profile=walking -f extract_url=https://download.geofabrik.de/asia/india/eastern-zone-261006.osm.pbf -f extract_date=2026-10-06 -f min_instances=0` | Builds, tests, pushes and deploys `saferoute-osrm-walking`, private. About 10 minutes | runner: none (public repository); image storage about US$0.05 a month; service: nothing while idle |
| 4 | `gh run watch` | Follow the run; it must end green with "Refused without credentials (403): success" in the summary | none |
| 5 | The command of row 3 with `-f profile=driving` | The same for `saferoute-osrm-driving` | as row 3 |
| 6 | `.\infra\staging\bootstrap-staging.ps1 -Apply` | Now adds `roles/run.invoker` for `sa-api-runtime` on both services | none |
| 7 | `.\infra\staging\bootstrap-staging.ps1 -Verify` | Must end with `VERIFY: OK`, every `Routing:` row PRESENT, both "is not public" rows `private` | none |
| 8 | Section 7 (log-privacy check) | One test run of routes, then the query that must return nothing | under US$0.01 |
| 9 | Section 6 (measure) | Cold start and latency on staging | under US$0.01 per run |

- **Order matters for privacy:** row 2 before row 3. The exclusions must exist before a
  service can receive its first request.
- If Geofabrik no longer has the dated file, pick a newer one (section 2, step 1) and use its
  date in both inputs.
- If row 2 fails on an exclusion ("FAILED"), add it in the console instead: Logging → Log
  router → `_Default` → Edit sink → "Choose logs to filter out of sink" → Add exclusion, name
  `exclude-saferoute-osrm-walking-requests` (and `…-driving-requests`), filter:

  ```text
  resource.type="cloud_run_revision" AND resource.labels.service_name="saferoute-osrm-walking" AND httpRequest.requestUrl:*
  ```

  Then run the audit again; the row must be PRESENT.
- If the workflow's last check fails ("Expected 403"), treat the service as public: open
  Cloud Run → the service → Security, require authentication, and run `-Verify`.
- The workflow is skipped when the repository variable `STAGING_DEPLOY_ENABLED` is not `true`.

**What is never done:** no `--allow-unauthenticated`, no `allUsers` binding, no role for
`sa-osrm-runtime`, no key. The app never calls these services; only the API will (P012b).

## 6. Measure on staging

The same script as in section 3, pointed at one service through the environment. Run it in a
PowerShell window you close afterwards; both values stay out of the command line and out of
the output.

```powershell
$env:OSRM_URL = gcloud run services describe saferoute-osrm-driving --region asia-south1 --format="value(status.url)"
$env:OSRM_ID_TOKEN = gcloud auth print-identity-token
node infra/osrm/scripts/measure.mjs --bbox 88.00,22.25,88.70,23.05 --pairs 200 --max-km 30 --label staging-driving-metro
Remove-Item Env:OSRM_URL, Env:OSRM_ID_TOKEN
```

- Your account needs permission to call the service (project owners have it).
- **Cold start:** wait 20 minutes without a request, then run the script with `--pairs 1`
  and note the time until it prints; repeat three times and record the slowest. That number
  decides between scaling to zero and a warm instance, and sets the API's timeout (P012b).
- Record in the prompt log: cold start, p50 and p95 warm, memory from the Cloud Run metrics
  page, and the date. Until then the staging numbers are "not recorded", and statewide
  routing stays out of every public coverage statement (ADR 0016).

## 7. Log-privacy check

OSRM takes the origin and the destination in the URL path, and Cloud Run stores request
URLs. Two protections, both checked after the first test routes (section 6 sends some):

1. **The container writes no request line.** `entrypoint.sh` sets log level `WARNING`; the
   workflow's smoke test proves it for the exact image it deploys.
2. **The platform's request log for the OSRM services is excluded.** One minute after the
   test routes:

   ```powershell
   gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name:saferoute-osrm AND httpRequest.requestUrl:*" --freshness=30m --limit=20 --format="table(timestamp, resource.labels.service_name, httpRequest.status)"
   ```

   - **Expected: no rows.** The command prints timestamps, service names and status codes
     only. **Never print `httpRequest.requestUrl` for these services.**
   - If rows appear: stop sending routes, fix the exclusion (section 5), and note how long
     the bucket keeps the entries
     (`gcloud logging buckets describe _Default --location=global --format="value(retentionDays)"`).
   - Everything else the services logged (there should be only start-up lines):

     ```powershell
     gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name:saferoute-osrm" --freshness=30m --limit=20 --format="table(timestamp, logName)"
     ```

## 8. Stop, warm up, roll back

- **Stop paying:** with minimum instances 0 an idle service costs nothing but image
  storage. Nothing to do.
- **Make a service unreachable:** remove the API account's invoker binding in the console
  (Cloud Run → the service → Security → Permissions). The API then answers "routing
  unavailable".
- **Keep one instance warm** (pilot, demo, the M4 measurement), and back afterwards. About
  US$19.71 a month per service while it is 1; see section 4 before doing it for both:

  ```powershell
  gcloud run services update saferoute-osrm-driving --region asia-south1 --min-instances 1
  gcloud run services update saferoute-osrm-driving --region asia-south1 --min-instances 0
  ```

- **Roll back a graph:** send traffic back to the previous revision. No database, no API
  deploy:

  ```powershell
  gcloud run revisions list --service saferoute-osrm-driving --region asia-south1 --limit 5
  gcloud run services update-traffic saferoute-osrm-driving --region asia-south1 --to-revisions <previous revision>=100
  ```

  Traffic is now pinned to that revision: a later run of the workflow creates a new revision
  that gets **no** traffic until you release the pin:

  ```powershell
  gcloud run services update-traffic saferoute-osrm-driving --region asia-south1 --to-latest
  ```

- **A new graph:** run the workflow with a newer extract; the image tag is
  `osrm-<profile>-<extract date>`.

## How this runbook was checked

- Sections 2 and 3: every command was run on 2026-10-07 on Windows with Docker Desktop.
- Section 4: computed from the prices on the official pages on that date.
- Section 5: the script's behaviour (what is MISSING, PENDING and PRESENT, the exact
  commands and their order) is covered by Pester tests with a mocked `gcloud`; the workflow
  passes actionlint. **Neither has run against Google Cloud.** Checked against local
  `gcloud` 587.0.0 help: `iam service-accounts create --display-name`,
  `logging sinks describe`, `logging sinks update --add-exclusion` (keys `name`, `filter`),
  `run services get-iam-policy --region`, and for `run deploy`: `--no-allow-unauthenticated`,
  `--service-account`, `--cpu`, `--memory`, `--concurrency`, `--timeout`, `--min-instances`,
  `--max-instances`, `--cpu-boost`, `--set-env-vars`.
- **Not verified:** that Cloud Logging accepts the exclusion filter as written (unquoted
  values and `httpRequest.requestUrl:*`); that `--no-allow-unauthenticated` works with the
  deploy account's rights (it cannot change IAM policies; the workflow's 403 check judges
  the result either way); that a GitHub runner has the memory and disk for the state graph;
  `gcloud auth print-identity-token`; the queries in section 7; cold start on Cloud Run.
- Sections 6 to 8: written, not run. `run services update --min-instances`,
  `run revisions list --service --region --limit` and `logging read --freshness --limit`
  were checked against local help.
