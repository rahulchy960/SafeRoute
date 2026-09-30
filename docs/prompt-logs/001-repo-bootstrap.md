# P001: Repository bootstrap

| Field | Value |
| --- | --- |
| Prompt | P001 · Repo bootstrap |
| Milestone | M0 |
| Branch | `main` (direct; the only direct-to-main prompt) |
| Commit title | `chore(repo): bootstrap SafeRoute monorepo and governance [P001]` |
| Commit #1 | `4936b3033f258f039c71dc5430ec87d692d7ca2a` |
| Commit #2 | this log: `docs(repo): add P001 prompt log [P001]` (SHA in Notion and the final report) |
| Repo | <https://github.com/rahulchy960/SafeRoute> (private) |
| CI | repo-checks run [36752296140](https://github.com/rahulchy960/SafeRoute/actions/runs/36752296140): success |
| Notion | [P001 · Repo bootstrap](https://app.notion.com/p/3eb0737077208177b26dd2e63e1a0aa6) in [SafeRoute Kolkata — Engineering](https://app.notion.com/p/3eb07370772081ea9cecd63786c30322) |
| Date | 2026-09-30 |
| Plan refs | Plan v7 §0, §4, §15.1, §17, §18, §19, §20 |

## 1. Objective

Create the SafeRoute Kolkata monorepo with governance files, commit Plan v7 and four generated
starter diagrams, publish the private GitHub repo, push `main`, and build the Notion engineering
workspace. From P002 on, every prompt runs on its own branch and PR under the rules written here.

## 2. Context & prerequisites

- The folder held only `SafeRoute_Plan_v7_MVP.pdf` and an Android Studio `.idea/` folder. Nothing
  was committed.
- Tools checked at preflight: git 2.43.0, gh 2.102.0 (logged in as `rahulchy960`, scopes `repo`,
  `workflow`), Node v22.17.0, npm 11.5.2, Notion MCP (read of the parent page OK).
- **pnpm was missing.** With Rahul's approval it was installed user-level with `npm install -g pnpm`
  (pnpm 12.8.1, in npm's user-level global folder, which is on the user PATH; new terminals pick
  it up).
- **The GitHub repo `rahulchy960/SafeRoute` already existed** (private, empty, created
  2026-09-30 11:39 UTC). `gh repo create` would have failed, so it was added as `origin` and pushed
  to. The result is the same, and nothing was overwritten.
- **Rahul's home directory (`<home>`) is itself a git repository** (no commits). SafeRoute is now its own nested
  repo, and git commands inside SafeRoute use the SafeRoute repo. Recorded as a follow-up; not
  touched.
- Inputs: owner `rahulchy960`, repo `SafeRoute`, plan PDF path, Notion parent page "SafeRoute".

## 3. Workflow executed

1. **Preflight**: checked git/gh/node/pnpm/Notion; installed pnpm; read Plan v7 §0–§7.5,
   §13–§20 (via `pdftotext`).
2. **Folders + READMEs (R1)**: `android/ backend/ moderation/ contracts/ infra/ tools/diagrams/
   docs/{plan,adr,diagrams,prompt-logs,runbooks} .github/workflows .claude/commands .githooks`.
3. **Governance**: `CLAUDE.md`, `.claude/settings.json`, `.claude/commands/{start,ship}-prompt.md`,
   `.githooks/pre-push`, `.gitmessage`, PR template, `.gitignore`, `.gitattributes`,
   `.editorconfig`, `.markdownlint-cli2.jsonc`, `README.md`.
4. **Diagram tool**: `tools/diagrams` (`pnpm install`, `pnpm generate`, `pnpm check`). The first
   render showed arrowheads pointing the wrong way (resvg ignores `orient="auto-start-reverse"`)
   and crossings in 02. Fixed with `orient="auto"`, lane reordering and a wrapped legend, then
   regenerated and inspected all four PNGs.
5. **Plan + ADRs**: copied the PDF to `docs/plan/`; wrote `docs/adr/template.md` and
   `0001-kotlin-native-openapi.md`.
6. **CI**: `.github/workflows/repo-checks.yml`. Actions are pinned by SHA (checkout v7.0.1,
   setup-node v7.0.0, markdownlint-cli2-action v24.2.0), gitleaks 8.30.1 is checksum-verified,
   and permissions are `contents: read`.
7. **Local checks** (see §6), then:

   ```sh
   git init -b main
   git add -A
   git update-index --chmod=+x .githooks/pre-push
   git commit -F <message>                           # commit #1: 4936b30
   git remote add origin https://github.com/rahulchy960/SafeRoute.git
   git push -u origin main
   gh run watch 36752296140 --exit-status            # success
   ```

8. **Notion**: created "SafeRoute Kolkata — Engineering" with the Prompt Log, Architecture
   Decisions and Follow-ups databases, the Runbooks page, P001–P022 rows and the ADR 0001 entry.
9. **Prompt log**: this file, committed as commit #2 and pushed to `main`.
10. **After commit #2** (recorded in Notion and the final report, and in this log during P002):
    lock main locally (`.claude/settings.local.json` deny rules + `git config core.hooksPath
    .githooks`), re-run the hook test, try the GitHub ruleset, finish the Notion P001 page.

## 4. Changes

| Area | Files |
| --- | --- |
| Governance | `CLAUDE.md`, `.claude/settings.json`, `.claude/commands/start-prompt.md`, `.claude/commands/ship-prompt.md`, `.githooks/pre-push`, `.gitmessage` |
| GitHub | `.github/pull_request_template.md` (11 sections), `.github/workflows/repo-checks.yml` |
| Repo config | `.gitignore`, `.gitattributes`, `.editorconfig`, `.markdownlint-cli2.jsonc`, `README.md` |
| Tools | `tools/diagrams/{package.json,pnpm-lock.yaml,diagram.schema.json,README.md,src/generate.mjs}` |
| Docs | `docs/plan/SafeRoute_Plan_v7_MVP.pdf`, `docs/adr/{README,template,0001-kotlin-native-openapi}.md`, `docs/diagrams/0{1..4}-*.{json,excalidraw,svg,png}`, folder READMEs |
| Folder READMEs | `android/`, `backend/`, `moderation/`, `contracts/`, `infra/`, `docs/{plan,diagrams,prompt-logs,runbooks}/` |

- API contract: none (`contracts/` has only a README; `openapi.json` arrives in P004).
- Database / migrations: none.
- `.claude/settings.json` deny rules: `git push` with `--force`/`-f`/`--force-with-lease`/`+refspec`/
  `--mirror`; `gh pr merge`; `gh pr review --approve`/`-a`; `gh pr close`; `gh api …pulls/*/merge`;
  `Read` of `**/.env`, `**/.env.*`, `**/*.jks`, `**/*.keystore`, `**/google-services.json`,
  `**/*service-account*.json`, `**/*-sa-key*.json`; `Edit` of `.env`/keystores/google-services.json.

## 5. Diagram

Generated by `tools/diagrams` from committed JSON specs, each as `.json` + `.excalidraw` + `.svg` + `.png`:

- [`01-prompt-workflow`](../diagrams/01-prompt-workflow.svg): prompt lifecycle (8 nodes)
- [`02-mvp-architecture`](../diagrams/02-mvp-architecture.svg): MVP topology (12 nodes, 3 lanes)
- [`03-sos-device-first`](../diagrams/03-sos-device-first.svg): device-first SOS flow (11 nodes)
- [`04-scaling-stages`](../diagrams/04-scaling-stages.svg): scaling stages 0–3 (4 nodes)

The PNG renderer is `@resvg/resvg-js` 2.6.2 (prebuilt native binary, installs cleanly on Windows,
no headless browser), so there is no PNG gap.

## 6. Quality gate & test results

| Check | Command | Result |
| --- | --- | --- |
| Markdown lint | `npx markdownlint-cli2 "**/*.md" "#**/node_modules"` | 18 files, **0 issues** |
| JSON validity | Node `JSON.parse` over `*.json` + `*.excalidraw` | **12/12 valid** |
| Secret scan | `gitleaks dir --redact .` (8.30.1) | **no leaks found** |
| Diagrams | `pnpm generate` | 4 diagrams generated, no errors |
| Diagrams deterministic | `pnpm check` | all 4 up to date |
| Tracked secrets | `git ls-files` scan for `.env`, `.jks`, `.keystore`, `google-services.json` | none |
| CI on GitHub | repo-checks run 36752296140 | **success** (all 5 steps) |
| Hook test (script, before lock) | `echo "refs/heads/main 0 refs/heads/main 0" \| .githooks/pre-push origin x` | **exit 1**, "REJECTED push to refs/heads/main" |
| Hook test (script, before lock) | `echo "refs/heads/test 0 refs/heads/test 0" \| .githooks/pre-push origin x` | **exit 0** |
| `.claude/settings.json` | JSON parse + read back | valid; 25 deny rules |

- Backend, contracts and Android gates: not yet applicable (no code).
- SOS failure matrix (Plan v7 §7.5): **no rows apply**, because no SOS, live-location or contacts
  code exists yet.

## 7. Decisions & ADRs

- **ADR 0001 (Accepted):** native Kotlin + Compose client, Hono + Zod → OpenAPI 3.1, Kotlin client
  generated from the spec. It also records the rule that the package name is chosen once and never
  changed (Plan v7 §15.1). `in.saferoute.app` is only a suggestion to confirm in P007.
- Diagram PNGs are rendered with resvg rather than a headless browser (clean Windows install).
- CI's gitleaks runs the checksum-verified release binary instead of `gitleaks-action`, so it
  needs no licence or token and only `contents: read`.
- `.gitattributes` uses `* text=auto eol=lf` so the working tree matches `.editorconfig` (LF)
  regardless of `core.autocrlf`. Hooks and `*.sh` are explicitly LF.
- The main-push deny rules live in the git-ignored `.claude/settings.local.json`, added after
  commit #2 (Plan v7 §17.2).

## 8. Security & privacy notes

- No secrets, tokens, keys, keystores, `.env` files, `google-services.json` or service-account
  files are in the repo (gitleaks + `git ls-files` check). No personal data was committed.
- **No production credentials exist anywhere** in the repo, the Notion workspace or this session.
  No GCP or Firebase setup was done.
- The repo is **private** (verified with `gh repo view`: `visibility: PRIVATE`).
- `.gitignore` covers `.env*`, `*.jks`, `*.keystore`, `*.p12`, `*.pem`, `google-services.json`,
  service-account JSON and `.claude/settings.local.json`.

## 9. Known issues & risks

- Claude Code permission rules match command prefixes and patterns. They can't block every shell
  form (e.g. reading a secret with an unusual command). CLAUDE.md rules, `.gitignore` and gitleaks
  are the backstops.
- A bare `git push` on `main` is blocked by the local deny rule (`Bash(git push)`) and by the
  hook. Claude Code must always push with an explicit branch refspec (documented in CLAUDE.md
  and `/ship-prompt`).
- The pre-push hook only runs where `core.hooksPath .githooks` is set. Each new clone needs it
  (README "One-time local setup").
- A GitHub ruleset on a private repo may need a paid plan. The result is recorded in Notion and
  the final report.

## 10. Follow-ups & prerequisites for next prompt

- **P002 first commit:** add the post-lock hook-test outputs and the ruleset result (from the
  Notion P001 page) to this log as a follow-up item.
- Home-directory git repo at `<home>/.git`: Rahul decides whether to remove it (outside
  this repo).
- Optional: add `pnpm check` (diagrams up to date) to repo-checks CI.
- P002 prerequisites: Node LTS + pnpm (done), `core.hooksPath .githooks` (set after commit #2).
- Next prompt: **P002, `feat/002-backend-skeleton`** (M1). Start with `/start-prompt`.

## 11. How Rahul can verify

1. Open <https://github.com/rahulchy960/SafeRoute> and confirm it is **Private** and has the
   folders listed in §4.
2. Actions tab: the `repo-checks` runs for both commits are green.
3. Open the Notion page "SafeRoute Kolkata — Engineering" and check the Prompt Log rows
   P001–P022, the ADR 0001 entry, Follow-ups and Runbooks.
4. Start a new `claude` session in the repo root and type `/start-prompt`. The command should
   appear (don't run it until P002).
5. In the terminal, `git push origin main` should be rejected by the pre-push hook ("REJECTED push
   to refs/heads/main"). Nothing is pushed.
6. Open `docs/diagrams/*.png`, or load a `.excalidraw` file at <https://excalidraw.com>.

## 12. Learning notes

- **Monorepo:** one git repository holds every part of the product: the Android app, backend,
  moderation web, API contract, infrastructure and docs. One pull request can change the API
  contract and the app together and stay consistent, and CI checks everything in one place. Each
  folder still builds with its own tool (Gradle for `android/`, pnpm for `backend/`).
- **Git hook:** a script git runs automatically at certain moments. `pre-push` runs just before
  `git push` sends anything. Git gives it the list of refs being pushed, and if it exits non-zero
  the push is cancelled. Ours refuses anything aimed at `refs/heads/main`. Hooks are local:
  `git config core.hooksPath .githooks` tells git to use the versioned `.githooks/` folder.
- **Branch ruleset:** a GitHub server-side rule on a branch, such as "changes to main must come
  through a pull request", "the repo-checks status must pass" or "no force-push or deletion".
  Unlike a local hook, it applies to everyone, including anyone pushing from another machine.
  GitHub's free plan may not enforce rulesets on private repositories.
- **CLAUDE.md:** a file Claude Code reads automatically at the start of every session in this
  repo. It works like standing instructions: project summary, the ten golden rules, naming
  conventions, which checks to run, and documentation duties. `.claude/settings.json` adds hard
  permission deny rules (e.g. no force-push, no merging PRs), and `.claude/commands/*.md` defines
  the `/start-prompt` and `/ship-prompt` slash commands.

## Post-lock verification (added in P002)

Recorded on 2026-09-30 during P002 (branch `feat/002-backend-skeleton`). This closes the P001
follow-up "add the post-lock hook-test outputs and the ruleset result".

**Local lock.** `git config core.hooksPath` prints `.githooks`. The git-ignored
`.claude/settings.local.json` holds 22 deny rules (bare `git push`, pushes whose refspec names
`main`, `--all`, `--no-verify`, for both the Bash and PowerShell tools).

**Hook test (re-run in P002):**

```text
$ echo "refs/heads/main 0 refs/heads/main 0" | .githooks/pre-push origin x
pre-push: REJECTED push to refs/heads/main on 'origin' (local ref: refs/heads/main).
pre-push: main is protected. Push your branch and open a pull request:
pre-push:   git push -u origin <type>/<NNN>-<core-work>
exit=1

$ echo "refs/heads/test 0 refs/heads/test 0" | .githooks/pre-push origin x
exit=0
```

**GitHub ruleset.** GitHub refused a ruleset while the repo was private on the free plan. Rahul made
the repo **public** after P001, and P002 created the ruleset `protect-main` (active) on the default
branch: pull request required (0 approvals, stale reviews dismissed, squash merge only), required
status check `repo-checks`, force pushes and branch deletion blocked, repository admins may bypass
only through a pull request. Details and read-back:
[`002-backend-skeleton.md`](002-backend-skeleton.md) §6.

**Public-repo note.** Local paths in this log (home directory, npm folder) were replaced with
placeholders in P002. The original text stays in the history of commit `9d3f06e`; see "History
exposure" in the P002 log.
