# ADR 0010: Adults only (18+) and per-purpose consent records

- **Status:** Accepted
- **Date:** 2026-10-06
- **Prompt:** P009a
- **Plan refs:** Plan v7 §1, §3.2 (F-01), §6.3, §11, §12.1; [addendum v7.1](../plan/addendum-v7.1.md) B.2, B.3

## Context

- SafeRoute stores a phone number, and later emergency contacts, live locations and reports.
  Plan v7 §12.1 requires consent before that data is collected.
- India's Digital Personal Data Protection Act (DPDP) treats everyone **under 18** as a child.
  Summaries of the Act and its rules say that processing a child's data needs verifiable
  parental consent and that tracking or behavioural monitoring of children is banned, with
  narrow exemptions. Secondary sources give **13 May 2027** as the date the consent and
  children provisions commence. **This is not legal advice; all of it is to be verified by a
  lawyer.**
- A navigation and live-location app is tracking by nature. Serving children would need parental
  verification that a solo founder cannot build or operate correctly now.
- Later features (SOS alerts, live sharing, safety reports, Trusted Circle) each use data for a
  different purpose. One "I agree to everything" tick at sign-up would not be specific, and could
  not be withdrawn per feature.

## Decision

1. **SafeRoute is for adults (18+) at launch.** The app asks "I am 18 or older" before sign-in.
   The Play listing targets adults. There are no child or teen accounts and no parental features.
2. **Self-declaration, recorded.** The server stores **when** the user made the declaration
   (`users.adult_attested_at`). `POST /v1/me/bootstrap` refuses to create an account without
   `ageConfirmed: true` (403 `adult_required`) and stores nothing in that case.
3. **No date of birth is collected.** A date of birth is more personal data than the decision
   needs, it is easy to mistype or invent, and holding it creates a duty to protect it. A
   yes/no declaration with a timestamp is the minimum that shows the question was asked.
4. **Consent is per purpose and requested just-in-time.** Onboarding records `account_core`
   (verify the phone number, keep the account). `sos_alerts`, `live_sharing` and
   `safety_reports` are requested when the feature is first used; `trusted_circle` joins the
   list when that feature ships ([ADR 0011](0011-trusted-circle-principles.md)).
5. **Consent comes before collection.** The app shows the notice before it asks for the phone
   number. The server creates no account without the `consent` object (403 `consent_required`).
6. **Append-only history.** `consent_records` gets a new row for every decision (purpose, status
   `granted` or `withdrawn`, notice version, notice locale, time). Rows are never updated. The
   current state of a purpose is its newest row. Each change also writes an `audit_log` row
   (`consent.recorded`, `consent.changed`) without personal data.
7. **`account_core` cannot be withdrawn on its own.** Without it there is no account, so
   `PUT /v1/me/consents/account_core` with `withdrawn` answers 409
   `account_deletion_required`. Withdrawing it means deleting the account (P020).
8. **Purposes are open strings validated by a server allowlist.** The contract and the table
   check only the shape (`^[a-z][a-z0-9_]{2,40}$`). The list of accepted purposes is a constant
   in `backend/src/modules/consents/purposes.ts`, so a new purpose needs no migration and is not
   a breaking API change. An unknown purpose is a 400 `validation_error`.
9. **The notice version is stored with every decision.** When the notice text changes, the app
   compares versions and asks again.
10. **Retention is decided in P020 with legal input.** Until then consent rows live as long as
    the account and are deleted with it (`ON DELETE CASCADE`). Whether a proof of consent must
    outlive the account is an open legal question.

## Alternatives considered

- **Ask for a date of birth.** Looks stricter, but a typed date is no more verified than a
  tick, and it adds personal data. Rejected; revisit if the lawyer requires it.
- **Verify age with a document or a third-party service.** Real verification, but costly,
  intrusive, and far more sensitive data than the app otherwise needs. Rejected for the MVP.
- **Allow teens with parental consent.** Needs verifiable parental consent and a design that
  avoids tracking children. Rejected until a lawyer has reviewed it and a new ADR exists.
- **One consent for everything at sign-up.** Simplest, but not specific to a purpose and not
  withdrawable per feature. Rejected.
- **A mutable "current consents" table.** Smaller, but it loses the history that shows what a
  user agreed to and when. Rejected.
- **A database enum or CHECK list for purposes.** Every new purpose would need a migration.
  Rejected in favour of the shape check plus the server allowlist.

## Consequences

- The under-18 path stores nothing on the server. The app keeps only a local flag.
- A self-declaration can be false. Minors using the app despite the gate is a recorded risk;
  the gate may need to become stricter after legal review (follow-up).
- Every feature that uses personal data for a new purpose must check and request its consent
  through the API before processing. `CLAUDE.md` → "Age and consent rules" makes this a rule.
- Consent UI for the optional purposes arrives with their features (P013, P016, P017).
- The notice text is a draft until a lawyer has reviewed it (P009b, follow-up).
- Revisit when: the lawyer review is done; DPDP rules commence or change; a teen or family
  feature is proposed.

## References

- Plan v7 §3.2, §11, §12.1; [addendum v7.1](../plan/addendum-v7.1.md).
- [ADR 0003](0003-database-conventions-and-migrations.md) (migrations, personal-data markers),
  [ADR 0004](0004-api-contract-and-conventions.md) (open strings, error codes),
  [ADR 0006](0006-authentication-and-roles.md) (bootstrap).
- `backend/drizzle/0002_consent_records_adult_attestation.sql`,
  `backend/src/modules/consents/`.
- Diagram: [`docs/diagrams/009a-consent-and-bootstrap.svg`](../diagrams/009a-consent-and-bootstrap.svg).
- Prompt log: [`docs/prompt-logs/009a-consent-backend-plan-addendum.md`](../prompt-logs/009a-consent-backend-plan-addendum.md).
