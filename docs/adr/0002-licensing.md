# ADR 0002: Licensing: AGPL-3.0-only for code, CC BY-NC-ND 4.0 for docs

> Note (P003c): product renamed to SafeRoute (see [ADR 0005](0005-product-name-and-multi-city-readiness.md)).

- **Status:** Accepted
- **Date:** 2026-10-01
- **Prompt:** P002a
- **Plan refs:** Plan v7 §0, §17, §20

## Context

- The repository `rahulchy960/SafeRoute` became public after P001 and had no license, which legally
  means "all rights reserved" but is unclear to visitors.
- SafeRoute is a personal-safety app (SOS, live location, incident reports). A careless or
  misleading copy could put people at risk, and users must be able to tell the official app from
  forks.
- Rahul is the sole author of everything in the repository. The backend will run as a network
  service (Cloud Run), and the Android app will be distributed through Google Play.
- The plan and prompt logs describe the design in detail. Their value lies partly in being
  published unchanged.

## Decision

- We will license all **source code** (everything outside `docs/`) under the **GNU Affero General
  Public License v3.0 only** (`AGPL-3.0-only`), with the official unmodified text in `/LICENSE`.
- We will license all **documentation** under `docs/` (including the plan and diagrams) under
  **CC BY-NC-ND 4.0** (`CC-BY-NC-ND-4.0`), except `docs/adr/template.md`, which anyone may reuse
  freely.
- We will **reserve the names** "SafeRoute" and "SafeRoute Kolkata", the app icon and logos
  (`TRADEMARKS.md`); forks must use a different name and must not look official.
- We will **not accept outside code contributions** for now (`CONTRIBUTING.md`), so Rahul stays
  the sole copyright holder. Issues are welcome, and vulnerabilities go through `SECURITY.md`.
- New source files carry `SPDX-License-Identifier: AGPL-3.0-only` from P003 onward; existing
  files are not mass-edited. `COPYRIGHT.md` summarises the setup.

## Alternatives considered

- **MIT or Apache-2.0 (permissive):** simplest and most widely used, but anyone could ship a
  closed-source fork or run a modified backend without sharing changes. For a safety app, that
  gives up visibility into derived versions. Rejected.
- **GPL-3.0:** copyleft for distributed software, but it doesn't cover software only run as a
  network service. The backend is exactly that. AGPL closes this gap. Rejected in favour of AGPL.
- **PolyForm Noncommercial (source-available):** blocks commercial use directly, but it isn't an
  OSI-approved open-source license, and it complicates using and combining standard open-source
  dependencies. Rejected.
- **No license (status quo):** legally restrictive but unclear to visitors, and it gives no
  explicit terms at all. Rejected.

## Consequences

- Anyone who distributes the code, or runs a modified version as a network service, must publish
  their source under AGPL-3.0. Closed forks are not allowed.
- Rahul's own app can still be published on Google Play; the copyright holder isn't bound by
  their own license. Bundled **third-party proprietary SDKs** (Firebase, Play Services) and
  MapLibre need a license review before release (follow-up, before P022).
- Every dependency must be AGPL-compatible. Permissive (MIT, BSD, ISC, Apache-2.0) and MPL-2.0
  dependencies are fine. The P002a check found only those.
- **Relicensing** (e.g. dual licensing, or moving to a permissive license) stays possible only
  while Rahul is the sole copyright holder. Accepting outside contributions later requires a
  contributor agreement first.
- The docs can't be adapted by others. This protects the plan's integrity but prevents
  derivative community documentation.
- Revisit if the project wants outside contributors, a foundation or organisation owner, or a
  commercial partner.

## References

- `LICENSE` (source: <https://www.gnu.org/licenses/agpl-3.0.txt>), `COPYRIGHT.md`,
  `docs/LICENSE.md`, `TRADEMARKS.md`, `CONTRIBUTING.md`
- CC BY-NC-ND 4.0 legal code: <https://creativecommons.org/licenses/by-nc-nd/4.0/legalcode>
- SPDX license list: <https://spdx.org/licenses/>
- `docs/prompt-logs/002a-add-license.md`
