# CLAUDE.md: SafeRoute

Claude Code loads this file at the start of every session. Read it fully before doing anything.
The full design lives in [`docs/plan/SafeRoute_Plan_v7_MVP.pdf`](docs/plan/SafeRoute_Plan_v7_MVP.pdf)
(cited as "Plan v7 §N").

## Project summary

SafeRoute is a navigation-first personal-safety Android app that launches first in Kolkata (see
"Naming rules" below). It offers a map, search, walking/driving routes, transparent
safety context along routes, temporary live-location sharing and a **device-first SOS** that
reaches emergency contacts even when the server or mobile data is down. The app is not an
emergency service: every SOS surface offers a one-tap call to **112**. Safety information is
context, never a guarantee (Plan v7 §1).

Work arrives as numbered prompts (P001–P022, Plan v7 §18). Rahul pastes each prompt into Claude
Code in Android Studio's terminal at the repository root. Rahul is new to Android, so explain
Android concepts as you use them.

## Fixed architecture decisions (do not redesign)

| Decision | Source |
| --- | --- |
| **Native Kotlin + Jetpack Compose** Android client (no cross-platform framework) | Plan v7 §5, ADR 0001 |
| **OpenAPI 3.1 contract**: Hono + Zod backend generates `contracts/openapi.json`; the Kotlin client is generated from it. No tRPC. | Plan v7 §6, ADR 0001 |
| **Modular monolith**: one API codebase organised by domain modules; extract services only when measurements justify it | Plan v7 §4 |
| **PostgreSQL + PostGIS is the only durable store**; jobs use pg-boss (outbox + enqueue in one transaction); SOS jobs on a dedicated queue | Plan v7 §4 |
| **Device-first SOS**: countdown, SMS, 112 dialer and location tracking work with no server; server orchestration is layered on top | Plan v7 §7 |
| **Provider adapters** for tiles, geocoding, routing and push | Plan v7 §4 |
| **Claude Code never receives production credentials**; it works only through branches, PRs and CI | Plan v7 §13.2 |
| **Android package name is chosen once (P007) and never changed after publishing** | Plan v7 §15.1 |

## Repo map

```text
android/        Kotlin + Compose app (created in P007)
backend/        Hono + Zod API and pg-boss worker (from P002)
moderation/     Moderator web app (P018)
contracts/      openapi.json, generated, never hand-edited (from P004)
infra/          GCP / Cloud Run / WIF configuration (from P006)
tools/diagrams/ JSON → Excalidraw + SVG + PNG diagram generator
docs/plan/      Plan v7 PDF (source of truth for design)
docs/adr/       Architecture Decision Records (template.md, NNNN-title.md)
docs/diagrams/  Diagram JSON specs + generated .excalidraw/.svg/.png
docs/prompt-logs/ One log per prompt (NNN-core-work.md)
docs/runbooks/  Operational runbooks
.claude/        settings.json (deny rules) + slash commands /start-prompt, /ship-prompt
.githooks/      pre-push hook that rejects pushes to main
.github/        PR template, CODEOWNERS, workflows (repo-checks, backend-ci)
```

## TEN GOLDEN RULES

1. One prompt = one branch = one pull request. Only P001 commits to main.
2. Never push to main. Never merge, approve or close a pull request. Rahul merges on GitHub.
3. Start every prompt with /start-prompt; finish with /ship-prompt.
4. Implement only the prompt's scope. Out-of-scope problems become Notion follow-ups.
5. Never commit secrets, keys, keystores, .env files or google-services.json.
6. Never push code that fails the quality gate. If you cannot make it pass, stop and report.
7. Document every prompt: docs/prompt-logs/NNN-slug.md + Notion Prompt Log page.
8. Draw a diagram in Excalidraw whenever a flow, state machine, schema or infrastructure changes.
9. SOS / live-location code requires the failure-matrix tests from Plan v7 §7.5.
10. Explain Android concepts you use in the 'Learning notes' section for Rahul.

Main is protected in layers: these rules, deny rules in `.claude/settings.json` and
`.claude/settings.local.json`, the `.githooks/pre-push` hook (`core.hooksPath .githooks`), and the
GitHub ruleset `protect-main` (active since P002: PR required, `repo-checks` must pass, squash
merge only, no force push or deletion, admins bypass only through a PR). Never try to get around
any layer. Don't use `--no-verify`, change `core.hooksPath`, edit deny rules or change repository
rulesets or settings unless a prompt explicitly asks for it.

## Public repository rules

The repository `rahulchy960/SafeRoute` is **public**. Everything committed, including prompt logs,
docs, commit messages, PR text and the full git history, is world-readable and may be cached or
copied by others. Treat every commit as **permanent**; deleting a file later does not un-publish it.

- Never commit personal data: names of people other than the maintainer, personal emails,
  phone numbers, home addresses, or real locations of people.
- Never commit local machine details: user-profile paths, OS usernames, hostnames, local IPs.
  Write `<home>`, `<repo>` or a repo-relative path instead.
- Never commit test data derived from real users. Use obviously fake values, e.g.
  `+91 00000 00000` or the coordinates of a public landmark.
- Never commit IDs that are not meant to be public: Notion integration tokens or share links,
  Google Cloud / Firebase project numbers, API keys, service-account emails, or production/staging
  URLs with embedded tokens. Use placeholders such as `<GCP_PROJECT_ID>`. Links to private Notion
  pages (`app.notion.com/p/<id>`) are allowed: they grant no access without workspace membership.
- Prompt logs, PR bodies and Notion pages are public-facing: write them accordingly and mask
  personal details in any tool output you paste (e.g. `r***@gmail.com`).
- Commits use the GitHub noreply address set in the repo-local git config. Don't change it.
- Report vulnerabilities privately (see [`SECURITY.md`](SECURITY.md)), never in a public issue.

## Licensing (since P002a, ADR 0002)

- **Code** (everything outside `docs/`): `AGPL-3.0-only`, full text in [`LICENSE`](LICENSE).
  **Docs** (`docs/`, including the plan and diagrams): `CC-BY-NC-ND-4.0`, see
  [`docs/LICENSE.md`](docs/LICENSE.md). Name and logo: not licensed ([`TRADEMARKS.md`](TRADEMARKS.md)).
  Summary in [`COPYRIGHT.md`](COPYRIGHT.md). Copyright holder: Rahul Chowdhury.
- **SPDX headers (from P003 onward):** every new source file in `backend/src`, `moderation/`,
  `contracts/` and `android/` starts with an SPDX line in that language's comment syntax, e.g.
  `// SPDX-License-Identifier: AGPL-3.0-only` (TypeScript, Kotlin, Gradle Kotlin DSL) or
  `<!-- SPDX-License-Identifier: AGPL-3.0-only -->` (XML). Don't mass-edit existing files.
  Generated files (e.g. `contracts/openapi.json`) are exempt.
- New packages (`package.json`, Gradle modules) declare `AGPL-3.0-only` and stay private /
  unpublished unless a prompt says otherwise.
- **No outside code contributions** ([`CONTRIBUTING.md`](CONTRIBUTING.md)). Never copy code from
  sources whose license is incompatible with AGPL-3.0; flag any new dependency with a copyleft-
  incompatible or proprietary license in the prompt log.
- Never write or reconstruct license texts from memory; fetch official texts.

## Database rules (since P003)

Follow [ADR 0003](docs/adr/0003-database-conventions-and-migrations.md) for every table and
migration (keys, `timestamptz`/UTC, expand → contract, never edit a merged migration, no precise
locations in `audit_log` or logs). How-to and the lng/lat convention:
[`backend/src/db/README.md`](backend/src/db/README.md).

## Naming rules (since P003c, ADR 0005)

- The product name is **"SafeRoute"**. The former, city-suffixed name remains only in historical
  records (Plan v7 PDF, past prompt logs, ADR bodies) and in `TRADEMARKS.md`, which reserves it.
- Don't put city names in code identifiers, API paths, operationIds, schema/table/column names,
  package names, or Gradle/module names (e.g. no `kolkataRoutes`, `/kolkata/...`,
  `in.saferoute.kolkata`).
- City-specific facts (launch area, map/routing extract, time zone, police-station data, festival
  load planning, local-language copy) live in configuration, data or docs.
- "Kolkata" appears only where it is a fact: the launch city, pilot areas, the OSRM/tiles extract,
  Durga Puja load planning, `Asia/Kolkata` conversions, Bengali UI, test landmarks.
- Multi-city support is deferred to Plan v7 §14.2 Stage 3. Don't build it early.

## Conventions (Plan v7 §17.3)

| Item | Convention | Example |
| --- | --- | --- |
| Branch | `<type>/<NNN>-<core-work>`, type ∈ feat, fix, chore, ci, docs, test | `feat/014-sos-device-flow` |
| Commit title | Conventional Commits + prompt tag `[P<NNN>]` | `feat(sos): device-first SOS trigger with offline SMS [P014]` |
| Commit body | `.gitmessage` template: Why / What changed / How tested / Risks-follow-ups / Refs | |
| PR title | Same as the main commit title | |
| PR body | `.github/pull_request_template.md` (11 sections) | |
| Merge | Squash and merge by Rahul on GitHub (one commit per prompt on main) | |
| Push | Always explicit: `git push -u origin <branch>`. Never a bare `git push`. | |
| Size | Keep one PR under ~800 changed lines excluding generated code; otherwise split NNNa/NNNb | |

## Quality gate per area (Plan v7 §17.5)

Run the gate for **every area the prompt touches**. Fix failures; never skip, disable or delete tests
to go green.

| Area | Commands | Status |
| --- | --- | --- |
| Repo-wide | markdown lint, JSON validity, gitleaks secret scan (`.github/workflows/repo-checks.yml`) | Active |
| Diagrams | `cd tools/diagrams && pnpm install && pnpm generate` (no errors) | Active |
| Backend | `pnpm typecheck` · `pnpm lint` · `pnpm format:check` · `pnpm test` · `pnpm build` (run inside `backend/`; CI: `.github/workflows/backend-ci.yml`) | Active (since P002) |
| Contracts | regenerate `contracts/openapi.json` (diff committed) + breaking-change diff (oasdiff) | Not yet applicable (from P004) |
| Android | `./gradlew lint testDebugUnitTest assembleDebug` (run inside `android/`) | Not yet applicable (from P007) |
| Moderation | typecheck · lint · test · build | Not yet applicable (from P018) |

## Documentation duties (every prompt)

- **Prompt log**: `docs/prompt-logs/NNN-core-work.md` with the 12-section template (see
  `docs/prompt-logs/README.md`).
- **Notion Prompt Log page**: a row in the *Prompt Log* database of "SafeRoute — Engineering",
  with the same 12 sections and properties (Status, Branch, PR URL, Commit SHA, Quality gate,
  Diagram URL, dates). If the Notion MCP fails, still write the repo log, report the
  failure, and sync it in the next `/start-prompt`.
- **Diagrams (Plan v7 §17.7)**: required when a prompt adds or changes a user flow, state machine,
  data model/schema, API interaction sequence, infrastructure/deployment or the CI pipeline. Write
  `docs/diagrams/NNN-topic.json` and generate `.excalidraw/.svg/.png` with `tools/diagrams`. Keep
  diagrams under ~20 nodes. Otherwise the Notion page says "No diagram needed".
- **ADRs**: `docs/adr/NNNN-title.md` from `docs/adr/template.md` for any decision that is hard to
  reverse (breaking API change, new infrastructure, package name). Add a row to the Notion
  *Architecture Decisions* database.
- **Follow-ups**: out-of-scope problems go in the Notion *Follow-ups* database (Title, Source prompt,
  Priority, Status, Notes). Don't fix them in the current prompt.

## SOS, live-location and contacts rules

Any code touching SOS, live location or emergency contacts **must** include the relevant rows of
the failure matrix (Plan v7 §7.5), either as automated tests or as documented manual test scripts
in the prompt log. At minimum, cover the rows affected by the change:

| Scenario | Expected behaviour |
| --- | --- |
| No mobile data, cellular signal present | SMS sent from device; server sync queued; live-link SMS sent when sync succeeds |
| No signal at all | SOS screen shows 112 button; device keeps retrying; local record kept; SMS retried when signal returns |
| SafeRoute API down / 5xx | Device path unaffected; WorkManager retries; no duplicate sessions (idempotency) |
| App swiped away / killed by OEM | Foreground service keeps running where allowed; on restart, state restored from Room and service resumed |
| Phone rebooted during SOS | On boot/app open, active SOS detected in Room and user prompted to continue or resolve |
| Double tap / duplicate trigger | Single session (clientSosId); second trigger shows existing SOS screen |
| SEND_SMS denied or not approved | SMS composer opens pre-filled; user taps send |
| No GPS fix | Send last known location with its age; mark as stale in SMS and viewer |
| FCM failure | Action recorded FAILED_RETRYABLE, retried; SMS already covers contacts |
| Worker crash mid-action | pg-boss re-delivers; provider call keyed by action idempotency key; no duplicate push |
| Accidental trigger | Cancel in countdown; after trigger, "I'm safe" sends a clear follow-up to contacts |

Emergency side effects must be idempotent and auditable. Never make a device-side SOS action wait
for the server.

## Android beginner support

Rahul has no prior Android experience.

- Every Android prompt's Notion page (and prompt log) has a **Learning notes** section explaining
  the Android/Kotlin concepts used (e.g. ViewModel, Hilt, Room, WorkManager, foreground services),
  in plain language with a pointer to the official docs.
- Add short KDoc comments where code is non-obvious (lifecycles, threading, permissions, services).
  Don't comment the obvious.

## Security and privacy

- Never read, print or commit `.env*`, `*.jks`, `*.keystore`, `google-services.json` or
  service-account JSON. Use placeholders such as `<GCP_PROJECT_ID>` and ask Rahul for real values.
- Never invent secrets, IDs or URLs.
- Personal data (phone numbers, exact locations, report text) never goes into logs, crash reports,
  Notion pages or prompt logs.
- Safety content describes incidents and conditions, never kinds of people (Plan v7 §1).
