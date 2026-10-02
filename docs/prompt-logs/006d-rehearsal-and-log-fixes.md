# P006d: rollback rehearsal record, verified deploy and runbook corrections

| Field | Value |
| --- | --- |
| Prompt | P006 · GCP staging deployment, **part d** (documentation only) |
| Milestone | M1 (depends on P006c, merged as `fef283b`) |
| Branch | `docs/006d-rehearsal-and-log-fixes` |
| PR title | `docs(deploy): record the staging rollback rehearsal and the verified deploy [P006d]` |
| Notion | [P006d row in the Prompt Log](https://app.notion.com/p/3ed0737077208130b3f8cbea977f7efa) |
| Date | 2026-10-02 |
| Plan refs | Plan v7 §3.4, §13.2, §17 |

## 1. Objective

Bring the repository's records in line with what has happened on staging since P006b and P006c
were merged:

- carry the P006c log commit that missed the merge of PR #12;
- mark the P006b log as "deploy verified": the first staging deploy succeeded;
- record the rollback rehearsal Rahul ran (Plan v7 §3.4, "rollback rehearsed");
- correct the rollback runbook where the rehearsal showed it was wrong or unverified;
- update Notion.

No code changes.

## 2. Context & prerequisites

- P006c merged (PR #12, `fef283b`). `main` clean, hooks active, no open pull requests.
- Staging has two green `deploy-staging` runs (36949131456 and 36950613989), both for commit
  `fef283b`. The second one was started by Claude Code through GitHub Actions, on Rahul's
  request, so that two real revisions exist for the rehearsal.
- The rehearsal itself was run by Rahul. Claude Code attempted it on his explicit instruction;
  its read-only checks ran, and the first command that changes anything was refused by Claude
  Code's permission layer. Nothing was changed by Claude Code (verified by reading the state
  afterwards).

## 3. Workflow executed

1. `/start-prompt P006d`: synced `main` (`fef283b`), tree clean, hooks active, PR #12 merged.
   Notion: new row P006d. `git switch -c docs/006d-rehearsal-and-log-fixes`.
2. `git cherry-pick 3174d7c` → `77460f9`: the CI results for the P006c log.
3. Read the public Actions logs of the seven `deploy-staging` runs with `gh run view`.
4. Edited the rollback runbook, appended a Revision section to the P006b log, wrote this log.
5. Quality gate, `/ship-prompt`.

## 4. Changes

| File | What |
| --- | --- |
| `docs/prompt-logs/006c-staging-setup-script.md` | Carried commit: `infra-ci` results on PR #12 for the three PowerShell hosts |
| `docs/prompt-logs/006b-staging-deploy-workflow.md` | Notice at the top changed from "unverified" to "verified" (the original sentence is kept); new "Revision 2026-10-02" section: the seven runs, what they settle, the confirmed hostname issue, the rehearsal result |
| `docs/runbooks/rollback-staging.md` | `Show-Traffic` (a table without URLs) replaces `yaml(status.traffic)` in steps 1 and 2; sample outputs; a note that same-commit revisions can't be told apart by the smoke test; "Rehearsal record" in step 7; "How this runbook was checked" split into verified and still unverified |
| `docs/prompt-logs/006d-rehearsal-and-log-fixes.md` | This log |

**API contract diff:** none. **Migrations:** none. **Code:** none. **Deployment impact:** none;
no file under `backend/` or `.github/workflows/` changed, so merging starts no deploy.

## 5. Diagram

No diagram needed: no flow, schema, infrastructure or pipeline changed.

## 6. Quality gate & test results

| Command | Result |
| --- | --- |
| markdownlint-cli2 (47 files in the working tree) | 0 issues |
| JSON validity (32 files) | all valid |
| gitleaks 8.30.1 | no leaks |
| `git grep -E 'saferoute-(stg\|prd)-'` · diff scan for local paths, e-mails, service-account addresses, URLs | no hits |

Other gates: not applicable (documentation only). **SOS failure matrix (Plan v7 §7.5):** not
applicable.

**Evidence used, and where it came from:**

- Deploy results: the public Actions logs of runs 36938352924 to 36950613989 (step outcomes,
  revision names, migration execution names, smoke-test lines).
- Rehearsal: the terminal transcript Rahul supplied. It shows the traffic table before, after
  the rollback and after rolling forward; the duration of both shifts; and three passing smoke
  tests. It contains no clock times.
- Starting state of the rehearsal: Claude Code's read-only check shortly before it
  (`saferoute-api-00004-ced` at 100%, revisions listed newest first).

**Rehearsal result: PASS** for the traffic rollback (runbook steps 1, 2 and 4). Rollback shift
9 s, roll forward 6 s. Steps 5 and 6 were not run.

## 7. Decisions & ADRs

No ADR.

- **The P006b log keeps its original "unverified" sentence** under the new notice, and the new
  facts go into a Revision section, as `docs/prompt-logs/README.md` prescribes. The log stays a
  record of what was known at the time.
- **The rehearsal counts as passed for what it covered, and the gaps are written next to the
  result:** same commit on both revisions, no migration in between, steps 5 and 6 not run.
- **The runbook adopts the command Rahul actually used** for the traffic table. It is the one
  proven on staging, and unlike the previous one it prints no URL.
- **Causes of the three failed runs are stated only as far as the logs show them.**

## 8. Security & privacy notes

- No identifiers added: revision names, execution names, run numbers and a commit SHA only. The
  supplied transcript contained a local path in its prompt lines; none of it was copied.
- **Confirmed:** the staging hostname is readable in the public log of both green deploy runs.
  `gcloud run deploy` prints the candidate URL before the workflow's masking lines run. The
  project ID is masked and nothing secret is exposed, but the hostname is public until those
  logs are deleted. Recorded in the P006b Revision and in the existing follow-up.
- New personal data: none.

## 9. Known issues & risks

- Two launch-relevant rehearsal steps are still open: a manual migration execution and the
  `saferoute-admin` job. Their commands are unverified.
- The rehearsal did not cross a code or schema change.
- `bootstrap-staging.ps1 -Verify` can't see secret **values**. A secret that exists with a
  malformed value passes verification and fails in the deploy, which is what two of the failed
  runs look like. `-SetGithubSecrets -Force` rewrites every value from the audited project.
- The observability runbook's two unverified points (execution label key, `timestamp` field)
  are unchanged.

## 10. Follow-ups & prerequisites for next prompt

Notion (*Follow-ups*):

- "Rehearse the staging rollback…" → Done for the traffic rollback.
- New: rehearse runbook steps 5 and 6 (manual migration execution, `saferoute-admin` job).
- New: document or add a value check for GitHub secrets (`-Verify` sees names only).
- Updated: "Decide whether to hide the staging hostname in deploy-staging logs" → confirmed,
  priority raised.

Before P007: unchanged from the P006c log (Android Studio with API 36, an Empty Activity
(Compose) project, the package name, the display name "SafeRoute").

## 11. How Rahul can verify

1. Read the "Rehearsal record" in `docs/runbooks/rollback-staging.md` and the Revision at the end
   of `docs/prompt-logs/006b-staging-deploy-workflow.md`; check that they match what you ran.
2. Check that `repo-checks` is green on the pull request.
3. Decide about the hostname: to remove it from public view, delete the logs of runs
   36949131456 and 36950613989 (Actions → the run → ⋯ → Delete all logs).
4. Squash and merge; delete the branch. Also delete the stray remote branch
   `ci/006c-staging-setup-script`, which a late push re-created after PR #12 was merged.

## 12. Learning notes

- **Rehearsal.** Practising a recovery procedure while nothing is broken. It turns "we think
  rollback works" into "rollback took 9 seconds on this date", and it finds the wrong command
  in the runbook before an incident does.
- **Traffic pinning and `--to-latest`.** After a rollback, Cloud Run keeps sending traffic to
  the chosen revision until told otherwise. `--to-latest` returns to "always the newest
  revision", which is also how every successful deploy ends.
  <https://cloud.google.com/run/docs/rollouts-rollbacks-traffic-migration>
- **Evidence in a log.** A record is only useful if it says where each fact came from and what
  was not checked. "Passed" next to "steps 5 and 6 not run" is more valuable than "passed".
