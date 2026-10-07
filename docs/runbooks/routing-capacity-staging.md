# Routing capacity on staging (OSRM)

How the routing graphs are built, what they were measured to need, what they cost and how to
check them. Decision and numbers: [ADR 0020](../adr/0020-routing-osrm.md). Code:
[`infra/osrm/`](../../infra/osrm/README.md).

> **Status (P012a, 2026-10-07): nothing is deployed.** Sections 1 to 4 work today on a PC with
> Docker. Sections 5 to 7 describe the staging services; the workflow and the setup-script
> changes that create them come with the next pull request, and the commands marked
> **not verified** have not been run by anyone yet.
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

## 4. Cost notes

Prices read on 2026-10-07 (Cloud Run pricing page, Tier 1, which includes `asia-south1`);
read them again before a decision. Per service and month of 730 hours:

| Setting | Cost |
| --- | --- |
| Scale to zero (minimum instances 0) | only the seconds that handle requests and starts: about US$0.000029 per second at 1 vCPU and 2 GiB |
| 1 warm instance, 1 vCPU and 2 GiB | US$19.71 |
| 1 warm instance, 1 vCPU and 1 GiB | US$13.14 |
| Stored images | about US$0.10 per GiB-month above 0.5 GiB; one pair of state images is about 1 GiB compressed |

The ceiling for both profiles together is US$30 a month (to be confirmed by Rahul). Two warm
state services at 2 GiB are US$39.42: over it.

## 5. Stop, start and warm up (after the next pull request)

- **Stop paying:** with minimum instances 0 an unused service costs nothing but image
  storage. To make a service unreachable, remove the API's invoker binding in the console;
  the app then shows "routing unavailable".
- **Keep one instance warm** (pilot, demo, the M4 measurement): set minimum instances to 1 on
  the service, and back to 0 afterwards. This is a cost decision; see section 4.
- **Roll back a graph:** deploy the previous image tag (`osrm-<profile>-<extract date>`).
  Nothing else changes: no database, no API deploy.

## 6. Measure on staging (after the next pull request; not verified)

The same script, pointed at one service through the environment. Run it in a PowerShell
window you close afterwards; both values stay out of the command line and out of the output.

```powershell
$env:OSRM_URL = gcloud run services describe saferoute-osrm-driving --region asia-south1 --format="value(status.url)"
$env:OSRM_ID_TOKEN = gcloud auth print-identity-token
node infra/osrm/scripts/measure.mjs --bbox 88.00,22.25,88.70,23.05 --pairs 200 --max-km 30 --label staging-driving-metro
Remove-Item Env:OSRM_URL, Env:OSRM_ID_TOKEN
```

- Your account needs `roles/run.invoker` on the service for this (owners have it).
- **Cold start:** wait 20 minutes without a request, then time one request; repeat three
  times and record the slowest. That number decides between scaling to zero and a warm
  instance, and sets the API's timeout (P012b).
- Record in the prompt log: cold start, p50 and p95 warm, memory from the Cloud Run metrics
  page, and the date. Until then the staging numbers are "not recorded".

## 7. Log-privacy check (after the next pull request; not verified)

OSRM takes the origin and the destination in the URL path, and Cloud Run stores request
URLs. Two protections, both to be checked after the first test route:

1. **The container writes no request line.** `entrypoint.sh` sets log level `WARNING`
   (`local-smoke.mjs` proves it for the image).
2. **The platform's request log for the OSRM services is excluded.** After one test route,
   wait a minute, then:

   ```powershell
   gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name:saferoute-osrm" --freshness=15m --limit=20 --format="table(timestamp, logName)"
   ```

   - **Expected: no rows with a `requests` log name**, and no row whose text holds a
     coordinate. Lines about the service starting are fine.
   - The command prints timestamps and log names only. **Never print
     `httpRequest.requestUrl` for these services.**
   - If rows appear, stop sending routes and fix the exclusion before anything else.

## How this runbook was checked

- Sections 2 and 3: every command was run on 2026-10-07 on Windows with Docker Desktop.
- Section 4: computed from the prices on the official pages on that date.
- Sections 5 to 7: written, not run. No service exists and Claude Code has no cloud access.
  `gcloud run services describe` and `gcloud logging read --freshness --limit` were checked
  in earlier prompts against local help; `gcloud auth print-identity-token` and the filter
  in section 7 were not.
