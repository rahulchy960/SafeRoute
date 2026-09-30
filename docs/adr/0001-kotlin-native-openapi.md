# ADR 0001: Native Kotlin client with an OpenAPI 3.1 contract

- **Status:** Accepted
- **Date:** 2026-09-30
- **Prompt:** P001
- **Plan refs:** Plan v7 §2 (finding F1), §5, §6, §15.1

## Context

SafeRoute Kolkata needs a reliable Android app with deep platform integration: a location-type
foreground service for SOS and live sharing, WorkManager for retryable sync, Room for offline SOS
state, direct `SmsManager` / SMS-intent use, and the 112 system dialer (Plan v7 §5, §7).

Plan v5 used a **tRPC** backend. tRPC's end-to-end type safety works by importing the server's
TypeScript router type into a TypeScript client. The SafeRoute client is Kotlin, so that mechanism is
unavailable. A Kotlin client would have to hand-code tRPC's wire format with no generated types.
The v5 review rated this Critical (finding F1).

The backend developer experience (TypeScript + Zod schemas as the single source of truth) is worth
keeping.

## Decision

1. The Android client is **native Kotlin + Jetpack Compose** (Material 3, Hilt, Retrofit/OkHttp +
   kotlinx.serialization, Room, WorkManager, MapLibre Native).
2. The backend is **Node.js (LTS) + Hono + `@hono/zod-openapi`**. Every route declares Zod
   request/response schemas, and an **OpenAPI 3.1** document is generated at build time and
   committed as `contracts/openapi.json`.
3. The **Kotlin client is generated from the spec** in CI with openapi-generator (Kotlin, Retrofit2,
   kotlinx.serialization). The app never hand-writes request/response models.
4. The API is versioned under **`/v1`**. CI runs a breaking-change diff (e.g. oasdiff) on
   `openapi.json`. A breaking change needs a new ADR plus either a new version path or a
   coordinated app release, because old app versions stay installed for months.
5. Errors use RFC 9457 `application/problem+json` with a stable `code` field.

## Alternatives considered

- **Keep tRPC and hand-write a Kotlin client.** No generated types, fragile wire format, and drift
  between client and server. Rejected (F1).
- **Cross-platform client (Flutter / React Native) sharing TypeScript types.** Weaker access to
  foreground services, OEM battery behaviour and SMS/dialer integration, which are the
  SOS-critical paths. iOS is a post-MVP non-goal anyway. Rejected.
- **gRPC / protobuf.** Good Kotlin support, but it adds proxying for the web viewer and the
  moderation app, and the toolchain is heavier for a solo developer. Rejected for the MVP.
- **Hand-written OpenAPI YAML.** The spec would drift from the server code. Rejected in favour of
  generating it from the Zod schemas.

## Consequences

- One schema source of truth (Zod) produces typed clients across languages. Contract changes show
  up as reviewable diffs of `contracts/openapi.json`.
- CI needs OpenAPI generation, a breaking-change diff and Kotlin client generation (P004, P008).
- Rahul learns Kotlin/Compose (M0 learning path). Android prompts include *Learning notes*.
- Backward compatibility is a first-class concern: old app versions call `/v1` for months.
- **Android package name rule (Plan v7 §15.1):** the application ID/package name is chosen
  **once** in P007 and **never changed after publishing**. A new package name would be a new Play
  listing, which loses users, reviews and Firebase continuity. `in.saferoute.app` is only a
  suggestion that Rahul must confirm in P007. The confirmed value is recorded in a follow-up ADR.
  Play App Signing is used so a future rewrite ships as an update to the same listing.

## References

- Plan v7 §2 (F1), §5 (Android client), §6 (API architecture), §15.1 (product identity)
- `docs/diagrams/02-mvp-architecture.svg`
- Hono: <https://hono.dev> · OpenAPI Generator: <https://openapi-generator.tech>
