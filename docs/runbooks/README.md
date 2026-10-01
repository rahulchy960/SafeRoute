# docs/runbooks/

Operational runbooks: step-by-step procedures a person follows under pressure.

| Runbook | Status |
| --- | --- |
| [One-time Google Cloud staging setup](gcp-staging-setup.md) (Artifact Registry, Cloud SQL, Secret Manager, IAM, Workload Identity Federation, GitHub environment) | P006a |
| Rollback of a staging Cloud Run revision, manual migration and admin jobs | Planned: P006b |
| Observability for staging (log queries, metrics to watch) | Planned: P006b |
| Cloud SQL backup export and quarterly restore drill (Plan v7 §15.3) | Planned: P020 |
| Load test execution and capacity review (Plan v7 §14.4) | Planned: P021 |
| Release to Play internal/closed track (Plan v7 §13.2) | Planned: P022 |

Each runbook is also linked from the Notion **Runbooks** page.

Runbooks contain placeholders and shell variables, never real project IDs, project numbers,
service-account emails or URLs (see "Public repository rules" in [`CLAUDE.md`](../../CLAUDE.md)).
