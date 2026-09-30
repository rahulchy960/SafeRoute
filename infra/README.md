# infra/

Google Cloud infrastructure and deployment configuration (Plan v7 §13): Cloud Run services
(saferoute-api, saferoute-worker, osrm), Cloud SQL for PostgreSQL + PostGIS, the migration Cloud
Run Job, Cloud Scheduler, Secret Manager, Artifact Registry, and GitHub OIDC → Workload
Identity Federation.

**Status:** empty. It is filled from **P006** (`ci/006-gcp-staging-deploy`), then by P012 (OSRM
image) and P020 (backup exports).

**Rules:** no service-account JSON keys, ever. Claude Code never receives production credentials.
Rahul runs any `gcloud` setup that needs owner rights himself.
