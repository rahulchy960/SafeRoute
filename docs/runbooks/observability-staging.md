# Runbook: observability on staging (logs and metrics)

Where to look when staging misbehaves: six log queries, how the API's JSON log lines appear in
Cloud Logging, and the handful of metrics that make up the first capacity dashboard
(Plan v7 §14.5). Deploy order and topology: [ADR 0007](../adr/0007-gcp-staging-topology.md).

| | |
| --- | --- |
| **Who runs it** | Rahul, signed in to `gcloud` as the project owner, or in the Cloud console. Claude Code never runs these commands and has no Google credentials. |
| **When** | A deploy failed, a request failed, or as the weekly look at staging. |
| **Scope** | The **staging** project only. All commands here only read. |

## What the API writes

The API, the migration job and the admin job write one JSON object per line to standard output
([`backend/src/lib/logger.ts`](../../backend/src/lib/logger.ts)). Cloud Run sends those lines to
Cloud Logging. Cloud Logging lifts `severity` out of the JSON into the entry's own severity and
keeps every other field under `jsonPayload`.

| Field | Where it shows in Cloud Logging | Meaning |
| --- | --- | --- |
| `severity` | `severity` | `DEBUG`, `INFO`, `WARNING`, `ERROR` or `CRITICAL` |
| `message` | `jsonPayload.message` | e.g. `request completed`, `authentication failed`, `migrations applied` |
| `service`, `version` | `jsonPayload.service`, `jsonPayload.version` | `saferoute-api` or `saferoute-migrate`; the commit SHA |
| `request_id` | `jsonPayload.request_id` | Same value as the `X-Request-Id` response header |
| `method`, `path`, `status`, `duration_ms` | `jsonPayload.…` | One `request completed` line per request. `path` is the route pattern, never the URL |
| `user_id` | `jsonPayload.user_id` | Internal UUID of the signed-in user, when there is one |
| `auth_failure` | `jsonPayload.auth_failure` | Why a token was refused: `missing`, `malformed`, `expired`, `invalid`, `wrong_provider`, `no_phone` or `unavailable` |
| `db_error` | `jsonPayload.db_error` | Error name and PostgreSQL code only |

Severity follows the outcome: a request that ends in 4xx is logged at `WARNING`, 5xx at `ERROR`.
Tokens, the `Authorization` header, phone numbers, Firebase uids, request bodies, query strings,
client addresses and precise locations are never logged (ADR 0006, Plan v7 §12.2).

Cloud Run also writes its **own** request log (`run.googleapis.com/requests`) with
`httpRequest.status` and `httpRequest.latency`. It is separate from the API's lines and is not
linked to them automatically: use `request_id` to follow one request through the API's lines.

## Before you start

- **Shell.** Commands are written for **Windows PowerShell 5.1**. In **Cloud Shell or bash**,
  define variables as `NAME="value"`, read them as `"$NAME"`, and write ``\`"`` as `\"`.
- **Quotes.** PowerShell 5.1 drops plain double quotes inside an argument. The filters below
  therefore use unquoted values, which the query language accepts for names made of letters,
  digits, `_` and `-`. Where a quote is unavoidable it is written as ``\`"``.
- **Console instead.** Every filter (the text between the outer quotes) can be pasted into
  **Logging → Logs Explorer**. There, write ``\`"`` as a plain `"`.
- **Never paste log output anywhere public.** Entries carry the project ID in their resource
  labels, and user UUIDs. Request ids, revision names and execution names are safe to record.

### Step 0: variables

```powershell
$API_SERVICE = "saferoute-api"
$MIGRATION_JOB = "saferoute-migrate"
$Columns = "table(timestamp, severity, jsonPayload.message, jsonPayload.path, jsonPayload.status, jsonPayload.request_id)"
```

**Verify:** the active project is the **staging** project.

```powershell
gcloud config get-value project
```

`gcloud logging read` returns the newest entries first and, unless told otherwise, only looks
at the last day (`--freshness=1d`). Add `--format=json` to see whole entries.

## Query 1: warnings and errors of the API

```powershell
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=$API_SERVICE AND severity>=WARNING" --freshness=1h --limit=50 --format=$Columns
```

Expect a few `WARNING` lines after every deploy: the smoke test calls `/v1/me` without a token
at least twice per run, and each call logs `authentication failed` and `request completed`
(401). Lines at `ERROR` or above deserve a look.

## Query 2: everything about one request

Take the id from the `X-Request-Id` response header, from a problem response's `requestId`, or
from the smoke test's output.

```powershell
$RequestId = "<REQUEST_ID>"
gcloud logging read "resource.type=cloud_run_revision AND jsonPayload.request_id=$RequestId" --freshness=7d --format=$Columns
```

## Query 3: authentication failures

```powershell
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=$API_SERVICE AND jsonPayload.auth_failure:*" --freshness=1d --limit=50 --format="table(timestamp, jsonPayload.auth_failure, jsonPayload.request_id)"
```

`jsonPayload.auth_failure:*` means "the field exists". What the reasons tell you:

| Reason | Usual cause |
| --- | --- |
| `missing`, `malformed` | A client bug, a scanner, or the smoke test (`missing`) |
| `expired` | The app didn't refresh its token; harmless in small numbers |
| `invalid`, `wrong_provider`, `no_phone` | Wrong Firebase project in the app or in `FIREBASE_PROJECT_ID`, or a sign-in method other than phone |
| `unavailable` | Google's public keys could not be fetched: the client got 503, not 401. Repeated lines mean an outbound network problem |

## Query 4: migration and admin job executions

All recent lines of the migration job (use `$ADMIN_JOB` from the rollback runbook the same way):

```powershell
gcloud logging read "resource.type=cloud_run_job AND resource.labels.job_name=$MIGRATION_JOB" --freshness=1d --limit=50 --format="table(timestamp, severity, jsonPayload.message, jsonPayload.count, textPayload)"
```

A healthy execution has one line: `migrations applied` (with `count`) or
`database already up to date`. `migration failed` comes with `db_error`. The admin job prints
plain text, which appears under `textPayload`.

One execution only (the name is in the workflow summary and in
`gcloud run jobs executions list`):

```powershell
$Execution = "<EXECUTION_NAME>"
gcloud logging read "resource.type=cloud_run_job AND labels.\`"run.googleapis.com/execution_name\`"=$Execution" --freshness=7d --format=json
```

⚠️ **Not verified:** the label key `run.googleapis.com/execution_name`. Google's page names the
label `execution_name` without showing the full key. If this returns nothing, run the first
command with `--limit=1 --format=json`, read the key under `labels`, and correct it here.

## Query 5: one revision (for example a failed candidate)

```powershell
$REVISION = "<REVISION_NAME>"
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.revision_name=$REVISION" --freshness=1d --limit=100 --format="table(timestamp, severity, jsonPayload.message, textPayload)"
```

A revision that never started usually shows a configuration error as plain text
(`textPayload`), for example a refused `DATABASE_URL` or a missing `FIREBASE_PROJECT_ID`, or a
platform message about the secret or the Cloud SQL connection (setup runbook, Troubleshooting).

## Query 6: 5xx responses as Cloud Run saw them

This reads Cloud Run's own request log, so it also shows requests that never reached the API
(no instance could start, a timeout).

```powershell
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=$API_SERVICE AND httpRequest.status>=500" --freshness=1d --limit=50 --format="table(timestamp, httpRequest.status, httpRequest.latency, resource.labels.revision_name)"
```

Don't add `httpRequest.requestUrl` to the columns when you intend to share the output.

## Confirm once that severity is mapped

Do this after the first deploy, then record the result on the Notion P006b page.

1. Run query 1 with `--limit=1 --format=json`.
2. Expected: the entry has `"severity": "WARNING"` at the **top level**, there is **no**
   `severity` inside `jsonPayload`, and `jsonPayload.message` is `authentication failed` or
   `request completed`.
3. If entries show `"severity": "DEFAULT"` or the JSON arrives as one `textPayload` string, the
   lines are not being parsed as JSON: stop and report it.
4. Look at `jsonPayload.timestamp` in the same entry. ⚠️ **Not verified:** the API writes its
   time as a `timestamp` text field, and Google's documentation lists `time` (text) or
   `timestamp` (an object with seconds and nanos) as the recognised forms. If
   `jsonPayload.timestamp` is present, Cloud Logging used its own receive time for the entry,
   which is accurate to well under a second here. Record what you see; changing the field name
   is a follow-up, not part of P006.

## Metrics to watch: the first capacity dashboard

No dashboard is built yet (follow-up: Cloud Monitoring dashboard and alert policies, Plan v7
§14.3, §14.5). Until then the same charts are in the console: **Cloud Run → saferoute-api →
Metrics**, and **SQL → saferoute-staging-db → System insights**. In **Monitoring → Metrics
explorer** the metric names below can be searched directly.

| What | Metric | Why it matters on staging |
| --- | --- | --- |
| Request latency, p95 and p99 | `run.googleapis.com/request_latencies` | The first number a user feels. With minimum instances 0, the first request after idle includes a cold start |
| 5xx rate | `run.googleapis.com/request_count`, grouped by `response_code_class` | Any 5xx on staging has a cause worth finding (query 6) |
| Instance count | `run.googleapis.com/container/instance_count` | Reaching the maximum (3) means requests queue. It also drives database connections |
| Container start time | `run.googleapis.com/container/startup_latencies` | How long a cold start takes |
| Container CPU and memory | `run.googleapis.com/container/cpu/utilizations`, `…/container/memory/utilizations` | Memory creeping towards the 512 MiB limit ends in a restart |
| Cloud SQL CPU | `cloudsql.googleapis.com/database/cpu/utilization` | The shared-core tier is slow under sustained load |
| Cloud SQL connections | `cloudsql.googleapis.com/database/postgresql/num_backends` | The tier allows 25. The API may use up to 3 instances × 5 = 15, plus one per job. A value near 20 needs a look |
| Cloud SQL memory and disk | `cloudsql.googleapis.com/database/memory/utilization`, `…/database/disk/utilization` | Disk grows automatically up to 20 GB, then writes fail |
| Job results | `run.googleapis.com/job/completed_execution_count` | A failed migration execution also fails the deploy run |

These are observations for one maintainer, not service-level objectives. Targets and alerts
come with the dashboard follow-up; staging's shared-core database must not be used to judge
production capacity (ADR 0007).

## Troubleshooting

| Symptom | Likely cause | What to do |
| --- | --- | --- |
| A query returns nothing | Older than `--freshness`, another project is active, or staging had no traffic | Raise `--freshness`; check step 0 |
| `PERMISSION_DENIED` on `logging read` | The account lacks log viewing rights | Use the project owner account |
| `Unparseable filter` | A value needs quotes (it contains `:`, `.`, `=`, `,` or a space) | Write the quote as ``\`"`` in PowerShell 5.1, or use Logs Explorer |
| `jsonPayload.…` columns are empty | The entry is plain text (start-up errors, the admin job) | Add `textPayload` to the columns |
| The same request shows two entries with a status | One is Cloud Run's request log, one is the API's `request completed` line | Expected |

## How this runbook was checked

Claude Code wrote it without access to Google Cloud, so no query here was executed against a
project. `gcloud logging read` and its flags were checked against Google's online `gcloud`
reference; resource types, resource labels and the handling of `severity` and `message`
against the Cloud Run logging and Cloud Logging agent documentation; filter syntax (`:*`,
`>=`, unquoted values, quoted label keys) against the Logging query language page; metric
names against the Google Cloud metrics list and the Cloud SQL metrics page. The field names
were read from the backend source, and the PowerShell quoting was run locally against a
stand-in for `gcloud` with fake values.

**Not verified** until Rahul runs them on staging:

- every query's result, and the `--format` column paths;
- the execution label key in query 4 and the `timestamp` handling (both marked ⚠️ above);
- that `httpRequest.status>=500` compares as a number;
- the console menu names.

If a query fails, record the exact error (with IDs masked) before trying variations.
