# SafeRoute Plan v7 — Addendum v7.1

> Copyright (C) 2026 Rahul Chowdhury. Licensed under CC BY-NC-ND 4.0
> (`CC-BY-NC-ND-4.0`), like everything under `docs/`: see [`../LICENSE.md`](../LICENSE.md).

- **Date:** 2026-10-06 · **Prompt:** P009a
- **The PDF is unchanged.** [`SafeRoute_Plan_v7_MVP.pdf`](SafeRoute_Plan_v7_MVP.pdf) stays as
  committed.
- **Where this addendum differs from v7, the addendum wins.** Read the PDF first, then this file.
- A full **v8** will be written after the MVP and company registration.
- Legal points here come from summaries, not legal advice. Each is marked **to be verified by a
  lawyer** (Plan v7 §12.1).

## A. Implementation facts that supersede v7

- **Name:** the product is "SafeRoute". Naming is city-neutral
  ([ADR 0005](../adr/0005-product-name-and-multi-city-readiness.md)).
- **Package:** `com.saferoute.app`. v7's `in.saferoute` is invalid: `in` is a Kotlin keyword
  ([ADR 0008](../adr/0008-android-foundation.md)).
- **Auth:** the API verifies Firebase ID tokens with `jose`, without `firebase-admin`. Roles are
  stored in the database, not in token claims
  ([ADR 0006](../adr/0006-authentication-and-roles.md); deviates from §12.4).
- **Staging network:** Cloud SQL with a public IP, reached through the connector with SSL
  required. **Production MUST move to private IP + Direct VPC egress + Cloud NAT before launch**
  ([ADR 0007](../adr/0007-gcp-staging-topology.md); deviates from §13.1).
- **Identities as built:** `sa-deploy`, `sa-api-runtime`, `sa-migration`. `sa-worker-runtime`
  exists and is unused until P015.
- **Instances:** staging API minimum instances 0. Production needs ≥ 1 for SOS.
- **Kotlin client:** generated at Android build time from `contracts/openapi.json`, not committed
  ([ADR 0009](../adr/0009-android-api-client.md)).
- **Licences:** code `AGPL-3.0-only`, docs CC BY-NC-ND 4.0
  ([ADR 0002](../adr/0002-licensing.md)). The repository is public, with a branch ruleset on
  `main`.
- **Prompt numbering** uses part letters: P003a/b/c, P004a/b, P005a/b, P006a/b/c, P008a/b,
  P009a/b.
- **Risk:** staging currently runs on a trial credit with an expiry date.

## B. Product decisions

1. **SOS entry points in the MVP (P014):**
   - Quick Settings tile, home-screen widget, optional pinned notification.
   - Each starts the same 5 s cancellable on-device countdown with vibration.
   - The in-app SOS button keeps hold-to-arm 2 s.
   - Entry from tile, widget or notification uses the countdown as the safeguard (confirm in
     P014).
   - Android may let users dismiss ongoing notifications, and manufacturer battery managers kill
     processes. The tile is the dependable entry.
   - A pinned notification must be re-posted after reboot.
   - Lock-screen behaviour and the TileService / add-tile APIs must be verified against current
     Android docs in P014 and tested on the OEM matrix.
   - No always-running service for these entry points.
2. **Adults only (18+) at launch:**
   - Age gate in onboarding. Play target audience: adults.
   - No child accounts or parental features without an ADR and legal review
     ([ADR 0010](../adr/0010-adults-only-and-consent-records.md)).
3. **Consent is per purpose, revocable and requested just-in-time:**
   - Onboarding records `account_core`.
   - `sos_alerts`, `live_sharing`, `safety_reports` and later `trusted_circle` are requested when
     each feature first needs them.
4. **Trusted Circle principles** ([ADR 0011](../adr/0011-trusted-circle-principles.md)):
   - Adults, mutual, visible, time-limited.
   - Either side can leave instantly. No hidden mode.
   - Not parental control.

## C. Section edits to v7

| § | Change |
| --- | --- |
| §1 | Adults-only and city-neutral wording. |
| §3.2 F-01 | Add the age gate and consent records. |
| §3.2 F-08 | Add tile, widget and notification entry. |
| §3.3 | Non-goals, add: voice trigger, civic module and medals, child features, satellite SOS. Server SMS / WhatsApp / voice calls stay non-goals (unchanged). |
| §3.4 | Launch criteria, add: age gate and consent records verified; SOS entry points tested on the OEM matrix; lawyer review of privacy policy, terms and consent notice before any open beta; production on private IP. |
| §5.2, §15.1 | Package name `com.saferoute.app`. |
| §5.3 | `POST_NOTIFICATIONS` is also needed for the pinned SOS notification. |
| §6.3 | Add `GET /v1/me/consents` and `PUT /v1/me/consents/{purpose}`. |
| §7.1 | Entry points as in B.1. |
| §7.4 | Unchanged: server SMS, WhatsApp and calls remain company-gated. |
| §7.5 | Add failure rows: SOS started from the tile while the phone is locked; pinned notification dismissed or killed; tile unavailable on the device. |
| §11 | Add `consent_records` and `users.adult_attested_at`. Sketch of `circle_links` (not built; ADR 0011). |
| §12.1 | Consent just-in-time. DPDP children rules: children are under 18; verifiable parental consent and a ban on tracking and behavioural monitoring of children apply, with narrow exemptions. Secondary sources give 13 May 2027 as the commencement of the consent and children provisions. **All to be verified by a lawyer.** |
| §12.4 | As in A (jose, roles in the database). |
| §13.1 | As in A (staging public IP + connector; production private IP). |
| §14 | The capacity model is Kolkata-only and must be redone in v8 before the first expansion ([ADR 0013](../adr/0013-regions-and-expansion.md)). |
| §18 | Prompts are split into lettered parts. P014 scope grows by B.1. |
| §21 | Replaced by section D. |
| §22 | Extended by section E. |

## D. Roadmap order (replaces §21)

1. **MVP:** P001–P022, with the P014 expansion (B.1).
2. **Beta feedback.**
3. **MVP+:**
   - police-station numbers table, bundled offline with a verified source and date;
   - WhatsApp share via a one-tap intent;
   - periodic SMS location check-ins when there is no data, within Android SMS limits.
4. **Company registration and lawyer review.** This also opens the company-gated channels of
   §7.4, which stay off until then: server SMS after TRAI DLT registration, automatic WhatsApp
   after Business verification, a scripted voice call after legal review. No generative AI voice.
5. **Trusted Circle for adults** (committed). ADR 0011 stays Proposed until the lawyer review.
6. **Civic "Ask govt" tab** (committed). Kolkata first; official handles are stored per region
   ([ADR 0014](../adr/0014-civic-reports-ask-govt.md)).
7. **Regional expansion, region by region:** nearby districts, then larger cities, then the rest
   of West Bengal ([ADR 0013](../adr/0013-regions-and-expansion.md)).
8. **Research items:**
   - opt-in voice trigger, during a trip or an SOS only (verify Android background-microphone
     rules first);
   - satellite SOS: a system and carrier feature, not available to third-party apps; no
     confirmed phone service in India; permits are required for satellite devices.

## E. New risks (§22 additions)

- False alarms reaching contacts.
- Stale police numbers.
- Per-message costs of automated channels.
- Minors using the app despite the 18+ gate.
- Trusted Circle misused for coercive monitoring.
- DPDP commencement date and the interpretation of children's-data rules.
- Trial credit expiry on staging.
- The survey sample is small and skewed to the founder's network: treat it as signals, not proof.
- Sparse data in rural regions.
- Per-region police data quality.
- Moderator capacity per region.
