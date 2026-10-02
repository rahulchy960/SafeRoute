# P006c: idempotent staging setup script with audit and verify modes

| Field | Value |
| --- | --- |
| Prompt | P006 · GCP staging deployment, **part c** (follow-up to P006a/b after the first deploy failed) |
| Milestone | M1 (depends on P006b, merged as `6e39ba4`) |
| Branch | `ci/006c-staging-setup-script` |
| PR title | `ci(deploy): idempotent staging setup script with audit and verify modes [P006c]` |
| Notion | [P006c row in the Prompt Log](https://app.notion.com/p/3ec07370772081aab870e45427b4bdea) |
| Date | 2026-10-02 |
| Plan refs | Plan v7 §13.1, §13.2, §17, §20; ADR 0007 |

> **The script has never been run against Google Cloud or GitHub, in any mode.** Claude Code has
> no credentials and must not run it. Everything below was verified with tests that replace
> `gcloud` and `gh`. The first real evidence is Rahul's `-Audit` run (section 11).

## 1. Objective

The first `deploy-staging` run failed because the one-time cloud setup had been done only partly
and under other names than the runbook assumed. Replace the error-prone manual steps with one
PowerShell script that:

- audits what exists (read-only);
- creates only what is missing;
- sets the GitHub environment secrets and variables the workflow reads;
- verifies the result against `.github/workflows/deploy-staging.yml`.

Also make the runbook and ADR 0007 match reality (R8, R9) and check the script in CI (R10).

**The prompt text arrived cut off** in the middle of section 8 ("…006c-staging-setup-script.md
(12"). Sections 1–7 were complete and were implemented. For documentation, diagram and the
final report, the standing rules in `CLAUDE.md` were used.

## 2. Context & prerequisites

- P006b merged (PR #11, `6e39ba4`). Three `deploy-staging` runs exist:
  - the push run and the first manual run were **skipped** (the switch was not visible to the
    job-level condition);
  - the second manual run **failed** at "Check configuration and the connection budget": ten
    environment values were empty. The guard runs before any cloud call, so nothing in the cloud
    was touched and no rollback was needed.
- Rahul's audit (names only) is in the prompt: the deploy account is `sa-deploy`, the instance
  is `saferoute-db`, the pool `github` exists, the provider was not found, only the secret
  `db-app-password` exists, and the Cloud Run service does not exist.
- Local tools: Windows PowerShell 5.1.26100. **PowerShell 7 is not installed**, Pester was 3.4
  and PSScriptAnalyzer was absent. Pester 6.2.0 and PSScriptAnalyzer 1.25.0 were unpacked into a
  temporary folder (SHA-512 checked against the PowerShell Gallery), not installed. PowerShell
  7.6.6 was run in a Linux container. Docker 28.3.3, Node 22.17.0, gh 2.102.0.
- `gcloud` was used for `--help` only (Google Cloud SDK 587.0.0).

## 3. Workflow executed

1. `/start-prompt P006c`: synced `main` (`6e39ba4`), tree clean, hooks active, PR #11 merged, no
   open PRs. Notion: P006b → Merged; new row P006c. `git switch -c ci/006c-staging-setup-script`.
2. Read the failed run's public log with `gh run view --log-failed` to see exactly what the
   guard reported.
3. Saved `--help` for the 29 `gcloud` commands the script uses and read `gh secret|variable
   set|list --help`. Re-checked GitHub's OIDC claim names (`repository`, `ref`, `environment`).
4. `aca404b`: the script, its Pester tests and the analyzer settings.
5. `6bf6ca0`: `infra-ci.yml` and `Invoke-InfraCheck.ps1`.
6. `a023dc4` runbook (R8), `e1cdea9` ADR note (R9), `a0d0391` diagrams, `CLAUDE.md`, infra README.
7. `3c5d6c7`: three fixes from a review against R2, R3 and R5.
8. Quality gate, this log, `/ship-prompt`.

The script was run by Claude Code **only** in `-Plan` mode, and with `gcloud` and `gh` removed
from `PATH`, to check the real entry point: exit code 0, every command listed, nothing started.

## 4. Changes

| Area | Files | What |
| --- | --- | --- |
| Script (R1–R7) | `infra/staging/bootstrap-staging.ps1` | Modes below. One wrapper (`Invoke-External`) starts every `gcloud`/`gh` process; one function (`Write-Line`) prints, and masks |
| Tests (§6) | `infra/staging/tests/bootstrap-staging.Tests.ps1`, `tests/fixtures/workflow-sample.yml` | 87 Pester tests; `gcloud` and `gh` answered from scenario data with fake identifiers |
| Gate | `infra/staging/tests/Invoke-InfraCheck.ps1`, `infra/staging/PSScriptAnalyzerSettings.psd1` | Pinned, checksum-verified modules; analyzer + tests; exit code |
| CI (R10) | `.github/workflows/infra-ci.yml` | Jobs `infra-ci` (Linux, PowerShell 7) and `infra-ci-windows` (5.1 and 7). `repo-checks.yml` unchanged |
| Runbook (R8) | `docs/runbooks/gcp-staging-setup.md`, `rollback-staging.md`, `observability-staging.md`, `README.md` | Script as the primary path; real names; variable-level lesson; troubleshooting |
| ADR (R9) | `docs/adr/0007-gcp-staging-topology.md` | Dated note; accepted text unchanged |
| Docs | `CLAUDE.md`, `infra/README.md` | Deployment rules, "Infra scripts" gate row |
| Diagrams | `docs/diagrams/006c-staging-setup-flow.*`, `006-deploy-pipeline.*` | Section 5 |

**Modes.**

- `-Audit` (default): read-only. Table of item · expected · found · status, then one next action
  per open item. Statuses: `PRESENT`, `MISSING`, `WRONG`, `WRONG-EXTRA` (a role beyond the list;
  reported, never removed), `NOTE` (optional or informational), `UNKNOWN` (a read failed).
- `-Apply`: asks for the project ID, then `y/n` per step; stops at the first error. In
  dependency order: enable missing APIs → create the provider → create the database URL secret
  → deploy the placeholder service → add missing IAM bindings → optionally set the registry
  cleanup policy.
- `-SetGithubSecrets`: derives the names from the workflow file, sets what is missing on the
  `staging` environment (values on standard input), keeps existing values unless `-Force`.
  Creates the repository switch as `false` only if it doesn't exist.
- `-Verify`: audit plus GitHub names and locations. Exit code 0 only if nothing is `MISSING`,
  `WRONG` or `UNKNOWN`.
- `-Plan`: prints every command of the chosen mode and runs nothing. `-ShowIds`: unmasked output.

**Never created or changed:** the Cloud SQL instance, database and user; any existing secret;
service accounts; the Workload Identity pool; the registry. A missing one produces the runbook
step to follow. Nothing is deleted or renamed. No key is created.

**No change** to `deploy-staging.yml`, the Dockerfile, the application, the contract or the
database schema. **API contract diff:** none. **Migrations:** none.

**PR size:** 5,425 added lines, of which 2,704 are generated diagram files. The remaining 2,721
are far over the ~800 guideline: 1,330 the script, 830 the tests, about 250 the runbook. The
script and its tests belong in one PR (neither is reviewable without the other); the runbook,
ADR and CI could have been a separate PR and were kept here because the prompt defines one PR.

## 5. Diagram

- New: [`docs/diagrams/006c-staging-setup-flow.svg`](../diagrams/006c-staging-setup-flow.svg)
  (13 nodes): refuse production → audit → apply → set GitHub secrets → verify → Rahul enables
  deploys; what is created, what is only checked, and where the values go.
- Updated: [`006-deploy-pipeline.svg`](../diagrams/006-deploy-pipeline.svg): the deploy account
  label is `sa-deploy`.

## 6. Quality gate & test results

Run on `ci/006c-staging-setup-script` at `3c5d6c7`:

| Command | Result |
| --- | --- |
| `Invoke-InfraCheck.ps1`, Windows PowerShell 5.1.26100 | PSScriptAnalyzer **0 findings**; Pester **87 passed**, 0 failed, 0 skipped |
| `Invoke-InfraCheck.ps1`, PowerShell 7.6.6 (Linux container) | PSScriptAnalyzer **0 findings**; Pester **85 passed**, 0 failed, 2 skipped (Windows-only) |
| PowerShell 7 **on Windows** | **not tested locally** (not installed); covered by the `infra-ci-windows` job on the PR (results below) |
| actionlint 1.7.12 + shellcheck, all six workflows | clean |
| `tools/diagrams`: `pnpm generate` · `pnpm check` | no warnings · 9 diagrams up to date |
| markdownlint-cli2 (47 files) · JSON validity (32 files) | 0 issues · all valid |
| gitleaks 8.30.1 | no leaks |
| `git grep -E 'saferoute-(stg\|prd)-'` · diff scan for local paths, e-mails, service-account addresses, 12-digit numbers | no hits (the fixtures' fake number is twelve zeros) |

Backend, contracts and container gates: not applicable, no file under `backend/` or `contracts/`
changed. **SOS failure matrix (Plan v7 §7.5):** not applicable.

**CI on PR #12** (first run of `infra-ci`, at `1c64336`), all with 0 analyzer findings:

| Job and step | Host | Pester |
| --- | --- | --- |
| `infra-ci` | PowerShell 7.6.6, Ubuntu | 85 passed, 2 skipped (Windows-only) |
| `infra-ci-windows`, first step | Windows PowerShell 5.1.26100 | 87 passed |
| `infra-ci-windows`, second step | PowerShell 7.6.6, Windows | 87 passed |

`actionlint`, `container-ci` and `repo-checks` passed too. So the `.cmd` path and the exact-bytes
standard input are proven on both PowerShell versions on Windows.

**What the tests cover (§6 of the prompt):**

- Audit table for (a) the real findings, (b) a fully configured project, (c) a missing pool;
  plus wrong settings (open network, version, backups, deletion protection, SSL), several
  databases, extra roles, a provider under another name, a soft-deleted provider.
- `-Apply`: exactly 13 commands for the real findings, in order; **none** when everything is
  present; only the missing APIs; never the pool, an account, the registry or Cloud SQL; an
  existing URL secret is not touched; wrong project ID typed → nothing; all answers `n` →
  nothing; stops at the first error; the organization-policy message.
- Roles granted are exactly the six in ADR 0007, never on the Firebase or worker account.
- Production guard (`prd`, `prod`) in all four modes; the refused ID is not printed.
- Masking by default in all four modes; identifiers only with `-ShowIds`; local paths as `<repo>`.
- Secret value: absent from output, from a recorded transcript and from an error message that
  echoes it; absent from every command line; the stdin bytes equal the expected URL exactly.
- URL encoding of `@ : / % #`, space, reserved punctuation and a non-ASCII character.
- The stdin helper against a real `node` child process: byte count and hex, no line break, no
  byte-order mark; and through a `.cmd` wrapper, as `gcloud.cmd` needs.
- Provider condition: 3 accepted forms, 10 rejected ones.
- Names derived from a fixture workflow and from the real `deploy-staging.yml`; every derived
  name has a value in the script.
- `-Verify` exit codes: 0 when complete; 1 for a missing secret, a secret stored as a variable,
  a variable stored as a secret, the switch on the environment, the switch `true` while
  incomplete, a wrong variable value, a secret name that differs from the workflow, a missing
  environment.
- `-Plan` starts no process in any mode.

**A real defect the tests found.** On Windows PowerShell 5.1 the child process received a UTF-8
byte-order mark before the value: .NET Framework builds the child's input writer from
`Console.InputEncoding` and writes its preamble when the process starts. It would have put three
stray bytes at the start of the database URL and of every GitHub secret. Fixed (the console
encoding is swapped for the duration of `Start()`), and the test now proves exact bytes on both
PowerShell versions.

**Command verification.** Nothing was executed against a project.

- Checked against saved `--help` (SDK 587.0.0), command and every flag used: `config get-value`,
  `projects describe | get-iam-policy | add-iam-policy-binding`, `services list | enable`,
  `iam service-accounts list | get-iam-policy | add-iam-policy-binding`,
  `iam workload-identity-pools describe`, `… providers list (--show-deleted) | create-oidc`,
  `artifacts repositories describe | get-iam-policy | add-iam-policy-binding |
  set-cleanup-policies`, `sql instances describe`, `sql databases list`, `sql users list`,
  `secrets describe | create (--data-file=-) | versions list | versions access | get-iam-policy |
  add-iam-policy-binding`, `run services describe | get-iam-policy | add-iam-policy-binding`,
  `run deploy`.
- Checked against `gh` 2.102.0 `--help`: `secret set` and `variable set` read the value from
  standard input when `--body` is absent; `secret list` and `variable list` accept `--json`.
- Checked against documentation: GitHub OIDC claim names and issuer; the sample image path
  `us-docker.pkg.dev/cloudrun/container/hello` (in `gcloud run deploy --help`); the note that a
  domain-restriction organization policy blocks unauthenticated access.

**Not verifiable here:**

- the JSON field names of real `gcloud` output (`state`, `attributeCondition`, `oidc.issuerUri`,
  `settings.ipConfiguration.sslMode`, `cleanupPolicies`, `bindings`): taken from the API
  references, represented by fixtures. A mismatch shows as `UNKNOWN` or an empty "found" value;
- the wording of `gcloud`'s "not found" errors, which decides between `MISSING` and `UNKNOWN`;
- that `gcloud run deploy --allow-unauthenticated` exits 0 when an organization policy refuses
  the public binding (the script checks the policy afterwards either way);
- that the password in `db-app-password` belongs to the discovered database user (the script
  says so; the migration job proves it);
- `gh` reading a secret value from a pipe exactly as sent.

## 7. Decisions & ADRs

No new ADR; a dated note was added to [ADR 0007](../adr/0007-gcp-staging-topology.md).

- **One script file, loaded by the tests through dot-sourcing.** The prompt asks for one script;
  a guard at the end keeps the entry point from running when it is dot-sourced.
- **`-Apply` follows R4 literally.** A missing pool, service account or registry is not created,
  although creating a pool would be harmless: R4 lists what may be created, and consistency is
  safer than judgement in a script that holds owner rights.
- **Order by dependency, not by R4's listing:** the URL secret is created before the bindings on
  it, and the placeholder before its public binding.
- **`-Plan` runs nothing at all, reads included.** It therefore lists every command a mode could
  run. The state-aware preview is the audit itself, which names each next action.
- **Statuses beyond the three in the prompt:** `WRONG-EXTRA` (from R3), `NOTE` and `UNKNOWN`.
  Only `MISSING`, `WRONG` and `UNKNOWN` make `-Verify` fail.
- **An authorized network other than `0.0.0.0/0` is a `NOTE`,** because R3 only forbids the open
  one; ADR 0007 expects none, and the note says so.
- **The repository switch is created as `false` if absent, never changed.** Without it `-Verify`
  could never pass on a fresh repository; creating it switched off can't start a deploy.
- **Variable values are compared, secret values can't be.** `-Verify` flags `API_SERVICE` or
  `GCP_REGION` when they differ from what was audited.
- **`Write-Host` for output:** Windows PowerShell 5.1 transcripts record it and drop
  `Write-Information`. The analyzer rule is suppressed on that one function with the reason.
- **One analyzer rule excluded** (`PSAvoidUsingPositionalParameters`, Information level), in a
  committed settings file with the reason. Everything else runs at defaults.
- **The script is pure ASCII.** Windows PowerShell 5.1 reads a file without a byte-order mark
  as ANSI; a test enforces it.
- **`infra-ci-windows` goes beyond R10's "on ubuntu".** The script is run on Windows, and that
  job is the only place PowerShell 7 on Windows and the `gcloud.cmd` path are tested together.
- **Stale names fixed outside the setup runbook too** (rollback and observability runbooks, the
  pipeline diagram): the rollback runbook's `$SQL_INSTANCE` would otherwise fail.
- **Pester 6.2.0** (current) rather than 5.x; pinned with PSScriptAnalyzer by SHA-512.

## 8. Security & privacy notes

- No credentials, project IDs, numbers, e-mail addresses or URLs in the diff. Fixtures use
  `example-staging-000`, `000000000000`, `owner@example.com` and `fake-password`.
- The script grants only the roles ADR 0007 lists, never widens the provider condition (it
  reports a permissive one as `WRONG` and leaves it), never enables public database access,
  never creates keys and never touches the Firebase Admin SDK account, the default compute
  account or `sa-worker-runtime`.
- The database password is read into memory, never printed, never written to disk, never passed
  as an argument, and removed from the script's structures when the step ends.
- Values go to `gcloud` and `gh` on standard input, byte-exact.
- Public Cloud Run access is intentional and stated in the script, the runbook and the ADR: the
  API checks Firebase tokens itself (ADR 0006). An organization policy that refuses it is
  reported, not worked around.
- The tests can't start the real `gcloud` or `gh`: the test file refuses to resolve them even if
  a mock were missing.
- `infra-ci` has `contents: read`, no secrets and no cloud login; safe for forked pull requests.
- New personal data: none.

## 9. Known issues & risks

- **Unproven against the real project.** The first `-Audit` is read-only and will show quickly
  whether field names and error texts match (section 6, "Not verifiable").
- **The password secret may not match the database user.** Nothing can check that without a
  database connection. A failed migration job with `password authentication failed` means the
  URL secret must be replaced by hand (runbook step 5, "Rotating the password later"), because
  the script never changes an existing secret.
- **Database and user are unknown.** If the instance has more than one of either, the audit asks
  for `-DbName` / `-DbUser`.
- **`STAGING_DEPLOY_ENABLED` was `true` on the repository when the failed run started** (a
  skipped job would not have reached the guard). If it still is, a merge that touches
  `backend/**` starts a deploy that fails at the guard while the setup is incomplete. Harmless,
  but `-Verify` reports it as `WRONG`.
- PR size (section 4).

## 10. Follow-ups & prerequisites for next prompt

New in Notion (*Follow-ups*, source P006c):

- Run the setup script on staging and report the audit (Rahul) — High.
- Correct the script after the first real audit if field names or error texts differ.
- The deploy guard does not check `GCP_WIF_PROVIDER`, `GCP_DEPLOY_SA` or `GCP_PROJECT_NUMBER`;
  a missing one fails later, at sign-in. Workflow change, out of scope here.
- Make `infra-ci` a required check together with `container-ci` (existing item extended).

Still open from P006b: rehearse the rollback; correct the runbooks after the first deploy; the
logger `time` field; the staging hostname in deploy logs.

**Before P007, Rahul must** finish section 11, get one green `deploy-staging` run, and do the
P006b post-merge checklist (health checks, rollback rehearsal). The Android prerequisites are
unchanged: Android Studio (stable) with API 36, an Empty Activity (Compose) project, the package
name and the display name "SafeRoute".

## 11. How Rahul can verify

**Before merging:**

1. Read the PR; skim `bootstrap-staging.ps1` from "Apply" downwards.
2. Check that `infra-ci`, `infra-ci-windows`, `actionlint` and `repo-checks` are green.
3. Optional, locally (needs Node; installs nothing):
   `powershell -NoProfile -ExecutionPolicy Bypass -File infra\staging\tests\Invoke-InfraCheck.ps1`
   → `PSScriptAnalyzer: 0 finding(s)` and `Pester: 87 passed`.

**After merging,** in a PowerShell window at the repository root, signed in to `gcloud` (staging
project active) and `gh`:

1. `.\infra\staging\bootstrap-staging.ps1 -Apply -Plan` — read what it could run. Nothing runs.
2. `.\infra\staging\bootstrap-staging.ps1` — the audit. **Paste its output to Claude Code** (it
   hides identifiers) if anything is `UNKNOWN` or looks wrong, before going on.
3. Switch deploys off while the setup is incomplete:
   `gh variable set STAGING_DEPLOY_ENABLED --repo rahulchy960/SafeRoute --body "false"`
4. `.\infra\staging\bootstrap-staging.ps1 -Apply` — type the project ID, answer each step.
5. `.\infra\staging\bootstrap-staging.ps1 -SetGithubSecrets`
6. `.\infra\staging\bootstrap-staging.ps1 -Verify` — must end with `VERIFY: OK`.
7. Switch deploys on (`--body "true"`), then Actions → deploy-staging → Run workflow → `main`.
8. Report the run's result. If the migration job fails with `password authentication failed`,
   the password secret did not belong to that database user.

## 12. Learning notes

No Android code in this prompt. Concepts used:

- **Idempotent script.** Running it twice gives the same result as running it once: it looks
  first and only adds what is missing. That is what makes it safe to rerun after a failure.
- **Audit, plan, apply.** Separating "look" from "change" lets you read what will happen before
  anything happens. Terraform works the same way (`plan`, `apply`); this script is a small,
  hand-written version of that idea for a dozen resources.
- **Repository variables and environment variables (GitHub Actions).** A variable on an
  environment is visible only after a job has entered that environment. The `if:` that decides
  whether the job runs at all is evaluated earlier, so it can only read repository variables.
  <https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-variables>
- **Secrets versus variables.** `secrets.X` and `vars.X` are separate stores. A name saved in the
  wrong one reads as empty, with no error.
- **Standard input.** A program can receive data through a pipe instead of as an argument.
  Arguments are visible in process lists and logs; standard input is not, which is why secret
  values are sent that way.
- **Byte-order mark and trailing newline.** Invisible bytes that tools add at the start or end
  of text. In a password or a URL they change the value. The script writes exact bytes and a
  test counts them.
- **Percent-encoding.** Characters such as `@`, `:` and `/` have a meaning inside a URL, so a
  password containing them is written as `%40`, `%3A`, `%2F`.
- **Mock.** A stand-in for something a test can't use for real (here `gcloud` and `gh`). The
  test decides what the stand-in answers and records what it was asked to do.
  <https://pester.dev/docs/usage/mocking>
- **Static analysis (PSScriptAnalyzer).** A tool that reads a script without running it and
  reports risky or unclear constructs.
  <https://learn.microsoft.com/powershell/utility-modules/psscriptanalyzer/overview>
- **Windows PowerShell 5.1 and PowerShell 7.** Two different programs (`powershell.exe`, built
  into Windows, and `pwsh`, installed separately). Scripts can behave differently; this one is
  tested on both.
