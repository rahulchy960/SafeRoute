# P012a3: keep OSRM images out of the registry cleanup

| Field | Value |
| --- | --- |
| Prompt | P012 · a small fix after part a (P012a, P012a2, **P012a3**), before P012b |
| Milestone | M4 (depends on P012a2, merged as `7ba7c14`) |
| Branch | `fix/012a3-keep-osrm-images` |
| PR title | `fix(infra): registry cleanup keeps OSRM routing images [P012a3]` |
| Notion | [P012a3 row in the Prompt Log](https://app.notion.com/p/3f207370772081239dc8e08b4618b489) |
| Date | 2026-10-07 |
| Plan refs | Plan v7 §13.1, §13.2; ADRs 0007, 0020 |

> **No cloud command was run.** The policy file changed in the repository only; the staging
> repository gets it when Rahul runs `bootstrap-staging.ps1 -Apply`.
>
> **Not verified:** how Artifact Registry's cleanup job applies the new rule on the real
> repository; whether `set-cleanup-policies` leaves policies with other names in place (the
> script therefore never runs it over a policy it did not write).

## 1. Objective

Answer whether the registry cleanup policy could delete an OSRM image that a Cloud Run
revision uses, and remove the risk.

## 2. Context & prerequisites

- P012a2 merged (PR #30, `7ba7c14`); no open pull requests; clean tree; hooks active.
- The policy before this change: delete images older than 7 days; keep the 10 most recent
  versions.
- Routing images are pushed as `saferoute-osrm:osrm-<profile>-<extract date>`.

## 3. Workflow executed

1. `/start-prompt`: main synced, PR #30 confirmed merged, Notion P012a2 set to Merged,
   branch, Notion page.
2. Read the Artifact Registry cleanup-policy documentation and the Cloud Run "Deploying
   container images" page (both on 2026-10-07).
3. Policy file, script, tests, runbooks, this log, `/ship-prompt`.

**The answer:**

| Question | Answer | Source |
| --- | --- | --- |
| Would the old policy delete an OSRM image that an active revision uses? | **Yes, it can.** An image older than 7 days is deleted unless it is among the 10 most recent versions of its image (`saferoute-osrm`, both profiles together). A profile that is rebuilt rarely falls out of the 10 | The policy file; Artifact Registry documentation: "If an artifact matches the criteria in both the delete policy and the keep policy, the artifact is kept" |
| Does a serving revision need the registry copy? | **No.** "The container image is imported by Cloud Run when deployed. Cloud Run keeps this copy of the container image as long as it is used by a serving revision. Container images are not pulled from their container repository when a new Cloud Run instance is started." | Cloud Run documentation, "Deploying container images" |
| Is there any risk left? | **Yes, for rollback.** That sentence covers a *serving* revision. The previous revision, which the runbook rolls back to, serves nothing, so its copy is not promised; and an old graph cannot be rebuilt once the dated extract is gone | Reading of the same sentence; runbook section 8 |

So the running services were never in danger, the rollback path was, and the keep rule is
added.

## 4. Changes

| Area | What |
| --- | --- |
| `infra/artifact-registry-cleanup-policy.json` | Third rule `keep-osrm-routing-images`: keep tagged images whose tag starts with `osrm-` |
| `infra/staging/bootstrap-staging.ps1` | New audit row "Registry cleanup keeps routing images (tag osrm-)": PRESENT when a keep rule for `osrm-` exists, NOTE when the repository deletes by age without one, absent when nothing deletes. `-Apply` sets the policy file again only when every policy on the repository has a name from that file |
| `infra/staging/tests/` | 8 new tests; the fake repository now returns the committed policy as the API reports it |
| Docs | `routing-capacity-staging.md` section 4 and the deploy table, `gcp-staging-setup.md` step 2, `infra/README.md`, this log |

No backend, contract, Android, workflow or migration change.

**Deviations and choices:**

- **The row is a NOTE, not MISSING**, like the existing optional cleanup row: it does not
  fail `-Verify`, because nothing is at risk until a routing image is 7 days old.
- **A policy the script did not write is never replaced.** The row then says how to add the
  rule by hand. This keeps the script additive (ADR 0007).
- **No package-name condition in the rule.** The documentation warns that a package prefix
  changes what a keep rule does; the tag prefix alone is enough, since API images are tagged
  with a commit SHA, which cannot start with `osrm-`.
- **The script reads a rule's action in double quotes.** An existing test forbids the
  single-quoted word that a deleting `gcloud` command would use; the new code only reads
  what the repository reports, and a comment says so.

## 5. Diagram

No diagram needed: no flow, schema or infrastructure shape changed.

## 6. Quality gate & test results

| Command | Result |
| --- | --- |
| `Invoke-InfraCheck.ps1`, Windows PowerShell 5.1 | PSScriptAnalyzer 0 findings; Pester **106 passed, 0 failed** (98 before) |
| `Invoke-InfraCheck.ps1`, PowerShell 7 | not run locally (not installed); `infra-ci` runs it on Linux and Windows |
| markdownlint-cli2 · JSON validity · gitleaks 8.30.1 | 87 files, 0 errors · 54 files valid · no leaks |

| New test | What it proves |
| --- | --- |
| The policy file | one delete rule, the keep-10 rule, and one conditional keep rule for tagged images with prefix `osrm-` and no age limit |
| The workflow's tag | `osrm-staging.yml` still builds the tag as `osrm-<profile>-<date>`, so the rule matches |
| Audit, current policy | the row is PRESENT |
| Audit, earlier policy | the row is a NOTE with its next action, and `-Verify` still exits 0 |
| Apply, earlier policy | exactly one command: `set-cleanup-policies` with the policy file |
| Apply, a foreign policy | no command; the output says the policy is not replaced and how to add the rule by hand |
| A hand-made keep rule | counts under any name |
| A policy without a delete rule | needs no keep rule; no row |

**What these tests cannot see:** the registry's cleanup job. They prove what the file says
and which command the script would run.

**SOS failure matrix (Plan v7 §7.5):** not applicable.

## 7. Decisions & ADRs

No new ADR. Routing images are exempt from automatic deletion; old ones are removed by hand.

## 8. Security & privacy notes

- No personal data, permission or secret involved. Nothing becomes public.
- The script still never deletes anything and never replaces a policy it did not write.
- Public-repository check: no project identifier, URL or local path in the diff.

## 9. Known issues & risks

- **Storage grows** by about 0.5 GiB (about US$0.05 a month) for every routing image ever
  pushed, until old ones are deleted by hand.
- If a pushed tag ever points at a multi-part image (an index with attestations), whether
  the parts are kept with the tag is not known. The workflow pushes a single image today.
- The rule protects nothing until `-Apply` has set it on the staging repository.

## 10. Follow-ups & prerequisites for next prompt

New in Notion (*Follow-ups*, source P012a3): delete old routing images by hand when they
are no longer a rollback target (Low).

The staging steps of P012a2 are unchanged and still owed; `-Apply` now also offers the
policy update. **Next: P012b.**

## 11. How Rahul can verify

1. Read the three rules in `infra/artifact-registry-cleanup-policy.json`.
2. `powershell -NoProfile -ExecutionPolicy Bypass -File infra\staging\tests\Invoke-InfraCheck.ps1`:
   0 findings, 106 passed.
3. CI green, then squash and merge. Nothing deploys.
4. On staging: `.\infra\staging\bootstrap-staging.ps1`. If "Registry cleanup keeps routing
   images" is a NOTE, run `-Apply` and accept that step; then
   `gcloud artifacts repositories list-cleanup-policies saferoute --location=asia-south1`
   must list three policies. Cost: none.

## 12. Learning notes

- **Cleanup policy.** Rules on a registry that delete old images so storage does not grow
  for ever. A keep rule wins over a delete rule for the same image.
- **Image copy at deploy.** Cloud Run copies an image when a revision is created and runs
  from that copy, so deleting the registry image does not stop a running service.
- **Why keep it anyway.** A rollback target is not running. What is promised for the thing
  in use is not promised for the thing you fall back to; check both.
