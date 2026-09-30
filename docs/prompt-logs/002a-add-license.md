# P002a: License and contribution policy

| Field | Value |
| --- | --- |
| Prompt | P002a · License and contribution policy |
| Milestone | M1 (depends on P002, merged as `4d751d4`) |
| Branch | `docs/002a-add-license` |
| Commit / PR title | `docs(repo): add AGPL-3.0 license, docs license and contribution policy [P002a]` |
| Notion | [P002a · License and contribution policy](https://app.notion.com/p/3eb07370772081ceb722e150778bd3cb) |
| Date | 2026-10-01 |
| Plan refs | Plan v7 §0, §17, §20 |

> **Not legal advice.** This log and the files it describes were written by an AI coding
> assistant, not a lawyer. They record project decisions and point to the official license texts,
> which are what apply. For legal questions, ask a qualified lawyer.

## 1. Objective

The public repository had no license ("all rights reserved" by default, which is unclear to
visitors). P002a adds a clear setup:

- code under `AGPL-3.0-only`;
- documentation under `CC-BY-NC-ND-4.0`;
- name/logo and data notices;
- a contribution policy that keeps Rahul the sole copyright holder.

## 2. Context & prerequisites

- P002 was merged (PR #1, squash `4d751d4`, 2026-09-30 18:42 UTC) and synced to Notion. `main`
  was clean, the hook was active, there were no open PRs, and the local P002 branch was deleted
  on Rahul's request.
- Copyright holder line: "Rahul Chowdhury" (given in the prompt; Rahul confirms in review).
- The P002a row was **created** in the Notion Prompt Log (not pre-populated): Number 2,
  Milestone M1, Status In progress.
- Notion is reached through the Notion **plugin** connector (see the P002 log §9).

## 3. Workflow executed

1. `/start-prompt`: `git switch main && git pull --ff-only` (at `4d751d4`), clean tree,
   `core.hooksPath=.githooks`, P002 PR merged, `git switch -c docs/002a-add-license`, Notion row
   created.
2. **R1:** `curl -sSLf https://www.gnu.org/licenses/agpl-3.0.txt`. Checked the header, the
   `END OF TERMS AND CONDITIONS` and "How to Apply" sections, the trailing newline, no CR, no
   non-ASCII or control characters, no trailing whitespace. Copied byte-for-byte to `/LICENSE`
   and ran `cmp` against the download: identical.
3. **R2, R3, R6, R8:** wrote `COPYRIGHT.md`, `docs/LICENSE.md`, `TRADEMARKS.md`,
   `CONTRIBUTING.md`.
4. **R4, R5, R7, R9:** updated `README.md` (License + Data sections, contributions line; the
   "all rights reserved" note removed), `CLAUDE.md` (Licensing section with the SPDX
   convention), `backend/package.json` and `tools/diagrams/package.json`
   (`"license": "AGPL-3.0-only"`), and the `android/` and `moderation/` READMEs.
5. **R10:** `pnpm licenses list` in `backend/` (prod and all) and `tools/diagrams/` (prod).
6. ADR 0002 + ADR index row; this log; quality gate; `/ship-prompt`.

## 4. Changes

| File | Change |
| --- | --- |
| `LICENSE` | New: official GNU AGPL v3 text, unmodified |
| `COPYRIGHT.md` | New: copyright line, R2 statement, scope table (code / docs / template / name / data) |
| `docs/LICENSE.md` | New: CC BY-NC-ND 4.0 notice for `docs/`, link to the official legal code, plain-language summary, ADR-template exception |
| `TRADEMARKS.md` | New: name and logo notice, no official-emergency-service claims, "not legal advice" |
| `CONTRIBUTING.md` | New: no code contributions yet, issues welcome, vulnerabilities via `SECURITY.md`, future contributor agreement |
| `docs/adr/0002-licensing.md`, `docs/adr/README.md` | New ADR + index row |
| `README.md` | "License" and "Data" sections; contributions line; stale "all rights reserved" note removed |
| `CLAUDE.md` | "Licensing" section: license summary, SPDX header convention (from P003), dependency rule, never reconstruct license texts |
| `backend/package.json` | `"license": "UNLICENSED"` → `"AGPL-3.0-only"` (still `"private": true`) |
| `tools/diagrams/package.json` | Same change (see §7) |
| `android/README.md`, `moderation/README.md` | License notes for future packages and files |

No code behaviour, CI logic or backend source changed. `docs/plan/` is untouched. Lockfiles are
unchanged. No API or DB changes.

## 5. Diagram

No diagram needed (documentation and metadata only).

## 6. Quality gate & test results

### LICENSE verification (R1)

| Check | Result |
| --- | --- |
| Source | <https://www.gnu.org/licenses/agpl-3.0.txt> (fetched 2026-10-01) |
| SHA-256 | `0d96a4ff68ad6d4b6f1f30f713b18d5184912ba8dd389f86aa7710db079abcb0` |
| Size | 34,523 bytes, 661 lines, LF only, ends with a newline |
| First lines | `GNU AFFERO GENERAL PUBLIC LICENSE` / `Version 3, 19 November 2007` |
| Completeness | contains `END OF TERMS AND CONDITIONS` (line 619) and "How to Apply These Terms" (line 621); ends with the gnu.org licenses link |
| Stray characters | 0 non-ASCII or control lines, 0 trailing-whitespace lines, 0 tabs |
| Repo copy | `cmp LICENSE <download>`: identical; `git check-attr`: `text: auto`, `eol: lf` (existing `* text=auto eol=lf` rule, so no new `.gitattributes` rule needed) |

### Repo and backend gate

| Command | Result |
| --- | --- |
| markdownlint-cli2 `**/*.md` | 28 files, **0 issues** |
| JSON validity (tracked + new `*.json`, `*.excalidraw`) | **14/14 valid** |
| gitleaks 8.30.1 `dir` (working tree) and `git` (history) | **no leaks found** |
| `backend/`: `pnpm install --frozen-lockfile` | OK, lockfile unchanged (also in `tools/diagrams/`) |
| `backend/`: `pnpm typecheck` · `lint` · `format:check` · `test` · `build` | **pass** · pass · pass · **32/32** · pass (unaffected, as expected) |
| `git grep -i "all rights reserved"` | only the historical P002 log follow-up and the ADR 0002 context; nothing contradicts the new license |
| Personal data in changed files (emails, local paths, phone numbers) | none (only the pre-existing masked example in CLAUDE.md) |

### Dependency license report (R10)

| Scope | Result |
| --- | --- |
| `backend/` production (`pnpm licenses list --prod`) | 16 packages: **MIT** 15 (hono, @hono/node-server, zod, pino and its helpers), **ISC** 1 (split2). All AGPL-compatible. |
| `backend/` all incl. dev | 160 packages: MIT 125, Apache-2.0 14, ISC 9, BSD-2-Clause 6, BSD-3-Clause 3, **MPL-2.0** 2 (`lightningcss` + its Windows binary, via vite), BlueOak-1.0.0 1 (`minimatch`). No GPL-incompatible, proprietary, unlicensed or non-commercial licenses. |
| `tools/diagrams/` production | `@resvg/resvg-js`: **MPL-2.0** |

MPL-2.0 is file-level copyleft and explicitly allows combination with GNU licenses (MPL-2.0 §3.3),
unless a file carries an "Incompatible With Secondary Licenses" notice. None of these are
shipped to users: they are dev or build tools. **No incompatibility found; no follow-up needed
for the backend.**

**Android items to check when the app is built (no action now):**

- **MapLibre Native Android:** BSD-2-Clause (per its repository), permissive and compatible.
  Re-check the exact version's license and bundled components.
- **Firebase / Google Play services SDKs:** proprietary Google terms. Publishing Rahul's own
  AGPL app on Google Play is fine, since the copyright holder isn't bound by their own license.
  But bundling proprietary SDKs with AGPL code needs a review before release: what recipients
  can rebuild, whether an AGPL "additional permission" (linking exception) is needed, and the
  notices shown in the app. Recorded as a follow-up before P022.
- An in-app **third-party licenses / notices** screen is needed before release (follow-up).

### SOS failure matrix

Not applicable.

## 7. Decisions & ADRs

**ADR 0002 (Accepted): licensing.** Rationale in five lines:

1. AGPL-3.0-only keeps every distributed or network-served modification open. This matters for a
   safety app whose backend runs as a service (GPL wouldn't cover that).
2. Permissive licenses (MIT/Apache) would allow closed forks. PolyForm Noncommercial isn't open
   source.
3. CC BY-NC-ND 4.0 lets the plan and docs be shared unchanged with credit, but not modified or
   used commercially.
4. The name and logo are reserved separately, because copyright licenses don't cover them and
   users must be able to tell the official app from forks.
5. No outside code contributions keeps Rahul the sole copyright holder, so relicensing stays
   possible.

Other decisions:

- **SPDX id `AGPL-3.0-only`** everywhere (not `-or-later`), as the prompt specifies.
- **`tools/diagrams/package.json` also changed** from `UNLICENSED` to `AGPL-3.0-only`. It is
  source code outside `docs/`, so `COPYRIGHT.md` already covers it. Leaving `UNLICENSED` would
  contradict it. This is a one-line change beyond R4's backend-only wording.
- **Scope boundary:** "code" = everything outside `docs/` (including root README/CLAUDE/SECURITY
  files and workflows). "Docs" = everything under `docs/`. Diagram JSON specs live in
  `docs/diagrams/`, so they are CC BY-NC-ND.
- **No README badge:** R9 allows badges only without external services, and a license badge
  would need shields.io. Plain text is used instead.
- **No `.gitattributes` change:** the existing `* text=auto eol=lf` already keeps `LICENSE`
  byte-identical, which was verified.

## 8. Security & privacy notes

- No new personal data. The copyright holder's name appears as Rahul chose it; no email address
  appears anywhere. Commits use the noreply identity.
- The data statement is accurate: no incident, user, location or moderation data, and no exports,
  are committed.
- No secrets; gitleaks is clean (§6).

## 9. Known issues & risks

- The legal texts are referenced, not reviewed by a lawyer (see the disclaimer).
- CC BY-NC-ND on `docs/` also covers the prompt logs and diagrams. Others can't adapt them, which
  is intended.
- Copyright statements in older docs are not retro-edited. The license applies from this commit;
  earlier public commits had no license.
- The Android proprietary-SDK question is open until reviewed (follow-up).

## 10. Follow-ups & prerequisites for next prompt

To record in the Notion *Follow-ups* database:

1. Optional SPDX header check (lint or CI) for new source files (Low).
2. Android third-party SDK license review (Firebase / Play services, MapLibre), before P022
   (Medium).
3. Revisit the contribution policy (contributor agreement) if outside contributors are wanted
   (Low).
4. Third-party licenses / NOTICE screen in the Android app before release (Medium).

The existing P002 follow-up "Choose a LICENSE" is **Done**.

Next prompt: **P003** (`feat/003-db-foundation`). It must use the SPDX header convention.

## 11. How Rahul can verify

1. Read the PR diff: only license and notice files, READMEs, CLAUDE.md, ADR 0002, two
   `package.json` license fields and this log changed.
2. Open `COPYRIGHT.md` and confirm "Rahul Chowdhury" is how you want your name to appear publicly.
3. Confirm you are happy with `AGPL-3.0-only` for code and CC BY-NC-ND 4.0 for docs (ADR 0002).
4. After merging, the repository sidebar on GitHub should show **"AGPL-3.0 license"** (GitHub
   detects `/LICENSE`; no settings change needed).
5. Squash and merge, then delete the branch (automatic).

## 12. Learning notes

- **Copyleft:** a license that lets anyone use, change and share the code, on the condition that
  shared or modified versions stay under the same license with source available. Permissive
  licenses (MIT, Apache) let others make closed versions. Copyleft doesn't.
- **What AGPL adds:** the GPL's obligations start when you *distribute* software. A company that
  only *runs* modified code on its servers never distributes it, so under the GPL it can keep
  its changes private. AGPL §13 closes that gap: if users interact with a modified version over
  a network, they must be offered its source. That fits the SafeRoute backend.
- **Why a license doesn't cover the name or the data:** copyright licenses cover creative works
  (code, text). A product name and logo are trademark matters, so AGPL forks may use the code but
  not the name. User and incident data isn't in the repo at all; it's personal data governed by
  privacy law (DPDP Act) and the privacy policy.
- **SPDX identifiers:** short, standard license IDs (e.g. `AGPL-3.0-only`, `CC-BY-NC-ND-4.0`,
  `MIT`) from the SPDX license list. A header line `SPDX-License-Identifier: AGPL-3.0-only` in
  each source file lets tools and people see the license of a file even when it's copied out of
  the repo. See <https://spdx.org/licenses/>.
