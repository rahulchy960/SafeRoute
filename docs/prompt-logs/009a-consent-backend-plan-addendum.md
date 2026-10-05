# P009a: consent records, age attestation and plan addendum v7.1

| Field | Value |
| --- | --- |
| Prompt | P009 · Plan addendum v7.1, consent and age model, Firebase phone sign-in, **part a** of two |
| Milestone | M2 (depends on P005a/b and P008a/b, all merged) |
| Branch | `feat/009a-consent-backend-plan-addendum` |
| PR title | `feat(auth): consent records, age attestation and plan addendum v7.1 [P009a]` |
| Notion | [P009a row in the Prompt Log](https://app.notion.com/p/3f007370772081fa9f05e873151625ef) (umbrella row: [P009](https://app.notion.com/p/3eb07370772081f9abd7c40514a78bc0)) |
| Date | 2026-10-06 |
| Plan refs | Plan v7 §1, §3.2 F-01/F-08, §6, §7, §8, §11, §12.1–12.4, §14, §18, §21, §22; ADRs 0003–0007 |

## 1. Objective

Part a of P009 does two things:

- **Record product decisions in the repository:** a plan addendum (v7.1), four ADRs, two new
  `CLAUDE.md` sections and a Notion ideas backlog.
- **Give the backend a consent and age-attestation model** that later features (SOS, live share,
  reports, Trusted Circle) reuse: a `consent_records` table, `users.adult_attested_at`, a
  bootstrap that refuses a new account without consent and an age declaration, and two consent
  endpoints.

An amendment pasted with the prompt changed the roadmap order, added ADR 0013 (regions,
`regionCode`) and ADR 0014 (civic reports), and adjusted the backlog, the risks and one follow-up.

Not in scope, and not built: any Android code (part b), contacts, SOS, live share, reports,
Trusted Circle code, child or teen accounts, multi-region code, account deletion.

## 2. Context & prerequisites

- P008b merged (PR #17, `4bcfbf5`, 2026-10-02). No open pull requests. Hooks active. Clean tree.
- Docker Desktop was not running at the start; it was started for the database tests, the
  oasdiff comparison and the container smoke test.
- The Plan v7 PDF can't be read on this machine; the plan text quoted in the prompt was used.
- Legal points (DPDP, commencement date) came from the prompt, which says they come from
  summaries. They are recorded as "to be verified by a lawyer" and were not researched further.

## 3. Workflow executed

1. `/start-prompt P009a`: `main` fast-forwarded to `4bcfbf5`; PR #17 confirmed merged; Notion
   P008b and umbrella P008 → Merged; rows P009a (In progress) and P009b (Planned) created;
   umbrella P009 and P014 annotated; branch created.
2. A6: Drizzle schema, `pnpm db:generate`, SPDX header and `COMMENT` statements added by hand
   to the new, unmerged migration.
3. A7: `consents` module, bootstrap change, problem codes, contract 0.3.0,
   `pnpm openapi:generate`.
4. Tests written; first full run: 249 of 250 passed. The failing test showed the index was
   created as `DESC NULLS LAST`, which a plain `ORDER BY decided_at DESC` does not match. The
   schema was changed and the migration regenerated (it was not merged, so this is allowed by
   ADR 0003). Second run: 250 of 250 (`ed6ec5d`).
5. A1, A2 and the amendment: addendum and ADRs 0010, 0011, 0013, 0014 (`3e48625`).
6. A3: `CLAUDE.md` (`4d84a51`). A4: `/start-prompt` (`72a22fc`). A8: diagram (`1392752`).
7. A5: Notion Ideas backlog, ADR rows, follow-ups.
8. This log, quality gates, `/ship-prompt`.

## 4. Changes

| Area | What |
| --- | --- |
| `backend/drizzle/0002_consent_records_adult_attestation.sql` | new table `consent_records`; nullable `users.adult_attested_at`; comments |
| `backend/src/db/schema/` | `consents.ts` (new), `users.ts` (+1 column), `index.ts` |
| `backend/src/modules/consents/` | `purposes.ts` (allowlist), `schema.ts`, `service.ts`, `routes.ts` |
| `backend/src/modules/users/` | bootstrap takes `consent`; checks before any write; one transaction |
| `backend/src/contract/` | version 0.3.0; three problem codes; 403 and 409 descriptions |
| `backend/test/` | `db/consents.test.ts` (new), `db/users.test.ts`, `db/schema.test.ts`, `db/migrate.test.ts`, `contract.test.ts` |
| `contracts/` | `openapi.json` regenerated; `README.md` (codes, consent, `regionCode`) |
| `docs/plan/` | `addendum-v7.1.md` (new); `README.md` |
| `docs/adr/` | 0010 (Accepted), 0011 (Proposed), 0013 (Accepted), 0014 (Proposed); notes in 0004 and 0005; index |
| `CLAUDE.md` | "Plan addendum", "Age and consent rules"; `regionCode` in the naming rules |
| `.claude/commands/start-prompt.md` | step 2: P001 exception; last part of a split prompt |
| `docs/diagrams/` | `009a-consent-and-bootstrap.*` |

**Migration (expand-only, ADR 0003).** `consent_records`: `id uuid` primary key
(`gen_random_uuid()`), `user_id` → `users(id)` `ON DELETE CASCADE`, `purpose` with
`CHECK (purpose ~ '^[a-z][a-z0-9_]{2,40}$')`, `status` in (`granted`, `withdrawn`),
`notice_version`, `notice_locale` in (`en`, `bn`), `decided_at timestamptz default now()`, index
(`user_id`, `purpose`, `decided_at desc`). Append-only: application code never updates a row.
Old code keeps working against the new schema.

**API contract diff (0.2.0 → 0.3.0, additive).** oasdiff 1.32.1 against `main`: 0 errors,
0 warnings, 3 info.

- `POST /v1/me/bootstrap`: new optional request property `consent`
  `{ageConfirmed, noticeVersion, noticeLocale, purposes}`.
- `GET /v1/me/consents` (`getMyConsents`): `{items: [{purpose, status, noticeVersion, decidedAt}]}`.
- `PUT /v1/me/consents/{purpose}` (`setMyConsent`): body `{status, noticeVersion, noticeLocale}`.
- New codes: `consent_required` (403), `adult_required` (403), `account_deletion_required` (409).

**Behaviour change that the contract diff does not show:** a bootstrap without `consent` now
gets 403 when the account does not exist yet. The debug app from P008 never calls bootstrap, and
no released app exists.

**Size:** about 1,790 changed lines without generated files: backend code and SQL 555, tests
687, documents and tooling 548. That is over the ~800-line guide. It was not split further
because the ADR and the `CLAUDE.md` rules describe the code in the same PR, and the amendment
added two ADRs after the prompt was sized. The five commits are separate review units.

**Deployment impact:** yes. The image changes, and the next staging deploy runs migration 0002
before the new revision. No deploy workflow, cloud configuration or secret change.

## 5. Diagram

[`docs/diagrams/009a-consent-and-bootstrap.svg`](../diagrams/009a-consent-and-bootstrap.svg)
(16 nodes): age gate → consent notice → OTP → bootstrap with consent → `users`,
`consent_records` and `audit_log` written in one transaction; later `PUT` and `GET` per purpose;
a grey node for the future Trusted Circle consent. The first render had arrows crossing two
boxes; two nodes got explicit ranks.

## 6. Quality gate & test results

| Command | Result |
| --- | --- |
| `pnpm typecheck` · `pnpm lint` · `pnpm format:check` (in `backend/`) | clean |
| `pnpm db:check` · `pnpm db:generate` | consistent · "No schema changes" (no drift) |
| `pnpm test` (unit + db, Testcontainers PostGIS) | **250 passed, 0 failed**, 21 files |
| `pnpm build` · `node dist/scripts/set-role.js --help` | clean |
| `pnpm openapi:check` · `pnpm openapi:lint` | up to date · valid |
| oasdiff 1.32.1 `breaking` and `changelog` against `main` (Docker) | 0 errors, 0 warnings, 3 info |
| `node scripts/container-smoke.mjs` | 34 checks passed |
| `pnpm generate` · `pnpm check` in `tools/diagrams` | 13 diagrams up to date |
| markdownlint-cli2 0.18.1 | 0 errors |
| JSON validity (tracked `*.json`) | 28 files, 0 invalid |
| gitleaks 8.30.1 | no leaks found |

No workflow file changed, so actionlint does not apply.

Tests by requirement:

| Requirement | Covered by |
| --- | --- |
| New user with valid consent → 201; user, consent row and two audit rows share one timestamp | `users.test.ts` |
| No consent → 403 `consent_required`; `ageConfirmed` false or absent → 403 `adult_required`; zero rows | `users.test.ts` (4 cases) |
| Unknown or malformed purpose, no `account_core`, bad notice version or locale → 400, value not echoed, zero rows | `users.test.ts` (8 cases) |
| Existing user with or without consent → 200 unchanged, consent ignored | `users.test.ts` |
| 10 parallel bootstraps → one user, one set of consent rows | `users.test.ts` |
| Phone never from the body | `users.test.ts` (existing) |
| `GET` returns the latest per purpose; `no-store` | `consents.test.ts` |
| Grant → withdraw → grant = three history rows; identical `PUT` inserts nothing; new notice version is a new row | `consents.test.ts` |
| 10 parallel identical `PUT`s insert one row | `consents.test.ts` |
| `account_core` withdrawal → 409; unknown purpose → 400 | `consents.test.ts` |
| Users cannot read or write another user's consents; a smuggled user id is ignored | `consents.test.ts` |
| Soft-deleted user → 403 `account_deleted`; 401 without a token | `consents.test.ts` |
| Table defaults, CHECKs, index, comments, cascade on user delete | `schema.test.ts`, `consents.test.ts` |
| Migration applies to an empty database, second run is a no-op, concurrent runs serialise | `migrate.test.ts` |
| Contract: paths allowlist, 0.3.0, open strings, no date-of-birth field, no "kolkata" | `contract.test.ts` |
| Logs carry `user_id` and purpose only; no phone or uid | `users.test.ts`, `consents.test.ts` |

**Not verified:** the staging deploy. Claude Code can't see it; it is unverified until Rahul
reports the Actions run.
**SOS failure matrix (Plan v7 §7.5):** not applicable, no SOS, live-location or contacts code.

## 7. Decisions & ADRs

- **[ADR 0010](../adr/0010-adults-only-and-consent-records.md) (Accepted):** adults only by
  recorded self-declaration, no date of birth, per-purpose just-in-time consent, append-only
  history, `account_core` withdrawn only by deleting the account, purposes as open strings with
  a server allowlist, retention decided in P020.
- **[ADR 0011](../adr/0011-trusted-circle-principles.md) (Proposed):** Trusted Circle principles.
- **[ADR 0013](../adr/0013-regions-and-expansion.md) (Accepted):** "region" and `regionCode`.
- **[ADR 0014](../adr/0014-civic-reports-ask-govt.md) (Proposed):** civic reports.
- ADR 0012 is reserved for the Firebase configuration decision in P009b.

Implementation decisions:

- **Consent is checked before the insert.** Bootstrap first looks the account up. Only if
  there is none does it check `consent` and `ageConfirmed`, so "existing user: consent ignored"
  and "new user: nothing written on failure" both hold.
- **Schema validation still applies to an existing user.** A malformed `consent` object (for
  example an unknown purpose) is a 400 for everyone; "ignored" means its valid content is not
  used.
- **The allowlist is a Zod `refine`,** so the contract shows a pattern (open string), and the
  error is the usual `validation_error` with a path and no echoed value.
- **`PUT` locks the user's row and uses `clock_timestamp()`.** Concurrent calls for one user
  run one after the other, and the row written last is the newest. Bootstrap keeps the default
  `now()`, so the account, the attestation and the consent share one timestamp.
- **`PUT` always answers 200 with the latest state,** whether or not a row was inserted.
- **Audit rows use entity `consent` with the user's id** and metadata without personal data.
- **Index order is plain `DESC`** (see step 4 in section 3).
- **Roadmap wording.** The amended roadmap no longer lists the company-gated channels as a
  step. The addendum keeps them as a note under "company registration and lawyer review",
  because §7.4 still gates them. Remove the note if that was not intended.
- **`/start-prompt`:** only the two requested changes. The search command is unchanged.

## 8. Security & privacy notes

- **New personal data:** `users.adult_attested_at` and `consent_records` (purpose, status,
  notice version, locale, time). Both are marked `PERSONAL DATA` in the schema and in database
  comments. No date of birth. Nothing is stored for a person who is under 18 or has not agreed.
- Identity comes from the verified token only. The consent routes take no user id; a test
  sends one in the body and the query and shows it is ignored.
- Logs: `user_id` and purpose only. Audit metadata: purposes, notice version, status.
- Validation errors never echo submitted values (tested).
- Test data is fake: `test-uid-N`, `+91000000NNNN`, the `demo-saferoute` project.
- Public-repository check on the diff: no project IDs, hosts, e-mail addresses, local paths or
  survey responses. The addendum names service accounts by their short names only, as ADR 0007
  already does.
- No new dependency. No `.env`, keystore or `google-services.json` touched or read.

## 9. Known issues & risks

- **Legal statements are unverified.** DPDP points and the commencement date come from
  summaries. Everything is marked "to be verified by a lawyer".
- **Self-declared age can be false.**
- **Optional purposes are not enforced yet.** `sos_alerts`, `live_sharing` and
  `safety_reports` can be recorded, but no feature checks them until it is built.
- **A consent row cannot outlive its account** (cascade). If proof of consent must be kept
  longer, P020 changes that.
- **Over the size guide** (section 4).
- **Correction to a commit message:** `72a22fc` says the `gh --search` query returned no rows.
  That was a mistyped query in the preflight. `[P008b]` finds PR #17; the bare `[P008]` finds
  nothing, which is the case the change handles.

## 10. Follow-ups & prerequisites for next prompt

New in Notion (*Follow-ups*, source P009a): lawyer review (DPDP statements, age gate, consent
model, ADR 0011 and 0014; High) · P020 retention and export of `consent_records` · P020 account
deletion path for withdrawing `account_core` · consent UI and server checks for the optional
purposes (P013, P016, P017, P014/P015) · stricter age gate if the lawyer requires it ·
`/start-prompt` umbrella-row rule · Plan v8 capacity model per region · staging trial credit
expiry.

Updated: "P017 to include regionCode (see ADR 0013)" replaces the `cityCode` item · "show and
record DPDP consent before bootstrap" is In progress (server side done).

Notion *Ideas backlog* (new database, 19 rows) and *Architecture Decisions* (4 rows) are filled.

**Next: P009b** (`feat/009b-signin-onboarding`), not started. It starts when Rahul says
"P009b". Before testing part b on the phone:

- Merge this PR and let staging deploy. The deploy applies migration 0002.
- `android/app/google-services.json` present locally and ignored by git.
- `saferoute.apiBaseUrl` still in the user-level `gradle.properties`.
- A Firebase test phone number and code, typed on the phone by Rahul only.

## 11. How Rahul can verify

1. Read the pull request commit by commit, then
   [`docs/plan/addendum-v7.1.md`](../plan/addendum-v7.1.md) and ADRs 0010, 0011, 0013 and 0014.
   Check the roadmap order in section D against your amendment.
2. In Notion: the *Ideas backlog* (19 rows; Trusted Circle and Ask govt are "After company"
   with blocker "lawyer review") and the *Prompt Log* (P009a, P009b, the note on P014).
3. With Docker Desktop running, in `backend/`: `pnpm install`, then `pnpm test` → 250 passed.
4. Look at the `contracts/openapi.json` diff: version 0.3.0, one optional property and two new
   paths, nothing removed. The `contracts-ci` job summary shows the oasdiff changelog.
5. CI green (`repo-checks`, `backend-ci`, `contracts-ci`, `container-ci`). Squash and merge.
   Delete the branch.
6. After the merge, watch *Actions → deploy-staging*. It runs migration 0002, then the new
   revision. Tell Claude Code the result.
7. Say "P009b".

## 12. Learning notes

No Android code in this part; the Android notes come with P009b. Backend concepts used here:

- **Consent per purpose.** One permission for one use of data (for example `sos_alerts`),
  asked for when the feature is first used, and withdrawable on its own.
- **Append-only table.** Rows are only added. A change of mind is a new row, so the table is
  also the history. The current state is the newest row.
- **`DISTINCT ON` (PostgreSQL).** "Give me one row per purpose: the first one in this order."
  With the order "newest first" that is the latest decision per purpose.
  <https://www.postgresql.org/docs/current/sql-select.html#SQL-DISTINCT>
- **Transaction.** A group of writes that all happen or none do. The account, its consent rows
  and its audit rows are written in one.
- **Row lock (`SELECT ... FOR UPDATE`).** Makes a second request for the same user wait until
  the first has finished, so two identical requests can't both insert.
- **`now()` vs `clock_timestamp()`.** `now()` is the time the transaction started;
  `clock_timestamp()` is the real time at that statement.
- **Open string vs enum in an API.** An enum in a response breaks old apps when a value is
  added. An open string plus a server-side allowlist doesn't.
- **Expand-only migration.** Only adds things (a table, a nullable column), so the code that is
  already deployed keeps working while the new code rolls out.
- **Addendum.** A short document that amends a longer one without rewriting it. Where they
  differ, the addendum applies.
