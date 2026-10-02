# docs/adr/

Architecture Decision Records: one file per decision that is hard to reverse (Plan v7 §17.2).
Copy [`template.md`](template.md) to `NNNN-short-title.md`, and add a row to the Notion
*Architecture Decisions* database.

| ADR | Title | Status |
| --- | --- | --- |
| [0001](0001-kotlin-native-openapi.md) | Native Kotlin client with an OpenAPI 3.1 contract | Accepted |
| [0002](0002-licensing.md) | Licensing: AGPL-3.0-only for code, CC BY-NC-ND 4.0 for docs | Accepted |
| [0003](0003-database-conventions-and-migrations.md) | Database conventions and migration policy | Accepted |
| [0004](0004-api-contract-and-conventions.md) | API contract (Zod → OpenAPI 3.1) and API conventions | Accepted |
| [0005](0005-product-name-and-multi-city-readiness.md) | Product name "SafeRoute" and multi-city readiness | Accepted |
| [0006](0006-authentication-and-roles.md) | Authentication with Firebase ID tokens (jose) and database-authoritative roles | Accepted |
| [0007](0007-gcp-staging-topology.md) | Google Cloud staging topology and deployment strategy | Accepted |
| [0008](0008-android-foundation.md) | Android foundation (package name, SDK levels, Compose, Hilt, design rules) | Accepted |
| [0009](0009-android-api-client.md) | Android API client (generated at build time) and network rules | Accepted |

A breaking API change, new infrastructure, a new data store or the Android package name each need
an ADR.
