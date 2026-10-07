# docs/runbooks/

Operational runbooks: step-by-step procedures a person follows under pressure.

| Runbook | Status |
| --- | --- |
| [One-time Google Cloud staging setup](gcp-staging-setup.md): the setup script (audit → apply → set GitHub secrets → verify), then the manual steps as reference (Artifact Registry, Cloud SQL, Secret Manager, IAM, Workload Identity Federation, GitHub environment) | P006a, script since P006c |
| [Rollback on staging](rollback-staging.md) (shift traffic to a previous revision, roll forward, rerun the migration job, the `saferoute-admin` job for `set-role`, rehearsal checklist) | P006b |
| [Observability on staging](observability-staging.md) (six Cloud Logging queries, how JSON log fields map, the metrics for the first capacity dashboard) | P006b |
| [Routing capacity on staging](routing-capacity-staging.md) (the P012a measurements, rebuilding the graphs, cost notes, how to measure on staging) | P012a |
| Cloud SQL backup export and quarterly restore drill (Plan v7 §15.3) | Planned: P020 |
| Load test execution and capacity review (Plan v7 §14.4) | Planned: P021 |
| Release to Play internal/closed track (Plan v7 §13.2) | Planned: P022 |

Each runbook is also linked from the Notion **Runbooks** page.

Runbooks contain placeholders and shell variables, never real project IDs, project numbers,
service-account emails or URLs (see "Public repository rules" in [`CLAUDE.md`](../../CLAUDE.md)).
