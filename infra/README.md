# infra/

Google Cloud infrastructure and deployment configuration (Plan v7 §13): Cloud Run services
(saferoute-api, saferoute-worker, osrm), Cloud SQL for PostgreSQL + PostGIS, the migration Cloud
Run Job, Cloud Scheduler, Secret Manager, Artifact Registry, and GitHub OIDC → Workload
Identity Federation.

**Status (P006b):** staging is set up by hand, once, with
[`docs/runbooks/gcp-staging-setup.md`](../docs/runbooks/gcp-staging-setup.md); the topology and
the reasons are in [ADR 0007](../docs/adr/0007-gcp-staging-topology.md). There is no Terraform
yet (ADR 0007 says when to revisit). The API image is built from
[`backend/Dockerfile`](../backend/Dockerfile) and deployed by
[`.github/workflows/deploy-staging.yml`](../.github/workflows/deploy-staging.yml)
([diagram](../docs/diagrams/006-deploy-pipeline.svg)).

| File | Used by |
| --- | --- |
| [`artifact-registry-cleanup-policy.json`](artifact-registry-cleanup-policy.json) | Runbook step 2: delete images older than 7 days, always keep the 10 most recent |

Later prompts add the OSRM image (P012) and backup exports (P020).

**Rules:** no service-account JSON keys, ever. Claude Code never receives production credentials
and never runs commands that change cloud resources. Rahul runs any `gcloud` setup that needs
owner rights himself. No real project IDs, project numbers or service-account emails in this
folder: use placeholders such as `<PROJECT_ID>`.
