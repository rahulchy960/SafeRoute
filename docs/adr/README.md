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
| [0010](0010-adults-only-and-consent-records.md) | Adults only (18+) and per-purpose consent records | Accepted |
| [0011](0011-trusted-circle-principles.md) | Trusted Circle principles | Proposed |
| [0012](0012-firebase-config-in-builds.md) | Firebase configuration in builds, and how sign-in is wrapped and tested | Accepted |
| [0013](0013-regions-and-expansion.md) | Regions and expansion (`regionCode`) | Accepted |
| [0014](0014-civic-reports-ask-govt.md) | Civic reports ("Ask govt") | Proposed |
| [0015](0015-map-stack-and-location-policy.md) | Map stack (MapLibre Native + MapTiler) and location policy | Accepted |
| [0016](0016-statewide-coverage-layers.md) | Statewide coverage in three layers (West Bengal launch, active regions, claims rule) | Accepted |
| [0017](0017-collect-statewide-publish-by-gate.md) | Collect reports statewide, publish by gate (`context_only`, `collecting`, `published`) | Accepted |
| [0018](0018-search-and-geocoding.md) | Place search and geocoding (server-side proxy, provider adapters, rate limits, evaluation) | Accepted |
| [0019](0019-privacy-in-urls.md) | Privacy in URLs: no user text, position or credential in a path or query; search becomes POST | Accepted |
| [0020](0020-routing-osrm.md) | Routing on self-hosted OSRM (West Bengal extent, MLD, private Cloud Run services that scale to zero, log privacy) | Accepted |
| [0021](0021-post-mvp-product-direction.md) | Post-MVP product direction: sequence, journey engine and guardrails for future features | Accepted |

A breaking API change, new infrastructure, a new data store or the Android package name each need
an ADR.
