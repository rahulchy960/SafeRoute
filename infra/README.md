# infra/

Google Cloud infrastructure and deployment configuration (Plan v7 §13): Cloud Run services
(saferoute-api, saferoute-worker, osrm), Cloud SQL for PostgreSQL + PostGIS, the migration Cloud
Run Job, Cloud Scheduler, Secret Manager, Artifact Registry, and GitHub OIDC → Workload
Identity Federation.

**Status (P006c):** staging is set up once, by Rahul, with the script below; the manual steps
and the reasons are in [`docs/runbooks/gcp-staging-setup.md`](../docs/runbooks/gcp-staging-setup.md)
and [ADR 0007](../docs/adr/0007-gcp-staging-topology.md). There is no Terraform yet (ADR 0007
says when to revisit). The API image is built from [`backend/Dockerfile`](../backend/Dockerfile)
and deployed by [`.github/workflows/deploy-staging.yml`](../.github/workflows/deploy-staging.yml)
([diagram](../docs/diagrams/006-deploy-pipeline.svg)).

| File | Used by |
| --- | --- |
| [`staging/bootstrap-staging.ps1`](staging/bootstrap-staging.ps1) | The setup script: `-Audit` (default, read-only), `-Apply`, `-SetGithubSecrets`, `-Verify`; `-Plan` prints every command and runs nothing ([flow](../docs/diagrams/006c-staging-setup-flow.svg)) |
| [`staging/tests/`](staging/tests/) | Pester tests with a mocked `gcloud`/`gh`, and `Invoke-InfraCheck.ps1`, the quality gate (PSScriptAnalyzer + Pester; CI: [`infra-ci`](../.github/workflows/infra-ci.yml)) |
| [`artifact-registry-cleanup-policy.json`](artifact-registry-cleanup-policy.json) | Runbook step 2: delete images older than 7 days, always keep the 10 most recent, and keep every routing image (tag prefix `osrm-`, since P012a3) |

```powershell
.\infra\staging\bootstrap-staging.ps1            # audit: what exists, what is missing
.\infra\staging\bootstrap-staging.ps1 -Apply     # create only what is missing (asks first)
.\infra\staging\bootstrap-staging.ps1 -SetGithubSecrets
.\infra\staging\bootstrap-staging.ps1 -Verify    # exit code 0 = ready to deploy
```

The script is additive and staging-only: it refuses a project whose ID contains `prd` or
`prod`, never deletes or renames anything, never creates keys, and never creates or changes
Cloud SQL, service accounts, the Workload Identity pool, the registry or an existing secret. Its
output hides project ID, project number, e-mail addresses and URLs unless `-ShowIds` is passed.

The routing engine images (OSRM over OpenStreetMap data, since P012a) are in [`osrm/`](osrm/README.md);
their private staging services are deployed by hand with
[`osrm-staging`](../.github/workflows/osrm-staging.yml) after the setup script has prepared the account
and the log exclusions ([runbook](../docs/runbooks/routing-capacity-staging.md), section 5). Backup exports follow in P020.

**Rules:** no service-account JSON keys, ever. Claude Code never receives production credentials
and never runs commands that change cloud resources. Rahul runs any `gcloud` setup that needs
owner rights himself. No real project IDs, project numbers or service-account emails in this
folder: use placeholders such as `<PROJECT_ID>`.
