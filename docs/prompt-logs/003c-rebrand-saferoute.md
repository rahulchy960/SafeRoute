# P003c: Rebrand to "SafeRoute" (city-neutral naming)

| Field | Value |
| --- | --- |
| Prompt | P003c · rename the product to "SafeRoute" and make naming city-neutral (wording only) |
| Milestone | M1 (depends on P003b, merged as `4a1317c`, PR #4) |
| Branch | `chore/003c-rebrand-saferoute` |
| PR title | `chore(repo): rename product to SafeRoute and make naming city-neutral [P003c]` |
| Notion | P003c row in the Prompt Log |
| Date | 2026-10-01 |
| Plan refs | Plan v7 §1, §14.2 (Stage 3), §15.1, §17, §20 |

## 1. Objective

Rahul renamed the product from "SafeRoute Kolkata" to **"SafeRoute"** because more cities are
planned. This prompt updates branding and wording across the repo, Notion and GitHub metadata. It
keeps "Kolkata" wherever it is a fact (launch city, pilot areas, OSRM extract, time zone, test
landmarks). It builds **no** multi-city features and changes no code behaviour, schema, migration
or API.

## 2. Context & prerequisites

- P003b merged (PR #4, squash `4a1317c`, 2026-09-30 20:29 UTC); its Notion row was already
  *Merged*. No open PRs.
- The GitHub repository was already named `SafeRoute`. The Android package name is still
  undecided (P007), so nothing has been published under the old name.
- Plan v7 PDF (`docs/plan/`) is titled "SafeRoute Kolkata" and is CC BY-NC-ND. It was **not**
  edited or re-exported.
- Multi-city support stays in Plan v7 §14.2 Stage 3.

## 3. Workflow executed

1. `/start-prompt`: `git pull --ff-only` (up to date at `4a1317c`), clean tree,
   `core.hooksPath = .githooks`, P003b merged. Notion: created the P003c row (*In progress*) and
   synced one P003b follow-up that was missing from *Follow-ups* (`CREATE INDEX CONCURRENTLY`
   plan).
2. `git switch -c chore/003c-rebrand-saferoute`.
3. **R1 inventory:** `git grep -n -i "kolkata"` and `git grep -n -i "saferoute kolkata"`,
   classified below.
4. **R2–R4 edits** with an exact-match replacement script. Each replacement asserted exactly one
   match, so nothing else changed silently. Then reflowed the edited paragraphs.
5. **R7:** ADR 0005 plus a one-line note under the title of ADRs 0001 and 0002. Row added to
   `docs/adr/README.md`.
6. **R5 Notion:** hub page renamed to "SafeRoute — Engineering" (intro line updated), ADR 0005
   row, five follow-ups (§10), P003c row.
7. **R6 GitHub:** `gh repo edit --description "SafeRoute: personal-safety navigation and SOS app
   (Android, Kotlin) with a TypeScript/PostgreSQL backend"`, then checked with `gh repo view`.
   Topics, visibility, settings and rulesets were not changed.
8. Re-ran both greps (§6), ran the quality gate, wrote this log, then `/ship-prompt`.

### R1 classification

Class (a) = product name → changed to "SafeRoute". Class (b) = factual geography → kept.
Class (c) = historical record → kept.

| File | `kolkata` hits | Class | Action |
| --- | --- | --- | --- |
| `README.md` | 4 | (a) | Title, intro, name/logo line, Notion name changed; new "Coverage" section mentions Kolkata as the launch city (b) |
| `CLAUDE.md` | 3 | (a) | Title, project summary, Notion name changed; new "Naming rules" section cites Kolkata facts (b) |
| `TRADEMARKS.md` | 2 | (a) | Reserves "SafeRoute" and the former name; the factual-use example is now "based on the SafeRoute source code" |
| `COPYRIGHT.md` | 1 | (a) | Name row: "SafeRoute" (and its former name) |
| `docs/LICENSE.md` | 2 | (a) | Attribution "Rahul Chowdhury, SafeRoute"; name line |
| `CONTRIBUTING.md` | 1 | (a) | Changed |
| `SECURITY.md` | 1 | (a) | Changed |
| `android/README.md` | 1 | (a) | Changed |
| `backend/README.md` | 1 | (a) | Changed |
| `backend/package.json` | 1 | (a) | `description` changed (metadata only) |
| `.claude/commands/start-prompt.md` | 1 | (a) | Notion page name |
| `backend/src/db/README.md` | 2 | (b) | Kept: lng/lat sanity check, UTM zone 45N |
| `backend/src/db/client.ts` | 1 | (b) | Kept: `Asia/Kolkata` time zone |
| `backend/test/db/postgis-spike.test.ts` | 2 | (b) | Kept: public landmarks |
| `docs/adr/0003-database-conventions-and-migrations.md` | 1 | (b) | Kept: `Asia/Kolkata`; no old product name, so no note |
| `docs/diagrams/02-mvp-architecture.{json,excalidraw,svg}` | 1 / 2 / 1 | (b) | Kept: "OSRM Kolkata extract". No diagram title used the old name, so nothing was regenerated |
| `docs/adr/0001-kotlin-native-openapi.md` | 1 | (c) | Body kept; P003c note added under the title |
| `docs/adr/0002-licensing.md` | 1 | (c) | Body kept; P003c note added under the title |
| `docs/plan/SafeRoute_Plan_v7_MVP.pdf` | 2 (binary) | (c) | Not touched |
| `docs/prompt-logs/001-*.md`, `002-*.md`, `003b-*.md` | 4 / 2 / 3 | (c) | Not touched |

`.env.example`, `CODEOWNERS`, the PR template, `tools/diagrams` and the diagram titles in
`docs/diagrams/*.json` had no hits, so nothing changed there.

## 4. Changes

| Area | Files |
| --- | --- |
| Branding / governance | `README.md` (+ "Coverage"), `CLAUDE.md` (+ "Naming rules"), `TRADEMARKS.md`, `COPYRIGHT.md`, `SECURITY.md`, `CONTRIBUTING.md`, `docs/LICENSE.md` |
| Folder READMEs / metadata | `android/README.md`, `backend/README.md`, `backend/package.json` (`description` only) |
| Slash command | `.claude/commands/start-prompt.md` (Notion page name) |
| ADRs | new `docs/adr/0005-product-name-and-multi-city-readiness.md`; note line in 0001 and 0002; index row in `docs/adr/README.md` |
| Prompt log | this file |
| Outside the repo | GitHub repo description; Notion hub title and intro; Notion rows (P003c, ADR 0005, 6 follow-ups) |

No API contract diff, no migration, no source-code change.

## 5. Diagram

No diagram needed. No flow, state machine, schema, API sequence, infrastructure or CI change, and
no diagram title contained the old name.

## 6. Quality gate & test results

| Check | Command | Result |
| --- | --- | --- |
| Old-name grep | `git grep -n -i "saferoute kolkata"` | Hits only in: plan PDF (binary), past prompt logs, ADR 0001/0002/0005 bodies, `TRADEMARKS.md` (former-name lines). No class (a) left |
| City grep | `git grep -n -i "kolkata"` | Remaining hits are all class (b) or (c), plus the new README "Coverage" and CLAUDE.md "Naming rules" sections |
| Markdown lint | `npx markdownlint-cli2 "**/*.md"` | Pass (0 errors) |
| JSON validity | same Node script as `repo-checks.yml` | Pass |
| Backend | `pnpm typecheck` · `pnpm lint` · `pnpm format:check` · `pnpm build` | Pass |
| Backend tests | `pnpm test` (Docker up) | 10 files, **73/73 passed** |
| Contracts | `pnpm openapi:*` | Skipped: doesn't exist yet (P004) |
| Secret scan | gitleaks | Runs in CI (`repo-checks`) |

SOS failure matrix (Plan v7 §7.5): not applicable, no SOS, live-location or contacts code.

## 7. Decisions & ADRs

- **ADR 0005** (Accepted): product name "SafeRoute"; both names reserved in `TRADEMARKS.md`;
  city-neutral naming rule; multi-city deferred to Stage 3, with a list (no implementation) of what
  a future city needs: city code on reports and official data, routing graph and tile extent,
  moderation coverage, consent and language copy, police and data sources.
- ADR number: the prompt set 0005. 0004 stays free.
- `TRADEMARKS.md` also asks forks not to use "SafeRoute" combined with a place name, so a fork
  can't pick "SafeRoute <City>".
- In `COPYRIGHT.md`, `docs/LICENSE.md` and `README.md` the old name is described as "its former
  name", so the literal string appears only in `TRADEMARKS.md` (the single source for name
  reservation).

## 8. Security & privacy notes

- No new data, secrets, emails, phone numbers or local paths were added. The GitHub description
  contains no personal information.
- Notion was written only in Rahul's Engineering workspace, through the Notion plugin connector.

## 9. Known issues & risks

- The Plan v7 PDF still carries the old name. That is intentional (CC BY-NC-ND, historical); a
  Plan v8 is a follow-up for Rahul.
- Links to Plan v7 sections keep working, since the file name did not change.
- The ADR 0005 Notion row links to the branch copy of the file. After merging, the same path
  exists on `main`.

## 10. Follow-ups & prerequisites for next prompt

Recorded in Notion *Follow-ups* (source P003c):

1. Publish Plan v8 under the new name. Rahul does this, not Claude Code (Low).
2. P007: app display name "SafeRoute"; package name decision, with `in.saferoute.app` suggested;
   no city in package/module names (High).
3. P009: city-neutral consent and onboarding copy, English + Bengali; Kolkata only as a coverage
   note (Medium).
4. P017: reports get a `cityCode` column (default `'kol'`) when designed (Medium).
5. Check domain, Play listing and Firebase/GCP project display names for the old name (Low).

Also synced during `/start-prompt`: the P003b follow-up "Plan for `CREATE INDEX CONCURRENTLY`
outside Drizzle's migration transaction".

**Next prompt:** P004 (`feat/004-openapi-contract-pipeline`). Apply the naming rules to
operationIds and schema names.

## 11. How Rahul can verify

1. Read the PR diff: docs/wording only, plus the `description` field in `backend/package.json`.
2. Read the README "Coverage" section and `TRADEMARKS.md`.
3. Run `git grep -n -i "saferoute kolkata"`. It should hit only the plan PDF, old prompt logs, ADR
   bodies and `TRADEMARKS.md`.
4. Check that Notion shows "SafeRoute — Engineering" and that the GitHub repo page shows the new
   description.
5. CI green (`repo-checks`, `backend-ci`). Then squash and merge, and delete the branch.

## 12. Learning notes

- **What a trademark notice does.** Copyright licenses such as the AGPL or CC BY-NC-ND let people
  copy and change the *work*. They don't give anyone the right to use the project's *name or
  logo*. `TRADEMARKS.md` makes that explicit, so a fork has to rename itself and can't pass as
  the official app. That matters for a safety app, where users must know which app actually sends
  their SOS. Reserving the former name too stops someone from reusing it. It's a notice, not a
  registered trademark. See GitHub's guide to
  [licensing a repository](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository).
- **Why identifiers should not contain city names.** Names in code are expensive to change later.
  An Android `applicationId` can **never** change after publishing on Google Play: a new ID is a
  new app, and users lose updates. API paths and operationIds are part of the public contract,
  and renaming them is a breaking change for every installed app. Database table and column names
  need expand → contract migrations. With something like `in.saferoute.kolkata` or
  `/kolkata/routes`, adding a second city would mean either a misleading name forever or a
  breaking change. Keeping identifiers neutral and putting the city in data (e.g. a `cityCode`
  column) or configuration makes the next city an addition instead of a rename. See Android's
  [Configure the app module](https://developer.android.com/build/configure-app-module#set-application-id)
  on why the application ID must stay stable.
