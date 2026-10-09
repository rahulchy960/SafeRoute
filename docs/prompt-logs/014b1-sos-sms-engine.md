# P014b1: SOS SMS alerts: message builder, sender engine, SmsManager gateway and build switch

| Field | Value |
| --- | --- |
| Prompt | P014 · part b, first half (**P014b1 the SMS engine**; P014b2 connecting it, permission, consent notice v2, composer, "I'm safe"; then P014c) |
| Milestone | M5 (depends on P014a3, merged as `5df1a1f`) |
| Branch | `feat/014b1-sos-sms-engine` |
| PR title | `feat(sos): SMS alert engine: message builder, sender with retries, SmsManager gateway and SEND_SMS build switch [P014b1]` |
| Notion | [P014b1 row in the Prompt Log](https://app.notion.com/p/3f407370772081678aa1d8cde90ea98b) |
| Date | 2026-10-09 |
| Plan refs | Plan v7 §3.2 F-08, §5.3, §7.1–7.5, §12.2; ADRs 0010, 0024, 0027 |

> **P014b is split, and this is the first half.** Part b (spike, build switch, permission
> flow, message builder, sender, composer, "I'm safe", consent notice version 2, Play
> declaration drafts and their tests) is several times the ~800-line guide. The cut:
> **this half builds and tests the engine and connects it to nothing.** No build can send an
> SMS after this pull request: nothing calls `SosAlerts` from a running SOS, and the
> permission is declared (in debug builds) but never requested.
>
> The reason for the cut is not only size. An alert may leave the phone only when the user
> has read what it does (notice version 2), has been told about the permission and the cost,
> and can end it with a message ("I'm safe"). Those arrive together in P014b2.
>
> **P014b2 brings:** sending at the trigger, the fresh fetch of the contacts with its 1.5 s
> limit, the composer fallback and its notification, the permission flow with the disclosure
> (B2), the 3-second rule for the position and the one location update (B4), "I'm safe" with
> "Tell my contacts" (B5), the notice version 2 and the replacement of every "not messaged in
> this version" text (B6), the per-contact lines on the active screen with counts on the lock
> screen, the Play declaration drafts (B7) and the phone scripts.
>
> **Not verified here:** anything on a phone. All tests run on the JVM (Robolectric); the
> test device cannot send SMS at all, which is itself one of the tested cases.
>
> **Still not reported:** the phone scripts M1 to M10 of part a.
>
> **Size:** about 2,280 added lines outside the generated diagram files (source 710, tests 900,
> documents, build files and CI 670). Over the guide; the engine and its tests belong together.

## 1. Objective

Build the part of the SOS that writes and sends the alert messages, so that it is correct
before it is switched on: the text in English and Bengali within three SMS parts, who gets a
message, what is retried and what is never sent twice, the one file that may talk to the
phone's SMS service, and a build switch that keeps `SEND_SMS` out of release builds.

## 2. Context & prerequisites

- Preflight: `main` synced at `5df1a1f`; PR #55 (P014a3) merged; no open pull requests; hook
  path `.githooks`. Notion: P014a3 set to Merged, a new row for P014b1.
- From part a: the `sos_actions` table (empty until now), `ActiveSosContacts`, `SosPoint`,
  `UuidV7Generator`, `FakeSosDeviceModule`.
- The app has no display name for the user. The message takes a name as an input and has a
  wording for "no name"; where the name is set is the SOS setup of P014c.

## 3. Workflow executed

1. `/start-prompt`: sync, PR #55 merged, Notion, branch.
2. **B0 spike**: `SmsManager` from the platform sources of API level 37; Google Play's policy
   page "Use of SMS or Call Log permission groups" (read on 2026-10-09). Results in ADR 0027,
   note of P014b1, each marked certain, uncertain or not recorded.
3. Decided the split (above).
4. `SosMessage.kt` (text and segment count), `SosAlerts.kt` (who, retries, records),
   `SosSms.kt` (the gateway), the Room part, the build switch, the CI check.
5. Tests. Found on the way:
   - Room returned the actions in the order of their last change; they are now read in the
     order they were created;
   - the JVM test device answers "Sms is not supported" when asked to split a message. That
     is what a tablet does, so it became a tested case, and the sending step is tested on
     its own with the parts given;
   - `SosColourUsageTest` failed on a file name in a comment (the three letters as a word)
     and `ContactIntentsTest` on its old "no SEND_SMS" rule; the comment was reworded and
     the test now follows the build switch;
   - typed control characters in a test were turned into real ones by the tooling; they are
     built in code instead.
6. Docs: ADR note, the message texts for the lawyer, CLAUDE.md, README, failure matrix,
   diagram. `/ship-prompt`.

## 4. Changes

**`core/emergency`**

- `SosMessage.kt`: `buildAlertMessage`, `buildSafeMessage`, `buildLocationUpdateMessage`,
  `smsSegments`, `messageName`, `SosLanguage`. Plain Kotlin.
- `SosAlerts.kt`: `SosAction`, `SosActionType`, `SosActionState`, `SosActionStore`,
  `SmsGateway`, `SmsOutcome`, `SosAlerts` (`prepare`, `dropOptedOut`, `sendDue`,
  `sendUntilDone`), `summarise`.

**`core/data`**: `RoomSosActionStore`; `SosDao.moveActionIf`; actions are read in creation
order; `SosContact` gains the contact's `id`.

**`feature/emergency`**: `SosSms.kt` with `AndroidSmsGateway`, `smsOutcome` (result codes)
and `canSendSmsAutomatically`; bound in `SosDeviceModule`.

**Build**: the Gradle property `saferoute.sendSmsEnabled`; `src/sendSmsOn/AndroidManifest.xml`
(declares `SEND_SMS`, telephony not required) and `src/sendSmsOff/AndroidManifest.xml`
(empty); `BuildConfig.SEND_SMS_DECLARED`. Default: debug yes, release no.

**CI** (`android-ci.yml`): the release build passes `-Psaferoute.sendSmsEnabled=false`; a
new step checks with `aapt2 dump permissions` that the release APK asks for no SMS
permission, with the debug APK as the control.

**Docs**: `docs/legal/sos-sms-text-v1.md` (the texts and six points for the lawyer).

**No change**: screens, strings, the runner, the service, the database tables, API contract,
backend.

## 5. Diagram

[`docs/diagrams/014b1-sos-sms-engine.svg`](../diagrams/014b1-sos-sms-engine.svg) (source
`.json`, also `.excalidraw` and `.png`): the states of one message and what moves it.

## 6. Quality gate & test results

| Check | Result |
| --- | --- |
| `./gradlew lint testDebugUnitTest assembleDebug` | Pass: 1015 tests, 0 failures, 0 skipped (44 new); lint without errors |
| `./gradlew assembleRelease -Psaferoute.apiBaseUrl=https://example.invalid/ -Psaferoute.sendSmsEnabled=false` | Pass; `aapt2 dump permissions` run locally: debug APK asks for SEND_SMS, release APK does not, neither asks for READ_SMS, RECEIVE_SMS or READ_PHONE_STATE |
| actionlint (with shellcheck) on `android-ci.yml` | Pass |
| `cd tools/diagrams && pnpm generate` | Pass; only the new diagram's files changed |
| markdownlint, gitleaks, relative links | See the pull request |

New tests: `SmsSegmentsTest` (4), `SosMessageTest` (13), `SosAlertsTest` (14),
`RoomSosActionStoreTest` (2), `SosSmsTest` (8), `SosSmsTextDocumentTest` (3). Changed:
`MainActivityTest` and `ContactIntentsTest` (both pinned "no SEND_SMS"; they now follow the
build switch and still pin that the app can never read SMS or the address book),
`ContactsDatabaseTest` (the contact id).

No test sends an SMS: the sender tests use `FakeSmsGateway`; the gateway tests run on
Robolectric's stand-in for the SMS service, which records and transmits nothing.

**Failure matrix** (full table: `docs/sos/failure-matrix.md`, updated):

| Row | Covered here | Status |
| --- | --- | --- |
| 1 No mobile data | The engine needs no data; one message per contact, recorded | Automated pass at data, logic and component level, **for the engine only** |
| 2 No signal | Retry every 30 s, 20 tries, stops when the SOS ends; result codes | Automated pass at logic level for the engine |
| 6 Duplicate trigger | No message twice; the one accepted exception after a process death | Automated pass at logic level |
| 7 SEND_SMS denied | The gateway refuses and is not retried; the build switch | Automated pass for the refusal and the switch; composer P014b2 |
| 8 No GPS fix | What the message says without a position, with an old one | Automated pass |
| 11 Accidental trigger | The follow-up text and who gets it | Automated pass at logic level; sending it P014b2 |
| Opted-out contact | Never in the list; skipped if the opt-out arrives before the message leaves | Automated pass at data and logic level |
| 3, 9, 10 | No | P015 |

**B8 against this half:** message builder, sender with a fake SMS service (sent, failure
codes, multipart, retry cadence in virtual time, no resend after SENT, opted-out skipped,
contact list changing mid-SOS), build-switch manifests, logging: covered. **Not covered,
P014b2:** dual SIM (the app uses the phone's default SIM and offers no choice), the composer
intent and its notification, permission states, the consent version 2 flow, "I'm safe".

## 7. Decisions & ADRs

Note of 2026-10-09 (P014b1) in [ADR 0027](../adr/0027-sos-device-flow.md). The ones that
differ from, or add to, the prompt:

- **No SIM choice in the app.** Listing SIMs needs `READ_PHONE_STATE`, which the prompt does
  not ask for. The phone's default SIM for SMS is used.
- **The coordinates are not repeated outside the link.** A second copy would push a Bengali
  message past three parts; they can be read in the link.
- **The texts are Kotlin constants, not string resources**: they are sent in the language the
  sender chose and are never shown on a screen.
- **A message caught mid-send by a process death is tried again**, so that one contact may
  get the alert twice.
- **An absent SIM is retried**, like every code that may pass; it ends after 20 tries.
- **No delivery reports** are requested.
- **The name is cut to 30 characters** and to one line.
- **Without a name** the message says "Someone who listed you as an emergency contact".
- **Follow-ups go only to contacts whose alert was sent.**
- `uses-feature telephony required=false` in the extra manifest, so that the permission does
  not hide the app from tablets on Play.

## 8. Security & privacy notes

- **No message can be sent by this build's code paths**: nothing calls `SosAlerts`, and the
  permission is never requested.
- `SEND_SMS` is declared in debug builds by default and in no release build unless the
  property is set. `READ_SMS`, `RECEIVE_SMS` and `READ_PHONE_STATE` are pinned as absent.
- `sos_actions` will hold a copy of a contact's name and number per emergency: private
  storage, never backed up, deleted with the record, after 30 days and at sign-out. The
  database is still not encrypted (follow-up).
- Names, numbers and message texts are never logged (source tests and a log capture in
  `SosSmsTest`); the types that hold them hide them in `toString()`; an error category is a
  fixed short word.
- The alert names the sender and shows where they are to people who are not users, over
  unencrypted SMS. Six points for the lawyer are in `docs/legal/sos-sms-text-v1.md`.
- The map link belongs to a third party; the app sends nothing to it.
- Tests use `+9190000100NN` and "Test Contact N".
- Google Play: `SEND_SMS` needs the Permissions Declaration Form under the exception
  "Physical safety/emergency alerts to send SMS". Whether a video is required and whether
  test tracks are exempt was not found on the page read: not recorded.

## 9. Known issues & risks

- Unverified on a phone: the real result codes, dual SIM, the split of a Bengali message,
  what a network does with a three-part SMS.
- The engine is unused until P014b2; a mistake in how it is connected is not caught here.
- One contact may get an alert twice after a process death in the middle of a send.
- With `RESULT_OK` the app records SENT; the message may still never arrive.
- The Bengali message texts are drafts: every Bengali sentence in `SosMessage.kt`.
  **Needs human review before release.**

## 10. Follow-ups & prerequisites for next prompt

In the Notion Follow-ups database (source P014b1):

- Lawyer review of the SMS texts (`docs/legal/sos-sms-text-v1.md`, six points).
- Bengali review of the SMS texts by a native speaker.
- Play Console: the Permissions Declaration Form for `SEND_SMS`; find out whether a video is
  required and whether test tracks are exempt.
- SMS cost to users (up to three parts per contact and message).
- A SIM choice for alerts, if testers with two SIMs need it (needs `READ_PHONE_STATE`).

**For P014b2:** in `SosRunner.onTriggered`, after the engine said yes: `alerts.prepare(id,
ALERT)`, then `sendUntilDone` in the app scope with `stillWanted = { runner is active }` and
a text built from `SosTrail.state.value.last`, the battery and the name; start a
`ContactsRepository.refresh()` with a 1.5 s limit and call `dropOptedOut` with the ids that
are left; choose composer mode when `canSendSmsAutomatically` is false; on "I'm safe",
`prepare(id, SAFE)` and send before the runner stops; show `summarise(actions)` on the
active screen, counts only when locked.

## 11. How Rahul can verify

1. Read `docs/legal/sos-sms-text-v1.md`: these are the messages your contacts would get. Say
   what should change before they are switched on.
2. Read the note of P014b1 in ADR 0027, especially the spike table and decisions 6, 10, 11.
3. Install this branch's debug build over the current one: the app behaves as before, asks
   for no new permission, and an SOS still messages nobody.
4. Check that `android-ci` is green: it now proves that the release APK asks for no SMS
   permission.
5. Say "P014b2" to connect it.

## 12. Learning notes

- **SmsManager.** Android's service for sending SMS from the phone's SIM. An app needs the
  `SEND_SMS` permission, which the user grants and Google Play restricts.
- **Multipart SMS.** A long text is cut into parts that the receiver's phone joins again.
  Plain Latin text has 160 characters per message; any other letter (Bengali) changes the
  whole message to 70.
- **PendingIntent as an answer.** The app gives the phone a "ticket" per part; the phone
  uses it later to say what happened (OK, or an error code).
- **BroadcastReceiver registered in code.** A listener that exists only while one message is
  waiting for its answer, and that other apps cannot reach (`RECEIVER_NOT_EXPORTED`).
- **`suspendCancellableCoroutine`.** Turns "call me back later" into a function that simply
  waits, and cleans up if the wait is cancelled.
- **Manifest merging and source sets.** A build can take an extra manifest from another
  folder; Gradle merges it into the main one. Here that is how one permission exists in one
  kind of build and not in another.
- **`BuildConfig`.** Constants Gradle writes into the app at build time, so code can ask
  "was this build made with SMS?".
- **Idempotent.** Doing it twice has the same result as doing it once: preparing the alerts
  again changes nothing, and a sent message is not sent again.
