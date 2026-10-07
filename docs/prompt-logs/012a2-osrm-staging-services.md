# P012a2: OSRM staging services (deploy workflow, setup script, log exclusion)

| Field | Value |
| --- | --- |
| Prompt | P012 · second half of part a (P012a images and measurements, **P012a2 staging services**, P012b routing API, P012c Android directions) |
| Milestone | M4 (depends on P012a, merged as `12e80e5`) |
| Branch | `feat/012a2-osrm-staging-services` |
| PR title | `feat(routing): private OSRM staging services, setup script and log exclusion [P012a2]` |
| Notion | [P012a2 row in the Prompt Log](https://app.notion.com/p/3f207370772081cf976dd3bbe8c588fe) |
| Date | 2026-10-07 |
| Plan refs | Plan v7 §12.2, §13.1, §13.2; addendum v7.2 §D; ADRs 0007, 0019, 0020 |

> **Nothing was run against Google Cloud.** Claude Code ran no cloud command, did not run
> `bootstrap-staging.ps1` in any mode and set no GitHub secret or variable. The services do
> not exist until Rahul follows section 5 of the runbook.
>
> **Not verified:** that Cloud Logging accepts the exclusion filter as written; that
> `--no-allow-unauthenticated` works with the deploy account's rights; that a GitHub runner can
> build the state graph; every staging measurement. The Pester tests ran on Windows PowerShell
> 5.1 only (PowerShell 7 is not installed on this PC; CI runs both).

## 1. Objective

Give the two private OSRM services decided in ADR 0020 a deploy path and a safe setup: a
manual workflow, a setup script that creates only what is needed and keeps the services
private, request logs that never store coordinates, and a runbook with the exact commands in
order and what each costs.

## 2. Context & prerequisites

- P012a merged (PR #29, `12e80e5`); no open pull requests; clean tree; hooks active.
- **The decision is recorded:** ADR 0020 is Accepted on `main`: the whole state, two private
  Cloud Run services (walking, driving), 1 vCPU and 2 GiB each, scale to zero, ceiling US$30
  a month for both.
- Name and branch: the ones proposed in the P012a log; Rahul's message named neither.

## 3. Workflow executed

1. `/start-prompt`: main synced, PR #29 confirmed merged, Notion P012a set to Merged, branch,
   Notion page.
2. `bootstrap-staging.ps1`: routing audit and apply steps; Pester fakes and 11 new tests.
3. `.github/workflows/osrm-staging.yml`; `infra-ci` path filter (`560796c`).
4. Runbook sections 4 to 8, ADR 0020 implementation note, setup runbook row, READMEs, diagram.
5. `/ship-prompt`.

`gcloud` flags were checked against the local help of version 587.0.0 (read-only `--help`):
`iam service-accounts create --display-name`, `logging sinks describe`,
`logging sinks update --add-exclusion` (keys `name`, `filter`), `run services get-iam-policy
--region`, `run services update --min-instances`, `run revisions list --service --limit`, and
for `run deploy`: `--no-allow-unauthenticated`, `--service-account`, `--cpu`, `--memory`,
`--concurrency`, `--timeout`, `--min-instances`, `--max-instances`, `--cpu-boost`,
`--set-env-vars`.

## 4. Changes

| Area | What |
| --- | --- |
| `.github/workflows/osrm-staging.yml` | New. `workflow_dispatch` only (profile, extract URL, extract date, minimum instances). Gated on repository, `main` and `STAGING_DEPLOY_ENABLED`; environment `staging`; keyless login as in `deploy-staging`. Order: build → smoke test → login → push `osrm-<profile>-<date>` → deploy private as `sa-osrm-runtime` → a call without credentials must get 403 → summary without identifiers |
| `infra/staging/bootstrap-staging.ps1` | New `Routing:` audit rows and `-Apply` steps: create `sa-osrm-runtime` (no roles), `serviceAccountUser` for `sa-deploy` on it, one request-log exclusion per OSRM service, `run.invoker` for `sa-api-runtime` once a service exists. Three new parameters for the names |
| `infra/staging/tests/` | 11 new tests, 2 adjusted (the real-findings apply now has 17 commands; the "never creates an account" test allows exactly the OSRM one) |
| `.github/workflows/infra-ci.yml` | Also runs when `osrm-staging.yml` changes (a test reads its names) |
| Docs | Runbook `routing-capacity-staging.md` sections 4 to 8 (budget table, deploy commands with costs, measuring, log-privacy check, warm-up and rollback); ADR 0020 implementation note; a row in `gcp-staging-setup.md`; READMEs; diagram |

No backend, contract, Android or migration change. `deploy-staging.yml` is untouched.

**Deviations from the prompt:**

- **Invoker rows are PENDING, not MISSING, while a service does not exist.** MISSING would
  make `-Verify` report staging as not ready and tell Rahul to switch deploys off, while the
  workflow that creates the services needs them on. PENDING is listed with its next action
  and does not fail `-Verify`.
- **The script creates one service account.** Until now it created none. `sa-osrm-runtime` is
  new, holds no role and is asked for by the prompt; existing accounts are still never
  changed. The script's help text and a test say so.
- **The exclusion covers entries with a request URL, not all logs of the services**, so
  start-up errors stay visible. The image writes no request line (smoke-tested).
- **Service and account names are workflow constants**, not GitHub variables: no new secret
  or variable is needed, and a test compares them with the script's defaults.
- **The service URLs are not stored anywhere yet.** Making them GitHub environment secrets is
  P012b's precondition (B0).
- **No hadolint or shellcheck change**: no Dockerfile or shell file changed; the workflow's
  shell is checked by actionlint.

## 5. Diagram

[`docs/diagrams/012a-routing-infra.svg`](../diagrams/012a-routing-infra.svg), updated: the
setup step ("first"), the manual workflow on the push arrow, and the group label.

## 6. Quality gate & test results

| Command | Result |
| --- | --- |
| `Invoke-InfraCheck.ps1`, Windows PowerShell 5.1 | PSScriptAnalyzer 0 findings; Pester **98 passed, 0 failed** (87 before) |
| `Invoke-InfraCheck.ps1`, PowerShell 7 | not run locally (not installed); `infra-ci` runs it on Linux and Windows |
| actionlint 1.7.12 (all workflows, includes shellcheck of `run:` steps) | clean |
| Deploy-workflow rules | no `pull_request_target`; 4 actions, all pinned by SHA; job gated on `STAGING_DEPLOY_ENABLED`; no step prints secrets, the environment or `gcloud config` |
| `pnpm generate` · `pnpm check` in `tools/diagrams` | 17 diagrams, up to date |
| markdownlint-cli2 · JSON validity · gitleaks 8.30.1 | 86 files, 0 errors · 54 files valid · no leaks |

What the new tests prove (all with a mocked `gcloud`):

| Test | What it proves |
| --- | --- |
| Audit of a project without routing | account, binding and both exclusions MISSING; services and invoker PENDING; no command that changes anything |
| Verify with services not deployed | PENDING does not fail `-Verify` |
| Apply, first run | exactly four commands in order: create the account, the binding, two exclusions; `LATER:` for the invoker; exit 0 |
| Apply never widens | no project-level binding for the OSRM account; no command contains `allUsers`, `allAuthenticatedUsers` or `allow-unauthenticated` |
| Apply after the deploy | exactly two commands: `run.invoker` for `sa-api-runtime` on each service |
| Second run | changes nothing |
| A public OSRM service | reported WRONG, `-Verify` exits 1, the script issues no command |
| A role on the OSRM account | reported `WRONG-EXTRA` |
| A disabled or rewritten exclusion | reported WRONG, not changed |
| The filter | no comma, double quote or percent sign |
| Names | the workflow's constants equal the script's defaults |

**What these tests cannot see:** Google Cloud. They prove which commands the script would
run, not that the cloud accepts them. That the platform log then holds nothing is Rahul's
query (runbook section 7).

**SOS failure matrix (Plan v7 §7.5):** not applicable.

## 7. Decisions & ADRs

No new ADR. [ADR 0020](../adr/0020-routing-osrm.md) got an "Implementation note of
2026-10-07 (P012a2)"; its decision is unchanged.

Confirmations asked for by the prompt:

- **No cloud commands were run by Claude Code.** Only local `gcloud … --help`.
- **Nothing public.** The workflow deploys with `--no-allow-unauthenticated` and fails unless
  an unauthenticated call gets 403; the script never binds `allUsers`, flags a public OSRM
  service as WRONG, and has tests for both.
- **OSRM request logs excluded**, and before the first deploy: the runbook puts `-Apply`
  ahead of the workflow.
- **Budget ceiling stated and checked:** runbook section 4. US$30 a month for both; the
  deployed setting (scale to zero) is about US$0 to 2; one warm service US$19.71; both warm
  US$39.42, which is over the ceiling and marked so. The arithmetic is written out there.

## 8. Security & privacy notes

- The extract is downloaded and built **before** the job holds any cloud credential.
- The deploy identity cannot change IAM policies; only Rahul's `-Apply` grants the invoker
  role, and only to the API's account.
- `sa-osrm-runtime` has no role: a compromised OSRM container can reach nothing in the
  project.
- The request used for the 403 check goes to `/` and carries no coordinate.
- The service URL is masked in the workflow; the summary has no project identifier.
- Until the exclusions are verified (runbook section 7), no route of a real user may be sent.
  No user can: the API has no routing endpoint before P012b.
- Public-repository check: no project identifier, URL, e-mail address or local path in the
  diff; test data uses the fake project `example-staging-000`.

## 9. Known issues & risks

- **The exclusion filter is unverified.** If `gcloud` or Cloud Logging rejects it, `-Apply`
  stops with FAILED and the runbook's console form is used instead.
- **`--no-allow-unauthenticated` with a deploy account that cannot set IAM policies** is
  unverified. The 403 check decides the outcome either way.
- **The runner may be too small** for the state graph (peak about 4 GB of 16 GB, a few GB of
  disk). If the first run fails there, the fallback is to build on Rahul's PC and push with
  his own credentials; that procedure is not written yet (follow-up).
- Cold start on Cloud Run is still unknown.
- After a rollback with `update-traffic --to-revisions`, a new deploy gets no traffic until
  `--to-latest` (the runbook says so).
- `-Verify` now needs `-Apply` once after this merge: until then four `Routing:` rows are
  MISSING and it reports "not ready".

## 10. Follow-ups & prerequisites for next prompt

New in Notion (*Follow-ups*, source P012a2):

- Run the routing setup and the two deploys on staging; report the results (High).
- Fallback: build the OSRM image on a PC and push it, if the runner is too small.

Closed: "Second half of P012a". Still open from P012a: measure on staging (High), and the rest.

**Next: P012b** (`feat/012b-routing-api`). Its preconditions (B0): this pull request merged;
both services deployed; `-Apply` run again so that `sa-api-runtime` is an invoker; the two
service URLs stored as GitHub environment secrets; the staging cold start recorded, because
it sets the API's timeout.

## 11. How Rahul can verify

1. Read the pull request and section 5 of
   [`docs/runbooks/routing-capacity-staging.md`](../runbooks/routing-capacity-staging.md).
2. `powershell -NoProfile -ExecutionPolicy Bypass -File infra\staging\tests\Invoke-InfraCheck.ps1`:
   0 findings, 98 passed.
3. CI green (`infra-ci`, `infra-ci-windows`, `actionlint`, `repo-checks`), then squash and
   merge. A merge deploys nothing.
4. Run the commands of runbook section 5 in order (audit, apply, workflow twice, apply,
   verify), then section 7 (log privacy) and section 6 (cold start and latency).
5. Report: did `-Apply` accept the exclusions; did both workflow runs end green; the 403
   line; the result of the log query; the cold start and p95.

## 12. Learning notes

No Android in this part. The ideas behind it:

- **Service account.** An identity for a program instead of a person. Giving the OSRM
  services their own account with no role means that, whatever happens inside that container,
  it cannot read a secret, a database or another service.
- **Invoker.** A private Cloud Run service answers only callers that hold the "invoker" role
  on it and prove who they are with a signed token. Here that is one caller: the API.
- **Why a 403 check.** Configuration can say "private" and still be wrong. Calling the
  service without credentials and requiring a refusal tests the result, not the intention.
- **Log exclusion.** A filter on the project's log router. Matching entries are never
  stored. It protects from the moment it exists, which is why it comes before the first
  deploy.
- **Idempotent setup.** Running the setup script twice changes nothing the second time. That
  makes it safe to rerun after each step, which is exactly how the deploy order uses it.
- **Manual workflow (`workflow_dispatch`).** A pipeline that runs only when a person starts
  it, with inputs. Right for something slow and rare like rebuilding a road graph.
