# P006a: container image, GCP staging setup runbook and container CI

| Field | Value |
| --- | --- |
| Prompt | P006 · GCP staging deployment, **part a of 2** (part b: deploy workflow, smoke tests, rollback and observability runbooks, pipeline diagram) |
| Milestone | M1 (depends on P005a/b; P005b merged as `9740997`) |
| Branch | `ci/006a-container-and-runbooks` |
| PR title | `ci(deploy): container image, GCP staging runbook and container CI [P006a]` |
| Notion | P006a row in the Prompt Log |
| Date | 2026-10-02 |
| Plan refs | Plan v7 §3.4, §4, §12.4, §13.1, §13.2, §14.5, §15.3, §17, §20 |

## 1. Objective

Take the backend from "runs on a laptop" to "ready to deploy", without Claude Code or GitHub
ever holding a credential. Part a delivers everything that needs no cloud access:

- R0: the Cloud SQL socket-form `DATABASE_URL` works with the config and with `pg`;
- R1: a hardened container image (`backend/Dockerfile`);
- R2/R3: a container smoke test that proves the image behaves like a deployable unit;
- R4: `container-ci`, which builds and smoke-tests the image on every pull request;
- R5: the one-time staging setup runbook that Rahul runs with `gcloud`;
- R6: ADR 0007; R7: README and `CLAUDE.md` updates.

Nothing cloud-side was attempted. P006b starts only after this PR is merged and Rahul reports
"runbook done".

## 2. Context & prerequisites

- P005a/b merged (PR #8 `98a2923`, PR #9 `9740997`). `main` clean, hooks active, no open PRs.
- Local tools: Docker 28.3.3 with buildx 0.27.0, Node 22.17.0 on the host (the image runs Node
  24.21.0), pnpm 12.8.1, gh 2.102.0, Google Cloud SDK 587.0.0 (used for `--help` only).
- The local `gcloud` failed to load in Git Bash because that shell finds Python 3.9 first. It
  was run with `CLOUDSDK_PYTHON` pointing at Python 3.12 for the `--help` calls. The runbook
  mentions this in step 0.
- The Plan PDF could not be opened in this session (no PDF renderer). The plan sections quoted
  in the prompt were used.
- The session was interrupted once after R4 was written. It resumed from the working tree;
  nothing was redone. Commits are now made per requirement.

## 3. Workflow executed

1. `/start-prompt`: synced `main`, checked the tree, hooks and merged PRs. Notion: P005b →
   Merged (`9740997`), P006 → In progress, new rows P006a (In progress) and P006b (Planned),
   follow-up "P005: reference firebaseBearer" → Done. `git switch -c ci/006a-container-and-runbooks`.
2. **R0 spike** (throwaway script, deleted): `new URL()` rejects
   `postgresql://u:p%40ss@/db?host=/cloudsql/demo:asia-south1:db` (credentials without a host),
   while `pg` 8.23.0 / `pg-connection-string` 2.14.0 parse it to host
   `/cloudsql/demo:asia-south1:db`, password `p@ss`. So `config.ts` needed a change. Node
   release index: 24.21.0 is the current Active LTS; `node:24.21.0-trixie-slim` exists.
3. Found a second blocker while testing R0: `node dist/db/migrate.js` with
   `NODE_ENV=production` exits 1 unless `FIREBASE_PROJECT_ID` is set. Fixed with a job config
   (§4, §7).
4. R1 Dockerfile and `.dockerignore`; built with `docker buildx build --load`.
5. R2 smoke script; 31 checks pass. A negative run (wrong expected SHA) exits 1 and leaves no
   containers or networks.
6. R4 workflow. actionlint was first added to `repo-checks.yml`; that was reverted (§7) and it
   now lives in `container-ci.yml`.
7. R5 runbook: every command checked (§6), PowerShell pieces run locally with fake values.
8. R6 ADR 0007, R7 docs, this log, `/ship-prompt`.

Commits on the branch (squashed by the merge): R0 `065ebb5`, R1 `fe8f3d9`, R2 `b772041`,
R4 `4f25a34`, R5 `c50bd64`, R6 `80d2656`, R7 `5109c6d`, then this log.

## 4. Changes

| Area | Files | What |
| --- | --- | --- |
| Config (R0) | `backend/src/config.ts` | `parsePostgresUrl()` accepts ordinary URLs and the socket form (only with an absolute `host` parameter) and never throws; `parseJobConfig()` validates like `parseConfig()` without the API's production requirements. The schema body only moved (whitespace) |
| Migration CLI | `backend/src/db/migrate.ts` | Uses `parseJobConfig`: the job needs `DATABASE_URL` only |
| Admin CLI | `backend/src/scripts/set-role.ts` | `maskDatabaseHost` uses `parsePostgresUrl`, so the socket form prints `(socket)` instead of failing |
| Image (R1) | `backend/Dockerfile`, `backend/.dockerignore` | Three stages (prod deps → build → runtime); `node:24.21.0-trixie-slim` by tag and digest; corepack pnpm; `dist/`, `drizzle/`, `package.json` only; `USER node`; `NODE_ENV=production`; OCI labels; `GIT_SHA`/`APP_VERSION` defaults from the `GIT_SHA` build arg. Allowlist `.dockerignore` |
| Smoke test (R2/R3) | `backend/scripts/container-smoke.mjs`, `backend/eslint.config.js` | Plain Node, no shell, no dependencies. ESLint now covers `.mjs` |
| CI (R4) | `.github/workflows/container-ci.yml`, `.github/actionlint.yaml` | Jobs `container-ci` and `actionlint`; `contents: read`; no secrets; SHA-pinned actions |
| Runbook (R5) | `docs/runbooks/gcp-staging-setup.md`, `docs/runbooks/README.md`, `infra/artifact-registry-cleanup-policy.json`, `infra/README.md` | Steps 0–12, troubleshooting, never-commit list |
| ADR (R6) | `docs/adr/0007-gcp-staging-topology.md`, `docs/adr/README.md` | §7 |
| Docs (R7) | `backend/README.md`, `CLAUDE.md` | "Running in a container"; "Deployment rules"; Container row in the quality gate |
| Tests | `backend/test/cloud-sql-url.test.ts`, `backend/test/config.test.ts` | §6 |

**Image facts.** Base `node:24.21.0-trixie-slim@sha256:8ec5d7557396cfe32d21c3f9c13072355ceab22b584578ca4bb28af31120cffe`
(multi-arch index; linux/amd64 manifest `sha256:b64fccfb…1697`). Final image: **86.0 MB** as
reported by `docker image inspect` (compressed layers; this is what a registry stores and a pull
downloads). The layers added on top of the base total 35.9 MB, of which 35.5 MB is
`node_modules` (8 direct production dependencies).

**API contract diff:** none (`contracts/` unchanged, version stays 0.2.0). **Migrations:** none.

**PR size:** 1,677 added lines, over the ~800 guideline. 576 are the runbook, 131 the ADR, 441
the smoke script and 128 tests. Production code changes are about 60 lines plus whitespace.
This is already the first half of the split the prompt prescribes.

## 5. Diagram

None in this part. The deploy-pipeline diagram (`docs/diagrams/006-deploy-pipeline.*`) is a
P006b deliverable, as the prompt specifies, because it draws the deploy workflow that P006b adds.

## 6. Quality gate & test results

Run on `ci/006a-container-and-runbooks` at `5109c6d` (Windows 11, Docker 28.3.3):

| Command | Result |
| --- | --- |
| `pnpm typecheck` · `lint` · `format:check` · `build` | pass |
| `pnpm test` | pass: **205 tests, 20 files** (was 195; 10 new) |
| `node dist/scripts/set-role.js --help` · `pnpm db:check` | pass |
| `pnpm openapi:check` · `pnpm openapi:lint` | pass; `contracts/` identical to `main` |
| `pnpm audit --prod` | no known vulnerabilities |
| `node scripts/container-smoke.mjs` | pass: **31 checks** |
| actionlint 1.7.12 + shellcheck 0.11.0 (Docker image) | clean |
| markdownlint-cli2 (42 files) · JSON validity (28 files) | 0 issues · all valid |
| gitleaks 8.30.1 (18 commits) | no leaks |
| `git grep -E 'saferoute-(stg\|prd)-'` on tracked files | no hits |
| Diff scan for local paths, personal e-mail, 12-digit numbers | no hits |

`container-ci` itself runs for the first time on this PR; its result is in the PR checks.

New tests:

- **Socket-form URL (7, `cloud-sql-url.test.ts`)**, fake values `u` / `p%40ss` /
  `demo:asia-south1:db`:
  - accepted by the config, in production too;
  - `pg.Client` built from `poolConfig()` gets host `/cloudsql/demo:asia-south1:db`, user `u`,
    database `db` and the decoded password;
  - `parsePostgresUrl` reports a socket (never the placeholder host); `maskDatabaseHost` prints
    `(socket)`;
  - three malformed host-less URLs are rejected with the fixed message and no echo;
  - connecting to the non-existent socket fails locally, and neither the error, its stack,
    `safeDbError` nor the JSON log line contains `p%40ss` or `p@ss`.
- **`parseJobConfig` (3)**: production without `FIREBASE_PROJECT_ID` is accepted where
  `parseConfig` rejects it; the `DATABASE_URL` presence check stays with the job; values are
  still validated without echo.

Container smoke test, 31 checks in order: image user, `NODE_ENV`, labels, default command;
contents (has `dist/`, `drizzle/`, production dependencies; has no `.env`, sources, tests or dev
dependencies); Node major equals `.nvmrc`; migrate twice (`count` 2, then 0) with no password in
the output, and exit 1 without `DATABASE_URL`; `set-role --help` and an unknown user against the
migrated database; baked and overridden `GIT_SHA`/`APP_VERSION`; **production rejects
`demo-saferoute`** with the guard message, and a missing project ID; `/health` 200 with
`version` equal to the `GIT_SHA` build arg; `/health/ready` 200; `/v1/me` 401 with
`WWW-Authenticate: Bearer` and `X-Request-Id`; non-root uid; SIGTERM → exit 0 in about 0.5 s;
JSON log lines with `severity`; no password in the logs.

**Runbook command verification.** Nothing was executed against a Google Cloud project.

Checked against the installed `--help` (Google Cloud SDK 587.0.0; command and every flag used):

- `gcloud auth login`, `config set`, `config get-value`, `projects describe`,
  `billing projects describe`, `services enable`, `services list`;
- `artifacts repositories create | describe | set-cleanup-policies | list-cleanup-policies |
  add-iam-policy-binding | get-iam-policy | delete`;
- `iam service-accounts create | list | add-iam-policy-binding | get-iam-policy | delete`;
- `sql instances create | describe | patch | delete`, `sql databases create | list`,
  `sql users create | list | set-password`;
- `secrets create | versions add | versions list | versions disable | add-iam-policy-binding |
  get-iam-policy | delete`;
- `projects add-iam-policy-binding | get-iam-policy | remove-iam-policy-binding`;
- `iam workload-identity-pools create | delete | undelete`, `… providers create-oidc | describe |
  delete | undelete`;
- `run deploy`, `run services describe | get-iam-policy | delete`.

Checked against `gh` 2.102.0 `--help`: `gh secret set --env --body`, `gh variable set`,
`gh secret list`, `gh variable list`, `gh api`, `gh auth status`.

Checked against current documentation (fetched on 2026-10-02):

- GitHub OIDC claims: `repository`, `ref` and `environment` exist; `environment` is present
  when the job references an environment; issuer URL;
- Google's GitHub federation guide: pool, provider and `principalSet://…/attribute.…` forms,
  the `workload_identity_provider` format;
- Cloud SQL: Enterprise Plus is the default for PostgreSQL 16, so `--edition=enterprise` is
  needed for shared-core tiers; Cloud Run connects through `/cloudsql/<connection name>` with
  `roles/cloudsql.client`, encrypted, no authorized networks; `max_connections` defaults to 25
  on the smallest tier; `cloudsqlsuperuser` membership for users created through Cloud SQL and
  its right to create extensions; PostGIS is supported (3.5.2 on PostgreSQL 16); stop and start
  with `--activation-policy`, storage and IP still billed while stopped;
- Artifact Registry: cleanup policy file format (`olderThan: "7d"`, `keepCount`,
  `--no-dry-run`), and that tagged images can't be deleted in an immutable-tag repository;
- GitHub REST: environment and deployment-branch-policy fields used in the step 9 verify.

Checked by reading the SDK source: `gcloud sql instances describe` reports `state` as `STOPPED`
when the activation policy is `NEVER`. Checked by running locally: gcloud's log files record
command arguments, and `CLOUDSDK_CORE_DISABLE_FILE_LOGGING=1` stops the log; `Send-Exact`
writes exactly the given bytes in PowerShell 5.1 (a plain pipe added a byte-order mark and a
line break on this machine); the password generator; argument quoting of the provider and
binding commands through a `.cmd` wrapper.

**Not verifiable here** (first real evidence is Rahul's run of the runbook):

- every command's behaviour against a real project, including permissions of the signed-in user;
- the `--format` field paths in the verify commands (API field names; `--help` doesn't list them);
- that `saferoute_app` can create the `drizzle` schema and tables on Cloud SQL PostgreSQL 16
  (expected through `cloudsqlsuperuser`; proven by the first migration job in P006b);
- the monthly cost estimate for `db-f1-micro` (pricing was not looked up) and the statement
  that shared-core tiers are outside the SLA;
- console labels (Billing → Budgets & alerts; GitHub Settings → Environments);
- the waiting periods in step 12 (instance-name reuse, 30-day soft delete of pools).

**SOS failure matrix (Plan v7 §7.5):** not applicable. No SOS, live-location or contacts code.

## 7. Decisions & ADRs

- **[ADR 0007](../adr/0007-gcp-staging-topology.md)** (Accepted): staging topology and deploy
  strategy, including the deviation from Plan v7 §13.1 (public IP with no authorized networks
  through the connector, instead of private IP).
- **Minimal config change for R0**, as the prompt allows: `parsePostgresUrl`. The socket form is
  accepted only with an absolute `host` parameter.
- **`parseJobConfig` (not in the prompt).** The image sets `NODE_ENV=production`, and the
  migration runner then demanded `FIREBASE_PROJECT_ID`. The alternative was to pass the Firebase
  project ID to the migration job; a job config is cleaner (the job serves no requests) and
  matches the job environment P006b specifies. API behaviour is unchanged.
- **Tags are not immutable in staging**, deviating from "immutable tags if available": the
  cleanup policy can't delete tagged images in an immutable repository, and a re-run for the
  same commit would be rejected. Deploys use the digest (ADR 0007, decision 6).
- **`GIT_SHA` and `APP_VERSION` are baked as defaults** from the build arg and can be overridden
  at runtime. That makes "`/health` version equals the build arg" true for the image by itself.
- **actionlint lives in `container-ci.yml`, not in `repo-checks.yml`.** `repo-checks` is the
  required check on `main`; it is unchanged. One existing shellcheck note in it (SC2016, a false
  positive) is ignored through `.github/actionlint.yaml`.
- **The smoke test is a Node script**, not bash: no path-conversion problems on Windows, and it
  needs no install step in CI. It reads the PostGIS image from `docker-compose.yml`, so there
  is no fourth copy of the digest.
- **Allowlist `.dockerignore`**: nothing enters the build context unless named.
- Source maps (`dist/**/*.js.map`) stay in the image. They hold file names and mappings, no
  source text and no secrets.

## 8. Security & privacy notes

- No credentials in the repository, the image or the logs. The image has no `.env`, sources,
  tests or dev dependencies (checked by the smoke test) and runs as uid 1000.
- The runbook keeps the database password in a shell variable for a few minutes, sends it to
  Secret Manager through a pipe, and switches off gcloud's local argument log first. It is
  verified with `versions list`, never `access`.
- Least privilege is listed binding by binding (runbook step 6). The deploy identity can't read
  the secret, connect to the database or change who may call the service.
- Public-repository threat: `container-ci` has `contents: read`, no secrets and no cloud login,
  so it is safe for forked pull requests. The federation condition (repository + `main` +
  `staging` environment) means forks and pull requests can't obtain Google tokens.
- Public repo check: only placeholders (`<PROJECT_ID>`, `<FIREBASE_PROJECT_ID>`), fake values
  (`demo:asia-south1:db`, `saferoute-ci-fake`, `smoke-only-password`) and shell variables. No
  project IDs, numbers, service-account emails, local paths or handles (§6 scans).
- New personal data: none.

## 9. Known issues & risks

- **The runbook has never been executed.** A flag may still behave differently than `--help`
  suggests. Each step has a verify command so a problem shows up where it happens.
- Cloud SQL ships PostGIS 3.5 for PostgreSQL 16; local and CI databases use 3.4.
- `db-f1-micro` allows 25 connections. Three API instances × pool 5 = 15, plus jobs: fine for
  staging, not for load tests.
- The base image digest is updated by hand.
- `backend/README.md` already had a duplicated "Production-style" block before this prompt. Not
  touched (out of scope).

## 10. Follow-ups & prerequisites for next prompt

Recorded in Notion (*Follow-ups*): production project with private IP, Direct VPC egress and
Cloud NAT; PITR and cross-region export (P020); minimum instances ≥ 1 in production; automated
base-image digest updates; Artifact Registry vulnerability scanning; Cloud Monitoring dashboard
and alerts; custom domain; `container-ci` as a required check and actionlint in `repo-checks`;
worker identity and service (P015); Terraform; rehearsal of the password rotation; IAM database
authentication; Cloud SQL connector enforcement; align the PostGIS version; duplicated README
block. The existing "DB roles and least privilege" item covers the role split.

Existing follow-ups this PR addresses (Done when it merges): "Ship backend/drizzle/ in the API
image; migration job command". Decided here and verified in P006b: "Cloud Run egress… /
DATABASE_URL format" and "Cloud SQL: allow postgis…".

**Before P006b, Rahul must:**

1. Merge this PR.
2. Run [`docs/runbooks/gcp-staging-setup.md`](../runbooks/gcp-staging-setup.md) step by step,
   checking each "Verify".
3. Tell Claude Code "runbook done" (without pasting any ID or URL).

## 11. How Rahul can verify

1. Read the PR, ADR 0007 and the runbook from top to bottom.
2. With Docker Desktop running:

   ```sh
   cd backend
   pnpm install
   pnpm test                          # 205 passed
   node scripts/container-smoke.mjs   # 31 checks passed; prints the image size
   ```

3. Optional: `docker run --rm saferoute-api:smoke id` prints `uid=1000(node)`.
4. Check that `container-ci`, `backend-ci`, `contracts-ci` and `repo-checks` are green on the PR.
5. Squash and merge, delete the branch, then follow "Before P006b" above.

## 12. Learning notes

- **Container image and Dockerfile.** An image is a packaged filesystem plus a start command:
  the operating-system files, Node, the compiled code and its dependencies. A Dockerfile is the
  recipe. The same image runs identically on a laptop, in CI and on Cloud Run.
  <https://docs.docker.com/get-started/docker-concepts/the-basics/what-is-an-image/>
- **Multi-stage build.** Compiling needs TypeScript and other tools that the running app does
  not. A multi-stage Dockerfile builds in one stage and copies only the results into a clean
  final stage, so the shipped image is smaller and has fewer things that could be attacked.
  <https://docs.docker.com/build/building/multi-stage/>
- **Pinning by digest.** A tag such as `node:24` can point to different contents tomorrow. A
  digest (`sha256:…`) names exact contents, so a build today and next month start from the same
  base.
- **Cloud Run.** Runs a container and scales the number of copies with traffic, down to zero.
  It sends SIGTERM before stopping a copy, which is why the API shuts down gracefully.
  <https://cloud.google.com/run/docs/overview/what-is-cloud-run>
- **Cloud SQL and its connector.** Cloud SQL is managed PostgreSQL. Instead of exposing the
  database to a network, Cloud Run mounts a unix socket (a file-like connection point) at
  `/cloudsql/…`; Google's connector behind it checks IAM permission and encrypts the traffic.
  <https://cloud.google.com/sql/docs/postgres/connect-run>
- **Service account and least privilege.** A service account is an identity for a program. Each
  one gets only the roles its job needs, so a mistake or a break-in in one place can't reach
  everything.
  <https://cloud.google.com/iam/docs/service-account-overview>
- **Workload Identity Federation versus a JSON key.** A key file is a password that works from
  anywhere until someone revokes it. With federation, GitHub proves "this job runs in this
  repository, on main, in the staging environment", and Google hands out a token that lasts an
  hour. There is nothing to leak or rotate.
  <https://cloud.google.com/iam/docs/workload-identity-federation>
- **Candidate revision, promotion and rollback.** Each deploy creates a new revision. Deploying
  it with no traffic and testing it first means users never see a broken version; rollback is
  just pointing traffic back at the previous revision.
  <https://cloud.google.com/run/docs/rollouts-rollbacks-traffic-migration>
- **Why migrations run as a separate job first.** If several API copies migrated at startup they
  would race, and a failed migration would take the API down. One job runs before the new code;
  migrations only add things, so the old code keeps working.
