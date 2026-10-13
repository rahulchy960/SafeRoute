# P014d2: server error reporting and the alert policies runbook

| Field | Value |
| --- | --- |
| Prompt | P014d (diagnostics), part 2: server and alerts |
| Milestone | M5 (follows P014d1c, merged as `7f7035d`) |
| Branch | `docs/014d2-server-alerts-runbook` |
| PR title | `docs(ops): server error reporting and alert policies runbook [P014d2]` |
| Notion | [P014d2 row in the Prompt Log](https://app.notion.com/p/3f807370772081299068dfe44c3f1c83) |
| Date | 2026-10-13 |
| Plan refs | Plan v7 §12.2, §13, §14.3, §14.5; ADR 0007, ADR 0019, ADR 0028 |

> **Nothing here was run against Google Cloud.** Claude Code has no access to it. No
> channel, policy, metric or budget exists yet, no alert has fired, and nobody has seen an
> error grouped in Error Reporting. The runbook says item by item what was checked and how.
>
> **This PR changes the backend image.** After the merge, `deploy-staging` runs (while
> `STAGING_DEPLOY_ENABLED` is `true`). That deploy is unverified until Rahul reports the
> Actions run.

## 1. Objective

Let the developer hear about server failures during the closed test without looking: errors
grouped in Cloud Error Reporting, e-mail alerts for the failures that matter, and a short
weekly check. And make sure that what the server logs about an error holds nothing personal.

## 2. Context & prerequisites

- P014d1c (PR #71) is merged as `7f7035d`; its Notion row was set to Merged. `android-ci`
  on `main` for that commit was still running at the preflight; it is not part of this
  prompt's gate.
- With this, P014d is complete: d1a, d1b, d1c (Android) and d2 (server).

### What the code did before

- The logger (`backend/src/lib/logger.ts`, pino) writes JSON lines with `severity`,
  `message`, `timestamp`, `service`, `version`.
- The error handler (`backend/src/app.ts`) logged an unexpected error as
  `log.error({ err }, 'unhandled error')`. pino's default turned `err` into
  `{ type, message, stack }`: **the error's own message, and the stack text that starts
  with it, went into the log.** A test asserted exactly that.
- Google's page "Format errors in logs" (read on 2026-10-13) says Error Reporting looks at
  `stack_trace`, then `exception`, then `message`, and otherwise searches every field of
  the payload for a stack trace. So the old lines may already have been grouped, through
  `err.stack`. Whether they were is not known: nobody looked.
- Three places log an error object: the handler (twice) and the shutdown path of
  `server.ts`. Database errors already go through `safeDbError`.

## 3. Workflow executed

1. Preflight: PR #71 confirmed merged with `gh pr view`, main synced, branch created.
2. Read the logger, the error handler, their tests and the observability runbook.
3. Read Google's pages on the error format and on alerting costs; checked every `gcloud`
   command with local `--help` (allowed: it reads nothing from the cloud).
4. The backend change with tests; the backend gate; the container smoke check.
5. The seven policy files, the runbook, the diagram, the other docs.
6. A commit after each step, with a line in `docs/prompt-logs/014d2-progress.md`.

## 4. Changes

- **backend:**
  - `src/lib/logger.ts`: `safeError(err)` keeps the error's class, a `code` when it is a
    short machine word, and the stack frames; it rebuilds `stack_trace` as Node prints it,
    with the first line `Class: code` or `Class: message not logged`. The logger applies
    it to every `err` field and adds `stack_trace` and `serviceContext`. **The error's
    message is no longer logged.**
  - `test/error-reporting.test.ts` (new, 6 tests) and one changed assertion in
    `test/errors.test.ts`.
- **infra:** `infra/staging/alerts/*.json`, seven alert policies (API 5xx, API p95
  latency, migration job failed, database CPU, database not up, routing engine 5xx, search
  provider errors). Files only: nothing was created in a project.
- **docs:** `docs/runbooks/alerts-staging.md` (new); the field table of the observability
  runbook; the runbook index; two rules in `CLAUDE.md`; the diagram; this log.

**Deployment impact:** yes, the image changes. The next staging deploy writes error lines
in the new shape. No configuration, secret or workflow change. Rahul does not have to do
anything first; the alert setup is separate and by hand.

## 5. Diagram

[`docs/diagrams/014d2-alerts-flow.svg`](../diagrams/014d2-alerts-flow.svg)
(source: `014d2-alerts-flow.json`).

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `pnpm typecheck` | Pass |
| `pnpm lint` | Pass |
| `pnpm format:check` | Pass |
| `pnpm test` | Pass: 35 files, 709 tests (6 new) |
| `pnpm build` | Pass |
| `pnpm openapi:check` | Pass: the contract is unchanged |
| `node scripts/container-smoke.mjs` | Pass: 39 checks |
| Policy files | Valid JSON |
| Diagram generator | Pass |
| Markdown lint (changed documents) | Pass, 0 findings |
| gitleaks on `main..HEAD` | Pass, no leaks |

What the log-capture test covers: every line the API's own logger wrote for a request
whose error quotes a search text, coordinates, a name, a phone number and a token, in its
message, in a second line of the message, in a line made to look like a stack frame, in
its `cause` and in extra properties. None of it is in the output, and none in the response.
**It says nothing about Cloud Run's own request log**, which the API does not write and
which records the URL (ADR 0019).

`gcloud` commands checked with local `--help` (Google Cloud SDK 587.0.0): `beta monitoring
channels create | list | describe`, `monitoring policies create | list | update | delete`,
`logging metrics create | list`, `billing budgets create | list`, `billing accounts list`,
`services enable`. **Could not be verified:** every command against a project; the shape of
the policy files against the API; the label `result="failed"` of the job metric and the
metric `cloudsql.googleapis.com/database/up`; the service name of the Error Reporting API;
GitHub's notification settings page; whether alerting is charged yet.

## 7. Decisions & ADRs

No ADR: nothing here is hard to reverse. Decisions, for the record:

- **The error's message is dropped from the log**, not filtered. A filter for "personal"
  text in a database driver's message cannot be trusted. The price is real: a developer
  no longer reads "what" in the log, only "where" (the stack) and "which kind" (class and
  code). Say so if that turns out to cost too much; the alternative is an allowlist of
  error classes whose messages are known to be fixed text.
- **Done in the logger, not at each call site**, so that a future `log.error({ err })`
  cannot bring the message back.
- **Counts, not rates, for the 5xx policies.** With a handful of testers a percentage of
  almost nothing means nothing.
- **Policy files in the repository** (`infra/staging/alerts/`), created with one command
  each, so that a threshold change is a reviewed diff.
- **A logs-based metric for the search provider**, because its failures are known only
  from the API's log lines.
- **The "database not up" policy is kept** although the setup runbook stops the database
  to save money; the runbook says how to switch the policy off first.
- **No rate-based "503" policy for search** as the prompt put it: the count of failed
  geocoder calls is what the code can give without a new log field.

## 8. Security & privacy notes

- The server log no longer holds an error's message. Stack frames name files, functions
  and lines.
- The runbook has a section on what not to paste when asking for help (project id, URLs,
  `user_id`, whole log entries, anything from Cloud Run's request log, tokens).
- The policy files contain service names, which are already in the repository, and no id.
- No secret, id, address or number was written, read or asked for. No cloud command was
  run; `gcloud ... --help` reads only the local installation.
- The budget amount, the credits' end date, the billing account and the e-mail address are
  **not recorded** in the repository.

## 9. Known issues & risks

- **Everything in the runbook is unverified against a project.** Expect at least one of
  the seven `policies create` commands to need a correction.
- **Whether Error Reporting groups the new lines is unknown** until staging logs a real
  error after the deploy.
- **The staging deploy of this change is unverified** until Rahul reports the run.
- **Thresholds are guesses.** There is no traffic to measure.
- Debugging on the server got harder: no error message in the log.
- The cost of alerting is from Google's documentation and was not confirmed as charged.
- E-mail to one person is not on-call. An alert at night is read in the morning.

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P014d2):

- Run the alerts runbook step by step and report which steps printed an error.
- Alert thresholds from real traffic, after the first weeks of the closed test.
- On-call expectations for a solo developer: what is promised to testers, and what is not.
- An incident template (what happened, since when, who was affected, what was done).
- How to tell testers about an outage (a status note, a channel, who writes it).
- If the dropped error messages hurt: an allowlist of error classes with fixed messages.
- Record the monthly cost of "Cloud Monitoring" after the first full month.

**Owed by Rahul for P014d:** phone scripts D1 to D6; the runbook's steps; the result of
the staging deploy.

### Inputs for P015 (SOS server orchestration), not started

What Rahul needs to decide or provide before that prompt. The list of P014c3 stands
(`docs/prompt-logs/014c3-sos-boot-recovery.md`, section 10); what P014d adds or sharpens:

1. **Firebase Cloud Messaging.** Which Firebase project (staging today; a production
   project is an open follow-up); Cloud Messaging switched on; how the server
   authenticates (workload identity, no key file, ADR 0007); whether the app adds the
   messaging SDK. `DiagnosticsBoundaryTest` and `DiagnosticsDeviceTest` now **fail** when
   `firebase-messaging` is declared or its components appear in the manifest: P015 must
   change those tests on purpose, with the reason in its ADR.
2. **Who gets a push.** A contact is not a user (ADR 0024), and the server sends no
   message to a contact without a lawyer's review. P015 must say who the receiver is.
3. **The notice.** The SOS alerts notice (version 4) says the server receives neither the
   alert nor a position. P015 changes that sentence: a new notice version, a new consent
   purpose in `purposes.ts`, a lawyer.
4. **The worker and pg-boss.** A second Cloud Run service or a job from the same image;
   minimum instances (a sleeping queue delays an SOS); the dedicated SOS queue; outbox and
   enqueue in one transaction; expand-only migrations.
5. **The contract.** New `/v1` routes for an SOS session, no coordinates in a URL
   (ADR 0019), idempotency by `clientSosId`; `info.version`; the Android client generated
   from it.
6. **Alerts for the new parts.** P015 adds failure modes this runbook does not cover: the
   queue's backlog, a worker that is down, failed pushes. Each needs a policy file in
   `infra/staging/alerts/` and a row in the runbook. And the worker's errors must go
   through the same logger, so that `safeError` applies.
7. **SOS reliability numbers.** ADR 0028: they come from the server's SOS sessions, not
   from crash reports. P015 should say which numbers the server will be able to give
   (sessions started, alerts acknowledged by the server, time to first sync) and which it
   cannot (whether an SMS arrived).
8. **Diagnostics on the phone.** The server sync brings new failures (sync failed, retried,
   given up). If they are to appear in "Report a problem", they are new typed events, each
   with a note in ADR 0028 that says what it gives away.
9. **Costs.** An always-on worker instance and the database's load. No figure is written
   here: none was measured.
10. **From the device side, ready:** `clientSosId` (UUID version 7), `syncState`
    (`NOT_SYNCED` only), the state `SYNCING` (unused), the rule that no device-side SOS
    action waits for the server, matrix rows 1, 3, 9, 10 and the draft scripts M24 to M26
    and M28. P015 must also count the P014c3 tests for rows 5 and A14 once `android-ci` is
    green on `main`.

## 11. How Rahul can verify

On the computer:

1. `cd backend`, then `pnpm typecheck`, `pnpm lint`, `pnpm format:check`, `pnpm test`,
   `pnpm build`.
2. Open `backend/test/error-reporting.test.ts`: the made-up search text, coordinates, name,
   number and token must not appear in any log line.

After the merge:

1. Open **GitHub → Actions → deploy-staging** and report whether the run passed.
2. Follow [`docs/runbooks/alerts-staging.md`](../runbooks/alerts-staging.md) **one step at
   a time**, in this order: step 0 (variables), step 1 (Error Reporting), step 2 (the
   channel and a test notification), step 3 (the logs-based metric), step 4 (the seven
   policies, one command at a time), step 5 (GitHub notifications), step 6 (the budget and
   the credits).
3. Report, per step: "worked" or the error text **without the project id**. For step 4
   also say which policy's chart shows "No data" although the service has traffic.
4. Put the weekly check into your calendar.

## 12. Learning notes

No Android in this part. The server and operations concepts used:

- **Structured logging.** The server writes one JSON object per line. Cloud Logging reads
  fields such as `severity` and stores the rest as `jsonPayload`, so a log can be searched
  by field instead of by text.
- **Error Reporting** reads those lines, finds the ones with a stack trace and puts the
  same failure into one group with a count. It is a view on the log, not a second copy.
- **A stack trace** is the list of functions that were running when the error happened:
  file, function, line. It says where, not what with.
- **An alert policy** is a rule: "this number, measured this way, beyond this threshold,
  for this long". When it holds, Cloud Monitoring opens an incident and notifies a
  **notification channel** (here: an e-mail address).
- **A logs-based metric** turns "how many log lines match this filter" into a number that
  a policy can watch.
- **p95 latency**: 95 of 100 requests were faster than this. It ignores the rare slow one
  and still shows when most people wait.
- **A budget** in Google Cloud sends e-mail at percentages of an amount. It never stops
  anything. With free credits it must count usage before credits, or it never fires.
- **A cold start**: with minimum instances 0, the first request after idle waits for a
  container to start. That is why the latency policy waits ten minutes before it fires.
