---
description: Preflight for a SafeRoute prompt (Plan v7 §17.4). Syncs main, checks the previous PR is merged, updates Notion, creates the branch.
argument-hint: <NNN> <type>/<NNN>-<core-work>  (e.g. 014 feat/014-sos-device-flow)
---

# /start-prompt: preflight (Plan v7 §17.4)

Arguments: `$ARGUMENTS`. The first token is the prompt number `NNN`; the second (optional) is the
branch name. If they are missing, take them from the pasted prompt header (`Branch:` line). If they
are still unknown, ask Rahul. Never guess.

Do these steps **in order**. Stop and report as soon as a step fails. Do not work around a failure.

## 1. Sync main and check the tree

```sh
git switch main
git pull --ff-only
git status --porcelain
```

- `git pull --ff-only` must succeed. If main has diverged, STOP and ask Rahul (never reset or force).
- The working tree must be clean. If it is not, STOP and show Rahul the files. Don't stash or
  delete anything without asking.
- Check that the pre-push hook is active: `git config core.hooksPath` must print `.githooks`. If it
  doesn't, STOP and tell Rahul to run `git config core.hooksPath .githooks`. Don't change it
  yourself.

## 2. Confirm the previous prompt's PR is merged

Find the previous prompt (`<PREV>`):

- **P001 is the one direct-to-main exception: it has no PR to check.** When the previous prompt
  is P001 (this prompt is P002), check that `docs/prompt-logs/001-*.md` exists on main instead
  and skip the `gh` check for it.
- **This prompt is a later part of a split prompt** (`NNNb`, `NNNc`, ...): `<PREV>` is the part
  before it (`NNNa` for `NNNb`).
- **Otherwise** `<PREV>` is `NNN - 1`. If that prompt was split into parts (there are logs named
  `docs/prompt-logs/<NNN-1>[a-z]-*.md`), `<PREV>` is its **last** part: that part's PR is the one
  that must be merged (before P009, that is P008b, not P008a).

```sh
gh pr list --state merged --search "[P<PREV>] in:title" --json number,title,mergedAt,mergeCommit,url
gh pr list --state open --json number,title,headRefName,url
```

- If the previous prompt's PR (for a split prompt, its last part's PR) is **not merged**, STOP
  and ask Rahul. Don't stack branches unless the prompt explicitly says so.
- Note the merge commit SHA and merge date for step 3.

## 3. Update the previous prompt's Notion page

Use the Notion MCP. In the **Prompt Log** database of "SafeRoute — Engineering", set the
previous prompt's row to: `Status = Merged`, `Merge SHA = <sha>`, `Date merged = <date>`.

- Also sync anything a previous session left unsynced (a prompt log in `docs/prompt-logs/` with no
  matching Notion content, or follow-ups listed in the log but missing from the *Follow-ups*
  database).
- If the Notion MCP fails, continue, and list the missing updates in the final report of this
  prompt.

## 4. Read context before writing code

- `CLAUDE.md` (golden rules, conventions, quality gate).
- The plan sections the prompt cites, from `docs/plan/SafeRoute_Plan_v7_MVP.pdf`. Read only the
  cited pages. If you can't read the PDF, use the specification copied into the prompt.
- Related ADRs in `docs/adr/`.
- The previous prompt log in `docs/prompt-logs/`, especially **Follow-ups & prerequisites for the
  next prompt**.

## 5. Create the branch

```sh
git switch -c <type>/<NNN>-<core-work>
```

`type` ∈ feat, fix, chore, ci, docs, test (Plan v7 §17.3). The branch must never be `main`.

## 6. Create this prompt's Notion page

In the Prompt Log database, find the row for `P<NNN>` (pre-created as *Planned*) or create it. Set:
`Status = In progress`, `Branch`, `Date started = today`. Add the 12 template section headings to
the page body (Objective · Context & prerequisites · Workflow executed · Changes · Diagram ·
Quality gate & test results · Decisions & ADRs · Security & privacy notes · Known issues & risks ·
Follow-ups & prerequisites for next prompt · How Rahul can verify · Learning notes).

## 7. Post a short plan in the terminal

5–10 bullets: what you will change and in which areas, which quality-gate commands apply, whether
a diagram is required (Plan v7 §17.7), whether failure-matrix rows apply (Plan v7 §7.5), and any
open questions for Rahul. Then start implementing. Stay inside the prompt's scope and non-goals.
