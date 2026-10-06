# CLAUDE.md: SafeRoute

Claude Code loads this file at the start of every session. Read it fully before doing anything.
The full design lives in [`docs/plan/SafeRoute_Plan_v7_MVP.pdf`](docs/plan/SafeRoute_Plan_v7_MVP.pdf)
(cited as "Plan v7 §N"), amended by [`docs/plan/addendum-v7.1.md`](docs/plan/addendum-v7.1.md)
(see "Plan addendum" below).

## Plan addendum (since P009a)

Read [`docs/plan/addendum-v7.1.md`](docs/plan/addendum-v7.1.md) after the PDF. **Where the addendum
differs from Plan v7, the addendum wins.** It records what was built differently from v7, the
product decisions made since (SOS entry points for P014, adults only, per-purpose consent, Trusted
Circle principles), the section edits to v7, the roadmap order and new risks. The PDF itself is
never edited; a full v8 comes after the MVP.

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
| **Android application ID is `com.saferoute.app`** (chosen in P007); it never changes after publishing | Plan v7 §15.1, ADR 0008 |

## Repo map

```text
android/        Kotlin + Compose app (from P007; Android Studio opens this folder)
backend/        Hono + Zod API and pg-boss worker (from P002)
moderation/     Moderator web app (P018)
contracts/      openapi.json, generated, never hand-edited (from P004)
infra/          GCP / Cloud Run / WIF configuration (from P006)
tools/diagrams/ JSON → Excalidraw + SVG + PNG diagram generator
docs/plan/      Plan v7 PDF (source of truth for design) + addendum-v7.1.md (wins where it differs)
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
- "Kolkata" appears only where it is a fact: the launch city, pilot areas, the OSRM/tiles extract,
  Durga Puja load planning, `Asia/Kolkata` conversions, Bengali UI, test landmarks.
- Multi-city support is deferred to Plan v7 §14.2 Stage 3. Don't build it early. Expansion is by
  "region" and the future optional field is `regionCode`, never `cityCode`
  ([ADR 0013](docs/adr/0013-regions-and-expansion.md)).

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
| Backend | `pnpm typecheck` · `pnpm lint` · `pnpm format:check` · `pnpm test` · `pnpm build` (run inside `backend/`; CI: `.github/workflows/backend-ci.yml`) | Active (since P002) |
| Contracts | in `backend/`: `pnpm openapi:generate` then commit the diff · `pnpm openapi:check` · `pnpm openapi:lint`; breaking changes need the PR label `breaking-api-change` + a new ADR (CI: `backend-ci` runs check + lint; `.github/workflows/contracts-ci.yml` runs them plus the oasdiff breaking-change gate) | Active (since P004a; oasdiff gate since P004b) |
| Container | in `backend/`: `node scripts/container-smoke.mjs` (needs Docker; builds the image and runs the smoke checks, including `scripts/smoke.mjs` pass and fail paths) · actionlint for workflow changes (CI: `.github/workflows/container-ci.yml`) | Active (since P006a) |
| Deploy workflow | actionlint + shellcheck clean · no `pull_request_target` · every action pinned by commit SHA · deploy job gated on `STAGING_DEPLOY_ENABLED` · no step prints secrets, the environment or `gcloud config` (CI: the `actionlint` job in `container-ci`; the deploy itself runs only on `main`) | Active (since P006b) |
| Infra scripts | `infra/staging/tests/Invoke-InfraCheck.ps1` with Windows PowerShell 5.1 **and** PowerShell 7 where installed: PSScriptAnalyzer (0 findings) and Pester (mocked `gcloud`/`gh`; needs `node`, no cloud access). Scripts stay pure ASCII (CI: `.github/workflows/infra-ci.yml`, Linux and Windows) | Active (since P006c) |
| Android | `./gradlew lint testDebugUnitTest assembleDebug` (run inside `android/`; lint errors fail, warnings don't; tests are JVM + Robolectric, no device; needs `app/google-services.json`, real or dummy) · `./gradlew assembleRelease -Psaferoute.apiBaseUrl=https://example.invalid/` when build files, `src/release` or `src/debug` change · actionlint for workflow changes (CI: `.github/workflows/android-ci.yml`, which also runs on `contracts/**`, scans the release APK, and since P009b writes the dummy Firebase file and proves the release guard) | Active (since P007a; release checks since P008b) |
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
