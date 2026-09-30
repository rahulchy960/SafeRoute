# P002: Backend skeleton + public-repo hardening

| Field | Value |
| --- | --- |
| Prompt | P002 · Backend skeleton + public-repo hardening |
| Milestone | M1 (depends on P001) |
| Branch | `feat/002-backend-skeleton` |
| PR title | `feat(backend): Hono API skeleton, /health and public-repo hardening [P002]` |
| Commits | (1) `chore(repo): public-repo hardening docs [P002]` · (2) `feat(backend): Hono API skeleton, /health and backend CI [P002]` (SHAs in the PR and Notion) |
| Repo | <https://github.com/rahulchy960/SafeRoute> (**public** since after P001) |
| Notion | P002 row in the Prompt Log: **not synced in this session** (see §9) |
| Date | 2026-09-30 |
| Plan refs | Plan v7 §0, §4, §6.2, §12.2, §12.4, §13.2, §14.3, §17, §20 |

## 1. Objective

Two streams in one PR:

- **A. Backend skeleton:** a minimal, production-shaped TypeScript backend in `backend/`: Hono app,
  Zod-validated config, Cloud Logging-friendly JSON logs with `request_id`, `GET /health`,
  RFC 9457 problem+json errors, graceful shutdown, Vitest tests and a backend CI workflow.
- **B. Public-repo hardening:** Rahul made the repo public after P001. Audit what is exposed,
  fix the working tree, apply server-side protection (ruleset, secret scanning, push protection,
  private vulnerability reporting, Actions hardening) and write public-repo rules and a security
  policy.

## 2. Context & prerequisites

- `main` = `9d3f06e` (P001, two direct commits, no PR). Working tree clean, `core.hooksPath` =
  `.githooks`. P001 had no PR, so "previous PR merged" was replaced by "the P001 log exists on
  main" (the `/start-prompt` P001 exception).
- Tools: git, gh (logged in as `rahulchy960`, scopes `repo`, `workflow`), pnpm 12.8.1,
  local Node **v22.17.0**. gitleaks and actionlint are not installed; checksum-verified release
  binaries (gitleaks 8.30.1, actionlint 1.7.12) were run from a temporary folder outside the repo.
- **Node LTS:** on 2026-09-30, Node **24 (Krypton)** is the Active LTS (maintenance from
  2026-10-20). Node 26 becomes LTS on 2026-10-28, and Node 22 is in maintenance. Source:
  nodejs.org `dist/index.json` + the nodejs/Release schedule. The gate was run on Node 22 (local)
  and on a checksum-verified portable Node 24.21.0.
- **Notion:** the claude.ai Notion connector reaches a different workspace (the P001 page IDs
  return 404), and the Notion plugin connector needs a browser OAuth login. Per CLAUDE.md the
  repo log was written and the Notion updates are listed in §10 for the next session.

## 3. Workflow executed

1. `/start-prompt`: `git switch main && git pull --ff-only` (up to date), clean tree, hook path
   OK, P001 log present, no open PRs. Read Plan v7 §4, §6.2, §12.2–12.4, §13.2, §14.3, §18.
   `git switch -c feat/002-backend-skeleton`.
2. **B1 audit** (no commits yet; raw output kept outside the repo): `git log` identities,
   `git grep` for paths/emails/phones/IDs/hostnames, `git log -p` + commit messages, PDF
   metadata, `gitleaks git` over full history. Summary in §8.
3. **B4 identity**: `gh api user` → noreply address; set with
   `git config --local user.name/user.email` (the global identity was a personal Gmail address).
4. **Scaffold** (`backend/`): `pnpm add` runtime + dev dependencies (exact versions). pnpm 12
   stopped on two supply-chain guards, both resolved without weakening them (§7).
5. **Implement A3–A10** with tests; `pnpm format`; gate green; manual smoke test with curl;
   shutdown test; `pnpm dev` check; `backend-ci.yml`; actionlint.
6. **B5 settings** through `gh api` (ruleset, security, Actions, repo metadata), each read back.
7. **B2/B6 docs**: sanitised the P001 log, appended its post-lock verification, `SECURITY.md`,
   `CODEOWNERS`, CLAUDE.md public-repo rules, README public status, PR template line.
8. Repo-wide checks (markdownlint, JSON, gitleaks), this log, `/ship-prompt`.

## 4. Changes

| Area | Files |
| --- | --- |
| Backend app | `backend/src/{server,app,config,types}.ts`, `src/lib/{logger,problem}.ts`, `src/middleware/{request-id,access-log}.ts`, `src/routes/health.ts`, `src/modules/README.md` |
| Backend tests | `backend/test/{config,health,request-id,access-log,errors}.test.ts`, `test/helpers.ts` |
| Backend config | `backend/{package.json,pnpm-lock.yaml,pnpm-workspace.yaml,tsconfig.json,tsconfig.build.json,eslint.config.js,.prettierrc,.prettierignore,.pino-prettyrc,.nvmrc,.env.example,README.md}` |
| CI | `.github/workflows/backend-ci.yml` (new) |
| Governance | `SECURITY.md` (new), `.github/CODEOWNERS` (new), `CLAUDE.md`, `README.md`, `.github/pull_request_template.md` |
| Docs | `docs/prompt-logs/001-repo-bootstrap.md` (paths generalised + appendix), this log |
| Repo settings | ruleset `protect-main`, private vulnerability reporting, fork-PR approval, delete-branch-on-merge (§6) |

- **API:** `GET /health` only, unversioned and outside `/v1` because it is operational. Whether it
  goes into `openapi.json` is decided in P004. No breaking change.
- **Database:** none.
- **Size:** about 1,500 changed lines excluding `pnpm-lock.yaml`, above the ~800 guideline. The
  prompt requires both streams in one PR. Backend source + tests are about 600 lines; the rest
  is docs.

### Dependencies (A2)

| Package | Version | Why |
| --- | --- | --- |
| `hono` | 4.13.10 | HTTP framework (Plan v7 §6.2) |
| `@hono/node-server` | 2.1.3 | Runs Hono on Node's `http` server |
| `zod` | 4.6.5 | Config validation (request schemas from P004) |
| `pino` | 10.3.1 | JSON logs |
| dev: `typescript` | 6.0.3 | **Not 7.0.2**: typescript-eslint 8.71 supports `>=4.8.4 <6.1.0` |
| dev: `tsx`, `vitest`, `eslint`, `typescript-eslint`, `prettier`, `@types/node` | 4.23.15, 5.0.2, 10.11.0, 8.71.0, 3.9.9, 24.19.0 | As listed in A2 |
| dev: `@eslint/js` | 10.0.1 | **Addition:** ESLint's own recommended rule set, a separate package since ESLint 9 |
| dev: `pino-pretty` | 13.1.3 | **Addition:** dev-only pretty logs; `pnpm dev` pipes JSON through it, and production code never loads it |

`vite` 8.3.1 is installed automatically as Vitest 5's required peer.

## 5. Diagram

No diagram needed (Plan v7 §18: Dgm = No for P002). The new CI workflow only adds a path-filtered
check job and doesn't change the prompt workflow in `01-prompt-workflow`.

## 6. Quality gate & test results

### Backend (in `backend/`, Node 22.17.0 and Node 24.21.0: same results)

| Command | Result |
| --- | --- |
| `pnpm install --frozen-lockfile` | OK; "Lockfile passes supply-chain policies" |
| `pnpm typecheck` (`tsc -p tsconfig.json`) | **pass**, 0 errors |
| `pnpm lint` (`eslint .`, strictTypeChecked + stylisticTypeChecked) | **pass**, 0 problems |
| `pnpm format:check` | **pass**, all files formatted |
| `pnpm test` (`vitest run`) | **pass**: 5 files, **32/32 tests** |
| `pnpm build` | **pass**; `dist/` runs with `pnpm start` |
| `pnpm audit --prod` / `pnpm audit` | **No known vulnerabilities found** |
| actionlint 1.7.12 on both workflows | no issues |

Test coverage by requirement:

- **config (9):** defaults; provided values; empty = unset; invalid `PORT` (text, 70000, 80.5),
  `LOG_LEVEL`, `NODE_ENV` rejected; the error names the variables but never the values.
- **health (1):** 200, exact keys, `Cache-Control: no-store`, `X-Request-Id`.
- **request-id (12):** UUID when absent; unique per request; valid IDs echoed and logged; too
  short, too long, bad chars, space, colon and empty are replaced; newline rejected (pattern
  test, since the Headers API can't carry a raw newline).
- **access-log (6):** exactly one line with request_id/method/path/status/duration_ms/timestamp;
  query string, Authorization, Cookie, X-Forwarded-For IP, body and the unmatched raw path never
  appear; route pattern (`/items/:id`) logged instead of the URL; 200 → INFO, 404 → WARNING,
  500 → ERROR.
- **errors (4):** 404 `not_found` problem+json with request_id; unhandled error → 500
  `internal_error` with no message/stack in the body, full error logged with request_id;
  `AppError(409, "conflict")` → same shape; a 5xx `AppError` is logged.

### Manual checks (local, Node 22)

```text
$ PORT=18080 node dist/server.js
$ curl -i localhost:18080/health
HTTP/1.1 200 OK · cache-control: no-store · x-request-id: <uuid>
{"status":"ok","service":"saferoute-api","version":"dev","uptime_s":0}
$ curl -H 'X-Request-Id: rahul-check-0001' 'localhost:18080/health?lat=1&lng=2'
x-request-id: rahul-check-0001
log: {"severity":"INFO",…,"request_id":"rahul-check-0001","method":"GET","path":"/health","status":200,…}
$ curl -i localhost:18080/nope
HTTP/1.1 404 Not Found · content-type: application/problem+json
{"type":"about:blank","title":"Not Found","status":404,"detail":"No route matches this request.","code":"not_found","request_id":"…"}
log: {"severity":"WARNING",…,"path":"(unmatched)","status":404,…}
$ PORT=abc LOG_LEVEL=loud node dist/server.js
Invalid configuration:
  - PORT: Invalid input: expected number, received NaN
  - LOG_LEVEL: Invalid option: expected one of "debug"|"info"|"warn"|"error"
exit=1
```

**Graceful shutdown (Node 24.21.0).** Windows can't deliver SIGTERM to a native process, and
Docker Desktop wasn't running, so a test-only `--import` preload emitted `SIGTERM` 1.5 s after
start. Output: `server listening` → `shutdown started {signal: SIGTERM}` → `shutdown complete`,
**exit 0**. Rahul's Ctrl+C check (SIGINT) is in §11. The in-flight drain relies on Node's
`server.close()` and wasn't exercised with a slow request.

### Repo-wide

| Check | Result |
| --- | --- |
| markdownlint-cli2 (`**/*.md`) | 22 files incl. this log, **0 issues** |
| JSON validity (tracked + new `*.json`, `.pino-prettyrc`, `.prettierrc`) | **16/16 valid** |
| gitleaks 8.30.1 `git` (full history, 2 commits) | **no leaks found** |
| gitleaks 8.30.1 `dir` (working tree) | **no leaks found** |
| `git ls-files`: `.env`, `*.jks`, `*.keystore`, `google-services.json`, service-account JSON | **none** (only `backend/.env.example`) |
| `git grep` for Windows user-profile paths and the npm AppData folder outside `docs/plan/` | **none left** |

### Stream B settings read-back (`gh api`)

| Item | Result | Read-back |
| --- | --- | --- |
| B5a ruleset `protect-main` (id 24264761) | **PASS** | `enforcement: active`, target `~DEFAULT_BRANCH`; rules `deletion`, `non_fast_forward`, `pull_request` (0 approvals, `dismiss_stale_reviews_on_push: true`, `allowed_merge_methods: [squash]`), `required_status_checks` (`repo-checks`, integration 15368 = GitHub Actions, not strict); bypass: RepositoryRole 5 (admin), `bypass_mode: pull_request`; `current_user_can_bypass: pull_requests_only`. `rules/branches/main` returns the same four rules |
| Required check name | **PASS** | from `commits/9d3f06e/check-runs`: `repo-checks` (app `github-actions`). `backend-ci` deliberately not required |
| B5b secret scanning | **PASS** | `enabled` (already on as a public-repo default; PATCH re-applied) |
| B5b push protection | **PASS** | `enabled` (already on; PATCH re-applied) |
| B5b private vulnerability reporting | **PASS** | was `false` → `{"enabled":true}` |
| B5c default workflow token | **PASS** | `default_workflow_permissions: read`, `can_approve_pull_request_reviews: false` (already read-only; PUT re-applied) |
| B5c fork PR approval | **PASS** | `first_time_contributors` → `all_external_contributors` (every outside fork PR needs approval before workflows run) |
| B5c Actions enabled | unchanged | `enabled: true`, `allowed_actions: all` |
| B5d Issues / Wiki / delete branch | **PASS** | `has_issues: true`, `has_wiki: false`, `delete_branch_on_merge: false → true` |
| B5e ruleset not tested by pushing | as required | verified by API only; the hook test is below |

GitHub added `require_extra_approval_for_unattributed_changes: true` to the pull-request rule by
default. P002 didn't set it, and Rahul's admin "via pull request" bypass covers it if it ever
blocks a merge.

### Hook re-test (P001 appendix)

`refs/heads/main` → exit 1 ("REJECTED push to refs/heads/main"); `refs/heads/test` → exit 0.

### SOS failure matrix (Plan v7 §7.5)

Not applicable: no SOS, live-location or contacts code.

## 7. Decisions & ADRs

No ADR: nothing hard to reverse, and A2 is followed apart from the justified additions in §4.

- **Node 24 LTS** in `.nvmrc` and `engines: ">=24"`, not 26 (not LTS until 2026-10-28) and not
  22 (maintenance). pnpm only warns on the engines mismatch on a Node 22 machine.
- **`backend/` is a standalone pnpm package**: no root `pnpm-workspace.yaml`, matching
  `tools/diagrams`. `backend/pnpm-workspace.yaml` exists only because pnpm 12 keeps package
  settings there.
- **pnpm supply-chain guards kept on.** (1) pnpm's minimum-release-age check rejected packages
  published the same day (hono 4.13.12, vitest 5.0.3). Rather than exempt them, P002 uses hono
  4.13.10 and vitest 5.0.2. (2) esbuild's install script is **explicitly denied**
  (`allowBuilds: esbuild: false`); its binary comes from an optional dependency, and the gate
  passes without the script.
- **TypeScript 6.0.3** instead of 7.x (typescript-eslint peer range). Revisit when
  typescript-eslint supports TS 7.
- **Logs are always JSON**; `pnpm dev` pipes them through pino-pretty (`.pino-prettyrc`). There is
  no environment-dependent transport, so what runs locally is what runs on Cloud Run. Writes are
  synchronous so the last lines before exit are kept.
- **Access-log `path` = matched route pattern** (e.g. `/v1/sos/:id`), and `(unmatched)` for 404s.
  Raw URLs can contain share tokens or IDs (Plan v7 §12.2).
- **Problem `type` = `about:blank`**, `title` = HTTP status phrase (RFC 9457 §4.2.1), and the
  stable `code` carries the meaning (Plan v7 §6.2). P004 can switch to resolvable type URIs if
  wanted.
- `HTTPException` (Hono's own) with status < 500 maps to code `http_error`; anything else unknown
  is a generic 500.
- **Ruleset** allows only squash merges, matching the "one commit per prompt on main" convention.
- **Notion links in docs kept.** They are `app.notion.com/p/<page-id>` links: a random page ID, no
  workspace name or slug, and the workspace is private, so a link grants no access. No
  "public/share" Notion links exist in the repo.
- Fork approval tightened to `all_external_contributors` ("first-time outside contributors /
  forks").

## 8. Security & privacy notes

### Exposure audit (B1), masked

| Finding | Where | Status |
| --- | --- | --- |
| Author + committer email is a **personal Gmail address** (`r***@gmail.com`), not a noreply | commits `4936b30`, `9d3f06e` | **Remains in history** (see History exposure). New commits use `98118074+rahulchy960@users.noreply.github.com` |
| Local Windows paths revealing the OS username: home directory (2×), npm global folder (1×) | `docs/prompt-logs/001-repo-bootstrap.md` | **Fixed in tree** (`<home>`, generic wording); remains in `9d3f06e` |
| Hostnames, IPs, phone numbers, personal emails in files | none (10-digit matches were GitHub Actions run IDs) | none |
| Notion page links (2 in the P001 log, 1 in the `9d3f06e` commit message) | P001 log, commit message | kept; judged not sensitive (§7) |
| Other IDs: commit SHAs, action SHAs, Actions run ID, gitleaks checksum | workflows, logs | public by nature |
| Secrets | gitleaks over both commits and the working tree | **none** |
| Plan PDF metadata | `docs/plan/SafeRoute_Plan_v7_MVP.pdf` | Author "SafeRoute Kolkata", Producer ReportLab; no personal data |
| Stray git repository in the home directory (`<home>/.git`) | local machine only | not touched (follow-up) |

### What the public plan PDF reveals (recommendation only; `docs/plan/` unchanged)

The plan is sensitive by design. It now publicly documents:

- **Abuse controls and thresholds:** report rules (verified phone, account age ≥ 24 h, example
  rate limit "3/day", duplicate clustering, reporter trust score, ~24 h publication delay), the
  **k-threshold** (a cell shows data only with reports from 3 distinct reporters), the H3
  resolution (9) and the 180-day window.
- **Rate-limited routes** (reports, share creation, SOS creation, public viewer polling, search)
  and the Postgres token-bucket approach.
- **Moderation rules** (what gets rejected) and the two-person rule for bulk moderator actions.
- **Threat model** (§12.4 and the risk register): OTP abuse, fake SOS spam, location scraping,
  contact takeover, brigading and their mitigations; App Check in *monitor mode* for the MVP.
- **SEND_SMS approach:** automatic SMS if the permission is granted, SMS-intent fallback otherwise,
  and the removal plan if Play rejects the declaration.
- **Infrastructure shape:** project names `saferoute-staging` / `saferoute-prod`, service-account
  role names, region, capacity numbers.

No credentials or personal data are in it. The main risk is that attackers learn the exact
thresholds to stay under (e.g. k = 3, 3 reports/day, 24 h account age). **Recommendation:**
either accept this (security through transparency; thresholds can be changed in config and the
published values called "examples"), or move the PDF to a private location and keep a short
public architecture summary. **Rahul decides** (follow-up).

### History exposure (B3)

Commits 1–2 (`4936b30`, `9d3f06e`) are public and still contain:

- the personal author/committer email (both commits);
- the local home-directory path and the npm folder path (in `9d3f06e`, P001 log);
- the Notion page URL in the `9d3f06e` commit message (low sensitivity).

Rahul's options:

- **(a) Leave as is.** Simplest. The email is already exposed; GitHub's noreply setting only
  protects future commits.
- **(b) Delete and recreate the repo** from a cleaned local history. This is the cleanest option
  while there are only two commits on `main` (plus this branch). The Notion workspace and local
  files are unaffected. It means recreating the ruleset and settings from §6, re-pushing, and
  losing the existing Actions runs and this PR, which would be recreated.
- **(c) Rewrite history** with `git filter-repo` (mailmap + text replacement), force-push, and
  temporarily lift the ruleset. **Not recommended**: it has the most moving parts, conflicts with
  the no-force-push rules, and forks or caches keep the old objects.

Whatever is chosen, anything that was public may already be cached by GitHub, search engines or
archives, so **treat it as public forever**. No credential was exposed, so nothing needs rotating.

### This PR

- No secrets, `.env`, keystores or `google-services.json`; only `backend/.env.example` (no
  values beyond defaults).
- Error bodies never include messages or stacks. Logs never include headers, query strings,
  bodies, IPs or raw paths. Config errors never echo values.
- Request IDs are restricted to `^[A-Za-z0-9._-]{8,64}$`, which stops log-line forging and
  oversized values.
- CI: `permissions: contents: read`, `persist-credentials: false`, all actions SHA-pinned
  (checkout v7.0.1, pnpm/action-setup v6.1.0, setup-node v7.0.0).
- `gh api` calls touched repository settings only; no token was printed or stored.

## 9. Known issues & risks

- **Notion not synced** (connector issue, §2). The P002 page (Status, Branch, PR URL, SHA,
  sections, "No diagram needed") and the follow-ups in §10 must be synced next session.
- Rahul's machine runs Node 22; the project targets Node 24 (install Node 24 LTS, §11). Everything
  also passes on Node 22 today.
- Graceful shutdown was checked with a simulated SIGTERM, not with Cloud Run or a slow in-flight
  request. P006 can re-check on staging.
- `/ship-prompt` lists the backend gate without `pnpm format:check`; CLAUDE.md and backend CI
  include it. `/ship-prompt` wasn't edited (only allowed if it blocks), so this is a follow-up.
- `require_extra_approval_for_unattributed_changes` is a GitHub default on the ruleset (§6).
- The diff is larger than the ~800-line guideline because the prompt combined two streams.

## 10. Follow-ups & prerequisites for next prompt

To record in the Notion *Follow-ups* database (not synced this session):

1. `/start-prompt` should handle the P001 direct-to-main exception explicitly (P2).
2. Stray git repository in Rahul's home directory: Rahul decides whether to remove it (P3).
3. Plan PDF publicity: keep `docs/plan/` public or move it private (§8) (P2, Rahul).
4. LICENSE: choose one or keep "all rights reserved" (P3, Rahul).
5. History exposure: choose (a)/(b)/(c) from §8, ideally before more commits accumulate (P1, Rahul).
6. `/ship-prompt` backend gate: add `pnpm format:check` (P3).
7. Consider enabling Dependabot security updates and "require actions pinned to full SHA" in
   repo settings (P3).
8. Upgrade to TypeScript 7 once typescript-eslint supports it (P3).
9. Notion: sync the P001 → Merged status (no merge SHA; direct commits), the P002 page and these
   follow-ups; check which Notion connector owns the "SafeRoute Kolkata — Engineering" workspace
   (P1).

Prerequisites for **P003** (`feat/003-db-foundation`): this PR merged; Docker or another local
Postgres + PostGIS for integration tests (CI can use a service container).

## 11. How Rahul can verify

1. Read the PR diff: only `backend/`, `.github/`, `SECURITY.md`, `CLAUDE.md`, `README.md` and
   `docs/prompt-logs/` changed.
2. GitHub → Settings → **Emails**: turn on **Keep my email addresses private** and **Block command
   line pushes that expose my email**. Claude Code can't do this.
3. Decide on History exposure (§8), ideally before more commits accumulate.
4. Decide whether `docs/plan/` stays public and whether to add a LICENSE.
5. GitHub → Settings → **Rules → Rulesets**: `protect-main` is **Active**. Settings → **Code
   security**: secret scanning and push protection are on, and private vulnerability reporting is
   enabled.
6. Install **Node 24 LTS** (nodejs.org or `winget install OpenJS.NodeJS.LTS`), then in `backend/`:
   `pnpm install`, `pnpm dev`, open <http://localhost:8080/health>.
7. `curl -i -H "X-Request-Id: my-test-id-001" http://localhost:8080/health`: the header is echoed
   and the log line shows `request_id: "my-test-id-001"`. Open
   <http://localhost:8080/health?lat=1&lng=2>: the log shows `path: "/health"` and no query
   string. Open <http://localhost:8080/nope>: a problem+json 404. Press Ctrl+C: "shutdown
   started" then "shutdown complete".
8. Check that `repo-checks` and `backend-ci` are green on the PR, then squash and merge (your
   admin bypass is "via pull request" only) and delete the branch (automatic now).

## 12. Learning notes

- **How Cloud Run runs a container:** Cloud Run starts the container and tells it which port to
  listen on through the `PORT` environment variable, so the server reads `PORT` rather than
  hard-coding 8080. To stop an instance (scale-down or new deploy) it sends **SIGTERM** and allows
  about 10 seconds before killing it. `server.ts` catches SIGTERM, stops accepting connections,
  lets running requests finish and exits. Instances must be **stateless**: any copy can serve any
  request and may disappear at any time, so nothing important lives in memory; it goes in
  Postgres (P003). Docs: <https://cloud.google.com/run/docs/container-contract>.
- **Request / correlation ID:** a unique ID given to each request, returned in the
  `X-Request-Id` header and written on every log line. When a user or the Android app reports an
  error, the `request_id` in the error body finds every log line for that request. Incoming IDs
  are accepted only in a safe format, because the value is copied into logs and headers.
- **Structured JSON logs:** each log line is one JSON object rather than free text. Cloud Logging
  reads fields such as `severity`, `message` and `timestamp`, so you can filter
  (`severity>=ERROR`, `path="/health"`) and build dashboards (request rate, `duration_ms`
  percentiles, Plan v7 §14.5). Docs: <https://cloud.google.com/logging/docs/structured-logging>.
- **RFC 9457 problem+json:** a standard error body (`type`, `title`, `status`, `detail`) that we
  extend with `code` and `request_id`. The Android app will switch on `code`, which never
  changes meaning, rather than on human-readable text.
- **Branch ruleset:** a server-side GitHub rule. `protect-main` makes every change to `main` go
  through a pull request with a green `repo-checks`, and forbids force-pushes and deleting `main`.
  Unlike the local pre-push hook, it applies to every machine and every person. Docs:
  <https://docs.github.com/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets>.
- **Secret scanning and push protection:** GitHub scans the repository for known credential
  formats (API keys, tokens). With push protection on, a `git push` containing one is **blocked
  before it reaches GitHub**, which is much better than finding it after it is public. Docs:
  <https://docs.github.com/code-security/secret-scanning/introduction/about-push-protection>.
- **Why everything in a public repo is permanent:** a commit, once pushed, lives in the git
  history (every clone and fork has a full copy), and crawlers, archives and caches may have
  copied it within minutes. Deleting a file only hides it from the latest version. So personal
  data, local paths and IDs must never be committed, and any credential that was ever pushed must
  be rotated, not just deleted.
- **pnpm supply-chain guards:** pnpm 12 refuses brand-new package versions for a short period
  (most malicious releases are caught within hours) and doesn't run install scripts unless a
  package is explicitly allowed. P002 kept both guards on.
