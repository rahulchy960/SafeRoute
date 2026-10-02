# ADR 0007: Google Cloud staging topology and deployment strategy

- **Status:** Accepted
- **Date:** 2026-10-02
- **Prompt:** P006 (split: P006a container image, setup runbook and container CI; P006b deploy workflow, smoke tests and rollback)
- **Plan refs:** Plan v7 §3.4, §4, §12.4, §13.1, §13.2, §14.2, §14.3, §14.5, §15.3

## Context

- Plan v7 §13.1/§13.2 fixes the platform: Cloud Run for the API in `asia-south1`, Cloud SQL for
  PostgreSQL + PostGIS, migrations as a Cloud Run Job under its own identity, Secret Manager,
  Artifact Registry, GitHub OIDC → Workload Identity Federation (no service-account keys),
  images tagged with the commit SHA, staging before production. It also says Cloud SQL uses a
  **private IP**.
- Staging has one maintainer, no users and a small budget. It exists to prove the deploy path
  and to give the Android app (from P009) something to call.
- The repository and its GitHub Actions logs are **public**. Project IDs, project numbers and
  service-account emails are not credentials, but they contain a personal handle.
- Claude Code has no Google credentials and must never get any (Plan v7 §13.2). Everything that
  changes cloud resources is done by Rahul (one-time setup) or by CI (deploys).
- The API verifies Firebase tokens with Google's public keys, fetched over HTTPS from
  `www.googleapis.com` (ADR 0006). It needs outbound internet access.
- Migrations are expand-only and are never edited after merge (ADR 0003).

## Decision

1. **Database connection: the Cloud Run built-in Cloud SQL connector over a unix socket.** The
   staging instance has a public IPv4 address with **no authorized networks** and SSL mode
   `ENCRYPTED_ONLY`. Nothing can connect to the address directly; the only path is the connector,
   which requires `roles/cloudsql.client` and encrypts the connection. `DATABASE_URL` has the
   socket form `postgresql://<user>:<password>@/<db>?host=/cloudsql/<connection name>`.
   **This deviates from Plan v7 §13.1 ("private IP") for staging**, because private IP needs a
   VPC, VPC egress from Cloud Run and then Cloud NAT so the API can still reach Google's token
   keys: three more billed, configured parts with no benefit for a database that holds no real
   user data. **Production moves to private IP + Direct VPC egress + Cloud NAT before launch.**
2. **One database user for staging** (`saferoute_app`, a member of `cloudsqlsuperuser`, so the
   first migration may `CREATE EXTENSION postgis`). The API and the migration job share it.
   Before production the roles are split: runtime (DML only), migration (DDL), read-only export.
3. **Three service accounts, least privilege, no keys.** `sa-api-runtime` and `sa-migration`
   each hold `roles/cloudsql.client` and `secretAccessor` on the one database secret.
   `gcp-deploy-staging` holds `roles/run.developer`, `artifactregistry.writer` on the one
   repository and `serviceAccountUser` on those two accounts. It can't read the secret, can't
   connect to the database and can't change who may call the service.
4. **GitHub authenticates through Workload Identity Federation.** The provider's attribute
   condition accepts a token only if `repository` is this repository, `ref` is
   `refs/heads/main` and `environment` is `staging`. Pull requests, other branches and forks
   can't satisfy it, and the deploy workflow never uses `pull_request_target`. Identifiers are
   stored as GitHub **environment secrets** purely so that GitHub masks them in public logs.
5. **One image, three commands.** `backend/Dockerfile` builds a single image that runs the API
   (`node dist/server.js`), the migration job (`node dist/db/migrate.js`) and the admin job
   (`node dist/scripts/set-role.js`). The migration job gets `DATABASE_URL` and nothing else;
   it does not need the API's `FIREBASE_PROJECT_ID`.
6. **Images are tagged with the commit SHA and deployed by digest.** The digest is the
   immutable identity of what runs. Artifact Registry tags are **not** immutable in staging:
   an immutable repository can't delete tagged images, so the cleanup policy (keep the 10 most
   recent, delete after 7 days) would do nothing, and re-running a deploy for the same commit
   would be rejected. The git SHA is also baked into the image as the default `GIT_SHA` and
   `APP_VERSION`, so `/health` reports what was built even if the deploy passes nothing.
7. **Deploy order: migrate first, then a candidate revision, then promote.** The migration job
   runs before any new API revision exists; if it fails, nothing else changes. The new revision
   is deployed with no traffic under the tag `candidate`, smoke-tested, and only then promoted.
   If the smoke test fails after promotion, traffic returns to the previous revision
   automatically. Database changes are never rolled back automatically: migrations are
   expand-only, so the previous revision keeps working against the newer schema, and mistakes
   are fixed forward (ADR 0003).
8. **Placeholder bootstrap.** The service is created once by hand from Google's sample image
   with `--allow-unauthenticated`. The API is public by design and enforces Firebase
   authentication itself (ADR 0006). CI never sets or changes that permission, and a previous
   revision exists for the first rollback.
9. **Capacity for staging:** minimum instances 0 (no idle cost, cold starts accepted), maximum 3.
   With `DB_POOL_MAX` 5 that is at most 15 API connections plus one per job, under the 25
   connections of the `db-f1-micro` tier. **Production runs at least one warm instance** for
   SOS latency (Plan v7 §13.1, §14.3).
10. **No Terraform yet.** The setup is one project, about a dozen resources, created once from a
    reviewed runbook with a verify command per step. Revisit when the production project is
    created, when a second environment must match the first, or when the worker and OSRM
    services arrive (P012, P015): whichever comes first.

## Alternatives considered

- **Private IP now (Plan v7 §13.1 as written).** Closest to production, but adds a VPC, Direct
  VPC egress or a connector, and Cloud NAT for outbound calls. More cost and more to get wrong
  for a database with no real data. Chosen for production, deferred for staging.
- **Cloud SQL Node.js connector with IAM database authentication.** Would remove the database
  password altogether. It adds a dependency and changes how the pool is created, which P006
  must not do ("no changes to application behaviour"). Worth revisiting with the production
  role split.
- **A service-account JSON key in GitHub secrets.** Simple, and exactly what Plan v7 §13.2
  forbids: a long-lived credential that can leak and must be rotated. Rejected.
- **Running migrations when the API starts.** Rejected in P003: several instances would race,
  startup would depend on the database, and a failed migration would take the API down.
- **Immutable tags in staging.** Stronger guarantee for tags, but it disables cleanup and breaks
  re-runs (decision 6). Reconsider for production, where images are kept and promoted.
- **Shifting traffic straight to the new revision.** Simpler pipeline, but a broken revision
  would serve requests before anything had checked it.
- **Terraform from day one.** Reproducible, but it needs remote state, another identity with
  broad rights and review of plans, before there is anything to reproduce.

## Consequences

- Rahul runs [`docs/runbooks/gcp-staging-setup.md`](../runbooks/gcp-staging-setup.md) once.
  Claude Code never runs cloud-changing commands and never handles credentials; deploys happen
  only through CI on `main`.
- The config accepts the socket-form URL (`parsePostgresUrl` in `backend/src/config.ts`), and
  the migration runner uses a job config without the API-only production requirements.
- Staging differs from production in ways that matter for testing: public IP path, a shared
  database user, cold starts, a shared-core database with 25 connections, and PostGIS 3.5 on
  Cloud SQL versus 3.4 in local and CI databases. Load tests (P021) must not run against this
  tier.
- A rollback target older than the 10 most recent images may no longer exist in the registry.
- Follow-ups (Notion): production project with private IP, Direct VPC egress and Cloud NAT;
  database role split; point-in-time recovery and cross-region export (P020); minimum instances
  ≥ 1 in production; automated base-image digest updates; Artifact Registry vulnerability
  scanning; Cloud Monitoring dashboard and alerts (Plan v7 §14.3, §14.5); custom domain;
  `container-ci` and `contracts-ci` as required checks; worker identity and service (P015);
  Terraform; a rehearsal of the password rotation; Cloud SQL connector enforcement.
- Revisit this ADR when production is created, or if staging ever holds real user data.

## Notes added after acceptance

The decisions above are unchanged. These notes record facts found later.

### 2026-10-02 (P006c): the names in the project, and the setup path

- **Deploy identity:** the service account that exists is `sa-deploy`. Wherever this ADR and
  Plan v7 §13.1 say `gcp-deploy-staging`, read `sa-deploy`. Its roles (decision 3) are the same.
- **Cloud SQL instance:** it is named `saferoute-db`; the first runbook assumed
  `saferoute-staging-db`. The database and user names are whatever the instance has;
  `saferoute_app` in decision 2 is an example, and the "one user for staging" decision stands.
- **Secrets:** the project already has a secret with only the database password
  (`db-app-password`). The URL secret of decision 1 (`saferoute-staging-database-url`) is built
  from it; the workflow mounts only the URL secret.
- **Setup path:** [`infra/staging/bootstrap-staging.ps1`](../../infra/staging/bootstrap-staging.ps1)
  is now the primary way to do and to check the one-time setup: audit → apply → set the GitHub
  secrets and variables → verify. The runbook's manual steps are the reference. Names are
  parameters of the script, so a different name is no longer a reason for a failed deploy.
  Decision 10 (no Terraform yet) stands: the script creates only APIs, the provider, the URL
  secret, IAM bindings and the placeholder service, and never Cloud SQL, service accounts, the
  pool or the registry.
- **Lesson for decision 4:** the deploy switch `STAGING_DEPLOY_ENABLED` has to be a
  **repository** variable. A job-level `if:` is evaluated before the job enters its environment,
  so an environment variable is invisible to it and the job is skipped without an error.
- Why: the first `deploy-staging` run stopped at its guard step, before any cloud call, because
  the setup had been done only partly and under these other names
  ([`docs/prompt-logs/006c-staging-setup-script.md`](../prompt-logs/006c-staging-setup-script.md)).

## References

- Plan v7 §3.4, §4, §13.1, §13.2, §14.2, §14.3, §14.5, §15.3.
- [ADR 0003](0003-database-conventions-and-migrations.md) (expand → contract migrations),
  [ADR 0006](0006-authentication-and-roles.md) (authentication in the app, outbound HTTPS).
- Cloud Run → Cloud SQL: <https://cloud.google.com/sql/docs/postgres/connect-run>.
- Workload Identity Federation with deployment pipelines:
  <https://cloud.google.com/iam/docs/workload-identity-federation-with-deployment-pipelines>.
- GitHub Actions OIDC token claims:
  <https://docs.github.com/en/actions/reference/security/oidc>.
- Artifact Registry cleanup policies:
  <https://cloud.google.com/artifact-registry/docs/repositories/cleanup-policy>.
- Prompt log: [`docs/prompt-logs/006a-container-and-runbooks.md`](../prompt-logs/006a-container-and-runbooks.md).
