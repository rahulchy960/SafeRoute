# ADR 0011: Trusted Circle principles

- **Status:** Proposed
- **Date:** 2026-10-06
- **Prompt:** P009a
- **Plan refs:** Plan v7 §1, §8, §11, §12.1; [addendum v7.1](../plan/addendum-v7.1.md) B.4, D

## Context

- People asked for a way to let a few trusted adults see where they are and receive their SOS
  alerts without creating a new share link each time.
- The same feature, built carelessly, is a stalking and coercive-control tool: one partner or
  relative watching another without real consent.
- SafeRoute is for adults only ([ADR 0010](0010-adults-only-and-consent-records.md)). This is
  **not** parental control.
- Nothing is built yet. This ADR records the principles so that no earlier prompt builds
  something that contradicts them. It stays **Proposed** until a lawyer has reviewed it.

## Decision

When the feature is built, it will follow all of these.

### Who and how

- Adults only, on both sides.
- **Mutual:** a link exists only after the other person accepts the invite inside the app.
- Invites go only to phone numbers the inviter enters. Responses never reveal whether a number
  is registered.
- Invites are rate-limited. Either side can block and report.
- At most **5 links** per user.

### What is shared

- **Scope is chosen per link by the person sharing:** live location while sharing, SOS alerts,
  both, or neither. The default is **none**.
- **No remote activation.** Nobody can switch on another person's sharing. A request to share
  must be accepted each time.
- Recipients see the live position only while sharing is on. There is **no location history to
  browse**. The trail is deleted 24 h after sharing ends (reuse `share_sessions`).

### Visibility and control

- **Visible:** a persistent indicator is shown while sharing. The other person is notified when
  sharing starts and when it stops.
- **Never a hidden mode.** No setting, build flag or role hides the indicator or the
  notifications.
- **Time-limited:** a link lasts 30 days by default and at most, unless renewed.
- **Either side leaves instantly,** and the other side is notified.

### Data and consent

- Consent purpose `trusted_circle`, requested when the feature is first used (ADR 0010).
- Sketch of the tables (not built):
  - `circle_links(id, user_a, user_b, status pending|active|revoked|expired, scope, created_at,
    expires_at, revoked_by, revoked_at)`
  - `circle_events` (invited, accepted, scope changed, sharing started/stopped, left, expired).
- **Legal review gate:** no code before a lawyer has reviewed the design.

## Alternatives considered

- **A one-sided "follow" (no acceptance).** Simplest, and exactly the stalking tool described
  above. Rejected.
- **Always-on sharing once linked.** Convenient, but it turns a safety feature into
  surveillance. Rejected: sharing is switched on by the sharer each time.
- **A guardian or parental mode.** Out of scope: adults only, and tracking children has its own
  legal rules (ADR 0010). Rejected.
- **Keep only share links (Plan v7 §8).** Enough for the MVP, and what ships first. The circle
  is a later addition, not a replacement.

## Consequences

- The MVP ships without this feature. Live sharing by link (P016) is unaffected.
- The design is deliberately less convenient than commercial family trackers. That is the
  point.
- Misuse for coercive monitoring remains a risk even with these rules (addendum v7.1, E). The
  notifications, time limits and instant leave reduce it; they don't remove it.
- Moving this ADR to Accepted needs the lawyer review and a prompt that builds it.

## References

- Plan v7 §8, §11, §12.1; [addendum v7.1](../plan/addendum-v7.1.md) sections B.4, D and E.
- [ADR 0010](0010-adults-only-and-consent-records.md).
- Prompt log: [`docs/prompt-logs/009a-consent-backend-plan-addendum.md`](../prompt-logs/009a-consent-backend-plan-addendum.md).
