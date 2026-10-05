# ADR 0014: Civic reports ("Ask govt")

- **Status:** Proposed
- **Date:** 2026-10-06
- **Prompt:** P009a
- **Plan refs:** Plan v7 §1, §3.3, §9; [addendum v7.1](../plan/addendum-v7.1.md) D

## Context

- The founder wants a civic tab: a user photographs a local problem (a broken street light, an
  open drain) and asks the responsible public body to fix it, with medals for taking part.
- It is committed on the roadmap after Trusted Circle, Kolkata first (addendum v7.1, D).
- Posting about named officials carries legal risk (defamation, tagging officials, children in
  photos) and brand risk for a safety app.
- Nothing is built. This ADR records the constraints and stays **Proposed** until a lawyer has
  reviewed them.

## Decision

When the feature is built, it will follow all of these.

- **The user posts, not SafeRoute.** The post is prepared on the phone and shared through the
  user's own X app via the Android share sheet. There is no API posting under the product's
  account.
- **Photos are cleaned on the device:** metadata is stripped, and faces and number plates are
  blurred, before anything leaves the phone.
- **Templated captions only** (English and Bengali). No free-text accusations.
- **Curated, verified official handles per region**
  ([ADR 0013](0013-regions-and-expansion.md)). Users don't type handles.
- **Official grievance channels are linked as alternatives** to a public post.
- **Medals are separate from safety reports,** so safety data cannot be gamed for rewards.
  Medals are shared as locally generated images.
- **No photographing while driving or walking in traffic.** The UI warns about it.
- **Lawyer review before launch:** defamation, tagging officials, children in photos.
- **Brand risk:** whether this is a module of SafeRoute or a separate app is a v8 decision.

## Alternatives considered

- **Post through an API from a SafeRoute account.** One voice and easy tracking, but SafeRoute
  would then publish every claim itself and carry the legal and moderation burden. Rejected.
- **Free-text posts.** More expressive, and an open door to accusations and abuse. Rejected.
- **Server-side blurring.** Simpler to build, but unblurred photos of people would leave the
  phone. Rejected.
- **Medals inside the safety-report system.** One reward system, but it would invite false
  safety reports. Rejected.

## Consequences

- No server stores civic photos in this design; the feature is mostly on the device.
- On-device blurring needs an on-device model and testing on low-end phones (to be evaluated).
- Official handles are data that someone must verify and maintain per region.
- Moving this ADR to Accepted needs the lawyer review, the module-or-separate-app decision, and
  a prompt that builds it.

## References

- Plan v7 §3.3 (non-goals: the civic module is out of the MVP);
  [addendum v7.1](../plan/addendum-v7.1.md) sections C and D.
- [ADR 0013](0013-regions-and-expansion.md).
- Prompt log: [`docs/prompt-logs/009a-consent-backend-plan-addendum.md`](../prompt-logs/009a-consent-backend-plan-addendum.md).
