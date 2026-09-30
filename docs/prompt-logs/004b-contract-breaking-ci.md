# P004b: Contract breaking-change CI (contracts-ci + oasdiff) and pipeline diagram

| Field | Value |
| --- | --- |
| Prompt | P004 · OpenAPI contract pipeline, **part b of 2** (part a: [`004a-openapi-contract.md`](004a-openapi-contract.md), PR #6) |
| Milestone | M1 (depends on P004a, merged as `1883f2b`) |
| Branch | `feat/004b-contract-breaking-ci` |
| PR title | `feat(contracts): contracts-ci breaking-change gate with oasdiff, fixtures and pipeline diagram [P004b]` |
| Notion | P004b row in the Prompt Log |
| Date | 2026-10-01 |
| Plan refs | Plan v7 §6.2, §13.2, §17, §17.7 |

## 1. Objective

Finish P004 with exactly the items deferred in the P004a split:

- the `contracts-ci` workflow (R8): stale check, lint, and an oasdiff breaking-change diff against
  the base branch, with the `breaking-api-change` label + new-ADR escape hatch;
- the oasdiff fixtures and CI self-test;
- the pipeline diagram (§14).

Nothing new was added.

## 2. Context & prerequisites

- P004a merged (PR #6, squash `1883f2b`, 2026-09-30 22:42 UTC). `main` now contains
  `contracts/openapi.json`, so this PR's `contracts-ci` runs a **real** base diff instead of the
  "no base spec" skip. The expected result is "No changes detected".
- The deferred work was built and tested before the split on the local branch `wip/004-full`
  (never pushed). It was **ported file by file** onto fresh `main` with
  `git checkout wip/004-full -- <paths>`. The wip branch was not merged.
- The label `breaking-api-change` already exists (created during P004a).

## 3. Workflow executed

1. `/start-prompt`: `git pull --ff-only` → `1883f2b`, clean, hooks active, P004a merged, no open
   PRs. Notion: P004a → Merged (SHA, date); P004b → In progress.
2. `git diff --stat main wip/004-full` to list what was left. The only items missing from `main`
   were the workflow, the 3 fixtures and the diagram. Every other difference was P004a's
   interim wording or the prompt log.
3. `git switch -c feat/004b-contract-breaking-ci`, then ported the files (`git checkout
   wip/004-full -- .github/workflows/contracts-ci.yml backend/test/contract-fixtures
   docs/diagrams/004-openapi-contract-pipeline.*`).
4. Updated the interim "arrives in P004b" wording in `CLAUDE.md`, `contracts/README.md` and
   `backend/README.md`. ADR 0004 is left unchanged (accepted; its "P004b" references stay
   accurate).
5. Ran the full gate, the oasdiff self-test and a real diff against `main` locally, plus
   actionlint and the diagrams check (§6). Then this log and `/ship-prompt`.

Workflow checks done while it was built (on `wip/004-full`, same file): actionlint 1.7.12 OK. I
also simulated all five decisions of the gate locally with the fixtures:

- additive change → pass;
- breaking, no label, no ADR → fail;
- breaking, label only → fail;
- breaking, ADR only → fail;
- breaking, label + ADR → pass.

## 4. Changes

| Area | Files |
| --- | --- |
| CI | `.github/workflows/contracts-ci.yml` (new) |
| Fixtures | `backend/test/contract-fixtures/{base,additive,breaking}.json` (new, OpenAPI 3.1) |
| Diagram | `docs/diagrams/004-openapi-contract-pipeline.{json,excalidraw,svg,png}` (new) |
| Docs | `CLAUDE.md` (contracts gate row), `contracts/README.md`, `backend/README.md` |
| Prompt log | this file |

### `contracts-ci.yml`

- **Triggers:** `pull_request` (`opened`, `synchronize`, `reopened`, `labeled`, `unlabeled`, so
  adding the label re-runs it) and `push` to `main`. Path filters: `backend/**`, `contracts/**`
  and the workflow file.
- **Permissions:** `contents: read` only. The label is read from the event payload, so no
  `pull-requests: read` is needed.
- **Pins:** every action by full commit SHA (same pins as `backend-ci`). oasdiff **1.32.1** is
  downloaded with its sha256 checked (`sha256sum --check --strict`).
- **Steps:**
  1. checkout (`fetch-depth: 0`, no persisted credentials) → pnpm and Node from `.nvmrc`
     (cached) → `pnpm install --frozen-lockfile`;
  2. `pnpm openapi:check` → `pnpm openapi:lint`;
  3. install oasdiff;
  4. **self-test:** the additive fixture must pass, and the breaking one must fail and name
     `new-required-request-property`, `response-required-property-removed` and
     `api-path-removed-without-deprecation`;
  5. **PR only:** `git show origin/<base>:contracts/openapi.json`. If it doesn't exist, the job
     prints a notice and skips. Otherwise `oasdiff changelog -f markdown` goes to the job summary
     and `oasdiff breaking --fail-on ERR` runs. On a breaking change the job passes only with
     the label `breaking-api-change` **and** a new file under `docs/adr/` (added in the PR,
     `README.md` excluded). Otherwise it fails with the Plan v7 §6.2 message.
- **Input handling:** the base ref and the label flag go through `env:`, not inline `${{ }}` in
  the script.
- **Not a required check:** path-filtered workflows would block unrelated PRs. Follow-up recorded
  in P004a.

### Fixtures

- `base.json`: 2 endpoints under `/v1`, a nullable field, `examples`, `$ref` components and
  problem+json errors.
- `additive.json`: a new endpoint, an optional response field (nullable latitude) and an optional
  request field.
- `breaking.json`: removes a required response field, adds a required request property and
  removes an endpoint.
- All names are neutral (`items`), with no place names.

## 5. Diagram

`docs/diagrams/004-openapi-contract-pipeline.{json,excalidraw,svg,png}`, 11 nodes:

- Zod route schemas (green) → `pnpm openapi:generate` (green) → `contracts/openapi.json`
  (committed, orange);
- the committed spec → "PR: contracts-ci" (purple), which fans out to `openapi:check` (stale?),
  Redocly lint and "oasdiff breaking vs main" (red);
- oasdiff → "Breaking? needs label + ADR" (red) → "Rahul reviews & merges" (blue);
- the committed spec → "P008: generate Kotlin client (planned)" (grey) → Android app (blue).

The PNG was checked: legible, with no arrows crossing boxes. `pnpm check`: all 6 diagrams up to
date. SVG: [`docs/diagrams/004-openapi-contract-pipeline.svg`](../diagrams/004-openapi-contract-pipeline.svg).

## 6. Quality gate & test results

| Command | Result |
| --- | --- |
| `pnpm install --frozen-lockfile` | Pass |
| `pnpm typecheck` · `lint` · `format:check` (includes the fixtures) · `build` · `db:check` | Pass |
| `pnpm openapi:check` · `pnpm openapi:lint` | Pass (2 documented ignores from P004a) |
| `pnpm test` (unit + db, Docker up) | **12 files, 87/87 passed** (unchanged from P004a) |
| oasdiff self-test (local, 1.32.1) | additive → exit 0; breaking → exit 1 with all 3 expected rule IDs |
| oasdiff vs `main` (local) | `breaking`: no breaking changes; `changelog`: "No changes detected" |
| actionlint 1.7.12 | OK (all workflows; shellcheck not installed locally) |
| markdownlint-cli2 | 0 issues. JSON validity: 25/25. Diagrams `pnpm check`: up to date |

SOS failure matrix (Plan v7 §7.5): **not applicable**. No SOS, share or contacts code.

## 7. Decisions & ADRs

- No new ADR. The policy is ADR 0004 (P004a).
- **Kept P004a's `backend-ci` contract steps** (`openapi:check` + `openapi:lint` and the
  `contracts/**` trigger). `contracts-ci` runs the same checks again. That duplication is
  cheap, and `backend-ci` stays a complete backend gate. Removing the steps would be a scope
  change.
- "New ADR" means a file **added** under `docs/adr/` in the PR (`--diff-filter=A` against the
  merge base), excluding the index `README.md`, so editing an old ADR doesn't count.
- `labeled`/`unlabeled` triggers were added so the gate re-evaluates when Rahul adds the label,
  without a new push.

## 8. Security & privacy notes

- Least privilege: `contents: read`, and checkout doesn't persist credentials.
- oasdiff binary: pinned version plus sha256. Actions: pinned by full SHA.
- Untrusted values (`github.base_ref`, label state) are passed to shell via `env:`, avoiding
  script injection.
- The fixtures and diagram hold no personal data, hosts or place names.

## 9. Known issues & risks

- `contracts-ci` is not a required check (by design, see the follow-up). A red `contracts-ci` on
  a PR must still block the merge by review.
- oasdiff may miss exotic schema changes. The changelog in the job summary is the reviewer's
  second look.
- shellcheck wasn't run locally (not installed); actionlint's structural checks passed.
- The local branch `wip/004-full` can be deleted after this PR is merged
  (`git branch -D wip/004-full`). It was never pushed.

## 10. Follow-ups & prerequisites for next prompt

No new follow-ups. All P004 follow-ups were recorded in P004a:

- P005 `firebaseBearer`;
- P008 Kotlin client + drift check;
- make contracts-ci required;
- staging-only docs UI;
- dev esbuild advisory.

**Next prompt: P005** (`feat/005-firebase-auth-api`). Inputs Rahul must provide:

1. A **Firebase staging project** (separate from production) with the **Phone** sign-in provider
   enabled.
2. Its **project ID**. Code and docs use the placeholder `<FIREBASE_PROJECT_ID>`; the real value
   goes into local `.env` / CI secrets, never into the repo.
3. A decision: tests use the **Firebase Auth emulator** (no real accounts) or a dedicated
   staging test user.
4. No service-account JSON in the repo or the chat. Token verification needs only the project
   ID, since Google's public keys are fetched at runtime.

## 11. How Rahul can verify

1. Read the diff: the workflow, 3 fixtures, the diagram and 3 doc lines.
2. On the PR, open the `contracts-ci` run. The self-test step should show the breaking fixture
   flagged (3 errors), and the job summary should say "API contract changelog (vs main) … No
   changes detected".
3. Look at `docs/diagrams/004-openapi-contract-pipeline.svg`.
4. Optional: on a scratch branch, rename `uptimeSeconds` in `src/routes/health.ts`, run
   `pnpm openapi:generate`, push and open a draft PR. `contracts-ci` should fail with the
   breaking-change message. Close the draft afterwards.
5. Once `repo-checks`, `backend-ci` and `contracts-ci` are green, squash and merge, delete the
   branch, and delete the local `wip/004-full`.

## 12. Learning notes

- **What oasdiff does.** It compares two OpenAPI files and classifies every difference as
  info (e.g. endpoint added), warning or error (breaking, e.g. a required response field
  removed). CI compares the PR's contract with `main`'s and fails on errors. See
  <https://github.com/oasdiff/oasdiff>.
- **The two concrete breaking examples in the fixtures.** Removing the required response field
  `note`: an installed app that reads `note` gets nothing and fails. Adding the required request
  property `kind`: old apps don't send it, so the server now rejects them with 400. By contrast,
  adding an optional field or a new endpoint (the additive fixture) keeps old apps working.
- **Why a label *and* an ADR.** The label proves a human saw the break on purpose. The ADR
  records *how* old apps are protected: a new path version (`/v2/...`) or a coordinated release.
  Apps stay installed for months (Plan v7 §6.2).
- **Why path-filtered checks aren't "required".** GitHub only reports a workflow when it runs.
  A PR that touches only docs never runs `contracts-ci`, so a required check would wait forever
  and block the merge. See
  <https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/troubleshooting-required-status-checks>.
