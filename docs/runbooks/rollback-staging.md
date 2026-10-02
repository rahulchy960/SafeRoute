# Runbook: rollback, manual migration and admin jobs on staging

What to do when a staging deploy went wrong, and how to run the two manual jobs: the migration
job and the one-off `saferoute-admin` job that changes a user's role. Design and reasons:
[ADR 0007](../adr/0007-gcp-staging-topology.md) (deploy order, rollback) and
[ADR 0003](../adr/0003-database-conventions-and-migrations.md) (migrations are fixed forward).

| | |
| --- | --- |
| **Who runs it** | Rahul, signed in to `gcloud` and `gh` as the project owner. Claude Code never runs these commands and has no Google credentials. |
| **When** | A bad revision is serving traffic, a migration must be rerun, a role must be changed, or for the rehearsal in step 7. |
| **Scope** | The **staging** project only. |
| **Time** | A rollback takes about two minutes. It changes where traffic goes and nothing else. |

## What the pipeline already does by itself

The `deploy-staging` workflow ([`.github/workflows/deploy-staging.yml`](../../.github/workflows/deploy-staging.yml))
handles most failures without you. Read the run's **Summary** first: it names the commit, the
candidate revision and the previous revision (the rollback target).

| The run failed at | State afterwards | What you do |
| --- | --- | --- |
| Build, container smoke test or push | Nothing in the cloud changed | Fix the code, merge again |
| Migration job | The database is unchanged: all pending migrations run in one transaction. No new API revision exists; the old one still serves | Read the job's log ([observability runbook](observability-staging.md), query 4). Fix forward (step 4), or retry (step 5) if the cause was outside the code |
| Smoke test on the candidate | The candidate exists with **no traffic**. The old revision still serves | Read the candidate's logs, fix forward. No rollback needed |
| Smoke test on the live service | The workflow already sent all traffic back to the previous revision | Confirm with step 1, then fix forward |
| Nothing: the run was green, but the new version misbehaves | The new revision serves all traffic | **Roll back by hand: steps 1 and 2** |

## Before you start

- **Shell.** Commands are written for **Windows PowerShell 5.1** in the repository root. In
  **Cloud Shell or bash**: define variables as `NAME="value"`, read them as `"$NAME"`, and end a
  continued line with `\` instead of a backtick.
- **Never paste the output anywhere public.** Revision lists show the deploy identity's e-mail,
  and service URLs contain the project number. Revision names (`saferoute-api-00007-abc`), job
  execution names and commit SHAs are safe to record.
- **Never put a secret in `--args` or `--update-env-vars`** (step 6). Job arguments are stored
  with the execution and written to Cloud Audit Logs, where they stay readable.

### Step 0: variables

```powershell
$REGION = "asia-south1"
$API_SERVICE = "saferoute-api"
$MIGRATION_JOB = "saferoute-migrate"
$ADMIN_JOB = "saferoute-admin"
$SQL_INSTANCE = "saferoute-db"
$SECRET_NAME = "saferoute-staging-database-url"
$GITHUB_REPO = "rahulchy960/SafeRoute"
```

**Verify:** the active project is the **staging** project (not production).

```powershell
gcloud config get-value project
```

## Step 1: see what is serving

```powershell
gcloud run revisions list --service=$API_SERVICE --region=$REGION --limit=10
function Show-Traffic {
  gcloud run services describe $API_SERVICE --region=$REGION --flatten="status.traffic" `
    --format="table(status.traffic.revisionName, status.traffic.percent, status.traffic.tag)"
}
Show-Traffic
```

- The first command lists the service's revisions, newest first.
- `Show-Traffic` prints one line per traffic entry and no URL, so its output is safe to keep.
  After a normal deploy there is one line: the newest revision serves 100% and also carries the
  `candidate` tag.

  ```text
  REVISION_NAME            PERCENT  TAG
  saferoute-api-00004-ced  100      candidate
  ```

To find which commit a revision runs, open the `deploy-staging` run that created it (Actions →
deploy-staging → the run → Summary): the table lists the commit next to the candidate revision.

Pick the rollback target: normally the **Previous revision** from the summary of the bad run.

```powershell
$REVISION = "<REVISION_NAME>"
```

## Step 2: send all traffic to the previous revision

1. Stop further deploys, so an unrelated merge doesn't put the bad code back:

   ```powershell
   gh variable set STAGING_DEPLOY_ENABLED --repo $GITHUB_REPO --body "false"
   ```

2. Shift the traffic. This needs no new build and took 9 seconds in the rehearsal.

   ```powershell
   gcloud run services update-traffic $API_SERVICE --region=$REGION --to-revisions="${REVISION}=100"
   ```

**Verify:** the traffic table shows `$REVISION` at 100%, and the smoke checks pass against the
version that is now served. The newer revision stays in the table with its `candidate` tag and
no percentage:

```text
REVISION_NAME            PERCENT  TAG
saferoute-api-00002-lin  100
saferoute-api-00004-ced           candidate
```

```powershell
Show-Traffic
$env:SMOKE_URL = gcloud run services describe $API_SERVICE --region=$REGION --format="value(status.url)"
$ServedSha = (Invoke-RestMethod "$env:SMOKE_URL/health").version
git log -1 --oneline $ServedSha
node backend/scripts/smoke.mjs --expect-version $ServedSha --timeout-seconds 60
```

**bash:** `SERVED_SHA="$(curl -s "$SMOKE_URL/health" | jq -r .version)"`

- `git log` prints the commit you meant to return to. If it prints a different commit, you
  picked the wrong revision: repeat with the right one.
- The script ends with `smoke test passed`. It prints status codes, the version and a request
  id, never the URL.
- If the target is the **placeholder** revision from the setup runbook (Google's sample page),
  `/health` doesn't exist there and the script fails by design. Check that the service URL
  answers `200` instead.
- If both revisions were built from the **same commit** (as in a rehearsal), the smoke test
  passes on either and can't tell them apart. The traffic table is the proof of which one serves.

Notes:

- A rollback target should be recent. The registry keeps the 10 newest images and deletes
  others after 7 days (ADR 0007), so an old revision may be unable to start new instances.
- The service stays pinned to `$REVISION` until something moves traffic again. The workflow
  does that itself: every successful deploy ends with `--to-latest`.

## Step 3: why the database is not rolled back

There is no "down" migration and no automatic database rollback, on purpose:

- Migrations are **expand-only** (ADR 0003): they add tables, columns and indexes and never
  remove or rename what running code uses. The previous revision therefore keeps working
  against the newer schema, which is what makes step 2 safe at any time.
- Undoing a migration would mean dropping columns or tables, and with them data written since.
  A traffic rollback loses nothing; a schema rollback can.
- A merged migration is **never edited** (ADR 0003). A wrong migration is corrected by a new
  migration that fixes forward.

If a migration damaged data (not just structure), stop and don't improvise: Cloud SQL keeps
seven daily backups (setup runbook, step 4). Restoring one replaces the whole database, so it is
a last resort and gets its own runbook in P020.

## Step 4: roll forward

Rolling back buys time. The fix always arrives the normal way:

1. Fix the problem on a branch (or revert the bad commit, but **not** its migration file), open
   a pull request, let CI pass, and merge.
2. Turn deploys back on, then start a run if the merge happened while the switch was off:

   ```powershell
   gh variable set STAGING_DEPLOY_ENABLED --repo $GITHUB_REPO --body "true"
   gh workflow run deploy-staging.yml --repo $GITHUB_REPO --ref main
   ```

3. Watch the run. It migrates, tests a candidate, and moves traffic to it with `--to-latest`.

Rolled back by mistake? Return to the newest revision without a deploy:

```powershell
gcloud run services update-traffic $API_SERVICE --region=$REGION --to-latest
```

**Verify:** step 1 shows the newest revision at 100%, and the smoke script passes with the SHA
of `main`.

## Step 5: rerun the migration job by hand

Needed rarely: the job failed for a reason outside the code (database stopped, a timeout) and
you want to retry without a new merge. The job runs the image of the **last deploy attempt**.
It is safe to repeat: applied migrations are skipped, a failed run changes nothing (one
transaction), and an advisory lock stops two runs from overlapping.

```powershell
gcloud run jobs execute $MIGRATION_JOB --region=$REGION --wait
```

**Verify:** the newest execution succeeded, and its log says `migrations applied` or
`database already up to date` (observability runbook, query 4).

```powershell
gcloud run jobs executions list --job=$MIGRATION_JOB --region=$REGION --limit=5
```

## Step 6: change a user's role with the `saferoute-admin` job

Roles (`user`, `moderator`, `admin`) live in the database and are changed only by the script
`dist/scripts/set-role.js`, which also writes an `audit_log` row (ADR 0006). On staging it runs
as a one-off Cloud Run Job execution, under the migration identity, with the arguments given
for that execution only.

### 6.1 Create or refresh the job

Run this the first time, and again before each use so the job has the image that matches the
current schema. It copies the image from the migration job. The job's own arguments are
`--help`, so executing it without overrides changes nothing.

```powershell
$PROJECT_ID = gcloud config get-value project
$MIGRATION_SA = "sa-migration@${PROJECT_ID}.iam.gserviceaccount.com"
$CONNECTION_NAME = gcloud sql instances describe $SQL_INSTANCE --format="value(connectionName)"
$IMAGE = gcloud run jobs describe $MIGRATION_JOB --region=$REGION --format="value(spec.template.spec.template.spec.containers[0].image)"

gcloud run jobs deploy $ADMIN_JOB --region=$REGION --image=$IMAGE `
  --service-account=$MIGRATION_SA `
  --set-cloudsql-instances=$CONNECTION_NAME `
  --set-secrets="DATABASE_URL=${SECRET_NAME}:latest" `
  --command=node --args="dist/scripts/set-role.js,--help" `
  --tasks=1 --max-retries=0 --task-timeout=2m
```

**Verify:** `$IMAGE` ends with `@sha256:` and 64 hex characters, and a plain execution succeeds
and prints the usage text in its log.

```powershell
gcloud run jobs execute $ADMIN_JOB --region=$REGION --wait
```

### 6.2 Change the role

The user ID is the internal UUID: the `id` field of `GET /v1/me`, or `user_id` in the logs. It
is **not** the Firebase uid and not a phone number.

```powershell
$UserId = "<USER_UUID>"
gcloud run jobs execute $ADMIN_JOB --region=$REGION --wait `
  --args="dist/scripts/set-role.js,--user-id,$UserId,--role,moderator,--confirm"
```

- `--args` replaces the job's arguments for this execution only; the job itself keeps `--help`.
- The whole `--args=…` value must stay inside one pair of quotes, with commas and no spaces.
- ⚠️ **Never put a secret in `--args`:** no tokens, passwords, database URLs or phone numbers.
  The script needs none of them; `DATABASE_URL` comes from Secret Manager.
- Without `--confirm` the script refuses and changes nothing.

**Verify:** the execution succeeded, and its log (observability runbook, query 4, with
`$ADMIN_JOB`) has one line such as
`User <uuid>: role user → moderator (database (socket)).` The user's next request uses the new
role; no new sign-in is needed.

| Result | Meaning |
| --- | --- |
| Succeeded, `role user → moderator` | Changed, and recorded in `audit_log` |
| Succeeded, `role is already moderator` | Nothing to do, nothing recorded |
| Failed, `No user with id …` | Wrong UUID, or the user hasn't called `POST /v1/me/bootstrap` yet |
| Failed, usage text | The arguments are malformed (role name, UUID, a space after a comma) |
| Failed, `Failed to change the role …` plus an error name | Database problem: check that the instance is running |

## Step 7: rehearsal checklist (Plan v7 §3.4, "rollback rehearsed")

Do this once after the first successful deploy, and again whenever this runbook changes. A
faithful rehearsal needs **two real revisions**, so run the workflow a second time first
(Actions → deploy-staging → Run workflow). With only the placeholder to return to, the traffic
mechanics can be rehearsed but the smoke script can't pass.

- [ ] Two green `deploy-staging` runs; note both revision names from the summaries.
- [ ] Step 1: the newer revision serves 100%.
- [ ] Step 2: deploy switch off, traffic moved to the older revision, started at `__:__`.
- [ ] Traffic table shows the older revision at 100%; smoke script passes, finished at `__:__`.
- [ ] `GET /v1/me` without a token still answers 401 (the smoke script checks it).
- [ ] Step 4: `--to-latest`; the newer revision serves 100% again; smoke script passes.
- [ ] Deploy switch back to `true`.
- [ ] Step 5: one manual migration execution; log says `database already up to date`.
- [ ] Step 6.1: admin job created; the plain execution prints the usage text.
- [ ] Step 6.2 (optional, needs a bootstrapped test user): change a role and change it back.
- [ ] Anything that didn't match this runbook is written down, with IDs masked.
- [ ] Result recorded on the Notion P006b page: date, the two revision names, minutes taken,
      pass or fail, deviations. No project IDs, e-mails or URLs.

### Rehearsal record

| Date | Run by | Revisions | Traffic rollback | Roll forward | Result |
| --- | --- | --- | --- | --- | --- |
| 2026-10-02 | Rahul | `saferoute-api-00004-ced` → `saferoute-api-00002-lin` → `saferoute-api-00004-ced`, both built from commit `fef283b` | 9 s | 6 s | **PASS** for steps 1, 2 and 4. Steps 5 and 6 not rehearsed |

Details of the 2026-10-02 rehearsal:

- **Setup:** two green `deploy-staging` runs (36949131456 and 36950613989) had produced the two
  revisions. Before the rehearsal `saferoute-api-00004-ced` served 100%.
- **Step 2:** the deploy switch was set to `false`, then all traffic was sent to
  `saferoute-api-00002-lin`. The traffic table showed it at 100% and the newer revision with
  only its `candidate` tag. The smoke script passed: `/health` 200 with version
  `fef283b342efeb0a86a5fce2b70530b175de39e6`, `/health/ready` 200, `/v1/me` without a token 401
  with `WWW-Authenticate: Bearer` and a request id.
- **Step 4:** `--to-latest` returned 100% to `saferoute-api-00004-ced`; the smoke script passed.
  A second `--to-latest` changed nothing (3 s) and the smoke script passed again. The switch was
  set back to `true`.
- **Deviations from this runbook, now corrected above:** the traffic table was read with the
  `--flatten` … `table(…)` command instead of `yaml(status.traffic)`. It prints no URL, so the
  runbook now uses it. Clock times were not noted; the two shifts were timed instead.
- **Limits of this rehearsal:** both revisions run the same commit, so it proves the traffic
  mechanics and the checks, not a return to older code. No migration lay between the two
  revisions. Step 5 (manual migration execution), step 6.1 (the `saferoute-admin` job) and
  step 6.2 were not run and stay open.

## Troubleshooting

| Symptom | Likely cause | What to do |
| --- | --- | --- |
| `update-traffic` says the revision was not found | Typo, or the revision belongs to another region | Copy the name from step 1; check `$REGION` |
| Traffic moved, but requests fail with 5xx and the revision can't start | Its image was removed by the cleanup policy | Choose a newer revision, or roll forward (step 4) |
| After a rollback, a later green deploy "did nothing" | It did: look at step 1. If the workflow was skipped, the switch is still `false` | Step 4.2 |
| `jobs execute` fails with `PERMISSION_DENIED … actAs` | Your account can't act as `sa-migration` | Run as the project owner |
| The job starts and fails at once with a database connection error | Cloud SQL is stopped (setup runbook, step 11) or the job lacks `--set-cloudsql-instances` | Start the instance; rerun 6.1 |
| `--args` is rejected or the script prints the usage text | Quotes or commas are wrong | One quoted `--args="a,b,c"` value, no spaces |
| `gh workflow run` answers `HTTP 422` or the run is skipped | Wrong ref, or `STAGING_DEPLOY_ENABLED` isn't `true` | `--ref main`; step 4.2 |

## How this runbook was checked

Claude Code wrote it without access to Google Cloud, so when it was written no command here
had been executed against a project. Commands and flags were checked against `--help` output of Google Cloud SDK 587.0.0
(`run jobs deploy`, `run jobs execute`, `run services describe`) or against Google's online
`gcloud` reference (`run revisions list`, `run services update-traffic`, `run jobs describe`,
`run jobs executions list`), and `gh workflow run` against `gh` 2.102.0. The quoting of
`--args`, `--to-revisions` and `--set-secrets` was run in PowerShell 5.1 with fake values.

**Verified on staging by the rehearsal of 2026-10-02** (record in step 7): `update-traffic`
with `--to-revisions` and with `--to-latest`; the traffic table command; `value(status.url)`;
the smoke script against the service URL; that a second workflow run for the same commit creates
a new revision; that revisions are listed newest first.

**Still not verified:**

- steps 5 and 6: `jobs execute`, `jobs deploy` for the admin job, `jobs executions list`, and
  the image path used in 6.1;
- how a revision whose image was deleted behaves;
- the exact wording of the error messages in the troubleshooting table.

If a command fails, stop and record the exact error (with IDs masked) before trying variations.
