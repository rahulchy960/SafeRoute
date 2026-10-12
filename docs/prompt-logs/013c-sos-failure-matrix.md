# P013c: the SOS failure matrix as a living document, with manual test scripts

| Field | Value |
| --- | --- |
| Prompt | P013c · docs only. Numbered after P013 by Rahul; it was run after P014b5 |
| Milestone | M5 (depends on P014a1 to P014b3 and P014b5, merged; `main` at `4334232`) |
| Branch | `docs/013c-sos-failure-matrix` |
| PR title | `docs(sos): add the SOS failure matrix as a living document with manual test scripts [P013c]` |
| Notion | [P013c row in the Prompt Log](https://app.notion.com/p/3f70737077208178b3e4c4da607702e3) |
| Date | 2026-10-12 |
| Plan refs | Plan v7 §7.5, §3.4; addendum v7.1, section B; ADRs 0024, 0027 |

> **Docs only.** No code, test, contract, migration, workflow or dependency changed.
>
> **No result was invented.** No script has been run on a phone, and no SMS alert has been
> reported as sent or received. Every built row is "Manual pending".
>
> **The prompt's input lines were empty** (manual SMS results, the phone and its Android
> version, the second phone, the P013a staging checks, the P013b phone results). Each is
> recorded as "not reported".
>
> **The matrix file already existed.** P014a1 created `docs/sos/failure-matrix.md` and
> P014a2 to P014b3 extended it, with the scripts M0 to M16 inside. This prompt restructures
> it; it does not start one.

## 1. Objective

Make the failure matrix a document that stays true: one row per scenario with what is
expected, where it is built, the tests that cover it, the script a person runs on a phone,
and a status that claims no more than the evidence shows; the scripts in a file of their
own; and a rule that every later SOS prompt keeps both current.

## 2. Context & prerequisites

- Preflight: `main` synced at `4334232`; PR #59 (P014b5) merged; no open pull requests; hook
  path `.githooks`. Notion: P014b5 set to Merged, a new row for P013c.
- `android-ci` on `main`: run 38240011508 for `4334232`, success (checked with
  `gh run list`). This is the evidence the "Automated pass" statuses rest on.
- Plan v7 §3.4 could not be read: this machine cannot render the PDF. The launch rule in
  the matrix is the one stated in the prompt, and the document says so; the crash-free
  figure is not repeated because the prompt did not give it.

### What was found (requirement 1)

**Prompt logs read:** `012f1-sos-tile`, `012f2-emergency-notification`, `013a-contacts-backend`,
`013b1-contacts-local-copy`, `013b2-contacts-screens`, `014a1-sos-data-and-state-machine`,
`014a2-sos-runner-and-service`, `014a3-sos-screens`, `014b1-sos-sms-engine`,
`014b2-sos-alert-dispatch`, `014b3-sos-alerts-consent`, `014b5-ci-failing-test`. There is no
single `014a` log; part a has three. No SOS log is later than P014b5.

**Manual scripts that existed:** M0 to M16, all in `docs/sos/failure-matrix.md` (M1 to M10
from part a of P014; M11 to M16 and M0 from part b). The two Play
drafts in `docs/play/` point to M3 to M6 and M11 to M16. No other document defines a
script; other "M" numbers in the repository are milestones.

**Texts:** `docs/legal/sos-alerts-notice-v1.md` and `-v2.md`, `docs/legal/sos-sms-text-v1.md`,
`docs/play/send-sms-declaration-draft.md`,
`docs/play/foreground-service-location-declaration-draft.md`.

**Test classes** (Android, JVM with Robolectric; the number is the count of tests):

| Subject | Classes |
| --- | --- |
| State machine and records | `SosStateMachineTest` (4), `SosEngineTest` (15), `SosHousekeepingTest` (2), `SosCoreBoundaryTest` (1) |
| Recovery | `SosRecoveryTest` (5), and the "process death" tests of `SosEngineTest`, `SosRunnerTest`, `SosViewModelTest` |
| Runner, trail, service | `SosRunnerTest` (14), `SosTrailTest` (8), `SosDeviceTest` (11), `AndroidSosHostTest` (2), `SosForegroundServiceTest` (3) |
| Message builder | `SosMessageTest` (13), `SmsSegmentsTest` (4), `SosSmsTextDocumentTest` (3) |
| Sender | `SosAlertsTest` (14), `RoomSosActionStoreTest` (2), `SosSmsTest` (8), `SosDispatchTest` (20), `SosAlertsDeviceTest` (8), `SosAlertLinesTest` (7) |
| Consent gate and permission | `SosAlertsDeviceTest`, `SmsPermissionCardTest` (10), `ApiContactsRepositoryTest` (25), `ContactsNoticeDocumentTest` (5) |
| Screens | `SosScreensTest` (20), `SosFlowTest` (5), `SosEmergencyScreenTest` (2), `SosViewModelTest` (20) |
| Entry points (P012f) | `EmergencyShortcutTest` (4), `SosTileServiceTest` (3), `EmergencyActivityTest` (5), `EmergencyShortcutsManifestTest` (4), `EmergencyNotificationTest` (14), `EmergencyShortcutFlowTest` (5), `SettingsShortcutsTest` (18) |
| Contacts | `ContactsDatabaseTest` (7), `ContactRulesTest` (7), `ContactsSessionSyncTest` (5), `ContactsBoundaryTest` (5), `ContactIntentsTest` (10), `ContactsScreensTest` (27), `ContactsViewModelsTest` (28), `ContactsFlowTest` (6) |

Backend: `backend/test/contacts-page.test.ts` and the contacts tests of P013a; no SOS code
exists on the server.

**Results already recorded:** the tile and the pinned notification on one phone (Samsung,
Android 17; the "Revision 2026-10-08" sections of the two P012f logs), and the P013a
staging checks (the "Revision (P014a1, 2026-10-09)" section of the P013a log). Nothing
else.

## 3. Workflow executed

1. `/start-prompt`: sync, PR #59 merged, Notion, branch.
2. Discovery (above). An index of every test class and test name was built from the test
   sources with a small script kept outside the repository.
3. `docs/sos/manual-test-scripts.md`: M0 to M16 moved with their numbers and steps, each
   with preconditions, steps, what to record, what to do if it fails and the rows covered;
   M17 to M28 new.
4. `docs/sos/failure-matrix.md`: generated from a list of rows in which every test is
   named by the start of its name; the generator stops on a name that does not exist or
   matches more than one test. It ran clean: 269 test names. Two citations that did not
   really belong to their rows were removed first.
5. Wiring: CLAUDE.md, the pull request template, `README.md`, `docs/plan/README.md`, the
   two Play drafts.
6. markdownlint (four findings, fixed), a link and anchor check, gitleaks.
7. Notion: the page of links, three follow-ups, the numbering note.
8. `/ship-prompt`.

## 4. Changes

**`docs/sos/failure-matrix.md` (restructured)**

- 23 rows: 1 to 11 (Plan v7 §7.5), A1 to A4 (addendum v7.1, section B), A5 (ADR 0024),
  A6 to A12 (new).
- Columns: ID, scenario, expected behaviour, implemented in, automated tests, manual
  script, status, last result date, notes.
- Sections: how to read the status, state of the build, Known gaps, Device results with
  the launch rule, Evidence (the file and the exact names of the tests per row), History.
- The test names are under "Evidence", one section per row, and the table cell names the
  classes and links there. Dozens of names in one table cell could not be read.
- A row may carry more than one status (see section 7).

**`docs/sos/manual-test-scripts.md` (new)**

- Safety rules, how to record a result, what to do when a script fails.
- M0 to M16: the same numbers and steps. Changes: the layout; "from which number" became
  "from which SIM" in M15; "tell me" became "Record".
- New, for what is built: M18 (the pinned notification dismissed or killed), M19 (a phone
  without the tile), M20 (no contact), M21 (contacts that never reached the phone), M22
  (every message fails), M23 (the contact list changes during an SOS).
- New drafts, not to be run: M17 (SOS from the tile or widget, P014c), M24 to M26 (the
  server rows, P015), M27 (the notice after a restart, P014c), M28 (the live link, P015 and
  P016).
- Troubleshooting: "the second phone received nothing", ten causes in the order to check.

**Wiring** (six files)

- `CLAUDE.md`: the rule in "SOS, live-location and contacts rules", four supporting rules
  (a status comes from evidence; a named test must exist; scripts keep their numbers; no
  personal data in a result), and `docs/sos/` in the repo map.
- `.github/pull_request_template.md`: "SOS failure matrix rows touched (link)".
- `README.md`, `docs/plan/README.md`: links.
- `docs/play/*`: the two checklist lines point to the scripts' new file.

**No change**: code, tests, contract, migrations, workflows, dependencies.

## 5. Diagram

No diagram needed (docs only; no flow, state machine, schema or infrastructure changed).

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| Every test named in the matrix exists under that name | Pass: 269 names resolved against the test sources |
| markdownlint (133 files) | Pass, 0 errors (seven `MD036` findings fixed on the way, three of them in this log after its first commit) |
| Relative links and anchors in the six changed Markdown files | Pass: 184 checked, 0 broken. External links: none in these files |
| gitleaks on the branch's commits | Pass: no leaks |
| `git diff --stat main...HEAD` | Docs, `CLAUDE.md`, `README.md` and the PR template only |
| `android-ci` on `main` (the evidence for "Automated pass") | Run 38240011508, `4334232`: success |

No Android, backend or contract gate applies: nothing under `android/`, `backend/` or
`contracts/` changed. `repo-checks` runs on the pull request.

### The matrix (statuses on 2026-10-12)

| ID | Scenario | Status |
| --- | --- | --- |
| 1 | No mobile data, signal present | Automated pass · Manual pending · Waiting on P015 |
| 2 | No signal at all | Automated pass · Manual pending |
| 3 | API down or 5xx | Waiting on P015 |
| 4 | App swiped away or killed by the OEM | Automated pass · Manual pending |
| 5 | Phone rebooted during SOS | Automated pass · Manual pending · Waiting on P014c |
| 6 | Double tap or duplicate trigger | Automated pass · Manual pending |
| 7 | SEND_SMS denied or not approved | Automated pass · Manual pending |
| 8 | No GPS fix | Automated pass · Manual pending |
| 9 | FCM failure | Waiting on P015 |
| 10 | Worker crash mid-action | Waiting on P015 |
| 11 | Accidental trigger | Automated pass · Manual pending |
| A1 | SOS started from the tile while the phone is locked | Waiting on P014c |
| A2 | Pinned notification dismissed or killed | Automated pass · Manual pending · Waiting on P014c |
| A3 | Tile unavailable on the device | Automated pass · Manual pending |
| A4 | Notification permission denied | Automated pass · Manual pending |
| A5 | Opted-out contact excluded | Automated pass · Manual pending |
| A6 | Contacts empty at SOS time | Automated pass · Manual pending |
| A7 | Contacts never synced to the phone | Automated pass · Manual pending · Waiting on P014c |
| A8 | SMS fails for every contact | Automated pass · Manual pending |
| A9 | Dual-SIM phone with the wrong default SIM | Manual pending |
| A10 | Contact list changed mid-SOS | Automated pass · Manual pending |
| A11 | Process death inside the 5-second countdown | Automated pass · Manual pending |
| A12 | SOS location unavailable | Automated pass · Manual pending |

No row is "Manual pass", "Fail" or "Not started".

### Known gaps, in short

- Not built: the server half of an SOS (P015), the live link (P016), an SOS from the tile,
  the widget or the notification, the notice after a restart, and a warning before an
  emergency that alerts are not ready (P014c).
- Built but never observed: the service on real phones, any real SMS, what a phone answers
  without a SIM or service, two SIMs (no automated test), the SMS app with several
  recipients. "Sent" is all the phone knows; nothing can show "delivered".
- Accepted in the design: an opt-out shortly before the trigger is missed; one case of a
  double alert after a process death.
- Not specified: a contact removed after the trigger and before their message left;
  nothing tries again after the 20 tries.

## 7. Decisions & ADRs

No ADR: nothing hard to reverse.

- **A row may carry more than one status.** The prompt lists single values; a row whose
  logic is tested, whose phone run is owed and whose server half is unbuilt is all three.
  One value would either hide the tests or claim the row. The values themselves are the
  seven the prompt names. A row is done only at "Manual pass".
- **Row 5, A2 and A7 carry "Waiting on P014c", row 1 "Waiting on P015"**, besides their
  other statuses, for the part that is not built. Row A1 is "Waiting on P014c" alone: its
  tests cover what exists (the tile opens the emergency options), not the row.
- **The earlier phone report is not "Manual pass".** The tile and the notification were
  reported as working on one phone before these scripts existed, without details. It is
  recorded under Device results and in the notes of A1 and A2.
- **Rows A6 to A12 have no text in the plan.** Their expected behaviour is the design of
  ADR 0027, and where that is silent the row says so and Known gaps lists it. Nothing was
  made up to fill the cell.
- **Draft scripts get numbers now** (M17, M24 to M28), so that rows 3, 9, 10 and A1 have
  something to link to. They say that they must not be run.
- **Four supporting rules in CLAUDE.md** beyond the sentence the prompt gave: they are the
  prompt's own conditions (status by evidence, no renumbering, no personal data), written
  where the next prompt will read them.
- **The generator is not committed.** The prompt is docs only, and a script in the
  repository would be code to maintain. A check in CI that every named test exists would be
  worth having; it is listed under follow-ups rather than built here.

## 8. Security & privacy notes

- No phone number, name or coordinate is in either document; the scripts forbid them in
  results. The only long number is a CI run id.
- The scripts send real SMS when followed. Their first section is the safety rules: only
  numbers the tester controls, the contact list checked before every session, practice
  mode first, never the call button, a test account, stop if an unexpected recipient is
  possible.
- The SMS and notice wording quoted in the scripts is the draft in `docs/legal/`, to be
  verified by a lawyer.
- No secret, no local path, no non-public id.

## 9. Known issues & risks

- **About 1,600 changed lines**, above the usual size for one pull request. About 300 are
  the Evidence section, which is generated, about 170 are the old matrix removed, and the
  scripts M0 to M16 were moved, not written.
- The statuses depend on `android-ci` staying green. A red run on `main` makes every
  "Automated pass" untrue until it is green again; nothing updates the document by itself.
- The Evidence section names tests at commit `4334232`. A renamed test makes it stale; the
  new rule in CLAUDE.md asks for the update, and nothing enforces it yet.
- The launch rule is taken from the prompt, not read from the plan.
- New scripts M18 to M23 were written from the code and the strings, not tried on a phone.
  M21 and M23 say where the outcome is not known.
- The troubleshooting point about a phone maker's own switch for sending SMS is from the
  prompt and not verified on such a phone; the text says so.

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P013c):

- Run the manual scripts on a stock phone and two OEM phones before the closed test.
- Record the P013b phone results and the missing details of the P013a staging checks.
- Fill the Known gaps.

Also noted in Notion: P014b4 does not exist (P014b5 follows P014b3), and the debug SMS
safeguard for testers (a follow-up of P014b3) is planned as P014b6.

Worth a later prompt: a small CI check that every test named in the matrix exists.

**For P014c:** it changes rows 5, A1, A2 and A7, turns M17 and M27 into real scripts, and
adds the widget. Its final report lists those rows.

## 11. How Rahul can verify

1. Read `docs/sos/failure-matrix.md`: the table, Known gaps, Device results.
2. Check that no status claims more than the evidence: pick three rows, open their section
   under "Evidence", and find the tests in the named files.
3. Read `docs/sos/manual-test-scripts.md`: the safety rules, then M0 to M16 against what
   you remember of them, then the new ones.
4. Confirm the rule in CLAUDE.md ("The living matrix") and the new line in the pull
   request template.
5. `repo-checks` green on the pull request; squash and merge.
6. When you have run scripts on a phone: give the results in a prompt (phone, Android
   version, build, SMS mode, date, scripts, pass or fail, what each script asks under
   "Record"), with no number, name or place.

## 12. Learning notes

No Android concept was used: nothing was built. Two ideas about testing:

- **A test on the JVM is not a test on a phone.** Robolectric runs Android's code on the
  computer, with stand-ins for the hardware. It can show that the app asks the phone to
  send an SMS; it cannot show that an SMS leaves, that a battery manager lets a service
  live, or what a lock screen shows. That is why most rows are both "Automated pass" and
  "Manual pending".
- **Evidence-based status.** A status is a claim. Writing next to it what it rests on (a
  named test and a green run, or a person's report with a date and a phone) lets a reader
  check it and makes it obvious when it stops being true.
