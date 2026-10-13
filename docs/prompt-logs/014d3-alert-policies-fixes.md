# P014d3: alert policy files and runbook commands checked against the documentation

| Field | Value |
| --- | --- |
| Prompt | P014d (diagnostics), part 3: corrections to the alert policies and the runbook |
| Milestone | M5 (follows P014d2, merged as `e99fc04`) |
| Branch | `fix/014d3-alert-policies-fixes` |
| PR title | `fix(ops): correct alert policy files and runbook commands after the first run [P014d3]` |
| Notion | [P014d3 row in the Prompt Log](https://app.notion.com/p/3f8073707720815ba3e6f969497d818c) |
| Date | 2026-10-13 |
| Plan refs | Plan v7 §13, §14.3; ADR 0007 |

> **No result of a run was available.** The prompt has two places for what failed when the
> runbook was run ("these steps failed", "these charts show No data"). Both arrived empty,
> three times. This prompt therefore did the part that needs no run: it compared the files
> and the commands with Google's documentation and tested locally how PowerShell passes
> the arguments. **Nothing here is confirmed against a project**, and the PR title's
> "after the first run" describes the prompt, not what happened.

## 1. Objective

Correct the alert policy files in `infra/staging/alerts/` and the commands in
`docs/runbooks/alerts-staging.md` where they are wrong, name what was checked, keep the
rest marked as unverified, and change no threshold.

## 2. Context & prerequisites

- P014d2 (PR #72) is merged. It wrote the seven policy files and the runbook without
  access to Google Cloud and listed what it could not verify.
- The failed steps and the "No data" charts were not given (see the note above).
- P014e (PR #73) was merged in the meantime as `54dfd74`; its Notion row and the five
  follow-ups it closed were updated at the start of this prompt.

## 3. Workflow executed

1. `/start-prompt`: `main` up to date, tree clean, hook path `.githooks`, PR #72 merged.
2. Local test of argument passing (see section 6).
3. Reading Google's pages (list in section 6) and comparing each file and command.
4. Two corrections to files, one to a command, wording and the verification table in the
   runbook.
5. Repo checks, commit, push, PR.

No `gcloud` command was run, not even `--help`: the flags were checked in P014d2 and no
new flag is used.

## 4. Changes

| File | Change | Why |
| --- | --- | --- |
| `infra/staging/alerts/osrm-5xx.json` | The filter selects the services with `starts_with("saferoute-osrm-")` instead of `monitoring.regex.full_match("saferoute-osrm-.*")` | Google's page on monitoring filters documents `starts_with` for resource labels and shows the regular-expression function only for metric labels and the project. Same services selected. Whether the old form would have been refused is not known |
| `infra/staging/alerts/api-latency-p95.json` | The text of the alert no longer says that a cold start is what the ten minutes filter out | Google's metrics list says the latency is measured from when the request reaches the running container and does not include the start of the container. Text only; the condition is unchanged |
| `docs/runbooks/alerts-staging.md`, step 6 | The argument `--threshold-rule=percent=1.0,basis=forecasted-spend` is in quotes | Without quotes Windows PowerShell 5.1 splits it at the comma when the program is started through a `.ps1` launcher, which is how PowerShell starts `gcloud` on this machine |
| `docs/runbooks/alerts-staging.md`, the table at the top | Rows rewritten: what is now checked against the documentation, and what is still unverified | The prompt asks for the list of what was checked |
| `docs/runbooks/alerts-staging.md`, step 4 | The reason for the latency policy corrected; notes on what the 5xx metric does not count and on what to look for in the database chart | Findings from the metrics list |

**No threshold, duration, alignment period or comparison was changed.**

Not changed, on purpose: `cloudsql-down.json`. See section 9.

## 5. Diagram

No diagram needed.

## 6. Quality gate & test results

What was checked, and how:

| Check | How | Result |
| --- | --- | --- |
| How Windows PowerShell 5.1 passes the arguments of every command in steps 2, 3, 4 and 6 | The same argument lists, with fake values, given to a small program that prints what it received; once started directly and once through a `.ps1` file that forwards its arguments | Directly: all whole. Through the `.ps1` file: all whole except the forecast threshold rule, split in two at the comma. With quotes: whole |
| Which launcher PowerShell uses for `gcloud` | `Get-Command gcloud -All` (lists files; runs nothing) | `gcloud.ps1` first, then `gcloud.cmd` |
| Field names of the policy files | Reference `projects.alertPolicies` (Monitoring API v3) | Every field used exists; `OR` is a valid combiner; durations must be multiples of a minute (0, 300, 600 and 900 s are); alignment periods of 300 s and 600 s are inside the limits |
| Aligners and reducers | The `Aligner` and `Reducer` lists on the same page | Each one used is valid for the kind of its metric |
| Cloud Run metrics | "Google Cloud metrics", service domains P to Z | `request_count` (delta, labels `response_code`, `response_code_class`), `request_latencies` (delta distribution, ms), `job/completed_execution_count` (delta, resource `cloud_run_job`, label `result`). The values of `result` are not listed |
| Cloud SQL metrics | "Cloud SQL metrics" (PostgreSQL) | `database/cpu/utilization` (gauge, 0.0 to 1.0, so the threshold 0.8 is in the right unit) and `database/up` (gauge, whole number) exist |
| Resource labels | "Monitored resource types" | `cloud_run_revision` has `service_name`; `cloud_run_job` has `job_name`; `cloudsql_database` exists |
| Filter syntax | "Monitoring filters" | Double-quoted strings and `AND` as written; see the first row of section 4 for the one correction |
| The log line the search metric counts | `backend/src/modules/search/service.ts` | The message is the fixed text `geocoder call`, at warning or error level: the filter of step 3 matches it |

All pages were read on 2026-10-13 through a tool that summarises a page; quotations in
the runbook are short and were taken from those summaries.

Repo checks:

| Check | Result |
| --- | --- |
| The seven policy files parse as JSON | Pass |
| Markdown lint (`markdownlint-cli2` v0.18.1) on the two changed documents | Pass |
| gitleaks v8.30.1 on `main..HEAD` | Pass, no leaks |

No other gate applies: no backend, Android, contract, workflow or script file changed.

## 7. Decisions & ADRs

- A documentation-only pass instead of waiting a third time. It fixes what can be shown
  to be wrong without a run and says plainly that the run's results are still missing.
- No ADR: nothing here is hard to reverse.

## 8. Security & privacy notes

- The local test used invented values only (a made-up project name, an `example.invalid`
  address, zeros for the billing account). No real id was read, printed or written.
- No cloud command was run and no credential was touched.
- The runbook still tells the reader not to paste a project id, a channel name or an
  e-mail address anywhere.

## 9. Known issues & risks

- **Everything is still unverified against a project.** A policy file can match the
  reference and still be refused, and a chart can still be empty.
- **`cloudsql-down.json` may not be able to report a stopped database.** The
  documentation does not say whether `database/up` reports 0 or nothing while the
  instance is stopped, and a threshold condition is not evaluated without data. Making
  the policy also react to missing data would change when it fires, and there is no
  observation to base that on, so the file is unchanged and the runbook says what to look
  for. Google documents a second metric, `database/instance_state`, as the instance's
  serving state.
- The value `failed` of the label `result` is still from memory.
- The PR title says "after the first run"; no run was reported.

## 10. Follow-ups & prerequisites for next prompt

- Rahul: run the runbook and report each step as "worked" or with its error text (ids
  and the e-mail address replaced), the file each step 4 error belongs to, and the
  charts that show "No data". A further correction needs these.
- Rahul: with the database running, and once stopped on purpose, look at the chart of
  the "database is not up" policy (step 4).
- Recorded in Notion: "Decide how the database-down alert treats missing data".
- P015 (SOS server orchestration) is next and was not started.

## 11. How Rahul can verify

1. `repo-checks` on the PR is green.
2. Read the table "What was verified, and what was not" in
   `docs/runbooks/alerts-staging.md`: it should match what you expect to be unknown.
3. Run the runbook on an up-to-date `main` after the merge, one command at a time.

## 12. Learning notes

Not an Android prompt. One thing worth knowing about the shell:

- **PowerShell and commas.** In PowerShell a comma makes a list. When a command is a
  PowerShell script (a `.ps1` file), an unquoted argument such as `a=1,b=2` can reach it
  as two arguments. Many command-line tools on Windows, `gcloud` among them, are started
  through such a script. Putting the whole argument in double quotes keeps it in one
  piece. See "about_Parsing" and "about_Quoting_Rules" in Microsoft's PowerShell
  documentation.
- **A threshold alert and missing data.** A condition such as "value below 1" needs a
  value. When a metric stops reporting, there is nothing to compare, and by default the
  condition is simply not evaluated. A separate kind of condition (metric absence), or
  the setting `evaluationMissingData`, covers the "no data" case. See "Alerting policies"
  in the Cloud Monitoring documentation.
