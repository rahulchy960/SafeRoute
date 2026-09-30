---
description: Finish a SafeRoute prompt (Plan v7 §17.5). Quality gate, diagram, prompt log, commit, push branch, open PR, complete Notion, final report.
argument-hint: <NNN>  (optional; defaults to the number in the current branch name)
---

# /ship-prompt: finish (Plan v7 §17.5)

Arguments: `$ARGUMENTS`. Take the prompt number from the arguments or the current branch
(`<type>/<NNN>-<core-work>`).

**Never push to main. Never merge, approve or close a PR.** First confirm that
`git branch --show-current` is not `main`. If it is, STOP.

## 1. Quality gate for every touched area

List the touched areas with `git diff --name-only main...HEAD` plus `git status --porcelain`. Run
every applicable gate (see the table in `CLAUDE.md`):

| Area touched | Commands |
| --- | --- |
| `backend/` | `pnpm typecheck` · `pnpm lint` · `pnpm test` · `pnpm build` |
| `contracts/` or backend routes | regenerate `contracts/openapi.json` (diff must be committed) + breaking-change diff |
| `android/` | `./gradlew lint testDebugUnitTest assembleDebug` (in `android/`) |
| `docs/diagrams/*.json` | `cd tools/diagrams && pnpm install && pnpm generate` |
| everything | markdown lint, JSON validity, gitleaks (same as `.github/workflows/repo-checks.yml`) |

- Fix failures. Never skip, disable or delete tests to get a pass.
- SOS / live-location / contacts code: run and record the Plan v7 §7.5 failure-matrix rows.
- If you can't make the gate pass, STOP and report (golden rule 6). Don't commit or push.
- Record exact commands and pass/fail counts for the log, the PR and Notion.

## 2. Diagram (Plan v7 §17.7)

Required if the prompt added or changed a user flow, state machine, data model/schema, API
interaction sequence, infrastructure/deployment or the CI pipeline:

- Write or update `docs/diagrams/NNN-topic.json` (under ~20 nodes, colours by role).
- Run `cd tools/diagrams && pnpm generate` to produce `.excalidraw`, `.svg` and `.png` in
  `docs/diagrams/`. Look at the PNG to check it's legible.
- If no diagram is needed, say "No diagram needed" in the log and on Notion.

## 3. Prompt log

Write `docs/prompt-logs/NNN-<core-work>.md` with the 12-section template from
`docs/prompt-logs/README.md`: Objective · Context & prerequisites · Workflow executed (steps +
commands) · Changes · Diagram · Quality gate & test results · Decisions & ADRs · Security & privacy
notes · Known issues & risks · Follow-ups & prerequisites for next prompt · How Rahul can verify ·
Learning notes.

## 4. Commit

Stage only files that belong to this prompt. Review `git status` and `git diff --cached --stat`.
Make sure no `.env*`, keystore, `google-services.json` or service-account file is staged.

Commit with the `.gitmessage` template:

```text
<type>(<scope>): <summary> [P<NNN>]

Why:
- ...

What changed:
- ...

How tested:
- ...

Risks / follow-ups:
- ...

Refs: Plan v7 §... · docs/prompt-logs/NNN-<core-work>.md · Notion: <url>
```

## 5. Push the branch (explicit refspec only)

```sh
git push -u origin <type>/<NNN>-<core-work>
```

Never use a bare `git push`, `--force`, `--all` or any refspec that targets `main`. The pre-push
hook rejects `refs/heads/main`. Don't bypass it (`--no-verify` is forbidden).

## 6. Open the pull request

```sh
gh pr create --base main --head <branch> --title "<commit title>" --body-file <body.md>
```

Fill the body from `.github/pull_request_template.md` (all 11 sections), including quality-gate
results, diagram links and the Notion page URL. Don't merge, approve or close it.

## 7. Complete the Notion page

Prompt Log row: `Status = In review`, `PR URL`, `Commit SHA`, `Quality gate = Pass`,
`Diagram URL` (GitHub link to the SVG on the branch). Fill all 12 sections of the page body to
match the prompt log. Add new follow-ups to the *Follow-ups* database and new ADRs to
*Architecture Decisions*. If Notion fails, say so in the report; it will sync at the next
`/start-prompt`.

## 8. Final terminal report

Concise:

- Branch, commit SHA, PR URL, Notion URL
- Quality-gate results (commands + pass/fail counts; failure-matrix rows if applicable)
- Diagram files
- Follow-ups recorded
- **Manual review checklist for Rahul** (Plan v7 §17.9): title/branch follow conventions; CI green;
  scope matches; no secrets; openapi diff understood; migrations expand-only; failure-matrix rows
  passed (SOS); diagram + Notion complete; run the "How Rahul can verify" steps; squash and merge;
  delete the branch.

Stop there. Don't start the next prompt.
