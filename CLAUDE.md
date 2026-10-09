# CLAUDE.md: SafeRoute

Claude Code loads this file at the start of every session. Read it fully before doing anything.
The full design lives in [`docs/plan/SafeRoute_Plan_v7_MVP.pdf`](docs/plan/SafeRoute_Plan_v7_MVP.pdf)
(cited as "Plan v7 §N"), amended by [`docs/plan/addendum-v7.1.md`](docs/plan/addendum-v7.1.md),
[`docs/plan/addendum-v7.2.md`](docs/plan/addendum-v7.2.md),
[`docs/plan/addendum-v7.3.md`](docs/plan/addendum-v7.3.md),
[`docs/plan/addendum-v7.4.md`](docs/plan/addendum-v7.4.md),
[`docs/plan/addendum-v7.5.md`](docs/plan/addendum-v7.5.md),
[`docs/plan/addendum-v7.6.md`](docs/plan/addendum-v7.6.md) and
[`docs/plan/addendum-v7.7.md`](docs/plan/addendum-v7.7.md) (see "Plan addendum" below).

## Plan addendum (since P009a)

Read [`docs/plan/addendum-v7.1.md`](docs/plan/addendum-v7.1.md) after the PDF. **Where the addendum
differs from Plan v7, the addendum wins.** It records what was built differently from v7, the
product decisions made since (SOS entry points for P014, adults only, per-purpose consent, Trusted
Circle principles), the section edits to v7, the roadmap order and new risks. The PDF itself is
never edited; a full v8 comes after the MVP.

Then read [`docs/plan/addendum-v7.2.md`](docs/plan/addendum-v7.2.md) (since P010c) and
[`docs/plan/addendum-v7.3.md`](docs/plan/addendum-v7.3.md) (since P010d), then
[`docs/plan/addendum-v7.4.md`](docs/plan/addendum-v7.4.md) (since P012d),
[`docs/plan/addendum-v7.5.md`](docs/plan/addendum-v7.5.md) (since P012g),
[`docs/plan/addendum-v7.6.md`](docs/plan/addendum-v7.6.md) (since P012h) and
[`docs/plan/addendum-v7.7.md`](docs/plan/addendum-v7.7.md) (since P012i). **Reading order: PDF →
v7.1 → v7.2 → v7.3 → v7.4 → v7.5 → v7.6 → v7.7; where they differ, the later document wins.** v7.2 records the launch
geography (West Bengal, with Kolkata as the first pilot area for community safety data), the
three coverage layers, the region model, the changed scopes of P011, P012, P017 and P019, and the
claims rule (see "Coverage claims" below). v7.3 replaces v7.2's "reports only in active regions":
reports are accepted anywhere inside West Bengal, and publication is gated region by region
(statuses `context_only`, `collecting`, `published`); it changes the scopes of P017, P018 and P019.
v7.4 records the post-MVP product direction (the order of features after the MVP, the journey
engine) and the guardrails for every future feature (see "Product guardrails" below); it changes
no MVP scope. v7.5 supplements v7.4 with a long-term direction (live local context) and a staged
path to it (see "Live local context" below); it builds nothing and changes no MVP scope. v7.6
records a concept, credits for verified place contributions, with its gates (see "Product
guardrails" below); it is Proposed, builds nothing and changes no MVP scope. v7.7 records a
passive daily-use loop, monetization hypotheses without prices, a Safe Date journey type and two
rejected ideas (see "Product guardrails" below); it is Proposed, builds nothing and changes no
MVP scope.

## Project summary

SafeRoute is a navigation-first personal-safety Android app that launches in West Bengal, with
Kolkata as the first pilot area for community safety data (see "Coverage claims" and "Naming
rules" below). It offers a map, search, walking/driving routes, transparent
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
| **Android application ID is `com.saferoute.app`** (chosen in P007); it never changes after publishing | Plan v7 §15.1, ADR 0008 |

## Repo map

```text
android/        Kotlin + Compose app (from P007; Android Studio opens this folder)
backend/        Hono + Zod API and pg-boss worker (from P002)
moderation/     Moderator web app (P018)
contracts/      openapi.json, generated, never hand-edited (from P004)
infra/          GCP / Cloud Run / WIF configuration (from P006)
tools/diagrams/ JSON → Excalidraw + SVG + PNG diagram generator
docs/plan/      Plan v7 PDF (source of truth for design) + addenda v7.1 to v7.7 (the later one wins)
docs/adr/       Architecture Decision Records (template.md, NNNN-title.md)
docs/diagrams/  Diagram JSON specs + generated .excalidraw/.svg/.png
docs/prompt-logs/ One log per prompt (NNN-core-work.md)
docs/runbooks/  Operational runbooks
.claude/        settings.json (deny rules) + slash commands /start-prompt, /ship-prompt
.githooks/      pre-push hook that rejects pushes to main
.github/        PR template, CODEOWNERS, workflows (repo-checks, backend-ci, contracts-ci, container-ci, infra-ci, android-ci, deploy-staging)
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
  Generated files are exempt: `contracts/openapi.json` is JSON and can't carry a comment; it is
  covered by `COPYRIGHT.md` and its `info.license` field.
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

## API contract rules (since P004, ADR 0004)

Follow [ADR 0004](docs/adr/0004-api-contract-and-conventions.md) and
[`contracts/README.md`](contracts/README.md): every route is declared with `createRoute` (so it
appears in `contracts/openapi.json`); product endpoints under `/v1`; camelCase JSON; RFC 9457
problem+json errors with an open-string `code`; `Idempotency-Key` for retryable writes;
`firebaseBearer` on protected routes; bump `info.version` in the PR that changes the spec.

## Privacy in URLs (since P011d, ADR 0019)

Follow [ADR 0019](docs/adr/0019-privacy-in-urls.md). **The platform logs every URL.** Cloud
Run's request log records the full URL with its query string, and our code cannot change that.

- Never put search text, coordinates or areas, phone numbers, tokens, keys or any text a user
  entered in a path or a query string of our API. Use a request body (POST) or a header.
- A URL may carry opaque server-made ids (UUIDs), fixed names (a consent purpose), paging and
  display options. Nothing else.
- `backend/test/contract.test.ts` ("privacy in URLs") fails on a path or query parameter whose
  name looks sensitive. An exception needs an allowlist entry there **and** an ADR that accepts
  it. Never rename a parameter to get past the test: the rule is about the value.
- A "nothing is logged" test must say which logs it sees. Capturing the API's own log lines
  proves nothing about the platform's request log; say so in the prompt log.
- Still to be decided, each with its own ADR before it is built: the safety cells area
  (`bbox`), the share viewer's token (`/v/{token}`, `/v1/public/shares/{token}/latest`), and the
  routing engine's request URLs.
- A new environment needs its log exclusion
  ([`docs/runbooks/observability-staging.md`](docs/runbooks/observability-staging.md)) before it
  serves users.

## Auth rules (since P005, ADR 0006)

Follow [ADR 0006](docs/adr/0006-authentication-and-roles.md):

- Every `/v1` route declares `security: [{ firebaseBearer: [] }]` and uses `authenticate` or
  `requireUser` (`backend/src/modules/auth/middleware.ts`). The only exceptions are routes that
  are public by design (e.g. the share viewer), and they say so in the spec.
- Never trust client-supplied user ids, roles or phone numbers. Take identity from the verified
  token and roles from `users.role` (`requireRole`).
- Never log tokens, the Authorization header, phone numbers or Firebase uids. Log `user_id`
  (internal UUID) only.
- Never add an auth bypass, dev login route, emulator switch, or a variable that changes the
  token issuer, audience, algorithm or key URL.

## Age and consent rules (since P009a, ADR 0010)

Follow [ADR 0010](docs/adr/0010-adults-only-and-consent-records.md):

- **Adults only (18+).** The user declares it; the server records when
  (`users.adult_attested_at`). **Never collect a date of birth** or any other proof of age.
- **No processing before consent.** Nothing about a person is stored or sent before they agreed to
  the notice; the phone number is asked for after it. The server creates no account without the
  `consent` object.
- **Consent is per purpose and just-in-time, through the API** (`GET /v1/me/consents`,
  `PUT /v1/me/consents/{purpose}`). A feature that uses personal data for a new purpose checks
  and requests its own purpose when it is first used. Never bundle purposes, never pre-tick, and
  never infer consent on the client alone. `consent_records` is append-only.
- New purposes are added to the allowlist in `backend/src/modules/consents/purposes.ts`, in the
  prompt that ships the feature.
- **No child or teen features, parental control or hidden tracking** without a new ADR and legal
  review.
- **Trusted Circle** must follow [ADR 0011](docs/adr/0011-trusted-circle-principles.md) (mutual,
  visible, time-limited, instant leave, no hidden mode, no remote activation). It is not built
  before the legal review.
- Legal statements in docs and notices are drafts marked "to be verified by a lawyer".

## Deployment rules (since P006a, ADR 0007)

Follow [ADR 0007](docs/adr/0007-gcp-staging-topology.md):

- Claude Code never runs a command that creates, changes or reads cloud resources (`gcloud`,
  `gsutil`, `bq`, cloud APIs) and never runs `gcloud auth`. Read-only local `gcloud <group> --help`
  and local `docker` are fine. Deploys happen only through CI after Rahul merges to `main`.
- Claude Code never handles credentials and never asks Rahul to paste secrets, project IDs,
  project numbers, service-account emails or service URLs. No service-account JSON keys, ever.
- One-time cloud setup is a runbook that Rahul runs:
  [`docs/runbooks/gcp-staging-setup.md`](docs/runbooks/gcp-staging-setup.md). The table of GitHub
  environment secrets and variables lives there (step 9), not in this file.
- The setup is audited, completed and verified with
  [`infra/staging/bootstrap-staging.ps1`](infra/staging/bootstrap-staging.ps1) (since P006c).
  **Rahul runs it; Claude Code never does, in any mode, `-Audit` included**, and never runs
  `gh secret set` or `gh variable set`. Claude Code changes it only with tests that mock `gcloud`
  and `gh`. The script must stay additive: it never deletes, renames or widens anything, never
  creates keys, and never touches Cloud SQL, existing secrets or accounts it doesn't own.
- Resource names in the project are facts, not conventions: the deploy account is `sa-deploy` and
  the instance is `saferoute-db` (ADR 0007, note of 2026-10-02). Don't assume a name; it is a
  parameter of the script.
- `STAGING_DEPLOY_ENABLED` is a **repository** variable. A job-level `if:` can't see environment
  variables.
- The API, the migration job and the admin job run from one image
  ([`backend/Dockerfile`](backend/Dockerfile)). Migrations never run at API startup.
- Workflows that deploy never use `pull_request_target`, never print secrets, environment dumps
  or `gcloud config`, and pin every action by commit SHA.
- Staging deploys (since P006b) run in
  [`.github/workflows/deploy-staging.yml`](.github/workflows/deploy-staging.yml): on a merge to
  `main` that touches `backend/**` or that workflow, or by hand (Actions → deploy-staging → Run
  workflow), and only while the repository variable `STAGING_DEPLOY_ENABLED` is `true`. Order:
  migration job → candidate revision with no traffic → smoke test → promote → smoke test →
  automatic traffic rollback on failure. Don't reorder or skip a step.
- Claude Code can't see a deploy. A staging deploy is **unverified** until Rahul reports the
  Actions run result; say so in the prompt log and the final report.
- Every PR fills the "Deployment impact" line of the PR template (in "Changes"): does it change
  the image, the deploy workflow, cloud configuration or a secret?
- Rollback, manual migration and the `saferoute-admin` job:
  [`docs/runbooks/rollback-staging.md`](docs/runbooks/rollback-staging.md). Log queries and
  metrics: [`docs/runbooks/observability-staging.md`](docs/runbooks/observability-staging.md).
  The database is never rolled back; migrations are fixed forward (ADR 0003).

## Naming rules (since P003c, ADR 0005)

- The product name is **"SafeRoute"**. The former, city-suffixed name remains only in historical
  records (Plan v7 PDF, past prompt logs, ADR bodies) and in `TRADEMARKS.md`, which reserves it.
- Don't put city names in code identifiers, API paths, operationIds, schema/table/column names,
  package names, or Gradle/module names (e.g. no `kolkataRoutes`, `/kolkata/...`,
  `in.saferoute.kolkata`).
- City-specific facts (launch area, map/routing extract, time zone, police-station data, festival
  load planning, local-language copy) live in configuration, data or docs.
- "Kolkata" appears only where it is a fact: the first pilot area and first candidate for a published region, the
  metro routing extent measured in P012, Durga Puja load planning, `Asia/Kolkata` conversions,
  Bengali UI, test landmarks. It is not "the launch city": the launch geography is West Bengal (ADR 0016).
- Multi-city support is deferred to Plan v7 §14.2 Stage 3. Don't build it early. Expansion is by
  "region" and the future optional field is `regionCode`, never `cityCode`
  ([ADR 0013](docs/adr/0013-regions-and-expansion.md)).

## Coverage claims (since P010c, ADR 0016; amended in P010d, ADR 0017)

Follow [ADR 0016](docs/adr/0016-statewide-coverage-layers.md),
[ADR 0017](docs/adr/0017-collect-statewide-publish-by-gate.md) and the addenda
[v7.2](docs/plan/addendum-v7.2.md) and [v7.3](docs/plan/addendum-v7.3.md) in every text that says where SafeRoute
works: UI strings, docs, the README, PR text, pitch material, the website and the store listing.

- **Always use the three layers; never a single "coverage" claim.**
  1. Core safety tools (device-first SOS by SMS, 112 dialer, emergency contacts, live location
     sharing): statewide, wherever cellular signal and GPS exist.
  2. Navigation: map and search statewide; routing statewide only if the P012 measurements pass,
     otherwise the recorded extent and a "Routes aren't available here yet" state, never an error.
     Wording for search (since P011f1): "Maps and search cover West Bengal; local shops and
     small businesses are incomplete because the map data is community-maintained." Never say
     or imply that search finds every bank, pharmacy or shop; "nothing found within N km" means
     nothing is on the map there, never that none exists.
  3. Safety data (community reports, official aggregates, exposure metric): reports are
     **accepted** anywhere inside West Bengal; they are **shown** only in **published regions**.
     Elsewhere: map context and "No community data here yet".
- **Collecting is not publishing.** A region is `context_only` (no reports accepted),
  `collecting` (accepted and moderated, never shown) or `published` (cells shown once the
  k-threshold is met and the publication gate holds: moderator plus backup within the 48 h SLA,
  backlog under the limit, lawyer-reviewed wording live). Don't say "active region".
- **Never claim or imply safety data, safety ratings or a "safe" status outside published
  regions.** No "safe" label, score or ranking anywhere. Absence of data never means "safe". The
  k-threshold (≥3 distinct reporters) is never lowered.
- Allowed wording, as an example: "Report unsafe spots anywhere in West Bengal. Community safety
  data appears where enough reports exist, starting in [list of published regions]. SOS and live
  sharing work wherever there is cellular signal and GPS." Use the current list; while it is
  empty, say that no area shows community data yet. Not allowed: "safety data across West
  Bengal" unless true; any "safe area" claim; unmeasured usage numbers.
- Reporter-facing text never promises publication or a police response, and keeps the
  official-path links (112, police portals).
- Never state user numbers, reliability figures, extents or hit rates that are not measured and
  recorded. Where a value is missing, write "not recorded".
- The pitch, the website, the store listing and `README.md` must match the addenda (v7.3 wins).
  Change the addendum first (in a prompt), then the public text.
- "Kolkata" appears only as a pilot-area fact (see "Naming rules").
- A report with a point outside the West Bengal boundary is rejected with a typed error, never
  dropped silently. Overlays and the exposure metric cover published regions only. The list of
  regions and their statuses is server-side configuration, never an identifier.
- Never show an unverified police number; unknown jurisdiction means 112 only.
- **Essentials (since P011f0).** A list of police stations, hospitals or other essentials
  carries a "may be incomplete" label until that region has been verified (named source, date,
  verifier). Never imply completeness: no "all hospitals", "the nearest police station" or a
  count for an unverified region, and an empty list never means that there is none. 112 stays
  the first answer. Background: addendum v7.3, section I (the map of one small town had its
  roads and buildings but no police station, bus stop, post office, bank, ATM or pharmacy).
- Legal wording about coverage and advertising is a draft marked "to be verified by a lawyer".

## Product guardrails (since P012d, ADR 0021)

Follow [ADR 0021](docs/adr/0021-post-mvp-product-direction.md) and
[addendum v7.4](docs/plan/addendum-v7.4.md), section D, in every future feature, UI string and
public text. The MVP prompts (P013–P022) keep their scope; v7.4 builds nothing.

- No safety score, risk label ("Low risk", "High risk"), ranking or "safe" claim about a place,
  a route or a neighbourhood. Show counts and facts with their period and source.
- No traffic claims without a licensed data source.
- No ads or promotions in any safety flow (SOS, 112, live sharing, check-ins, journeys, alerts,
  reports, the safety layer). No targeting by location history. No selling or sharing of
  personal data.
- Never paywall SOS, 112, basic live sharing or basic check-ins.
- City-neutral naming (ADR 0005), adults only (ADR 0010), and SafeCircle (the Trusted Circle)
  follows [ADR 0011](docs/adr/0011-trusted-circle-principles.md): no code before its lawyer review.
- Journeys: only the traveller starts one; nobody else can start or force it; no continuous
  background tracking beyond a user-started journey; saved places stay on the device until a
  prompt brings the consent purpose, encryption and retention design. A missed arrival prompts
  the traveller first, and the wording is "check in", never "alarm".
- No user-generated content (posts, reviews, ratings, photos, comments) before every
  community-feed gate in addendum v7.4, section F is met. Posts never become safety facts.
- A places or events data source needs a terms check recorded in an ADR before its adapter.
- No revenue claim in public materials until measured.
- **Contributions and rewards** (since P012h, [addendum v7.6](docs/plan/addendum-v7.6.md),
  [ADR 0025](docs/adr/0025-credits-and-place-contributions.md), Proposed: nothing is built, and
  no prompt starts it on the strength of a Proposed ADR):
  - No credits, points, rewards, badges with value or contests for incident or safety
    reports. Safety data is never gamified.
  - No gallery or file uploads for a contribution: a photo comes from the app's own camera
    flow, with the location, its accuracy and the time of capture.
  - Rewards are never paid in cash (or as a wallet transfer) without an ADR and a legal
    review. No points-to-money rate appears in any public text.
  - A place contribution follows the evidence and licence rules of addendum v7.6, sections C
    and E: the contributor's own photo or observation, never Google Maps or another
    protected source.
- **Money, disclosures and dating** (since P012i, [addendum v7.7](docs/plan/addendum-v7.7.md),
  [ADR 0026](docs/adr/0026-daily-use-loop-and-plus-hypothesis.md), Proposed: nothing is built,
  and no prompt starts it on the strength of a Proposed ADR):
  - Never paywall SOS, 112, basic live sharing or basic check-ins (as above), and never sell
    a way around a Trusted Circle principle of ADR 0011, to a person or to an institution.
  - Never charge to read other people's disclosures or safety reports.
  - No confession or anonymous-story feed and no dating features (matching, profiles, chat)
    without an ADR, the gates in addendum v7.7 and a lawyer's review.
  - Dating-related wording says "safer", with the specific feature named; never "safe".
  - Subscriptions, prices, revenue figures and competitor benchmark numbers stay out of the
    repository and out of every public material until validated. They live in the private
    Notion backlog, marked as hypotheses.
- A feature that can't fit these rules needs a new ADR first.

## Live local context (since P012g, ADR 0023, Proposed)

Follow [addendum v7.5](docs/plan/addendum-v7.5.md) and
[ADR 0023](docs/adr/0023-live-local-context-staged-path.md). It is a direction, not scope:
nothing in it is built, and no prompt starts a stage on the strength of a Proposed ADR.

- "Google Maps tells you where to go. SafeRoute tells you what nearby people report along the
  way" is a **long-term direction, never a description of the app today**. Pitch, website,
  store listing and README claims must match the live feature set and the live coverage.
- **Home stays the map.** Local context goes into the Home bottom sheet, in this order:
  (1) a Nearby panel with essentials, labelled "may be incomplete"; (2) curated alerts posted
  by staff from official or verified sources, each source's terms checked first; (3) community
  reports with expiry, confirmations, abuse controls and delays that protect the reporter;
  (4) social posts, only behind every gate of addendum v7.4, section F.
- Before stage 3, every conflict in addendum v7.5, section C needs an answer in an ADR and a
  lawyer's review. **No "normal", "quiet" or "safe" wording**, and never "0 reports" as if it
  meant that nothing is happening.
- No traffic or weather claim without a licensed source.
- Route-affected alerts need server-side matching of a route and a position, background
  location, new consent purposes, retention limits and an opt-in. The promise that routes and
  positions are not stored changes only with explicit consent, a lawyer's review and a
  superseding ADR (ADR 0015, ADR 0022).
- Community content appears in a region only above a minimum density that is recorded in the
  plan first. The k-threshold of the safety layer is never lowered for it.

## Search and geocoding rules (since P011a, ADR 0018)

Follow [ADR 0018](docs/adr/0018-search-and-geocoding.md):

- The geocoding key is **server-only** (`GEOCODING_API_KEY`, Secret Manager → Cloud Run). It is never
  in the app, a tracked file, a log, an error, a diagram, Notion or a chat, and it is not the map
  key. Claude Code never reads it; the evaluation harness (`pnpm search:eval`) is run by Rahul.
- Only `backend/src/modules/search/providers/` knows a geocoding provider. Everything else uses
  `GeocoderProvider`, and the provider is chosen by name (`GEOCODING_PROVIDER`). The contract names
  no provider.
- **Read a provider's full terms before writing its adapter** (proxying, commercial use, caching,
  attribution, safety clauses) and record them in the ADR in your own words. MapTiler geocoding,
  Stadia Maps and the public Nominatim service failed that check; don't add them back without a
  new finding.
- Search is `POST /v1/search` with a JSON body; never a GET with query parameters ("Privacy in
  URLs").
- Search queries, coordinates and results are never logged, stored or cached on the server. The
  adapter's errors carry a kind only (the key travels in the request URL). `near` is coarsened to
  two decimals on the server.
- No recent searches or saved places without a consent purpose and a retention rule (ADR 0010).
- Rate-limit numbers live in `SEARCH_LIMITS` and `SEARCH_GLOBAL_DAILY_LIMIT` only. New rate limits
  use `src/lib/rate-limit.ts`.
- Never state a search hit rate that `pnpm search:eval` did not measure ("Coverage claims").
- Category and brand search (since P011f1): the classifier matches **whole words** from
  `backend/src/modules/search/intents.json` and nothing fuzzy; a new entry says who added it,
  and a Bengali one keeps `review: native-speaker` until a native speaker confirmed it. Such a
  search looks inside a circle (`SEARCH_CATEGORY_RADIUS_KM`, widened once to 25 km, never
  beyond) and **never falls back to places further away or to a fuzzy name search**: an empty
  list is a valid answer. No radius parameter in the contract. Every provider call takes the
  rate-limit tokens. The log line carries the kind of match and counts, never the text, the
  category or the brand.

## Emergency contacts rules (since P013a, ADR 0024)

Follow [ADR 0024](docs/adr/0024-emergency-contacts-and-opt-out.md):

- A contact is a person who is **not a user**. Names, phone numbers and opt-out tokens never
  appear in a URL, a log line, an error body, `audit_log.metadata`, a test's output, Notion or a
  prompt log. Tests use `+91000010NNNN` and "Test Contact N".
- **The server sends no message to a contact** (no SMS, push or call) without a prompt that asks
  for it and a lawyer's review. Invites are sent by the user from their own phone.
- Nothing about a contact is stored without the user's `sos_alerts` consent, and withdrawing it
  erases every contact, tombstone and token in the same transaction. Don't weaken either.
- **An opted-out contact is never alerted, invited or added again by that user.** Code that
  reads contacts for an alert must filter on `opted_out_at` (failure matrix).
- The opt-out token goes in the URL **fragment** (`/c#<token>`) and then in a request body.
  Never a path or a query. Only its SHA-256 is stored. The page `/c` stays static, the same for
  everyone, with its hash-based CSP, no cookie and no external resource; changing its script or
  style changes the hash automatically, and `contacts-page.test.ts` must still pass.
- Never expose, or let a response or its timing reveal, whether a number belongs to a
  registered user (`has_app_user_id` stays NULL until P015 decides).
- Limits live in `CONTACT_LIMITS`, `MAX_CONTACTS` and `MAX_TOKENS_PER_CONTACT` only.
- The page wording (English and Bengali) and the tombstone retention are drafts "to be verified
  by a lawyer".

## Android rules (since P007a, ADR 0008)

Follow [ADR 0008](docs/adr/0008-android-foundation.md), [ADR 0009](docs/adr/0009-android-api-client.md)
and [`android/README.md`](android/README.md):

- The application ID and namespace are `com.saferoute.app`. Never change the application ID.
  No city names in packages, classes, resource names or Gradle modules (Naming rules above).
- One Gradle module, feature packages. Create a package only in the prompt that fills it. Every
  version goes in `android/gradle/libs.versions.toml`; stable releases only, and no `@OptIn` of
  an experimental API in production code.
- Every new Kotlin, Gradle, XML and properties file starts with the SPDX line. Third-party
  assets keep their own licence and are listed in [`android/THIRD_PARTY.md`](android/THIRD_PARTY.md).
- Every user-visible string is a resource, in **both** `values/strings.xml` and
  `values-bn/strings.xml`, with the same key. Bengali written by Claude Code is a draft: list
  new strings in the prompt log as "needs human review before release". Write the emergency
  number as `112` in Latin digits.
- Colours, type, shapes and spacing come from `core/designsystem/theme`. The `sos` red is only
  for the emergency button and the emergency dialog. Dynamic colour stays off. Interactive
  elements are at least 48 dp and have a label or content description.
- A screen is a stateless composable (`HomeScreen`) plus a `Route` composable that connects its
  `@HiltViewModel`; state is a `StateFlow` read with `collectAsStateWithLifecycle`. Only
  `navigation/` knows which screen leads where. Features depend on `core`, never the reverse.
- Emergency surfaces open the dialer with `ACTION_DIAL` and `tel:112`. Never `ACTION_CALL`,
  and never wording that suggests the app calls for help by itself.
- Add a permission to the manifest only in a prompt whose feature needs it, and explain it in
  the prompt log. `allowBackup` stays `false`.
- Network code follows [ADR 0009](docs/adr/0009-android-api-client.md) (since P008a): never
  hand-write API models (they are generated from `contracts/openapi.json` at build time; never
  edit or commit generated code); never send tokens to non-API hosts; never log bodies. Only
  `core/network` uses OkHttp or Retrofit; the rest of the app calls `apiCall { }`. The API base
  URL comes from the Gradle property `saferoute.apiBaseUrl` and is never written in a tracked
  file, printed or read from the user-level `gradle.properties` by Claude Code.
- Debug-only tools (since P008b) live in `app/src/debug` with empty stand-ins in
  `app/src/release`; nothing in `src/main` refers to them (`DeveloperToolsLayoutTest`, and
  `android-ci` scans the release APK). Their tests go in `src/testDebug`.
- Sign-in follows [ADR 0012](docs/adr/0012-firebase-config-in-builds.md) (since P009b):
  onboarding order is **age → consent → phone**, and nothing is sent to any server before the
  first two. Only `core/auth` uses the Firebase SDK; the rest of the app uses `PhoneAuthGateway`.
  Never log, store or put in a test a real phone number, ID token, SMS code, verification id or
  Firebase uid; tests use `FakePhoneAuthGateway` and its `FAKE_...` values and never start
  Firebase (a Hilt test that reaches sign-in replaces `AuthModule`). DataStore holds flags only.
  No other Firebase product (Analytics, Crashlytics, App Check) without a prompt that asks for it.
- Onboarding (since P009c): the session state decides the screen (`navigation/OnboardingNavHost`);
  Home is shown only for `SessionState.Ready`. "I am under 18" has no in-app undo and needs the
  confirmation dialog first (ADR 0010, addendum). The consent notice text lives in the `notice_*`
  strings and is mirrored in `docs/legal/consent-notice-v1.md` (`ConsentNoticeDocumentTest`);
  changing it means changing `NOTICE_VERSION`. It is a draft until a lawyer has reviewed it. A UI
  test that starts `MainActivity` replaces `SessionBindingModule` with `FakeSession`.
- Work that changes the session state runs in the app-lifetime scope (`@ApplicationScope`, inside
  `SessionRepository`), never in a screen's `viewModelScope`: a state change replaces the screen,
  which cancels that screen's ViewModel (the P009d bug). A loading state must always end, in
  `Ready` or in the retry screen, within `SESSION_CHECK_TIMEOUT_MILLIS`. Any change to this flow
  needs a test with the REAL `SessionRepository` in which the calling screen is cleared mid-flow
  (`PostSignInFlowTest`, `SignInToHomeTest`); a `FakeSession` test alone cannot see such a bug.
- The map follows [ADR 0015](docs/adr/0015-map-stack-and-location-policy.md) (since P010a):
  only `core/map/MapLibreEngine.kt` imports MapLibre (`MapLibreBoundaryTest`); the rest of the
  app uses `MapEngine`, `MapController` and the plain types in `MapTypes.kt`, and tests use
  `FakeMapEngine`. Provider settings (style names, URL, credit, cache size) live in
  `MapProviderConfig.kt` only. The map key comes from the Gradle property
  `saferoute.mapTilerKey` and is never written in a tracked file, printed, logged, shown or
  read from the user-level `gradle.properties` by Claude Code. Never give the API's OkHttp
  client to the map (`setOkHttpClient`). The credit line stays visible whenever a map is
  shown. A map failure must never affect the emergency button. Changing the map or tile
  provider needs a superseding ADR and a parity test suite at `MapController`. Never weaken
  `checkReleaseMapKey`.
- Location follows ADR 0015, "Location policy" (since P010b): **foreground only** (no
  `ACCESS_BACKGROUND_LOCATION`, ever, without a prompt that asks for it; `MainActivityTest`
  pins the list). The one exception (since P014a2, ADR 0027): during an SOS the user started,
  the foreground service `SosForegroundService` (type `location`) keeps positions arriving,
  and `SosTrail` stores them in `sos_points` on the phone. No other code may start a
  foreground service, use `TrailLocationSource` or store a position. Outside an SOS,
  positions are never stored, logged or printed,
  and the types that hold one hide it in `toString()`; a position leaves the phone only in a
  request the user started and the disclosure names (directions: the start of the route;
  search, since P011e2: the position rounded to two decimals); the system permission dialog is
  requested only after the user tapped "my location" and continued past the disclosure, never
  at app start or in onboarding, and never in a loop; the permission state is recomputed on
  every resume. Only `core/location/FusedLocation.kt` uses `play-services-location`; the rest
  of the app uses `LocationRepository`, and tests use `FakeLocationRepository` and
  `FakeLocationEnvironment`. Tests use round fixture coordinates, never a real place of a
  person. The disclosure wording is a draft until a lawyer has reviewed it; changing what
  location is used for means changing the disclosure in the same prompt.
- Search follows [ADR 0018](docs/adr/0018-search-and-geocoding.md) (since P011b): the app calls
  only the SafeRoute API for search, never a geocoding provider, and holds no geocoding key.
  Only `feature/search/SearchRepository.kt` uses `SearchApi`; tests use `FakeSearchRepository`
  (`FakeSearchModule` replaces the real one in every Hilt test). The typed text, the results
  and the chosen place are never logged, stored or sent anywhere else; the types that hold
  them hide them in `toString()`. The search area (since P011e2) is decided only in
  `feature/search/SearchArea.kt`: the user's position when the permission is granted and the
  fix is at most 5 minutes old, otherwise the map's centre, otherwise none; always rounded to
  two decimals before it leaves the phone. Search never asks for a permission and never starts
  location updates. Distances shown are the server's `distanceMeters`, labelled with what they
  are measured from, and make no claim about a place. No recent searches or saved
  places without a prompt that brings the consent purpose. A newer answer must never be
  replaced by an older one (`collectLatest`; `SearchViewModelTest` proves it), and no
  experimental coroutine API (`debounce`, `flatMapLatest`) is used. The credit line from the
  API stays visible with the results. Search screens and Home talk through
  `core/map/MapSelection`, and a chosen place is drawn as an overlay description, so MapLibre
  types stay in `MapLibreEngine.kt`. Quick-search chips (since P011f2) send `category` and no
  text; the app holds no dictionary of category words, the server classifies typed text. The
  line above the results comes from the server's `searchedRadiusKm` and `searchedAround`, and
  never says "your location" about a circle around a typed place or an unknown centre. An
  empty circle reads "Nothing found within N km": never that no such place exists, and no
  "best", "nearest" or rating wording on chips or rows. The OpenStreetMap link is
  `feature/search/MapSite.kt` (`ACTION_VIEW`, a fixed address, no extras).
- Following a route follows [ADR 0022](docs/adr/0022-follow-me-navigation.md) (since P012c2a):
  **on screen only**. No foreground service, background location, notification or new
  permission without a superseding ADR and a prompt that asks for it. It starts only with
  precise location and a position from the last 10 seconds, and is never restored after the
  process ended. The route, the progress and the positions stay in memory: never saved,
  logged or printed. **No automatic rerouting**: a new route is requested only when the user
  taps "Recalculate", one request at a time, with a time limit. The arithmetic lives in
  `feature/directions/RouteProgress.kt` (`RouteTracker`, plain Kotlin, no clock of its own);
  its numbers change only with a note in ADR 0022. The screen is kept on only through
  `KeepScreenOn`, only while following. The banner states a distance, a time and a clock
  time: no label, score or colour about safety or traffic, and it never reaches the SOS
  control (`FollowScreenTest`). A test that leaves following running must end it (the ticker
  never stops by itself in virtual time; see `FollowRouteTest.followTest`). Since P012c2b a
  route may start at a place chosen in search (`MapSelection.choosingStart`,
  `DirectionsUiState.Open.origin`): it is memory only like every place, and such a route can
  be previewed but **never followed**. A tap on a route's line selects it: the hit test is
  `nearestLine` in `core/map/RouteHitTest.kt` (screen pixels, no map types), and the list
  and the map agree through `RouteDisplay`.
- Emergency contacts follow [ADR 0024](docs/adr/0024-emergency-contacts-and-opt-out.md)
  (since P013b1): the server is the source of truth and the phone keeps a copy in Room. Only
  `core/data` uses Room (`ContactsBoundaryTest`); the rest of the app uses
  `ContactsRepository` and `ActiveSosContacts`, and tests use `FakeContactsRepository`
  (`FakeContactsModule` and `TestDatabaseModule` replace the real ones in every Hilt test).
  A failed fetch never changes the copy; every change goes to the server first.
  `ActiveSosContacts` reads the phone only and never returns an opted-out contact. The copy is
  emptied when the session leaves the signed-in states (`ContactsSessionSync`). Names, numbers
  and the invite token are never logged, and the types that hold them hide them in
  `toString()`; the token is never stored. No `READ_CONTACTS`, `SEND_SMS` or message sent by
  the app without a prompt that asks for it. A table change needs a new database version, its
  committed schema file, a `Migration` and a migration test; never
  `fallbackToDestructiveMigration`. Screens (since P013b2): a number is picked only through
  the system contact picker (`pickPhoneNumberIntent`), and an invite leaves the app only
  through `openInviteSms` (`ACTION_SENDTO`); both live in `feature/contacts/ContactIntents.kt`.
  The `sos_alerts` notice is the `contacts_notice_*` strings, mirrored in
  `docs/legal/sos-alerts-notice-v1.md` (`ContactsNoticeDocumentTest`); changing it means
  changing `SOS_ALERTS_NOTICE_VERSION`, and it is asked for only when "Add a contact" opens.
  Until SOS alerts exist, no text may say that a contact is or will be messaged: "in a later
  version". What a person typed or picked, a contact and the invite link stay in memory:
  never in `rememberSaveable`, a `SavedStateHandle` or a navigation argument (an argument
  carries the contact's id only). A Hilt test that needs the Home card calls
  `FakeContactsPreferences.clearSnooze()`; it is hidden by default in tests.
- SOS on the device follows [ADR 0027](docs/adr/0027-sos-device-flow.md) (since P014a1): the
  record in Room is the emergency. Only `core/emergency/SosEngine` changes its state, and it
  **writes the new state first**; a side effect (vibration, a message, the location trail)
  happens only after the engine said yes. Never keep an emergency's state only in memory, a
  ViewModel or DataStore, and never run a timer that is not rebuilt from `countdownEndsAt`.
  A countdown found after its end is **asked about** ("Send now or Cancel"), never sent or
  dropped silently. Cancel exists only before the alert; afterwards only "I'm safe". The
  package is `core/emergency` (a package named after the three letters would trip
  `SosColourUsageTest`). `core/emergency` and the SOS files of `core/data` never log, never
  touch the network and hold no means to send a message (`SosCoreBoundaryTest`); the types
  that hold a position or a contact hide it in `toString()`. Records and points are purged
  after 30 days and wiped when the session leaves the signed-in states (`SosHousekeeping`).
  Since P014a2 the emergency is run by `core/emergency/SosRunner` in the app-lifetime scope
  (never a screen's scope, never logic inside the service class): the service is only its
  host (`SosHost`), is `START_NOT_STICKY`, and is skipped when there is no location
  permission or Android refuses the start. **Location never blocks an SOS** and the SOS never
  asks for a permission: every way of not getting a position ends in a `SosTrailMode`, not
  in a wait or an error. The running-SOS notification holds fixed text only. Nothing resumes
  an emergency in the background; `SosRunner.resume()` is called when a screen is visible.
  A Hilt test never starts the real service, vibration or WorkManager
  (`FakeSosDeviceModule`); a test of the runner uses `SchedulerClock` so that the clock and
  virtual time agree.
  Screens (since P014a3): the arm step is `EmergencyDialog` with `EmergencyArm`; the
  emergency screen is `EmergencyActivity` with `SosViewModel`, which sends every command to
  the runner in the **app** scope and never starts an emergency from a re-created screen
  (`fresh`). Back is not Cancel. "I'm safe" always goes through the unlock
  (`requestDismissKeyguard`) and a confirmation; a notification action may only open the
  screen. **No SOS text says that a contact is or will be messaged** until the SMS alerts
  exist (P014b): "start", not "send" (`SosScreensTest`). Every SOS screen shows "SafeRoute
  is not an emergency service. Call 112." and a Call 112 button; Cancel is at least 72 dp
  and never red. Anything shown on the lock screen is a status in words or a count, never a
  name, a number or a place. A practice run lives in the ViewModel only: no runner, record,
  service, contact or position. The red may be used by `SosScreens.kt` as well
  (`SosColourUsageTest`). What location is used for during an SOS is said in
  `sos_arm_location_note`; changing the use means changing that text.
  Every SOS change updates [`docs/sos/failure-matrix.md`](docs/sos/failure-matrix.md):
  "Automated pass" only for rows with passing tests, with the level it was tested at.
- `android/app/google-services.json` is never opened, printed or committed by Claude Code. CI and
  fresh clones use `android/scripts/write-dummy-google-services` (project `demo-saferoute`); a
  release build refuses the dummy or a missing file unless `-Psaferoute.allowDummyFirebase=true`
  (CI's release-assembly check only). Never weaken `checkReleaseFirebaseConfig`.
- Never commit `local.properties`, keystores (`*.jks`, `*.keystore`) or `google-services.json`.
  Before shipping run `git ls-files android | grep -iE "local.properties|\.jks|google-services\.json"`:
  it must print nothing. (The script `android/scripts/write-dummy-google-services` is tracked on
  purpose; it contains fake values only.)
- Claude Code has no phone or emulator. It verifies with JVM tests (Robolectric) and says so;
  on-device checks are steps for Rahul in "How Rahul can verify".
- If `gradlew` can't find a JDK, set `JAVA_HOME` to Android Studio's bundled JDK for that
  command only. Never change it persistently.
- Quality gate, inside `android/`: `./gradlew lint testDebugUnitTest assembleDebug`.

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
| Backend | `pnpm typecheck` · `pnpm lint` · `pnpm format:check` · `pnpm test` · `pnpm build` (run inside `backend/`; CI: `.github/workflows/backend-ci.yml`). `pnpm search:eval` is a measurement Rahul runs with his own key; it is not part of the gate and never runs in CI | Active (since P002) |
| Contracts | in `backend/`: `pnpm openapi:generate` then commit the diff · `pnpm openapi:check` · `pnpm openapi:lint`; breaking changes need the PR label `breaking-api-change` + a new ADR (CI: `backend-ci` runs check + lint; `.github/workflows/contracts-ci.yml` runs them plus the oasdiff breaking-change gate) | Active (since P004a; oasdiff gate since P004b) |
| Container | in `backend/`: `node scripts/container-smoke.mjs` (needs Docker; builds the image and runs the smoke checks, including `scripts/smoke.mjs` pass and fail paths) · actionlint for workflow changes (CI: `.github/workflows/container-ci.yml`) | Active (since P006a) |
| Deploy workflow | actionlint + shellcheck clean · no `pull_request_target` · every action pinned by commit SHA · deploy job gated on `STAGING_DEPLOY_ENABLED` · no step prints secrets, the environment or `gcloud config` (CI: the `actionlint` job in `container-ci`; the deploy itself runs only on `main`) | Active (since P006b) |
| Infra scripts | `infra/staging/tests/Invoke-InfraCheck.ps1` with Windows PowerShell 5.1 **and** PowerShell 7 where installed: PSScriptAnalyzer (0 findings) and Pester (mocked `gcloud`/`gh`; needs `node`, no cloud access). Scripts stay pure ASCII (CI: `.github/workflows/infra-ci.yml`, Linux and Windows) | Active (since P006c) |
| Android | `./gradlew lint testDebugUnitTest assembleDebug` (run inside `android/`; lint errors fail, warnings don't; tests are JVM + Robolectric, no device; needs `app/google-services.json`, real or dummy) · `./gradlew assembleRelease -Psaferoute.apiBaseUrl=https://example.invalid/` (add `-Psaferoute.mapTilerKey=dummy-map-key-for-ci -Psaferoute.allowDummyMapKey=true` on a machine without a map key) when build files, `src/release` or `src/debug` change · actionlint for workflow changes (CI: `.github/workflows/android-ci.yml`, which also runs on `contracts/**`, scans the release APK, since P009b writes the dummy Firebase file and proves the release guard, and since P010a proves the map-key guard and checks 16 KB page alignment of the native libraries) | Active (since P007a; release checks since P008b) |
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
