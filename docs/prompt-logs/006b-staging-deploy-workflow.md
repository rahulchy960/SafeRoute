# P006b: Cloud Run staging deploy with migration job and smoke tests

| Field | Value |
| --- | --- |
| Prompt | P006 · GCP staging deployment, **part b of 2** (part a: container image, setup runbook, container CI) |
| Milestone | M1 (depends on P006a, merged as `dd97c35`, and on Rahul's run of the setup runbook) |
| Branch | `ci/006b-staging-deploy-workflow` |
| PR title | `ci(deploy): Cloud Run staging deploy with migration job and smoke tests [P006b]` |
| Notion | [P006b row in the Prompt Log](https://app.notion.com/p/3ec073707720813ba174ed2907021f0d) |
| Date | 2026-10-02 |
| Plan refs | Plan v7 §3.4, §4, §13.1, §13.2, §14.2, §14.3, §14.5, §17, §20 |

> **Update 2026-10-02: the staging deploy is VERIFIED and the rollback was rehearsed.** See the
> [Revision](#revision-2026-10-02-p006d-deploy-verified-rollback-rehearsed) at the end.
>
> As written at the time: **the staging deploy is UNVERIFIED.** Claude Code cannot run or see a
> deploy. Nothing in this prompt was executed against Google Cloud. The workflow counts as
> working only after Rahul reports the result of the first `deploy-staging` run (section 11).

## 1. Objective

Finish the path from a merge to a running staging API, without a credential anywhere:

- R8: `deploy-staging` workflow (keyless sign-in, build, migration job, candidate revision,
  smoke test, promote, automatic traffic rollback);
- R8e: one smoke script used by the workflow, the container test and the runbooks;
- R9: rollback runbook, including the `saferoute-admin` job for `set-role` and the rehearsal
  checklist (Plan v7 §3.4);
- R10: observability runbook (log queries and the first capacity metrics, Plan v7 §14.5);
- R11: `CLAUDE.md`, PR template and README updates;
- R12: the post-merge checklist for Rahul, and the statement above;
- the deploy-pipeline diagram.

## 2. Context & prerequisites

- P006a merged (PR #10, `dd97c35`). Rahul then ran
  [`docs/runbooks/gcp-staging-setup.md`](../runbooks/gcp-staging-setup.md) and reported: steps
  0–10 completed and verified, `STAGING_DEPLOY_ENABLED` is `false`. No deviations were listed.
- Local tools: Docker 28.3.3, Node 22.17.0 on the host (the image runs Node 24), pnpm 12.8.1,
  gh 2.102.0. `gcloud` was **not run at all** in the final session, not even `--help`: commands
  were checked against `--help` output saved earlier from Google Cloud SDK 587.0.0 and against
  Google's online reference (section 6).
- The Plan PDF could not be opened (no PDF renderer); the plan sections quoted in the prompt
  were used.
- The session was lost once, while the observability runbook was being written; that file had
  not been saved. Work resumed from the three commits that existed (`f5bac0c`, `da6946b`,
  `6e81524`). The workflow and the smoke script were not redone, only re-verified.

## 3. Workflow executed

1. `/start-prompt P006b`: synced `main` (`dd97c35`), hooks active, PR #10 merged. Notion:
   P006a → Merged, P006b → In progress. `git switch -c ci/006b-staging-deploy-workflow`.
2. `f5bac0c`: carried the last P006a log commit, which was pushed after PR #10 had already been
   merged (image size and first `container-ci` result).
3. R8 preparation: `gcloud run deploy`, `run jobs deploy`, `run jobs execute`,
   `run services describe` and `run services update` flags read from saved `--help` output;
   release tags of `google-github-actions/auth` and `setup-gcloud` resolved to commit SHAs with
   `gh api`; the SDK source read to confirm that `gcloud` skips its "enable this API?" check when
   the caller may not list services.
4. `da6946b`: `backend/scripts/smoke.mjs`, and `container-smoke.mjs` extended to run it as a
   CLI with a right and a wrong expected version.
5. `6e81524`: `.github/workflows/deploy-staging.yml`; actionlint and hygiene greps.
6. Session lost; resumed. `4b47491` R9, `a270d3c` R10, `d331107` diagram, `4af4c99` R11, each
   committed after its checks passed.
7. R8 verified again without changing it (section 6), full quality gate, this log,
   `/ship-prompt`.

## 4. Changes

| Area | Files | What |
| --- | --- | --- |
| Deploy workflow (R8) | `.github/workflows/deploy-staging.yml` | Triggers: push to `main` (paths `backend/**` and the workflow) and `workflow_dispatch`. One job, `if:` this repository **and** `refs/heads/main` **and** `vars.STAGING_DEPLOY_ENABLED == 'true'`; `environment: staging`; `id-token: write`, `contents: read`; `concurrency: deploy-staging` without cancellation. Steps in section "Pipeline" below |
| Smoke script (R8e) | `backend/scripts/smoke.mjs`, `backend/scripts/container-smoke.mjs`, `backend/eslint.config.js` | `/health` (200, `version`), `/health/ready` (200), `/v1/me` without a token (401, `WWW-Authenticate: Bearer`, `X-Request-Id`); retries with backoff for up to 120 s; prints status codes, version and request id, never the URL or a body; exit 0 / 1 / 2 |
| Rollback runbook (R9) | `docs/runbooks/rollback-staging.md` | What the pipeline handles itself; list revisions; shift 100% of traffic; why the database is never rolled back; roll forward; rerun the migration job; `saferoute-admin` job; rehearsal checklist; troubleshooting |
| Observability runbook (R10) | `docs/runbooks/observability-staging.md` | Field mapping, six log queries, a one-time severity check, metrics for the first capacity dashboard |
| Diagram | `docs/diagrams/006-deploy-pipeline.{json,excalidraw,svg,png}` | Section 5 |
| Docs (R11) | `CLAUDE.md`, `.github/pull_request_template.md`, `README.md`, `backend/README.md`, `infra/README.md`, `docs/runbooks/README.md` | Deployment rules and a "Deploy workflow" quality-gate row; "Deployment impact" line; "How deploys happen" |
| Log correction | `docs/prompt-logs/006a-container-and-runbooks.md` | Step 2 above |

**Pipeline (one job, in order):**

1. Check that every environment value exists, and that `API_MAX_INSTANCES × DB_POOL_MAX` fits
   the connection budget: **3 × 5 = 15**, budget 20, of the 25 connections the `db-f1-micro`
   tier allows (the rest is for the migration and admin jobs and Cloud SQL's own reserve).
2. Sign in through Workload Identity Federation (`google-github-actions/auth`, no
   `credentials_json`), set up `gcloud` 587.0.0, log Docker in to Artifact Registry.
3. Build the image with the commit SHA as `GIT_SHA`, run the container smoke test on that exact
   image, push it, record its digest.
4. Record the revision that serves traffic now (the rollback target).
5. Deploy and execute the migration job (`node dist/db/migrate.js`, `sa-migration`,
   `--max-retries 0`, 10-minute timeout, `--wait`). A failure stops here: no new API revision
   exists yet.
6. Deploy the API by digest with `--no-traffic --tag candidate` (`sa-api-runtime`, 1 CPU,
   512 MiB, concurrency 80, timeout 60 s, `--cpu-boost`, no `--allow-unauthenticated`).
7. Smoke-test the candidate URL. On failure: traffic is not shifted, the job fails and says so.
8. `update-traffic --to-latest`, smoke-test the service URL. On failure: traffic goes back to
   the recorded revision and the job fails.
9. Job summary: commit, image digest, migration execution, previous and candidate revisions,
   each outcome. No project identifiers and no URLs.

**API contract diff:** none (`contracts/` unchanged, version stays 0.2.0). **Migrations:** none.
**Application behaviour:** unchanged; no file under `backend/src` was touched.

**PR size:** 4,632 added lines, of which 3,526 are generated diagram files. The rest
(about 1,100 plus this log) is over the ~800 guideline: 509 lines are the two runbooks and 308
the workflow. This is already the second half of the split the prompt prescribes.

## 5. Diagram

[`docs/diagrams/006-deploy-pipeline.svg`](../diagrams/006-deploy-pipeline.svg) (source
[`006-deploy-pipeline.json`](../diagrams/006-deploy-pipeline.json), 17 nodes): `container-ci`
on the pull request → merge to `main` → `deploy-staging` → GitHub OIDC token → Workload Identity
Federation → `gcp-deploy-staging` → Artifact Registry → migration job → API service → candidate
revision → smoke tests → promote, or keep the previous revision. A third lane shows what the job
and the API use: Secret Manager, Cloud SQL, Firebase public keys, and the Android app later.

## 6. Quality gate & test results

Run on `ci/006b-staging-deploy-workflow` at `4af4c99` (Windows 11, Docker 28.3.3):

| Command | Result |
| --- | --- |
| `pnpm typecheck` · `lint` · `format:check` · `build` · `db:check` | pass |
| `pnpm test` | pass: **205 tests, 20 files** (unchanged; no application code changed) |
| `pnpm openapi:check` · `pnpm openapi:lint` | pass; `contracts/` identical to `main` |
| `node scripts/container-smoke.mjs` | pass: **34 checks** (was 31; three new for `smoke.mjs`) |
| actionlint 1.7.12 + shellcheck 0.11.0 (Docker image), all five workflows | clean |
| `pnpm generate` · `pnpm check` in `tools/diagrams` | 17 nodes, no warnings · all 8 diagrams up to date |
| markdownlint-cli2 (46 files) · JSON validity (30 files) | 0 issues · all valid |
| gitleaks 8.30.1 | no leaks |
| `git grep -E 'saferoute-(stg\|prd)-'` on tracked files | no hits |
| Diff scan for local paths, personal e-mail, 12-digit numbers, service-account e-mails, `run.app` URLs | no hits |

**R8 verified again before shipping** (the file was not changed):

| Check | How | Result |
| --- | --- | --- |
| actionlint and shellcheck | Docker image `rhysd/actionlint:1.7.12` | clean |
| No `pull_request_target` | grep: the only match is the comment that forbids it | pass |
| Every action pinned by commit SHA | grep for `uses:` without a 40-hex SHA: none. Each SHA compared with its tag through `gh api`: `actions/checkout` v7.0.1, `actions/setup-node` v7.0.0, `google-github-actions/auth` v3.0.0, `google-github-actions/setup-gcloud` v3.0.1 all match | pass |
| Deploy job skips unless `STAGING_DEPLOY_ENABLED` is `'true'` | Read the job-level `if:` (one job; the variable is a repository variable, so it is readable before the environment is entered). `act` is not installed, so this was **not simulated** | pass by reading |
| No step prints secrets, the environment or `gcloud config` | Every `echo`/`printf` line read: digests, revision names, execution names and outcomes only. The access token goes to `docker login --password-stdin`. No `printenv`, `env`, `set -x`, `toJSON(secrets)` or `gcloud config` | pass |

**Smoke script pass and fail paths** (inside `container-smoke.mjs`, against the local
container): passes with the right version (exit 0); a deliberately wrong expected version exits
1; the URL never appears in its output. Run by hand earlier: no arguments → usage, exit 2; an
unreachable host → exit 1 with an error code only.

**Runbook command verification.** Nothing was executed against a Google Cloud project.

- Checked against saved `--help` output (SDK 587.0.0): `run deploy`, `run jobs deploy`,
  `run jobs execute` (`--args`, `--update-env-vars`, `--wait`), `run services describe`.
- Checked against Google's online `gcloud` reference (fetched 2026-10-02):
  `run services update-traffic` (`--to-revisions`, `--to-latest`), `run revisions list`,
  `run jobs describe`, `run jobs executions list`, `logging read` (`--freshness` default `1d`,
  `--limit`, `--order` default `desc`).
- Checked against `gh` 2.102.0 `--help`: `gh workflow run --ref`, `gh variable set`.
- Checked against documentation: rollback and `--to-latest` behaviour (a pinned revision stays
  pinned until traffic is moved again); per-execution overrides leave the job definition
  unchanged; resource types `cloud_run_revision` and `cloud_run_job` and their labels; `severity`
  is lifted out of the JSON while `message` stays in `jsonPayload`; query syntax (`:*`, `>=`,
  when values need quotes, quoted label keys); metric names for Cloud Run and Cloud SQL.
- Checked by reading the code: all pending migrations run in one transaction, so a failed
  migration job leaves the database unchanged; log field names; auth-failure reasons.
- Checked by running locally: PowerShell 5.1 passes `--args="a,b,c"`,
  `--to-revisions="${REVISION}=100"` and the log filters intact, through a stand-in for both
  `gcloud.cmd` and `gcloud.ps1`. Plain double quotes inside an argument are dropped, which is
  why the filters use unquoted values and ``\`"`` where a quote is required.

**Not verifiable here** (first evidence is the first deploy and the rehearsal):

- the whole `deploy-staging` run, including the federation sign-in and every `gcloud` call;
- that `saferoute_app` may create the `drizzle` schema and the PostGIS extension on Cloud SQL
  (proven or disproven by the first migration execution);
- `--format` field paths in the runbooks and the `jq` paths in the workflow
  (`status.traffic`, `status.url`, `status.latestCreatedRevisionName`, the job's image path);
- the log label key `run.googleapis.com/execution_name` (marked in the runbook);
- how Cloud Logging treats the API's `timestamp` text field (section 9);
- that a second run for the same commit creates a new revision (needed for the rehearsal);
- console menu names.

**SOS failure matrix (Plan v7 §7.5):** not applicable. No SOS, live-location or contacts code.

## 7. Decisions & ADRs

No new ADR: [ADR 0007](../adr/0007-gcp-staging-topology.md) already fixes the deploy order,
identities and rollback strategy, and this prompt implements it.

- **One job instead of several.** The prompt says "every job has `if:`"; one job needs one
  sign-in, keeps step outputs (digest, revisions, URLs) out of job outputs, and makes "roll back
  if a later step failed" a plain `if: failure()`.
- **`github.ref == 'refs/heads/main'` in the job condition**, in addition to the federation
  condition, so a manual run from another branch is skipped instead of failing at sign-in.
- **The image is smoke-tested before it is pushed.** The exact image that will run on staging
  passes the same 34 checks as in `container-ci` first.
- **Deploy by digest, tag by commit SHA** (ADR 0007, decision 6).
- **Identifiers are environment secrets, URLs are masked, and `smoke.mjs` never prints its
  URL.** `GCP_PROJECT_NUMBER` is referenced only so that GitHub masks it.
- **`gcloud` is pinned to 587.0.0** in the workflow, the version the flags were checked against.
- **`DB_POOL_MAX=5` is set by the workflow** and guarded against the connection budget.
- **`saferoute-admin`'s own arguments are `--help`.** Executing the job without overrides can
  never change a role; the real arguments exist only on the execution that needs them.
- **Manual rollback starts by switching deploys off**, so an unrelated merge can't redeploy the
  bad commit while the fix is being written.
- **"Deployment impact" is a line inside "Changes"**, as the prompt words it, not a twelfth
  section: `CLAUDE.md` and `/ship-prompt` both refer to 11 sections.
- **The diagram puts the API service before the candidate revision**, as the prompt lists it,
  and says "this repo + main + staging only" for the federation condition, which is what the
  runbook configured (the prompt's label said "repo + main only").
- **No `gcloud` in the final session**, by Rahul's instruction, including `--help`.

## 8. Security & privacy notes

- No credentials in the repository, the image, the workflow or its logs. Sign-in is Workload
  Identity Federation; the one-hour token is passed to `docker login` on standard input.
- Public-repository threat: the workflow never uses `pull_request_target`, has
  `permissions: {}` at the top and only `id-token: write` + `contents: read` on the job, and
  checks out with `persist-credentials: false`. Forks and pull requests can't satisfy the
  federation condition (repository + `main` + `staging` environment).
- The deploy identity can't read the database secret or connect to the database (setup runbook,
  step 6); the workflow only names the secret.
- Runbooks warn never to put a secret in `--args`: job arguments are stored with the execution
  and in audit logs. `set-role` needs only a user UUID and a role.
- Logs: the observability runbook restates what is never logged and warns that log output and
  revision lists carry identifiers and must not be pasted publicly.
- Public repo check: only placeholders (`<REVISION_NAME>`, `<USER_UUID>`, `<REQUEST_ID>`),
  resource names that the setup runbook already publishes, and shell variables. No project IDs,
  numbers, service-account e-mails, URLs or local paths (section 6 scans).
- New personal data: none.

## 9. Known issues & risks

- **The deploy has never run.** A first run can fail on something only a real project shows.
  The workflow is built so that this is safe: it stops before traffic moves and names the
  revision that still serves.
- **`gcloud`'s own output may show the staging hostname in the public run log.**
  `gcloud run deploy` prints the service and candidate URLs when it finishes, before the
  workflow's `add-mask` lines run. The project number inside a URL is masked (it is a secret);
  a hostname without it is not. It is not a credential and the API is public by design, but it
  makes staging easy to find. Check the first run's log; hiding it is a follow-up.
- **The API's `timestamp` field may not set the log entry's time.** Google documents `time`
  (text) or `timestamp` (an object) as the recognised forms; the logger writes `timestamp` as
  text. If so, Cloud Logging uses its receive time, which is close enough for staging. Changing
  the logger is application behaviour and out of scope here.
- **The first rollback target is the placeholder** (Google's sample page). The rehearsal
  therefore needs a second run first.
- **Old rollback targets can disappear:** the registry keeps 10 images and deletes others after
  7 days (ADR 0007).
- `container-ci`'s `actionlint` job is still not a required check, so a broken workflow file
  could be merged (existing follow-up).
- PR size is over the guideline (section 4).

## 10. Follow-ups & prerequisites for next prompt

New in Notion (*Follow-ups*, source P006b):

- Rehearse the staging rollback and record the result (Plan v7 §3.4) — High, Rahul.
- Correct the runbooks after the first deploy: `--format` paths, the execution label key,
  anything that didn't match.
- Logger: write `time` so that Cloud Logging uses the application's timestamp.
- Decide whether to hide the staging hostname in `deploy-staging` logs.

Existing items this prompt moves: "Cloud Run egress… / DATABASE_URL format" and "Cloud SQL:
allow postgis…" stay *In progress* until the first deploy proves them. "Cloud Monitoring
dashboard and alert policies" stays open; the observability runbook lists its first metrics.

**Before P007 (`feat/007-android-app-shell`), Rahul must:**

1. Finish the post-merge checklist in section 11 and report the Actions result.
2. Install Android Studio (stable) with the Android API 36 SDK.
3. Create the project with the *Empty Activity* (Compose) wizard.
4. Decide the package name (permanent once published, Plan v7 §15.1) and confirm the display
   name "SafeRoute".

## 11. How Rahul can verify

**Before merging:**

1. Read the PR, the workflow and the two runbooks from top to bottom.
2. Check that `repo-checks`, `backend-ci`, `contracts-ci` and `container-ci` (both jobs) are
   green on the PR.
3. Optional, with Docker Desktop running: `cd backend`, `pnpm install`, `pnpm test` (205 passed),
   `node scripts/container-smoke.mjs` (34 checks passed).

**Post-merge checklist (R12).** The trigger path is a manual run (`workflow_dispatch`):

1. Confirm that the `staging` environment has its eight secrets and six variables
   (`gh secret list --env staging`, `gh variable list --env staging`), and that
   `STAGING_DEPLOY_ENABLED` is still `false`.
2. Squash and merge the PR. The push starts a `deploy-staging` run whose job is **skipped**:
   that is expected while the switch is `false`.
3. Turn the switch on and start the first deploy by hand:

   ```powershell
   gh variable set STAGING_DEPLOY_ENABLED --repo rahulchy960/SafeRoute --body "true"
   gh workflow run deploy-staging.yml --repo rahulchy960/SafeRoute --ref main
   ```

   (Or: Actions → deploy-staging → Run workflow → branch `main`.)
4. Watch the run: build → container smoke test → push → migration job → candidate → smoke →
   promote → smoke. Read the Summary table.
5. Open the service URL: `/health` and `/health/ready` answer 200, and `version` equals the
   merge commit's SHA.
6. `GET /v1/me` without a token answers 401.
7. Rehearse the rollback: [`docs/runbooks/rollback-staging.md`](../runbooks/rollback-staging.md),
   step 7. Do the one-time severity check in
   [`docs/runbooks/observability-staging.md`](../runbooks/observability-staging.md).
8. Check the budget alert. If staging won't be used this week, stop the Cloud SQL instance
   (setup runbook, step 11: switch off first, then `--activation-policy=NEVER`).
9. Record the results on the Notion P006b page and tell Claude Code the outcome of the run:
   green or red, the name of the failing step, and the error text **with IDs and URLs removed**.

Until step 9 the deployment is **UNVERIFIED**.

## 12. Learning notes

No Android code in this prompt. Deploy concepts used:

- **GitHub Actions workflow, job, step.** A workflow is a YAML file that GitHub runs on an
  event. It contains jobs (each on a fresh virtual machine) made of steps (commands or reusable
  actions). `if:` on a job decides whether it runs at all; a skipped job is not a failure.
  <https://docs.github.com/en/actions/get-started/understand-github-actions>
- **`workflow_dispatch`.** An event that means "a person pressed Run workflow". It lets the
  first deploy start when you choose instead of at the moment of a merge.
  <https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow>
- **Environment, secrets and variables.** A GitHub environment (`staging`) is a named set of
  secrets and variables with its own rules, such as "only from `main`". Secrets are masked in
  logs; variables are plain settings.
  <https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments>
- **Pinning an action by commit SHA.** `uses: owner/action@<40 hex characters>` runs exactly
  that code. A tag such as `v3` can be moved to different code later; a SHA can't.
- **Concurrency group.** Two deploys at once would fight over traffic. A concurrency group
  makes the second run wait for the first.
- **Candidate revision and traffic tag.** Cloud Run keeps every deploy as a revision.
  `--no-traffic --tag candidate` creates one that real users never reach but that has its own
  URL, so it can be tested first. Promotion and rollback only change which revision gets traffic.
  <https://cloud.google.com/run/docs/rollouts-rollbacks-traffic-migration>
- **Smoke test.** A few fast checks that answer "is it switched on and wired up?", not "is
  every feature correct?". Here: the right version runs, the database answers, and sign-in is
  enforced.
- **Cloud Run Job and execution.** A job is a container that runs to completion instead of
  serving requests. Each run is an execution; arguments can be overridden for one execution
  without changing the job. <https://cloud.google.com/run/docs/execute/jobs>
- **Roll forward instead of rolling the database back.** Code can go back to an older version
  in seconds. A database can't without losing what was written since, so schema changes only
  add things and mistakes are corrected by another change.
- **Structured logs and severity.** Writing each log line as JSON lets Cloud Logging filter by
  field (`jsonPayload.request_id`) and by level (`severity>=WARNING`) instead of searching text.
  <https://cloud.google.com/logging/docs/structured-logging>
- **p95 and p99 latency.** The time within which 95% or 99% of requests finish. Averages hide
  slow requests; these numbers show what the unluckiest users experience.

## Revision 2026-10-02 (P006d): deploy verified, rollback rehearsed

Added after the merge, from the public Actions logs and from the rehearsal Rahul ran. Sections
1–12 above are unchanged and describe what was known when the pull request was opened.

**The deploy path works.** Seven `deploy-staging` runs, in order:

| Run | Trigger | Result | What happened |
| --- | --- | --- | --- |
| 36938352924 | push (merge of PR #11) | skipped | The job condition was false: the switch was not `true` as a repository variable at that moment |
| 36938899601 | manual | skipped | Same condition. A job-level `if:` only sees repository variables, not environment ones |
| 36939495196 | manual | failed | Guard step: ten environment values were empty. No cloud call was made |
| 36948179956 | manual | failed | "Deploy the migration job": `gcloud` rejected the migration account value (`Unsupported service account`) |
| 36948774279 | manual | failed | "Deploy the migration job": `gcloud` rejected the Cloud SQL connection name (it must have the form `project:region:instance`) |
| 36949131456 | manual | **success**, 2 min 31 s | Migration execution `saferoute-migrate-8bqd7`; candidate `saferoute-api-00002-lin` replaced the placeholder `saferoute-api-00001-zfv` |
| 36950613989 | manual | **success**, 2 min 34 s | Migration execution `saferoute-migrate-8fhmj`; candidate `saferoute-api-00004-ced`; previous `saferoute-api-00002-lin` |

- Every failure stopped before a new API revision existed and before traffic moved, as designed.
  No rollback was ever needed.
- All three failures were setup values, not workflow defects. The workflow file is unchanged
  since PR #11. The setup script and the corrected runbook came with P006c (PR #12).
- In both green runs the smoke test passed on the candidate before any traffic moved and on the
  live service after promotion: `/health` 200 with version
  `fef283b342efeb0a86a5fce2b70530b175de39e6`, `/health/ready` 200, `/v1/me` without a token 401.

**What this settles from "Not verifiable here" (section 6):**

- the whole run, including the federation sign-in and every `gcloud` call: works;
- the database user can create the `drizzle` schema and the PostGIS extension on Cloud SQL: the
  first migration execution succeeded;
- the `jq` paths in the workflow (`status.traffic`, `status.url`,
  `status.latestCreatedRevisionName`): the previous revision, the candidate and both URLs were
  resolved;
- a second run for the same commit creates a new revision: it did.

Still open: the log label key for one job execution, how Cloud Logging treats the `timestamp`
field, and the `--format` paths of the job commands in the rollback runbook (steps 5 and 6).

**Known issue confirmed (section 9).** `gcloud run deploy` prints "The revision can be reached
directly at …" with the candidate URL before the workflow's masking lines run, so the staging
hostname is readable in the public log of both green runs. The project ID is masked. It is not
a credential. Hiding it needs a workflow change and stays a follow-up; the logs of those two
runs can be deleted in the Actions UI if the hostname should not stay public.

**Rollback rehearsed (Plan v7 §3.4): PASS** for the traffic rollback. Run by Rahul on
2026-10-02; the full record is in
[`docs/runbooks/rollback-staging.md`](../runbooks/rollback-staging.md), step 7.

| Step | Result |
| --- | --- |
| Deploy switch off | done |
| Traffic to `saferoute-api-00002-lin` | 9 s; table showed it at 100% |
| Smoke test on the rolled-back service | passed |
| `--to-latest` back to `saferoute-api-00004-ced` | 6 s; table showed it at 100% |
| Smoke test after rolling forward | passed |
| Deploy switch on | done |

Not part of this rehearsal, and still open: one manual migration execution (step 5) and the
`saferoute-admin` job (step 6). Both revisions were built from the same commit, so the rehearsal
proves the traffic mechanics and the checks, not a return to older code or across a migration.

Post-merge checklist (section 11): items 1–6 are done and item 7's rollback part is done. The
one-time severity check (item 7), the budget check (item 8) and the two job steps remain.
