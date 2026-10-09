# P014b3: SOS SMS alerts switched on: notice version 2, the consent gate, the SMS permission flow and the Play drafts

| Field | Value |
| --- | --- |
| Prompt | P014 · part b, third and last piece (P014b1 the SMS engine; P014b2 sending behind a closed gate; **P014b3 consent, permission and the switch-on**; then P014c) |
| Milestone | M5 (depends on P014b2, merged as `ecc7af3`) |
| Branch | `feat/014b3-sos-alerts-consent` |
| PR title | `feat(sos): switch on SMS alerts with notice version 2, a consent gate read on the phone, the SMS permission flow and Play declaration drafts [P014b3]` |
| Notion | [P014b3 row in the Prompt Log](https://app.notion.com/p/3f4073707720814bb068e1485bc8241b) |
| Date | 2026-10-09 |
| Plan refs | Plan v7 §3.2 F-08, §5.3, §7.1–7.5, §12.1, §12.2; ADRs 0010, 0024, 0027 |

> **Part b of P014 is complete with this piece, and it changes what the app does.** A user
> who has agreed to the notice version 2 can start an SOS that **sends real SMS** to their
> emergency contacts: by itself in a build with the SMS permission once the user allowed it
> (debug builds by default), otherwise through the phone's SMS app.
>
> **Nothing was run on a phone.** No SMS has been sent by anyone with this code. The first
> real message will be sent by Rahul, following scripts M11 to M16 in
> `docs/sos/failure-matrix.md`, **only to phones he owns**.
>
> **Before the scripts:** the texts a contact receives are in
> `docs/legal/sos-sms-text-v1.md`, the notice in `docs/legal/sos-alerts-notice-v2.md`. Both
> are drafts that no lawyer and no native speaker has reviewed.
>
> **Still not reported:** the phone scripts M1 to M10 of part a.
>
> **Size:** about 1,700 added lines outside the generated diagram files (source 370, strings
> 75, tests 410, documents 800 including the notice, the two Play drafts and the scripts).

## 1. Objective

Let alerts go out, on the conditions the project set for itself: the user has read and
accepted a notice that describes them, the phone can check that without the network, the
SMS permission is asked for only after the app's own explanation and never during an SOS,
and every text says what really happens.

## 2. Context & prerequisites

- Preflight: `main` synced at `ecc7af3`; PR #57 (P014b2) merged; no open pull requests; hook
  path `.githooks`. Notion: P014b2 set to Merged, a new row for P014b3.
- From P014b2: `SosDispatch` behind `SosAlertPolicy`, whose only class answered "no".
- From P013: the `sos_alerts` consent flow at "Add contact", notice version 1, the server's
  consent records with a notice version.
- The backend needed no change: it stores whatever notice version the app sends and returns
  the latest decision per purpose with its version.

## 3. Workflow executed

1. `/start-prompt`: sync, PR #57 merged, Notion, branch.
2. Read the consent code on both sides; confirmed that the server keeps the version.
3. The flag on the phone and its three writers; `ConsentSosAlertPolicy`; the arm step and
   the emergency screen ask the gate.
4. Notice version 2 in both languages, generated into its document from the app's strings;
   the other texts changed with it.
5. The "Send alerts automatically" card and its disclosure.
6. Tests. On the way:
   - one run of the gate ended in an internal crash of lint on a file this prompt did not
     touch; the next run was clean. It is recorded here in case it returns;
   - six older tests failed because they pinned what this prompt changes on purpose (the
     notice wording "in a later version", one DataStore key, one call at sign-in). Each now
     pins the new behaviour;
   - a test of mine flagged "if you say you are safe" in the notice as a safety claim; it
     is the user's own statement, and the test now tells the two apart.
7. The Play declaration drafts, the phone scripts for sending, ADR note, CLAUDE.md, diagram.
8. `/ship-prompt`.

## 4. Changes

**`feature/contacts`**

- `SOS_ALERTS_NOTICE_VERSION` is `2026-10-alerts-draft2`; the notice strings
  (`contacts_notice_*`) are rewritten; `contacts_intro` and `contacts_card_body` no longer
  say "in a later version".
- `ContactsPreferences.alertsNoticeVersion`: the version the user agreed to, a flag in the
  app's DataStore. `ApiContactsRepository` writes it after `grantConsent` succeeded and
  after every `hasConsent` answer, and removes it on withdrawal and `clearLocal`.
- `hasConsent` is true only for the current version.
- `ContactsSessionSync` also checks the consent once after sign-in.
- The contacts list shows "SOS alerts are off" with the way to the notice, or the card below.

**`feature/emergency`**

- `ConsentSosAlertPolicy` replaces `ClosedSosAlertPolicy`; `SosArmViewModel` for the
  screens; `SosViewModel` asks the gate before it starts anything.
- `SmsPermissionCard.kt` (new): the card, its four states, the disclosure, the request.

**`feature/home`, `feature/search`**: the emergency dialog offers the hold only when the
gate is open; otherwise the reason and, on Home, "Set up SOS alerts".

**`core/emergency`**: `SosAlertPolicy` is now a flow with a suspend shortcut.

**Strings** (EN and BN): 19 new (`sms_*`, `sos_arm_not_set_up`, `sos_arm_set_up`,
`contacts_alerts_*`), 17 changed.

**Docs**: `docs/legal/sos-alerts-notice-v2.md` (new; version 1 marked as replaced),
`docs/play/send-sms-declaration-draft.md`,
`docs/play/foreground-service-location-declaration-draft.md`.

**No change**: manifest, permissions, build files, database, API contract, backend.

## 5. Diagram

[`docs/diagrams/014b3-sos-alerts-consent.svg`](../diagrams/014b3-sos-alerts-consent.svg)
(source `.json`, also `.excalidraw` and `.png`): from the notice to the flag to the gate,
the permission card, and what the SOS control offers in each case.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` | Pass: 1071 tests, 0 failures, 0 skipped (17 new); lint without errors. One earlier run crashed inside lint (see section 3) |
| `./gradlew assembleRelease` | Not run: no build file, manifest, `src/release` or `src/debug` change |
| `cd tools/diagrams && pnpm generate` | Pass; only the new diagram's files changed |
| markdownlint, gitleaks, relative links | See the pull request |

New tests: `SmsPermissionCardTest` (10), four consent tests in `ApiContactsRepositoryTest`,
two in `SosAlertsDeviceTest`, two in `SosViewModelTest`, one in `SosScreensTest`, one in
`SosFlowTest`. Changed on purpose: `ContactsScreensTest` (the notice now describes the
alerts), `ContactsBoundaryTest` (two DataStore keys), `ContactsSessionSyncTest` (the consent
check at sign-in), `ContactsNoticeDocumentTest` (reads version 2), `SosScreensTest` (the
rule is now "no text calls a message delivered or read, or the user safe").

**Failure matrix** (full table and scripts M0 to M16: `docs/sos/failure-matrix.md`):

| Row | Covered here | Status |
| --- | --- | --- |
| 1 No mobile data | The gate that lets alerts out; consent per notice version | Automated pass at data, logic, component and screen level; manual pending (M11, M12) |
| 7 SEND_SMS denied | The permission card: explanation before Android's dialog, refused, blocked, not in the build | Automated pass at screen level; manual pending (M13, M14) |
| 2, 4, 5, 6, 8, 11 | Unchanged in code; the scripts now involve real SMS | Automated as before; manual pending (M1 to M9) |
| Opted-out contact | Unchanged; a script added | Manual pending (M16) |
| 3, 9, 10 | No | P015 |

**B8 against part b as a whole:** message builder, sender, composer and notification,
permission states, consent version 2 flow, "I'm safe", build-switch manifests, logging,
string parity: covered across b1 to b3. **Not covered:** dual SIM (no choice in the app;
script M15), and everything a phone would show.

## 7. Decisions & ADRs

Note of 2026-10-09 (P014b3) in [ADR 0027](../adr/0027-sos-device-flow.md). The ones that
differ from, or add to, the prompt:

- **Consent is checked on the phone from a flag**, written only after the server recorded
  or reported it. The prompt says "if the user's latest granted record has an older
  version, show the new notice"; that is what `hasConsent` now means.
- **Without consent no SOS can be started from the app**, but a practice run stays
  available beside Call 112 (the prompt: "only Call 112").
- **The permission card lives under Emergency contacts** and appears once alerts are set
  up. The prompt places the flow in the readiness checklist (P014c) "and from the first SOS
  setup"; P014c can move or reuse the card.
- **The notice keeps seven paragraphs** and its two buttons, with a new title.
- **A build without the permission** gets its own sentence on the card instead of a button
  that cannot work.
- **Play drafts** state what was not found (video, test tracks) instead of guessing.

## 8. Security & privacy notes

- **Real SMS can now leave a phone.** They go from the user's SIM to the user's own
  emergency contacts, only after the user started an SOS and let the countdown finish.
- No alert without recorded consent to the notice that describes it; the check needs no
  network and fails closed (an unreadable flag means "no").
- The flag is a version string, not personal data; it is removed on withdrawal and sign-out.
- The SMS permission is requested in one file, after the app's own disclosure, never during
  an SOS, at app start or in onboarding (source test).
- No new permission, no manifest change in this piece.
- The notice, the disclosure and the SMS texts are drafts, to be verified by a lawyer.
- The Play drafts contain no account detail; Claude Code submits nothing to Google Play.

## 9. Known issues & risks

- **Unverified on a phone, and now with real consequences**: a wrong number in a test
  account would receive a real alert. The scripts say how to avoid that.
- An account with consent and contacts, on a debug build with the permission granted, sends
  SMS on every SOS that finishes its countdown, including during tests of other things.
  Use an account without contacts for those.
- A user who agreed to version 1 loses the ability to start an SOS until they accept
  version 2. They are told why in the dialog and on the contacts screen.
- On a new phone the flag arrives with the first successful consent check after sign-in;
  until then (for example with no network at first start) alerts are off.
- The lint crash of section 3 may return.
- 36 Bengali strings are drafts (19 new, 17 changed): every `sms_*`,
  `contacts_notice_*`, `contacts_alerts_*`, and the changed `sos_*` and `contacts_*` texts.
  **Needs human review before release.**

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P014b3):

- Run the sending scripts M0 and M11 to M16 with phones you own; record the cost.
- Lawyer review: notice version 2 and the SMS disclosure (with the SMS texts of P014b1).
- Bengali review of 36 strings.
- A debug-build safeguard for testers: an account with real contacts sends real SMS.
- Submit the two Play declarations when a build with the permission is to be uploaded.

**For P014c:** `StartSos(entryPoint)` for the tile, the widget and the notification must go
through the same gate (`SosAlertPolicy`); the readiness screen can reuse
`SmsAlertModeCard` and `AlertsNotSetUpCard`, and adds the name for alerts
(`SosMessageSettings.name`), the SOS language and the countdown sound; the boot notice.

## 11. How Rahul can verify

**Read first, send second.**

1. Read `docs/legal/sos-alerts-notice-v2.md` and `docs/legal/sos-sms-text-v1.md`.
2. Put **only your own second phone** into the test account's emergency contacts.
3. Install this branch's debug build. Run **M0** (the set-up): the notice, "Not now", then
   "I agree".
4. Run **M13** first (the SMS app opens; you decide whether to press Send), then **M11**
   (automatic sending) and **M2** (Cancel sends nothing).
5. When there is time: M12, M14, M15, M16, and M1 to M9 with an account without contacts.
6. Tell me the phone, the Android version, the SMS app, what arrived on the second phone,
   how long it took and what it cost.
7. Say "P014c" for the tile, the widget, the readiness screen and the boot notice.

## 12. Learning notes

- **Runtime permission flow.** A "dangerous" permission is declared in the manifest and then
  asked for while the app runs. Google expects the app's own explanation before Android's
  dialog ("prominent disclosure"). After two refusals Android stops showing the dialog;
  `shouldShowRequestPermissionRationale` returning false after a refusal is how an app
  notices, and system Settings is then the only way.
- **Activity Result API for permissions.** `rememberLauncherForActivityResult(
  RequestPermission())` starts Android's dialog and hands the answer to a callback.
- **`LifecycleResumeEffect`.** Runs code every time the screen comes back to the front,
  here to read the permission again after a visit to system Settings.
- **Consent versions.** A consent is for a text. When the text changes its meaning, the old
  "yes" does not carry over: the version in the record says which text was agreed to.
- **Offline check of an online fact.** The server holds the truth; the phone keeps a small
  note of it, written only when the server confirmed, so that it can act without asking.
- **Fail closed.** When the note cannot be read, the answer is "no".
- **DataStore flags.** Small key-value storage; here a version string, never a person's data.
