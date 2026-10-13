# Alerts on staging: error reporting, alert policies and the weekly check

How the developer hears that the staging server is failing during the closed test, without
looking: e-mail from Cloud Monitoring, from the billing budget and from GitHub. And how to
read what arrived. Written in P014d2.

**Rahul runs every command here. Claude Code runs none of them** (CLAUDE.md, "Deployment
rules"). Run them **one at a time** and note which one printed an error.

This runbook sets up alerts for one person on one environment. It is not an on-call system:
nobody is woken at night, and an e-mail can sit unread. SOS on the phone does not depend on
the server (Plan v7 §7), which is why that is acceptable for a closed test.

Related: [observability on staging](observability-staging.md) (the log queries),
[rollback on staging](rollback-staging.md), [routing capacity](routing-capacity-staging.md),
[the one-time setup](gcp-staging-setup.md).

## What was verified, and what was not

| Item | Checked how | Result |
| --- | --- | --- |
| `gcloud beta monitoring channels create` with `--display-name`, `--description`, `--type`, `--channel-labels` | Local `--help` (Google Cloud SDK 587.0.0, beta 2026.09.25) | Exists; the e-mail example in the help uses `--type=email --channel-labels=email_address=...`. There is **no** `gcloud monitoring channels` without `beta`. |
| `gcloud beta monitoring channels list`, `describe` | Local `--help` | Exist |
| `gcloud monitoring policies create` with `--policy-from-file` and `--notification-channels` | Local `--help` | Exist. The help says that flags given with a file override the file's settings. |
| `gcloud monitoring policies list`, `update --[no-]enabled`, `delete` | Local `--help` | Exist |
| `gcloud logging metrics create NAME --description --log-filter`, `list` | Local `--help` | Exist |
| `gcloud billing budgets create` with `--billing-account`, `--display-name`, `--budget-amount`, `--threshold-rule`, `--filter-projects`, `--credit-types-treatment`, `--calendar-period` | Local `--help` | Exist. `--credit-types-treatment` accepts `exclude-all-credits`. |
| `gcloud billing accounts list`, `gcloud billing budgets list` | Local `--help` | Exist |
| `gcloud services enable` | Local `--help` | Exists. The service name `clouderrorreporting.googleapis.com` is from memory. |
| How Error Reporting finds an error in a JSON log line | Google's page "Format errors in logs", read on 2026-10-13 | It looks at `stack_trace`, then `exception`, then `message`, and otherwise searches every field for a stack trace. |
| The price of alerting | Google's page "Manage alerting costs", read on 2026-10-13 | See "What it costs". Whether charging has started was **not confirmed**. |
| **Every command against a project** | Not possible: Claude Code has no access to Google Cloud | **Not verified.** Nothing here has been run. |
| The files in `infra/staging/alerts/` | Valid JSON; the field names follow the `projects.alertPolicies` reference from memory, not from a run | **Not verified** against the API. If `policies create` refuses a file, the error names the field. |
| Metric names and labels (`run.googleapis.com/request_count` with `response_code_class`, `request_latencies`, `job/completed_execution_count` with `result`, `cloudsql.googleapis.com/database/cpu/utilization`, `database/up`) | The first four are in the observability runbook; the label `result="failed"` and the metric `database/up` are from memory | **Not verified.** Check each in **Monitoring → Metrics explorer** before trusting the policy (step 4). |
| GitHub's notification settings for Actions | From memory of the settings page | **Not verified** on the day. The words on the page may differ. |

## Before you start

- **Shell.** Commands are written for **Windows PowerShell 5.1**, as in the other runbooks.
- **Quotes.** PowerShell 5.1 drops plain double quotes inside an argument. Log filters
  below use unquoted values for that reason.
- **Run from the repository root**, on an up-to-date `main`: the policy files are read from
  `infra/staging/alerts/`.
- **Never paste into a chat, an issue or a log:** the project id or number, the billing
  account id, a channel's or a policy's full name (it contains the project id), a service
  URL, a token, or your e-mail address. Say "the channel" and "policy 3".

### Step 0: variables

```powershell
$PROJECT = gcloud config get-value project
$ALERT_EMAIL = "<your e-mail address>"
```

**Verify:** `$PROJECT` is the **staging** project. Do not print it into anything you share.

## Step 1: errors grouped in Error Reporting

Since P014d2 the API writes an error as the fields Error Reporting reads
([`backend/src/lib/logger.ts`](../../backend/src/lib/logger.ts), `safeError`):

| Field | Example | Meaning |
| --- | --- | --- |
| `severity` | `ERROR` | As before |
| `message` | `unhandled error`, `request failed` | A fixed text. Never the error's own message |
| `err.type` | `RangeError`, `AppError` | The error's class |
| `err.code` | `22P02`, `search_unavailable`, `ECONNRESET` | Only when the error has a short machine code |
| `stack_trace` | `RangeError: 22P02` and the `at ...` lines | What Error Reporting groups by. The first line is the class and the code, or "message not logged" |
| `serviceContext` | `service`, `version` | The service name and the commit |
| `request_id` | | The same value as the `X-Request-Id` response header |

**The error's own message is never logged.** It can quote what the request carried. To find
out what happened, use the stack and the request id.

This needs the deploy of the commit that contains P014d2. Until then error lines have the
old shape.

1. Make sure the API is on:

   ```powershell
   gcloud services enable clouderrorreporting.googleapis.com
   ```

2. Open **Console → Error Reporting** (search for "Error Reporting"). It is empty until an
   error with a stack has been logged. Staging may have none: that is a good sign, not a
   fault of the setup.
3. **Not verified:** that the lines are grouped. The first real error shows it. If an
   `ERROR` line with `stack_trace` is in Logs Explorer and nothing appears in Error
   Reporting after a few minutes, note that as the result of this step.

### Reading an error

- One row is one **group**: the same class at the same place in the code. The count and the
  chart say how often and since when.
- Open the group, then a recent sample, then **View logs**. In the log entry, copy
  `jsonPayload.request_id`.
- With the request id, [query 2 of the observability runbook](observability-staging.md)
  shows every line of that request: the route pattern, the status, the duration and, for a
  signed-in request, the internal user id. That is where a request id leads, and no
  further: it does not show what the request contained.
- The same id is what a tester sees as "request id" on the debug Developer screen, and what
  the API returns in a problem response.
- "Resolved" and "Muted" in Error Reporting only change what you see. They fix nothing.

### What not to paste when asking for help

Paste: the error's class and code, the stack frames, the route pattern, the status, the
revision name, a request id. **Do not paste:** the project id or number, a service URL, a
`user_id`, a full log entry as JSON (its resource labels contain the project id), anything
from Cloud Run's own request log (its `requestUrl` is a full URL, ADR 0019), a token, an
`Authorization` header, a database URL.

## Step 2: the notification channel

One e-mail channel, used by every policy.

```powershell
gcloud beta monitoring channels create --display-name="SafeRoute staging alerts" --description="Developer e-mail for staging alerts" --type=email --channel-labels=email_address=$ALERT_EMAIL
```

```powershell
$CHANNEL = gcloud beta monitoring channels list --filter="displayName='SafeRoute staging alerts'" --format="value(name)"
```

**Verify:** `$CHANNEL` is one line that ends in `notificationChannels/` and a number. If it
is empty, the filter's quotes were dropped by the shell: run
`gcloud beta monitoring channels list --format="table(displayName, name)"` and copy the name
into `$CHANNEL` by hand.

An e-mail channel needs no confirmation click. Send a test from **Console → Monitoring →
Alerting → Edit notification channels → Send test notification** and check that it arrives
(and is not in the spam folder).

## Step 3: the logs-based metric for search

The search provider's failures are known only from the API's own log lines, so one policy
needs a counter built from the log.

```powershell
gcloud logging metrics create search_provider_errors --description="Failed calls to the geocoding provider, from the API's log" --log-filter="resource.type=cloud_run_revision AND resource.labels.service_name=saferoute-api AND jsonPayload.message:geocoder AND severity>=WARNING"
```

**Verify:** `gcloud logging metrics list --format="table(name, filter)"` shows it. The
counter starts at the moment it is created; it does not count the past.

The log line it counts carries an outcome word and a duration, never the search text
(ADR 0018).

## Step 4: the alert policies

Seven files in [`infra/staging/alerts/`](../../infra/staging/alerts/). Each is one policy
with one condition. Create them one at a time; after each, note "created" or the error.

```powershell
gcloud monitoring policies create --policy-from-file=infra/staging/alerts/api-5xx.json --notification-channels=$CHANNEL
gcloud monitoring policies create --policy-from-file=infra/staging/alerts/api-latency-p95.json --notification-channels=$CHANNEL
gcloud monitoring policies create --policy-from-file=infra/staging/alerts/migration-job-failed.json --notification-channels=$CHANNEL
gcloud monitoring policies create --policy-from-file=infra/staging/alerts/cloudsql-cpu.json --notification-channels=$CHANNEL
gcloud monitoring policies create --policy-from-file=infra/staging/alerts/cloudsql-down.json --notification-channels=$CHANNEL
gcloud monitoring policies create --policy-from-file=infra/staging/alerts/osrm-5xx.json --notification-channels=$CHANNEL
gcloud monitoring policies create --policy-from-file=infra/staging/alerts/search-provider-errors.json --notification-channels=$CHANNEL
```

| File | Fires when | Why this number |
| --- | --- | --- |
| `api-5xx.json` | More than 5 responses with 5xx from `saferoute-api` in 5 minutes | A count, not a rate: with a handful of testers a percentage of almost nothing means nothing |
| `api-latency-p95.json` | p95 latency above 3 s for 10 minutes | Minimum instances is 0, so a single slow first request is a cold start. Ten minutes of it is not |
| `migration-job-failed.json` | An execution of `saferoute-migrate` ended as failed | Any failure matters; the deploy run fails too |
| `cloudsql-cpu.json` | Database CPU above 80% for 15 minutes | The shared-core tier is slow under sustained load |
| `cloudsql-down.json` | The database reports "not up" for 5 minutes | See "Stopping the database on purpose" |
| `osrm-5xx.json` | More than 5 responses with 5xx from the routing services in 5 minutes | The services are named `saferoute-osrm-...` |
| `search-provider-errors.json` | More than 5 failed geocoder calls in 10 minutes | Users see "search unavailable" (503) |

**Every threshold is a first guess.** Nothing was measured: there is no traffic yet. Change
them after the first weeks (follow-up "alert thresholds from real traffic").

**Verify:**

```powershell
gcloud monitoring policies list --format="table(displayName, enabled)"
```

Seven rows, all enabled. Then, for each policy, open it in **Console → Monitoring →
Alerting** and look at the chart of its condition:

- A chart with a line (even a flat zero) means the metric and its labels exist.
- **"No data" for a metric that should have data** (the API's latency after you used the
  app) means a name or a label in the file is wrong. Note which policy. The two most likely:
  `migration-job-failed.json` (the label `result`) and `cloudsql-down.json` (the metric
  `database/up`): both were written from memory.
- "No data" for the 5xx policies is normal while there are no 5xx.

If `policies create` refuses a file, do not edit the file on your machine: note the error
text (without the project id) so that the file can be fixed in the repository.

### Stopping the database on purpose

The setup runbook (step 11) stops the Cloud SQL instance to save money. That is exactly
what `cloudsql-down.json` reports, and the API then answers readiness with 503. Before a
planned stop:

```powershell
gcloud monitoring policies list --format="table(displayName, name)"
gcloud monitoring policies update <name of the "database is not up" policy> --no-enabled
```

After the start, the same command with `--enabled`. If you never stop the database, leave
it alone.

### Removing a policy

`gcloud monitoring policies delete <name>` removes one policy. The channel and the
logs-based metric stay.

## Step 5: a failed deploy (GitHub)

The deploy runs in GitHub Actions (`deploy-staging`). GitHub can e-mail the person whose
push or merge started a failed run. Nothing in the repository is changed for this.

1. On GitHub, open your **profile picture → Settings → Notifications**.
2. Under **Actions**, choose to be notified by **Email**, and tick **Only notify for failed
   workflows**.
3. Check that the e-mail address under **Default notifications email** is the one you read.

**Verify:** the next failed run (of any workflow you started) sends an e-mail. Do not fail
a deploy on purpose to test it.

A failed deploy has already rolled traffic back by itself; what to read is in the
[rollback runbook](rollback-staging.md).

**Not verified:** the wording of that settings page on the day you read this.

## Step 6: the budget

The setup runbook created a budget in the console (step 11). Two things to check in
**Console → Billing → Budgets & alerts**, because a budget that never fires is worse than
none:

1. **Credits.** While the account spends free credits, the cost after credits is zero. A
   budget that counts credits therefore never reaches 50%. Open the budget, and under
   **Credits** untick the credit types, so that it counts usage before credits.
2. **The date the credits end.** It is on **Billing → Credits**. **Not recorded** in this
   repository: write the date into your own calendar, with a reminder two weeks before.
   On that day the same usage starts to cost money.

To create the budget from the command line instead (once; not in addition to an existing
one):

```powershell
gcloud billing accounts list --format="table(displayName, open)"
$BILLING = "<billing account id, from the console; do not share it>"
gcloud billing budgets create --billing-account=$BILLING --display-name="SafeRoute staging" --budget-amount=<amount in the account's currency> --calendar-period=month --filter-projects=projects/$PROJECT --credit-types-treatment=exclude-all-credits --threshold-rule=percent=0.5 --threshold-rule=percent=0.9 --threshold-rule=percent=1.0 --threshold-rule=percent=1.0,basis=forecasted-spend
```

**The amount is not recorded here**: choose it from the cost section of the
[routing capacity runbook](routing-capacity-staging.md) and what the billing report shows
for a full month.

**Verify:** `gcloud billing budgets list --billing-account=$BILLING --format="table(displayName)"`.

A budget only sends e-mail, to the billing account's administrators. **It does not stop
spending.**

## What it costs

| Item | Cost |
| --- | --- |
| Alert policies | Google's page "Manage alerting costs" (read on 2026-10-13) gives $0.10 per condition per month and $0.35 per million time series returned by a condition's query, "starting no sooner than May 1, 2026". **Whether charging has started was not confirmed.** By that page's own example a condition that returns one time series costs about $0.03 a month on top of the $0.10. Seven policies with one condition each: in the order of one US dollar a month, if charged. Check the Google Cloud Observability pricing page. |
| The e-mail channel | No charge is listed on the pages read. **Not confirmed.** |
| The logs-based metric | A user-defined metric is billed as metric data beyond a free monthly amount. For one counter with a few points an hour the amount is **not known** and expected to be small. |
| Error Reporting | No separate charge is listed; the log lines it reads are billed as logs, as before. **Not confirmed.** |
| The budget | No charge is listed. **Not confirmed.** |
| GitHub notifications | None |

None of these figures was measured on this project. Look at the billing report after the
first month and record what the "Cloud Monitoring" line shows.

## When an alert arrives

1. Read the e-mail: which policy, since when. Do not forward it: it names the project.
2. **API 5xx or slow.** [Query 1 and query 6](observability-staging.md) show the errors as
   the API and as Cloud Run saw them. Then Error Reporting (step 1) for the stack. If it
   began right after a deploy, the [rollback runbook](rollback-staging.md).
3. **Migration job failed.** The deploy run on GitHub shows the same failure and has not
   promoted the new revision. [Query 4](observability-staging.md) shows the job's lines. The
   database is never rolled back: fix forward (ADR 0003).
4. **Database CPU high or not up.** **Console → SQL → saferoute-db**: is it running, and
   what does "System insights" show. If you stopped it yourself, see "Stopping the database
   on purpose".
5. **Routing engine 5xx.** The [routing capacity runbook](routing-capacity-staging.md):
   memory, instances, warm-up. In the app, routes fail with "routing unavailable"; the map,
   search and SOS are unaffected.
6. **Search provider failing.** The provider's status page, and the key's quota in the
   provider's console. An `ERROR` line with `alert: geocoder_key_rejected` means the key
   was refused. Never paste the key anywhere (ADR 0018).
7. **Budget.** The billing report, grouped by service. The routing services and the
   database are the two that cost money while idle.
8. Write down what it was and what you did, in a few lines (follow-up "an incident
   template").

## The weekly check (10 minutes)

Once a week during the closed test, on the same day:

1. **Error Reporting** (2 min): any new group since last week? Open each new one once.
2. **Monitoring → Alerting → Incidents** (1 min): anything open or closed this week that
   you did not see as an e-mail? If yes, the e-mail path is broken: send a test
   notification (step 2).
3. **Cloud Run → saferoute-api → Metrics** (2 min): request count, 5xx, p95 latency for the
   last 7 days. One look at the shape, not a number hunt.
4. **SQL → saferoute-db → System insights** (1 min): CPU, connections, disk.
5. **GitHub → Actions** (1 min): the last `deploy-staging` and `android-ci` runs on `main`
   are green.
6. **Billing → Reports** (2 min): this month so far, by service. And the date the credits
   end.
7. **Firebase → Crashlytics** (1 min): new crashes from testers who opted in. Remember that
   most testers have not (ADR 0028).

Write one line per week somewhere you keep: the date and "nothing" or what you saw.

## Troubleshooting

| Symptom | Likely cause | What to do |
| --- | --- | --- |
| `Invalid choice: 'channels'` | `gcloud monitoring channels` without `beta` | Use `gcloud beta monitoring channels` |
| `policies create` says a field is unknown or invalid | A policy file does not match the API | Note the field named in the error; the file is fixed in the repository |
| A policy's chart says "No data" although the service has traffic | A metric name or a label in the filter is wrong | Find the metric in Metrics explorer and compare the names; note the policy |
| No e-mail although an incident is listed | The channel's address, or the spam folder | Send a test notification (step 2) |
| The budget never sends anything | It counts credits | Step 6 |
| Error Reporting stays empty although `ERROR` lines with `stack_trace` exist | The API is off, or the lines are not recognised | Step 1; note it as a result |
| `PERMISSION_DENIED` | The account lacks the Monitoring or Billing role | Use the project owner account |

## How this runbook was checked

Claude Code wrote it without access to Google Cloud. No command here was executed against a
project, no policy was created, and no alert has fired. What was checked, and how, is the
table at the top. Report which steps printed an error, with the error text and without the
project id, so that the runbook and the policy files can be corrected.
