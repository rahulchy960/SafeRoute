# P014b2: SOS SMS alerts: sending at the trigger, composer fallback, status on the screen, safe follow-up (behind a gate)

| Field | Value |
| --- | --- |
| Prompt | P014 · part b, second of three (P014b1 the SMS engine; **P014b2 sending, behind a closed gate**; P014b3 the notice version 2, the permission flow and the switch-on; then P014c) |
| Milestone | M5 (depends on P014b1, merged as `331a3b7`) |
| Branch | `feat/014b2-sos-alert-dispatch` |
| PR title | `feat(sos): send SOS alerts at the trigger with composer fallback, counts on the active screen and safe follow-up, behind a closed gate [P014b2]` |
| Notion | [P014b2 row in the Prompt Log](https://app.notion.com/p/3f407370772081dfa23ee29a3f3a2687) |
| Date | 2026-10-09 |
| Plan refs | Plan v7 §3.2 F-08, §7.1–7.5, §12.2; ADRs 0010, 0024, 0027 |

> **P014b is in three pieces, and this is the second.** The P014b1 log announced one more
> piece. It is two, in this order on purpose:
>
> - **P014b2 (this):** everything that happens when an alert is sent, connected to a running
>   SOS, **behind a gate that answers "no"**. The gate is the user's consent to the notice
>   that describes the alerts; that notice (version 2) does not exist yet.
> - **P014b3:** the notice version 2 and its re-consent flow (B6), the permission screen with
>   the disclosure (B2), the replacement of the "not messaged in this version" and "start"
>   texts, the Play declaration drafts (B7), the real gate, and the phone scripts for sending.
>
> In this order every merged state is truthful: no screen describes SMS alerts before they
> can happen, and no alert can leave before the user could agree to it. The other order
> (texts first) would have described alerts that did not exist.
>
> **After this pull request the app still messages nobody.** The automated tests open the
> gate with a fake. **Nothing new can be tried on a phone.**
>
> **Not verified here:** anything on a phone, including which separator an SMS app accepts
> for several recipients.
>
> **Still not reported:** the phone scripts M1 to M10 of part a.
>
> **Size:** about 1,930 added lines outside the generated diagram files (source 630, strings
> 30, tests 840, documents 435). Over the ~800-line guide.

## 1. Objective

Connect the SMS engine to a running SOS so that it behaves correctly the moment it is
switched on: an alert at the trigger that never delays the emergency, a short wait for a
position, one location update, a fresh look at the contacts that is never waited for, the
SMS app as the fallback, counts on the active screen, and the follow-up after "I'm safe".

## 2. Context & prerequisites

- Preflight: `main` synced at `331a3b7`; PR #56 (P014b1) merged; no open pull requests; hook
  path `.githooks`. Notion: P014b1 set to Merged, a new row for P014b2.
- From P014b1: `SosAlerts`, `SosMessage`, `SmsGateway`, `canSendSmsAutomatically`.
- From part a: `SosRunner`, `SosTrail`, the active screen, `SosViewModel`.

## 3. Workflow executed

1. `/start-prompt`: sync, PR #56 merged, Notion, branch.
2. Decided the order of the two remaining pieces (above).
3. `SosDispatch` (core), connected in `SosRunner`; the phone's side in `SosAlertsDevice.kt`
   (gate, mode, composer, contact refresh, message settings); the active screen, the
   confirmation, the ViewModel; strings in both languages.
4. A design change while writing the tests: the first version waited up to 1.5 seconds for
   the fresh contact list before sending. That makes a device-side SOS action wait for the
   server, which the project rules forbid. It is now started and never waited for.
5. Tests; one failure was a mistake in a test (a fake shared across "processes").
6. Docs: ADR note, CLAUDE.md, failure matrix, diagram. `/ship-prompt`.

## 4. Changes

**`core/emergency`**

- `SosDispatch.kt` (new): `SosDispatch` (`start`, `stop`, `tellContactsSafe`,
  `reopenComposer`, `status`), `SosAlertStatus`, and the interfaces `SosAlertPolicy`,
  `SosMessageSettings`, `ContactsFreshener`, `SmsModeSource`, `SmsComposer`; the limits
  `ALERT_POSITION_WAIT_MILLIS` (3 s), `LOCATION_UPDATE_WINDOW_MILLIS` (60 s),
  `CONTACT_REFRESH_LIMIT_MILLIS` (1.5 s).
- `SosRunner`: tells the dispatcher when an emergency becomes active or is picked up again;
  stops it on cancel and "I'm safe"; `markSafe(tellContacts)` keeps the host up to 10 s for
  the follow-up.
- `SosAlerts.sendUntilDone`: an `onRound` callback for the counts.

**`feature/emergency`**

- `SosAlertsDevice.kt` (new): `ClosedSosAlertPolicy` (answers "no"), `AppSosMessageSettings`
  (no name yet, the app's language), `AndroidSmsModeSource`, `RepositoryContactsFreshener`,
  `AndroidSmsComposer` with its intent, channel and notification.
- `SosViewModel`, `SosScreens.kt`, `EmergencyActivity`: the alert lines, "Open SMS app
  again", the "Tell my contacts that I am safe" checkbox.

**Strings** (EN and BN): 14 new, `sos_alerts_*` and `sos_composer_*`. They are shown only
when the gate is open.

**No change**: manifest, permissions, build files, database, API contract, backend, the
texts shown today.

## 5. Diagram

[`docs/diagrams/014b2-sos-alert-dispatch.svg`](../diagrams/014b2-sos-alert-dispatch.svg)
(source `.json`, also `.excalidraw` and `.png`): from the trigger to the message, with the
gate and the two ways of sending.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` | Pass: 1054 tests, 0 failures, 0 skipped (39 new); lint without errors |
| `./gradlew assembleRelease` | Not run: no build file, manifest, `src/release` or `src/debug` change |
| `cd tools/diagrams && pnpm generate` | Pass; only the new diagram's files changed |
| markdownlint, gitleaks, relative links | See the pull request |

New tests: `SosDispatchTest` (20), `SosAlertsDeviceTest` (7), `SosAlertLinesTest` (7),
`SosViewModelTest` (+5). No test sends an SMS or opens an app: fakes throughout, and the
composer tests look at the intent and the notification only.

**Failure matrix** (full table: `docs/sos/failure-matrix.md`, updated):

| Row | Covered here | Status |
| --- | --- | --- |
| 1 No mobile data | An alert per contact at the trigger; counts on the screen | Automated pass up to screen level, **with the gate opened by the test** |
| 2 No signal | A failing message never holds up the SOS and is retried; retries continue after a process death | Automated pass at logic level |
| 6 Duplicate trigger | Nobody who has the alert gets it again after a restart | Automated pass at logic level |
| 7 SEND_SMS denied | The composer with every number and the text, its notification, "Open SMS app again", nothing recorded as sent | Automated pass at logic, component and screen level; the separator is not verified |
| 8 No GPS fix | 3 seconds at most; one update within 60 seconds; nothing to wait for without a permission | Automated pass at logic level |
| 11 Accidental trigger | Cancel sends nothing; "I'm safe" with and without "Tell my contacts" | Automated pass at logic and screen level |
| Opted-out contact | Taken off when the fresh list arrives before the message left | Automated pass; **known gap** when the alert leaves at once |
| 3, 9, 10 | No | P015 |

## 7. Decisions & ADRs

Note of 2026-10-09 (P014b2) in [ADR 0027](../adr/0027-sos-device-flow.md). The ones that
differ from, or add to, the prompt:

- **The gate, and a closed implementation of it in this piece.** The prompt puts the consent
  check into B6; here it is the first thing the dispatcher asks.
- **The fresh contact list is never waited for.** The 1.5 s is a limit on the fetch, not a
  delay of the alert. The residual risk the prompt asked to document is in the ADR: an
  opt-out made shortly before the trigger can be missed.
- **Counts on the screen also when unlocked.** The prompt allowed names when unlocked.
- **The composer always posts its notification**, also when it could open the SMS app
  directly, and the active screen has "Open SMS app again".
- **In composer mode nothing is recorded as sent.**
- **"Tell my contacts" is shown only when someone was told.**
- **The host stays up to 10 seconds after "I'm safe"** for the follow-up.
- **After the app was closed** the SMS app is not opened again and no second update is sent.
- **A "good" position** for the alert is one taken since the countdown began.
- **No name yet** (P014c); the language is the app's language (the SOS language setting is
  P014c).

## 8. Security & privacy notes

- **No alert can be sent**: `ClosedSosAlertPolicy` answers "no". A test pins that.
- No new permission and no manifest change. The contact refresh is the existing
  `GET /v1/contacts`; nothing about the emergency is sent to the server.
- The screen and the notification show counts and fixed texts. The numbers and the message
  travel in an immutable PendingIntent to the phone's SMS app only.
- Nothing is logged (the source tests of P014b1 cover the new core files).
- The opt-out gap (above) and the texts are points for the lawyer.
- Tests use `+9190000100NN` and "Test Contact N".

## 9. Known issues & risks

- Unverified on a phone, and not verifiable there until P014b3.
- An opt-out made shortly before the trigger can be missed when the alert leaves at once.
- The separator for several recipients (`;`) is not verified; some SMS apps want a comma.
- With notifications off and the phone locked at the trigger, composer mode can show
  nothing until the user opens the active screen.
- The gate is one class; replacing it carelessly switches alerts on. P014b3 must test it
  against recorded consent.
- 14 Bengali strings are drafts: every `sos_alerts_*` and `sos_composer_*`. **Needs human
  review before release.**

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P014b2):

- Lawyer: the opt-out gap at the trigger (the alert does not wait for the server).
- Phone check: which separator SMS apps accept for several recipients.
- Bengali review of 14 strings.
- Composer mode with notifications off and the phone locked.

**For P014b3:** replace `ClosedSosAlertPolicy` with one that reads, on the phone, whether the
user's latest `sos_alerts` consent is for notice version 2 (store that flag when consent is
granted or fetched; DataStore holds flags only); write notice version 2, mirror it in
`docs/legal/sos-alerts-notice-v2.md`, raise `SOS_ALERTS_NOTICE_VERSION` and show the notice
again to users with an older one; replace `contacts_intro`, `contacts_card_body`,
`contacts_notice_p5`, `sos_active_contacts_none`, `sos_arm_location_note`, "Hold to start
SOS" and "Start SOS now"; the "Send alerts automatically" screen with the disclosure before
the system dialog (granted, denied, permanently denied, revoked on resume; never at SOS
time); `docs/play/*-declaration-draft.md`; the phone scripts for rows 1 and 7.

## 11. How Rahul can verify

1. Install this branch's debug build: an SOS behaves exactly as before (scripts M1 to M9),
   and the active screen still says that your contacts are not messaged.
2. Read the note of P014b2 in ADR 0027, especially points 4 (the opt-out gap), 6 (composer)
   and 9 (counts only).
3. Look at the diagram.
4. Say "P014b3" for the notice, the permission screen and the switch-on.

## 12. Learning notes

- **A gate as an interface.** The dispatcher asks "may I?" through `SosAlertPolicy`. Today's
  answer is a class that says no; the next piece swaps the class, not the dispatcher.
- **Structured waiting.** `withTimeoutOrNull(3000) { ... }` waits for something for at most
  three seconds and then goes on with null: the alert has a deadline, not a condition.
- **`StateFlow.first { ... }`.** Suspends until the value fulfils a condition, for example
  "a position newer than the countdown".
- **Background activity starts.** Android does not let an app open another app's screen
  while it is in the background. A notification is the allowed way: the user's tap opens it.
- **Notification importance.** "High" makes a notification appear at once on top; "low" (the
  running SOS) sits quietly in the drawer.
- **`toggleable` row.** The checkbox and its text are one control with one spoken name and a
  48 dp touch area.
- **Why not wait for the server.** A device-first SOS means the phone acts with what it has;
  the server may improve the result but never stands in the way.
